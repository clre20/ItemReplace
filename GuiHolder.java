package clre20.itemReplace.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import clre20.itemReplace.ItemReplace;

/**
 * 本插件所有箱子 UI 的持有者（簽署）。
 * 其他插件可以用 {@code inventory.getHolder(false) instanceof GuiHolder}，
 * 或 {@link #getPlugin()}、{@link #getSignature()} 辨識這是 ItemReplace 的介面。
 */
public abstract class GuiHolder implements InventoryHolder {

    public static final String SIGNATURE = "ItemReplace";

    protected final ItemReplace plugin;
    protected Inventory inventory;

    protected GuiHolder(ItemReplace plugin) {
        this.plugin = plugin;
    }

    public ItemReplace getPlugin() {
        return plugin;
    }

    public String getSignature() {
        return SIGNATURE;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public abstract void open(Player player);

    /**
     * 只會收到左鍵點擊；原始事件已經被取消。
     *
     * @param top     true = 點的是介面本身；false = 點的是玩家自己的背包
     * @param clicked 被點的物品，可能為 null
     */
    public abstract void onLeftClick(Player player, int rawSlot, boolean top, ItemStack clicked);

    public void onClose(Player player) {
    }
}
