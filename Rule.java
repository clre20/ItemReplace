package clre20.itemReplace.model;

import java.util.List;
import java.util.regex.Pattern;

import org.bukkit.inventory.ItemStack;

/**
 * 一條替換規則：每 1 個「目標」換成全部的「替換成」物品。
 * 物品在這裡只當樣本使用，不會被修改；要放進世界時一律先複製。
 */
public final class Rule {

    public static final int MAX_REPLACEMENTS = 9;

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

    private final String id;
    private final boolean enabled;
    private final MatchMode mode;
    private final ItemStack target;
    private final List<ItemStack> replacements;
    private final String targetData;
    private final List<String> replacementData;

    /** 換出來的物品會再被哪條規則替換；null 表示沒有循環。由 RuleManager 重新計算。 */
    private String conflictWith;

    public Rule(String id, boolean enabled, MatchMode mode, ItemStack target, List<ItemStack> replacements,
                String targetData, List<String> replacementData) {
        this.id = id;
        this.enabled = enabled;
        this.mode = mode;
        this.target = target;
        this.replacements = List.copyOf(replacements);
        this.targetData = targetData;
        this.replacementData = List.copyOf(replacementData);
    }

    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    public Rule withEnabled(boolean value) {
        return new Rule(id, value, mode, target, replacements, targetData, replacementData);
    }

    public String id() {
        return id;
    }

    public boolean enabled() {
        return enabled;
    }

    public MatchMode mode() {
        return mode;
    }

    /** 目標樣本（數量 1）。請勿修改。 */
    public ItemStack target() {
        return target;
    }

    /** 替換成的樣本（數量 = 每 1 個目標給幾個）。請勿修改。 */
    public List<ItemStack> replacements() {
        return replacements;
    }

    /** 目標的 Base64 資料（存檔用，建立時就算好）。 */
    public String targetData() {
        return targetData;
    }

    public List<String> replacementData() {
        return replacementData;
    }

    public String conflictWith() {
        return conflictWith;
    }

    public void setConflictWith(String conflictWith) {
        this.conflictWith = conflictWith;
    }

    /** 啟用、且沒有循環時才會真的生效。 */
    public boolean active() {
        return enabled && conflictWith == null;
    }

    /** 物品是否已經是這條規則的替換結果（只比材質的規則用來避免重複替換）。 */
    public boolean produces(ItemStack item) {
        for (int i = 0, size = replacements.size(); i < size; i++) {
            if (replacements.get(i).isSimilar(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(ItemStack item) {
        if (mode == MatchMode.EXACT) {
            return target.isSimilar(item);
        }
        return target.getType() == item.getType() && !produces(item);
    }
}
