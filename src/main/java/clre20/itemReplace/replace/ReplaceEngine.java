package clre20.itemReplace.replace;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.config.PluginConfig;
import clre20.itemReplace.model.Rule;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;

/**
 * 替換核心。只在主執行緒使用。
 * <p>
 * 比對先用材質查表：沒有任何規則用到的材質一次查表就返回，不讀取物品資料、不配置物件。
 */
public final class ReplaceEngine {

    /** 單次替換最多產生幾組物品（保護 CPU 與記憶體的硬上限，正常設定不會碰到）。 */
    private static final int STACK_BUDGET = 4096;
    /** 單次操作最多往物品內部檢查幾個物品（防止刻意塞滿袋中袋的容器拖慢伺服器）。 */
    private static final int NODE_BUDGET = 20_000;
    /** 檢查循環時往物品內部看幾層（與 config 無關，寧可多看）。 */
    private static final int CONFLICT_DEPTH = 5;
    private static final int SHULKER_SLOTS = 27;
    private static final double BUNDLE_CAPACITY = 64.0;
    private static final double NESTED_BUNDLE_WEIGHT = 4.0;
    private static final long DISCARD_WARN_INTERVAL_MS = 60_000L;

    private static final EquipmentSlot[] STAND_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND
    };

    private final ItemReplace plugin;

    private Map<Material, Rule[]> index = Map.of();
    private boolean active;

    private boolean nested = true;
    private int maxDepth = 3;
    private boolean dropOverflow = true;
    private int maxDrops = 64;

    /** 正在丟出放不下的物品（這些物品不需要再檢查）。 */
    private boolean dropping;
    private long lastDiscardWarn;

    public ReplaceEngine(ItemReplace plugin) {
        this.plugin = plugin;
    }

    public void configure(PluginConfig config) {
        this.nested = config.nestedEnabled;
        this.maxDepth = config.nestedMaxDepth;
        this.dropOverflow = config.overflowDrop;
        this.maxDrops = config.overflowMaxDrops;
    }

    /** @param index 材質 → 生效中的規則（完全相符的排在前面） */
    public void setIndex(Map<Material, Rule[]> index) {
        this.index = index;
        this.active = !index.isEmpty();
    }

    /** 目前有沒有任何生效的規則；沒有時所有監聽器直接返回。 */
    public boolean isActive() {
        return active;
    }

    public boolean isDropping() {
        return dropping;
    }

    public void clear() {
        setIndex(Map.of());
    }

    // ------------------------------------------------------------------ 比對

    /** 低成本的預先過濾：這個物品有沒有可能需要處理。 */
    public boolean isCandidate(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        if (index.containsKey(item.getType())) {
            return true;
        }
        return nested && hasContents(item);
    }

    private static boolean hasContents(ItemStack item) {
        return item.hasData(DataComponentTypes.CONTAINER) || item.hasData(DataComponentTypes.BUNDLE_CONTENTS);
    }

    public static Rule match(Map<Material, Rule[]> index, ItemStack item) {
        Rule[] rules = index.get(item.getType());
        if (rules == null) {
            return null;
        }
        for (Rule rule : rules) {
            if (rule.matches(item)) {
                return rule;
            }
        }
        return null;
    }

    /** 檢查循環用：物品本身或它裡面的物品，會不會被 index 中的規則替換。 */
    public static Rule matchDeep(Map<Material, Rule[]> index, ItemStack item) {
        return matchDeep(index, item, 0);
    }

    private static Rule matchDeep(Map<Material, Rule[]> index, ItemStack item, int depth) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        Rule rule = match(index, item);
        if (rule != null || depth >= CONFLICT_DEPTH) {
            return rule;
        }
        ItemContainerContents container = item.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            for (ItemStack inner : container.contents()) {
                rule = matchDeep(index, inner, depth + 1);
                if (rule != null) {
                    return rule;
                }
            }
        }
        BundleContents bundle = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack inner : bundle.contents()) {
                rule = matchDeep(index, inner, depth + 1);
                if (rule != null) {
                    return rule;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 轉換

    /**
     * @return null = 不需要變動；否則是替換結果，第一個放回原位，其餘另外安置；空清單 = 刪除
     */
    private List<ItemStack> transform(ItemStack item, int depth, Session session) {
        Rule rule = match(index, item);
        if (rule != null) {
            session.count(rule, item.getAmount());
            return expand(rule, item.getAmount(), session);
        }
        if (!nested || depth >= maxDepth || session.nodes <= 0) {
            return null;
        }
        if (item.hasData(DataComponentTypes.CONTAINER)) {
            return transformContainer(item, depth, session);
        }
        if (item.hasData(DataComponentTypes.BUNDLE_CONTENTS)) {
            return transformBundle(item, depth, session);
        }
        return null;
    }

    private List<ItemStack> expand(Rule rule, int amount, Session session) {
        List<ItemStack> samples = rule.replacements();
        if (samples.isEmpty()) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>(samples.size());
        for (int i = 0, size = samples.size(); i < size; i++) {
            ItemStack sample = samples.get(i);
            long total = (long) amount * sample.getAmount();
            int max = Math.max(1, sample.getMaxStackSize());
            while (total > 0) {
                if (session.budget <= 0) {
                    session.discard(rule, total);
                    break;
                }
                session.budget--;
                int part = (int) Math.min(total, max);
                out.add(sample.asQuantity(part));
                total -= part;
            }
        }
        return out;
    }

    /** 界伏盒等帶有「容器內容」的物品：格子位置保留，多出來的先放空格，再不行就往外送。 */
    private List<ItemStack> transformContainer(ItemStack holder, int depth, Session session) {
        ItemContainerContents data = holder.getData(DataComponentTypes.CONTAINER);
        if (data == null) {
            return null;
        }
        List<ItemStack> contents = data.contents();
        int size = contents.size();
        if (size == 0) {
            return null;
        }
        session.nodes -= size;
        ItemStack[] slots = null;
        List<ItemStack> extras = null;
        for (int i = 0; i < size; i++) {
            ItemStack inner = contents.get(i);
            if (!isCandidate(inner)) {
                continue;
            }
            List<ItemStack> result = transform(inner, depth + 1, session);
            if (result == null) {
                continue;
            }
            if (slots == null) {
                int capacity = Tag.SHULKER_BOXES.isTagged(holder.getType()) ? Math.max(size, SHULKER_SLOTS) : size;
                slots = contents.toArray(new ItemStack[capacity]);
            }
            slots[i] = result.isEmpty() ? null : result.get(0);
            for (int k = 1; k < result.size(); k++) {
                if (extras == null) {
                    extras = new ArrayList<>();
                }
                extras.add(result.get(k));
            }
        }
        if (slots == null) {
            return null;
        }
        List<ItemStack> out = new ArrayList<>(1);
        out.add(null);
        if (extras != null) {
            int cursor = 0;
            for (ItemStack extra : extras) {
                while (cursor < slots.length && slots[cursor] != null && !slots[cursor].isEmpty()) {
                    cursor++;
                }
                if (cursor < slots.length) {
                    slots[cursor++] = extra;
                } else {
                    addCopies(out, extra, holder.getAmount(), session);
                }
            }
        }
        int last = slots.length - 1;
        while (last >= 0 && (slots[last] == null || slots[last].isEmpty())) {
            last--;
        }
        List<ItemStack> rebuilt = new ArrayList<>(last + 1);
        for (int i = 0; i <= last; i++) {
            rebuilt.add(slots[i] == null ? ItemStack.empty() : slots[i]);
        }
        ItemStack copy = holder.clone();
        copy.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(rebuilt));
        out.set(0, copy);
        return out;
    }

    /** 束口袋：換出來的物品在容量內就留在袋子裡，超過容量的往外送。 */
    private List<ItemStack> transformBundle(ItemStack holder, int depth, Session session) {
        BundleContents data = holder.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (data == null) {
            return null;
        }
        List<ItemStack> contents = data.contents();
        int size = contents.size();
        if (size == 0) {
            return null;
        }
        session.nodes -= size;
        List<ItemStack> merged = null;
        BitSet produced = null;
        for (int i = 0; i < size; i++) {
            ItemStack inner = contents.get(i);
            List<ItemStack> result = isCandidate(inner) ? transform(inner, depth + 1, session) : null;
            if (result == null) {
                if (merged != null) {
                    merged.add(inner);
                }
                continue;
            }
            if (merged == null) {
                merged = new ArrayList<>(contents.subList(0, i));
                produced = new BitSet();
            }
            for (ItemStack stack : result) {
                produced.set(merged.size());
                merged.add(stack);
            }
        }
        if (merged == null) {
            return null;
        }
        double weight = 0;
        for (int i = 0; i < merged.size(); i++) {
            if (!produced.get(i)) {
                weight += bundleWeight(merged.get(i), 0);
            }
        }
        List<ItemStack> out = new ArrayList<>(1);
        out.add(null);
        List<ItemStack> kept = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            ItemStack stack = merged.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!produced.get(i)) {
                kept.add(stack);
                continue;
            }
            double w = bundleWeight(stack, 0);
            if (weight + w <= BUNDLE_CAPACITY + 1.0e-6) {
                weight += w;
                kept.add(stack);
            } else {
                addCopies(out, stack, holder.getAmount(), session);
            }
        }
        ItemStack copy = holder.clone();
        copy.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(kept));
        out.set(0, copy);
        return out;
    }

    /** 束口袋的佔用量（滿 = 64）：每個物品佔 64 / 最大堆疊數，袋中袋另加 4。 */
    private static double bundleWeight(ItemStack item, int depth) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        double single = BUNDLE_CAPACITY / Math.max(1, item.getMaxStackSize());
        if (depth < CONFLICT_DEPTH) {
            BundleContents inner = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (inner != null) {
                single = NESTED_BUNDLE_WEIGHT;
                for (ItemStack stack : inner.contents()) {
                    single += bundleWeight(stack, depth + 1);
                }
            }
        }
        return single * item.getAmount();
    }

    /** 外層物品如果有堆疊（數量 n），裡面多出來的東西也要給 n 份。 */
    private static void addCopies(List<ItemStack> out, ItemStack stack, int copies, Session session) {
        out.add(stack);
        for (int i = 1; i < copies; i++) {
            if (session.budget <= 0) {
                session.discarded += stack.getAmount();
                continue;
            }
            session.budget--;
            out.add(stack.clone());
        }
    }

    // ------------------------------------------------------------------ 對外操作

    /**
     * 檢查玩家背包。
     *
     * @param slotMask 要檢查的格子（第 n 個位元 = 第 n 格）；-1 表示全部
     */
    public void processPlayer(Player player, long slotMask) {
        Inventory inventory = player.getInventory();
        Session session = null;
        int size = Math.min(inventory.getSize(), Long.SIZE);
        for (int slot = 0; slot < size; slot++) {
            if ((slotMask & (1L << slot)) == 0) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            if (!isCandidate(item)) {
                continue;
            }
            if (session == null) {
                session = new Session();
            }
            applyToSlot(inventory, slot, item, session);
        }
        if (session != null && session.changed) {
            Location location = player.getLocation();
            placeExtras(session, inventory, location);
            finish(session, Where.PLAYER, player.getName(), location);
        }
    }

    /** 檢查一個容器的全部格子。 */
    public void processInventory(Inventory inventory, Location location, Player actor) {
        ItemStack[] contents = inventory.getContents();
        Session session = null;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isCandidate(item)) {
                continue;
            }
            if (session == null) {
                session = new Session();
            }
            applyToSlot(inventory, slot, item, session);
        }
        if (session != null && session.changed) {
            placeExtras(session, inventory, location);
            finish(session, Where.CONTAINER, actor != null ? actor.getName() : null, location);
        }
    }

    private void applyToSlot(Inventory inventory, int slot, ItemStack item, Session session) {
        List<ItemStack> result = transform(item, 0, session);
        if (result == null) {
            return;
        }
        session.changed = true;
        inventory.setItem(slot, result.isEmpty() ? null : result.get(0));
        for (int i = 1; i < result.size(); i++) {
            session.extra(result.get(i));
        }
    }

    /**
     * 檢查地上的掉落物。
     *
     * @return true = 這個掉落物應該消失（規則是直接刪除）
     */
    public boolean processItemEntity(Item entity) {
        ItemStack item = entity.getItemStack();
        if (!isCandidate(item)) {
            return false;
        }
        Session session = new Session();
        List<ItemStack> result = transform(item, 0, session);
        if (result == null) {
            return false;
        }
        Location location = entity.getLocation();
        boolean remove = result.isEmpty();
        if (!remove) {
            entity.setItemStack(result.get(0));
            for (int i = 1; i < result.size(); i++) {
                session.extra(result.get(i));
            }
        }
        dropLater(session, location);
        finish(session, Where.GROUND, null, location);
        return remove;
    }

    public void processItemFrame(ItemFrame frame) {
        ItemStack item = frame.getItem();
        if (!isCandidate(item)) {
            return;
        }
        Session session = new Session();
        List<ItemStack> result = transform(item, 0, session);
        if (result == null) {
            return;
        }
        if (result.isEmpty()) {
            frame.setItem(null, false);
        } else {
            // 展示框只放得下 1 個，其餘掉在旁邊
            ItemStack first = result.get(0);
            if (first.getAmount() > 1) {
                session.extra(first.asQuantity(first.getAmount() - 1));
                first = first.asOne();
            }
            frame.setItem(first, false);
            for (int i = 1; i < result.size(); i++) {
                session.extra(result.get(i));
            }
        }
        Location location = frame.getLocation();
        dropLater(session, location);
        finish(session, Where.ITEM_FRAME, null, location);
    }

    public void processArmorStand(ArmorStand stand) {
        EntityEquipment equipment = stand.getEquipment();
        Session session = null;
        for (EquipmentSlot slot : STAND_SLOTS) {
            ItemStack item = equipment.getItem(slot);
            if (!isCandidate(item)) {
                continue;
            }
            if (session == null) {
                session = new Session();
            }
            List<ItemStack> result = transform(item, 0, session);
            if (result == null) {
                continue;
            }
            session.changed = true;
            equipment.setItem(slot, result.isEmpty() ? null : result.get(0), true);
            for (int i = 1; i < result.size(); i++) {
                session.extra(result.get(i));
            }
        }
        if (session != null && session.changed) {
            Location location = stand.getLocation();
            dropLater(session, location);
            finish(session, Where.ARMOR_STAND, null, location);
        }
    }

    /** 物品展示實體只是顯示用，不會掉出多的物品。 */
    public void processItemDisplay(ItemDisplay display) {
        ItemStack item = display.getItemStack();
        if (!isCandidate(item)) {
            return;
        }
        Session session = new Session();
        List<ItemStack> result = transform(item, 0, session);
        if (result == null) {
            return;
        }
        display.setItemStack(result.isEmpty() ? null : result.get(0));
        finish(session, Where.ITEM_DISPLAY, null, display.getLocation());
    }

    // ------------------------------------------------------------------ 安置與紀錄

    /** 多出來的物品先塞進同一個背包／容器，塞不下的掉在地上。 */
    private void placeExtras(Session session, Inventory inventory, Location location) {
        List<ItemStack> extras = session.extras;
        if (extras == null) {
            return;
        }
        for (ItemStack extra : extras) {
            Map<Integer, ItemStack> rest = inventory.addItem(extra);
            for (ItemStack left : rest.values()) {
                drop(session, left, location);
            }
        }
        session.extras = null;
    }

    private void drop(Session session, ItemStack item, Location location) {
        if (item == null || item.isEmpty()) {
            return;
        }
        if (!dropOverflow || location == null || location.getWorld() == null || session.drops >= maxDrops) {
            session.discarded += item.getAmount();
            return;
        }
        session.drops++;
        dropping = true;
        try {
            location.getWorld().dropItem(location, item);
        } finally {
            dropping = false;
        }
    }

    /**
     * 實體相關的情況（物品生成事件、區塊實體載入事件）不在事件裡直接生成新實體，
     * 改到下一個 tick 再丟出。
     */
    private void dropLater(Session session, Location location) {
        List<ItemStack> extras = session.extras;
        if (extras == null) {
            return;
        }
        session.extras = null;
        if (!dropOverflow || location == null || location.getWorld() == null) {
            for (ItemStack extra : extras) {
                session.discarded += extra.getAmount();
            }
            return;
        }
        List<ItemStack> toDrop = new ArrayList<>(Math.min(extras.size(), maxDrops));
        for (ItemStack extra : extras) {
            if (toDrop.size() >= maxDrops) {
                session.discarded += extra.getAmount();
            } else {
                toDrop.add(extra);
            }
        }
        if (toDrop.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (location.getWorld() == null || !location.isChunkLoaded()) {
                return;
            }
            dropping = true;
            try {
                for (ItemStack stack : toDrop) {
                    location.getWorld().dropItem(location, stack);
                }
            } finally {
                dropping = false;
            }
        });
    }

    private void finish(Session session, Where where, String playerName, Location location) {
        if (session.firstRule != null) {
            plugin.replaceLog().record(session.firstRule.id(), where, playerName, location, session.firstCount);
            if (session.moreRules != null) {
                for (int i = 0; i < session.moreRules.size(); i++) {
                    plugin.replaceLog().record(session.moreRules.get(i).id(), where, playerName, location,
                            session.moreCounts[i]);
                }
            }
        }
        if (session.discarded > 0) {
            long now = System.currentTimeMillis();
            if (now - lastDiscardWarn >= DISCARD_WARN_INTERVAL_MS) {
                lastDiscardWarn = now;
                String id = session.discardRule != null ? session.discardRule.id()
                        : session.firstRule != null ? session.firstRule.id() : "-";
                plugin.getComponentLogger().warn(plugin.messages().console("console.discarded",
                        "count", session.discarded, "id", id));
            }
        }
    }

    /** 一次操作（一個背包、一個容器或一個實體）的暫存狀態，用完即丟。 */
    private static final class Session {
        int budget = STACK_BUDGET;
        int nodes = NODE_BUDGET;
        int drops;
        long discarded;
        boolean changed;
        List<ItemStack> extras;

        Rule firstRule;
        int firstCount;
        List<Rule> moreRules;
        int[] moreCounts;
        Rule discardRule;

        void extra(ItemStack item) {
            if (extras == null) {
                extras = new ArrayList<>();
            }
            extras.add(item);
        }

        void discard(Rule rule, long amount) {
            discarded += amount;
            discardRule = rule;
        }

        void count(Rule rule, int amount) {
            if (firstRule == null || firstRule == rule) {
                firstRule = rule;
                firstCount += amount;
                return;
            }
            if (moreRules == null) {
                moreRules = new ArrayList<>(2);
                moreCounts = new int[4];
            }
            int at = moreRules.indexOf(rule);
            if (at < 0) {
                at = moreRules.size();
                moreRules.add(rule);
                if (at >= moreCounts.length) {
                    moreCounts = java.util.Arrays.copyOf(moreCounts, moreCounts.length * 2);
                }
            }
            moreCounts[at] += amount;
        }
    }
}
