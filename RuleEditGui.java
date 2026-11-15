package clre20.itemReplace.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.config.Messages;
import clre20.itemReplace.model.MatchMode;
import clre20.itemReplace.model.Rule;
import clre20.itemReplace.util.RuleText;

/**
 * 規則編輯介面（6 排）。
 * <pre>
 * 第 1 排：[-10][-1][已選取][+1][+10]
 * 左邊：目標（1 格）   右邊：替換成（3x3 共 9 格）
 * 最後一排：返回｜比對方式｜啟用｜儲存｜說明｜刪除
 * </pre>
 * 全部用左鍵：先點自己背包裡的物品「選取」（只是複製，物品不會被拿走），再點目標格或替換格放入；
 * 點已經有東西的格子會把它拿起來。編輯的是草稿，按「儲存」才會生效。
 */
public final class RuleEditGui extends GuiHolder {

    private static final int SIZE = 54;
    private static final int SLOT_MINUS_10 = 2;
    private static final int SLOT_MINUS_1 = 3;
    private static final int SLOT_PICKED = 4;
    private static final int SLOT_PLUS_1 = 5;
    private static final int SLOT_PLUS_10 = 6;
    private static final int SLOT_TARGET_LABEL = 11;
    private static final int SLOT_TARGET = 20;
    private static final int SLOT_ARROW = 22;
    private static final int[] SLOT_REPLACEMENTS = {14, 15, 16, 23, 24, 25, 32, 33, 34};
    private static final int SLOT_BACK = 45;
    private static final int SLOT_MODE = 47;
    private static final int SLOT_ENABLED = 48;
    private static final int SLOT_SAVE = 49;
    private static final int SLOT_INFO = 51;
    private static final int SLOT_DELETE = 53;
    private static final long DELETE_CONFIRM_MS = 5000L;

    private String id;
    private final boolean autoId;
    private boolean isNew;

    private ItemStack target;
    private final ItemStack[] replacements = new ItemStack[Rule.MAX_REPLACEMENTS];
    private MatchMode mode = MatchMode.EXACT;
    private boolean enabled = true;

    private ItemStack picked;
    private boolean dirty;
    private long deleteArmedAt;
    /** 介面自己重新開啟時，不要當成「沒儲存就關閉」。 */
    private boolean reopening;

    private RuleEditGui(ItemReplace plugin, String id, boolean isNew, boolean autoId) {
        super(plugin);
        this.id = id;
        this.isNew = isNew;
        this.autoId = autoId;
    }

    public static RuleEditGui forNew(ItemReplace plugin, String id, boolean autoId) {
        return new RuleEditGui(plugin, id, true, autoId);
    }

    public static RuleEditGui forExisting(ItemReplace plugin, Rule rule) {
        RuleEditGui gui = new RuleEditGui(plugin, rule.id(), false, false);
        gui.target = rule.target().clone();
        List<ItemStack> list = rule.replacements();
        for (int i = 0; i < list.size() && i < gui.replacements.length; i++) {
            gui.replacements[i] = list.get(i).clone();
        }
        gui.mode = rule.mode();
        gui.enabled = rule.enabled();
        return gui;
    }

    @Override
    public void open(Player player) {
        inventory = Bukkit.createInventory(this, SIZE, plugin.messages().get("gui.edit.title", "id", id));
        render();
        reopening = true;
        try {
            player.openInventory(inventory);
        } finally {
            reopening = false;
        }
    }

    private void render() {
        Messages messages = plugin.messages();
        GuiItems items = plugin.guiItems();

        ItemStack filler = items.button(Material.GRAY_STAINED_GLASS_PANE, messages.itemName("gui.filler"), null, false);
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        // 已選取的物品與數量按鈕
        if (picked == null) {
            inventory.setItem(SLOT_PICKED, items.button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    messages.itemName("gui.edit.picked-empty"), messages.list("gui.edit.picked-empty-lore"), false));
        } else {
            inventory.setItem(SLOT_PICKED, items.display(picked, null,
                    messages.list("gui.edit.picked-lore", "amount", picked.getAmount()), false));
            List<net.kyori.adventure.text.Component> amountLore = messages.list("gui.edit.amount-lore");
            inventory.setItem(SLOT_MINUS_10, items.button(Material.RED_STAINED_GLASS_PANE,
                    messages.itemName("gui.edit.amount-minus-10"), amountLore, false));
            inventory.setItem(SLOT_MINUS_1, items.button(Material.PINK_STAINED_GLASS_PANE,
                    messages.itemName("gui.edit.amount-minus-1"), amountLore, false));
            inventory.setItem(SLOT_PLUS_1, items.button(Material.LIME_STAINED_GLASS_PANE,
                    messages.itemName("gui.edit.amount-plus-1"), amountLore, false));
            inventory.setItem(SLOT_PLUS_10, items.button(Material.GREEN_STAINED_GLASS_PANE,
                    messages.itemName("gui.edit.amount-plus-10"), amountLore, false));
        }

