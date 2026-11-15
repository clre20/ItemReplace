package clre20.itemReplace.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;

/** 建立介面用的物品。每個放進介面的物品都帶有 PDC 標籤 itemreplace:gui（簽署）。 */
public final class GuiItems {

    private final NamespacedKey guiKey;

    public GuiItems(Plugin plugin) {
        this.guiKey = new NamespacedKey(plugin, "gui");
    }

    public NamespacedKey key() {
        return guiKey;
    }

    public ItemStack button(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.customName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(guiKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    /** 規則物品的展示用複本：保留原本的外觀，名稱可覆寫，說明接在原本的 Lore 後面（或整個取代）。 */
    public ItemStack display(ItemStack source, Component name, List<Component> lore, boolean replaceLore) {
        ItemStack item = source.clone();
        item.editMeta(meta -> {
            if (name != null) {
                meta.customName(name);
            }
            if (lore != null && !lore.isEmpty()) {
                List<Component> merged = new ArrayList<>();
                List<Component> original = replaceLore ? null : meta.lore();
                if (original != null) {
                    merged.addAll(original);
                }
                merged.addAll(lore);
                meta.lore(merged);
            }
            meta.getPersistentDataContainer().set(guiKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }
}
