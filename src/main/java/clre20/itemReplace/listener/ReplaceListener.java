package clre20.itemReplace.listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.replace.ReplaceEngine;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;

/**
 * 被動觸發的四個時機。每個監聽器都先做最便宜的判斷：
 * 沒有生效的規則 → 直接返回；物品的材質沒有任何規則用到 → 直接返回。
 */
public final class ReplaceListener implements Listener {

    private static final String BYPASS = "itemreplace.bypass";
    private static final long ALL_SLOTS = -1L;
    private static final int NEVER_PICKUP = Short.MAX_VALUE;
    private static final int MAX_SPAWN_QUEUE = 2048;

    private final ItemReplace plugin;
    private final ReplaceEngine engine;

    /** 等下一個 tick 檢查的玩家與格子（位元遮罩）。只存 UUID；離線與停用時清除。 */
    private final Map<UUID, long[]> pending = new HashMap<>();

    public ReplaceListener(ItemReplace plugin, ReplaceEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    /** 這個 tick 生成、等下一個 tick 檢查的掉落物。只保留一個 tick 就清空。 */
    private final List<Item> spawned = new ArrayList<>();

    public void clear() {
        pending.clear();
        spawned.clear();
    }

    // ------------------------------------------------------------------ 玩家身上

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        if (!engine.isActive() || !plugin.settings().triggerPlayerInventory) {
            return;
        }
        if (!engine.isCandidate(event.getNewItemStack())) {
            return;
        }
        int slot = event.getSlot();
        queue(event.getPlayer().getUniqueId(), slot >= 0 && slot < Long.SIZE ? 1L << slot : ALL_SLOTS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!engine.isActive() || !plugin.settings().triggerPlayerInventory) {
            return;
        }
        queue(event.getPlayer().getUniqueId(), ALL_SLOTS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    /** 同一位玩家同一個 tick 只排一次工作；事件裡不直接改背包。 */
    private void queue(UUID uuid, long mask) {
        long[] slots = pending.get(uuid);
        if (slots != null) {
            slots[0] |= mask;
            return;
        }
        pending.put(uuid, new long[]{mask});
        Bukkit.getScheduler().runTask(plugin, () -> {
            long[] queued = pending.remove(uuid);
            if (queued == null) {
                return;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !engine.isActive() || player.hasPermission(BYPASS)) {
                return;
            }
            engine.processPlayer(player, queued[0]);
        });
    }

    // ------------------------------------------------------------------ 開啟容器

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!engine.isActive() || !plugin.settings().triggerContainerOpen) {
            return;
        }
        Inventory inventory = event.getInventory();
        if (!isRealContainer(inventory)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player) || player.hasPermission(BYPASS)) {
            return;
        }
        Location location = inventory.getLocation();
        engine.processInventory(inventory, location != null ? location : player.getLocation(), player);
    }

    /**
     * 只處理真正的容器（方塊、實體、終界箱）。
     * 其他插件自己做的選單不會動，避免把別人介面裡的圖示換掉。
     */
    private static boolean isRealContainer(Inventory inventory) {
        InventoryType type = inventory.getType();
        if (type == InventoryType.ENDER_CHEST) {
            return true;
        }
        if (type == InventoryType.MERCHANT || type == InventoryType.PLAYER
                || type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof BlockInventoryHolder || holder instanceof DoubleChest) {
            return true;
        }
        return holder instanceof Entity && !(holder instanceof Player);
    }

    // ------------------------------------------------------------------ 掉落物

    /**
     * 掉落物在下一個 tick 才處理，同一個 tick 的全部掉落物共用一個工作。
     * 原因：/give 之類的指令會先生成一個「只有動畫、撿不起來」的假掉落物，
     * 生成當下還分辨不出來；如果直接替換，多出來的物品會變成真的掉落物（等於複製）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!engine.isActive() || engine.isDropping() || !plugin.settings().triggerItemSpawn) {
            return;
        }
        Item item = event.getEntity();
        if (!engine.isCandidate(item.getItemStack())) {
            return;
        }
        if (spawned.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, this::processSpawned);
        }
        if (spawned.size() < MAX_SPAWN_QUEUE) {
            spawned.add(item);
        }
    }

    private void processSpawned() {
        if (spawned.isEmpty()) {
            return;
        }
        Item[] batch = spawned.toArray(new Item[0]);
        spawned.clear();
        if (!engine.isActive()) {
            return;
        }
        for (Item item : batch) {
            // 撿不起來的掉落物（假掉落物、其他插件的展示用物品）不處理
            if (!item.isValid() || item.getPickupDelay() >= NEVER_PICKUP) {
                continue;
            }
            UUID thrower = item.getThrower();
            if (thrower != null) {
                Player player = Bukkit.getPlayer(thrower);
                if (player != null && player.hasPermission(BYPASS)) {
                    continue;
                }
            }
            if (engine.processItemEntity(item)) {
                item.remove();
            }
        }
    }

    // ------------------------------------------------------------------ 區塊實體載入

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!engine.isActive() || !plugin.settings().triggerEntitiesLoad) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            switch (entity) {
                case Item item -> {
                    if (engine.processItemEntity(item)) {
                        item.remove();
                    }
                }
                case ItemFrame frame -> engine.processItemFrame(frame);
                case ArmorStand stand -> engine.processArmorStand(stand);
                case ItemDisplay display -> engine.processItemDisplay(display);
                default -> {
                }
            }
        }
    }
}
