package clre20.itemReplace.gui;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.config.Messages;
import clre20.itemReplace.model.Rule;
import clre20.itemReplace.util.RuleText;

/** 規則列表（6 排）：上 5 排是規則，最後一排是功能按鈕。 */
public final class RuleListGui extends GuiHolder {

    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_INFO = 47;
    private static final int SLOT_CREATE = 49;
    private static final int SLOT_CLOSE = 51;
    private static final int SLOT_NEXT = 53;

    private int page;
    private int pages = 1;
    private final String[] slotIds = new String[PER_PAGE];

    public RuleListGui(ItemReplace plugin, int page) {
        super(plugin);
        this.page = page;
    }

    @Override
    public void open(Player player) {
        Messages messages = plugin.messages();
        List<String> ids = plugin.rules().ids();
        pages = Math.max(1, (ids.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        inventory = Bukkit.createInventory(this, SIZE,
                messages.get("gui.list.title", "page", page + 1, "pages", pages));
        GuiItems items = plugin.guiItems();

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = start + i;
            slotIds[i] = null;
            if (index >= ids.size()) {
                continue;
            }
            Rule rule = plugin.rules().get(ids.get(index));
            if (rule == null) {
                continue;
            }
            slotIds[i] = rule.id();
            inventory.setItem(i, items.display(rule.target(),
                    messages.itemName("gui.list.rule-name", "id", rule.id()),
                    messages.list("gui.list.rule-lore",
                            "id", rule.id(),
                            "status", RuleText.status(messages, rule),
                            "mode", messages.get(rule.mode().messageKey()),
                            "target", rule.target().effectiveName(),
                            "replacements", RuleText.replacements(messages, rule)),
                    true));
        }

        ItemStack filler = items.button(Material.GRAY_STAINED_GLASS_PANE, messages.itemName("gui.filler"), null, false);
        for (int slot = PER_PAGE; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        if (page > 0) {
            inventory.setItem(SLOT_PREV, items.button(Material.ARROW, messages.itemName("gui.list.prev"), null, false));
        }
        if (page < pages - 1) {
            inventory.setItem(SLOT_NEXT, items.button(Material.ARROW, messages.itemName("gui.list.next"), null, false));
        }
        inventory.setItem(SLOT_INFO, items.button(Material.BOOK, messages.itemName("gui.list.info"),
                messages.list("gui.list.info-lore", "count", ids.size()), false));
        inventory.setItem(SLOT_CREATE, items.button(Material.NETHER_STAR, messages.itemName("gui.list.create"),
                messages.list("gui.list.create-lore"), false));
        inventory.setItem(SLOT_CLOSE, items.button(Material.BARRIER, messages.itemName("gui.list.close"), null, false));

        player.openInventory(inventory);
    }

    @Override
    public void onLeftClick(Player player, int rawSlot, boolean top, ItemStack clicked) {
        if (!top || rawSlot < 0 || rawSlot >= SIZE) {
            return;
        }
        Messages messages = plugin.messages();
        if (rawSlot < PER_PAGE) {
            String id = slotIds[rawSlot];
            if (id == null) {
                return;
            }
            // 介面上的每個動作都重新檢查權限
            if (!player.hasPermission("itemreplace.edit")) {
                messages.send(player, "general.no-permission");
                return;
            }
            Rule rule = plugin.rules().get(id);
            if (rule == null) {
                messages.send(player, "general.rule-not-found", "id", id);
                new RuleListGui(plugin, page).open(player);
                return;
            }
            RuleEditGui.forExisting(plugin, rule).open(player);
            return;
        }
        switch (rawSlot) {
            case SLOT_PREV -> {
                if (page > 0) {
                    new RuleListGui(plugin, page - 1).open(player);
                }
            }
            case SLOT_NEXT -> {
                if (page < pages - 1) {
                    new RuleListGui(plugin, page + 1).open(player);
                }
            }
            case SLOT_CREATE -> {
                if (!player.hasPermission("itemreplace.create")) {
                    messages.send(player, "general.no-permission");
                    return;
                }
                if (plugin.rules().size() >= plugin.settings().maxRules) {
                    messages.send(player, "general.max-rules", "max", plugin.settings().maxRules);
                    return;
                }
                RuleEditGui.forNew(plugin, plugin.rules().nextAutoId(), true).open(player);
            }
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }
}