        // 左邊：目標
        inventory.setItem(SLOT_TARGET_LABEL, items.button(Material.TARGET,
                messages.itemName("gui.edit.target-label"), messages.list("gui.edit.target-label-lore"), false));
        inventory.setItem(SLOT_TARGET, target == null ? null
                : items.display(target, null, messages.list("gui.edit.target-lore"), false));

        // 右邊：替換成
        inventory.setItem(SLOT_ARROW, items.button(Material.SPECTRAL_ARROW,
                messages.itemName("gui.edit.arrow"), messages.list("gui.edit.arrow-lore"), false));
        for (int i = 0; i < SLOT_REPLACEMENTS.length; i++) {
            ItemStack item = replacements[i];
            inventory.setItem(SLOT_REPLACEMENTS[i], item == null ? null
                    : items.display(item, null,
                            messages.list("gui.edit.replacement-lore", "amount", item.getAmount()), false));
        }

        // 最後一排
        inventory.setItem(SLOT_BACK, items.button(Material.OAK_DOOR,
                messages.itemName("gui.edit.back"), messages.list("gui.edit.back-lore"), false));
        inventory.setItem(SLOT_MODE, items.button(mode == MatchMode.EXACT ? Material.COMPARATOR : Material.PAPER,
                messages.itemName("gui.edit.mode", "mode", messages.get(mode.messageKey())),
                messages.list("gui.edit.mode-lore"), false));
        inventory.setItem(SLOT_ENABLED, items.button(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                messages.itemName(enabled ? "gui.edit.enabled-on" : "gui.edit.enabled-off"),
                messages.list("gui.edit.enabled-lore"), false));
        inventory.setItem(SLOT_SAVE, items.button(Material.WRITABLE_BOOK,
                messages.itemName(dirty ? "gui.edit.save-dirty" : "gui.edit.save"),
                messages.list(dirty ? "gui.edit.save-dirty-lore" : "gui.edit.save-lore"), dirty));
        Rule saved = plugin.rules().get(id);
        inventory.setItem(SLOT_INFO, items.button(Material.BOOK, messages.itemName("gui.edit.info"),
                messages.list("gui.edit.info-lore", "status", RuleText.status(messages, isNew ? null : saved)), false));
        if (!isNew) {
            boolean armed = deleteArmed();
            inventory.setItem(SLOT_DELETE, items.button(armed ? Material.TNT : Material.LAVA_BUCKET,
                    messages.itemName(armed ? "gui.edit.delete-armed" : "gui.edit.delete"),
                    messages.list("gui.edit.delete-lore"), armed));
        }
    }

    private boolean deleteArmed() {
        return deleteArmedAt != 0 && System.currentTimeMillis() - deleteArmedAt <= DELETE_CONFIRM_MS;
    }

    @Override
    public void onLeftClick(Player player, int rawSlot, boolean top, ItemStack clicked) {
        Messages messages = plugin.messages();

        if (!top) {
            // 點自己背包裡的物品 = 選取（複製一份，原物品不動）
            if (clicked == null || clicked.isEmpty()) {
                return;
            }
            int maxBytes = plugin.settings().maxItemBytes;
            int bytes = clicked.serializeAsBytes().length;
            if (bytes > maxBytes) {
                messages.send(player, "edit.item-too-large", "size", bytes / 1024, "max", maxBytes / 1024);
                return;
            }
            picked = clicked.clone();
            render();
            return;
        }
        if (rawSlot < 0 || rawSlot >= SIZE) {
            return;
        }
        if (rawSlot != SLOT_DELETE) {
            deleteArmedAt = 0;
        }

        switch (rawSlot) {
            case SLOT_PICKED -> picked = null;
            case SLOT_MINUS_10 -> adjustPicked(player, -10);
            case SLOT_MINUS_1 -> adjustPicked(player, -1);
            case SLOT_PLUS_1 -> adjustPicked(player, 1);
            case SLOT_PLUS_10 -> adjustPicked(player, 10);
            case SLOT_TARGET -> {
                ItemStack previous = target;
                if (picked != null) {
                    target = picked.asOne();
                    picked = previous;
                    dirty = true;
                } else if (previous != null) {
                    picked = previous;
                    target = null;
                    dirty = true;
                } else {
                    messages.send(player, "edit.need-picked");
                }
            }
            case SLOT_MODE -> {
                mode = mode.next();
                dirty = true;
            }
            case SLOT_ENABLED -> {
                enabled = !enabled;
                dirty = true;
            }
            case SLOT_SAVE -> {
                save(player);
                return;
            }
            case SLOT_DELETE -> {
                delete(player);
                return;
            }
            case SLOT_BACK -> {
                new RuleListGui(plugin, 0).open(player);
                return;
            }
            default -> {
                int index = replacementIndex(rawSlot);
                if (index < 0) {
                    return;
                }
                ItemStack previous = replacements[index];
                if (picked != null) {
                    replacements[index] = picked;
                    picked = previous;
                    dirty = true;
                } else if (previous != null) {
                    picked = previous;
                    replacements[index] = null;
                    dirty = true;
                } else {
                    messages.send(player, "edit.need-picked");
                }
            }
        }
        render();
    }

    private static int replacementIndex(int rawSlot) {
        for (int i = 0; i < SLOT_REPLACEMENTS.length; i++) {
            if (SLOT_REPLACEMENTS[i] == rawSlot) {
                return i;
            }
        }
        return -1;
    }

    private void adjustPicked(Player player, int delta) {
        if (picked == null) {
            plugin.messages().send(player, "edit.need-picked");
            return;
        }
        int max = Math.max(1, picked.getMaxStackSize());
        picked.setAmount(Math.max(1, Math.min(max, picked.getAmount() + delta)));
    }

    private void save(Player player) {
        Messages messages = plugin.messages();
        boolean exists = plugin.rules().get(id) != null;
        // 介面開著的期間權限可能被收回，儲存時再檢查一次
        if (!player.hasPermission(exists ? "itemreplace.edit" : "itemreplace.create")) {
            messages.send(player, "general.no-permission");
            return;
        }
        Rule current = plugin.rules().get(id);
        if (!isNew && current != null && current.enabled() != enabled
                && !player.hasPermission("itemreplace.toggle")) {
            messages.send(player, "general.no-permission");
            return;
        }
        if (target == null) {
            messages.send(player, "edit.need-target");
            return;
        }
        boolean renamed = false;
        if (isNew && exists) {
            // 編輯期間有人用了同一個名稱
            if (!autoId) {
                messages.send(player, "general.rule-exists", "id", id);
                return;
            }
            id = plugin.rules().nextAutoId();
            exists = false;
            renamed = true;
        }
        if (!exists && plugin.rules().size() >= plugin.settings().maxRules) {
            messages.send(player, "general.max-rules", "max", plugin.settings().maxRules);
            return;
        }
        List<ItemStack> list = new ArrayList<>(replacements.length);
        for (ItemStack item : replacements) {
            if (item != null && !item.isEmpty()) {
                list.add(item.clone());
            }
        }
        Rule rule = plugin.rules().create(id, enabled, mode, target.clone(), list);
        if (rule == null) {
            int max = plugin.settings().maxItemBytes / 1024;
            messages.send(player, "edit.item-too-large", "size", ">" + max, "max", max);
            return;
        }
        plugin.rules().put(rule);
        isNew = false;
        dirty = false;
        messages.send(player, "rule.saved", "id", id);
        Rule stored = plugin.rules().get(id);
        if (stored != null && stored.conflictWith() != null) {
            messages.send(player, "rule.conflict", "id", id, "other", stored.conflictWith());
        }
        if (renamed) {
            open(player);
        } else {
            render();
        }
    }

    private void delete(Player player) {
        Messages messages = plugin.messages();
        if (isNew) {
            return;
        }
        if (!player.hasPermission("itemreplace.remove")) {
            messages.send(player, "general.no-permission");
            return;
        }
        if (!deleteArmed()) {
            deleteArmedAt = System.currentTimeMillis();
            messages.send(player, "edit.delete-confirm", "id", id);
            render();
            return;
        }
        deleteArmedAt = 0;
        dirty = false;
        if (plugin.rules().remove(id)) {
            messages.send(player, "rule.removed", "id", id);
        }
        new RuleListGui(plugin, 0).open(player);
    }

    @Override
    public void onClose(Player player) {
        if (dirty && !reopening) {
            dirty = false;
            plugin.messages().send(player, "rule.unsaved", "id", id);
        }
    }
}
