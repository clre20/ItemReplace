package clre20.itemReplace.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import clre20.itemReplace.gui.GuiHolder;

/** 箱子 UI 的點擊處理：一律取消原始事件，只接受左鍵。 */
public final class GuiListener implements Listener {

    private static GuiHolder holderOf(Inventory top) {
        // getHolder(false)：不建立方塊快照，一般容器在這裡就直接返回
        InventoryHolder holder = top.getHolder(false);
        return holder instanceof GuiHolder gui ? gui : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        GuiHolder holder = holderOf(top);
        if (holder == null) {
            return;
        }
        // 不論點哪裡、用什麼方式，物品都不能被移動
        event.setCancelled(true);
        if (event.getClick() != ClickType.LEFT) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() == null) {
            return;
        }
        int rawSlot = event.getRawSlot();
        holder.onLeftClick(player, rawSlot, rawSlot >= 0 && rawSlot < top.getSize(), event.getCurrentItem());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (holderOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        GuiHolder holder = holderOf(event.getView().getTopInventory());
        if (holder != null && event.getPlayer() instanceof Player player) {
            holder.onClose(player);
        }
    }

    /** 插件停用或重載時關閉所有還開著的介面。 */
    public static void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (holderOf(player.getOpenInventory().getTopInventory()) != null) {
                player.closeInventory();
            }
        }
    }
}
