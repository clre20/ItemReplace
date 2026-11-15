package clre20.itemReplace.replace;

/** 物品被替換時所在的位置（寫紀錄用）。 */
public enum Where {

    PLAYER("log-file.where.player"),
    CONTAINER("log-file.where.container"),
    GROUND("log-file.where.ground"),
    ITEM_FRAME("log-file.where.item-frame"),
    ARMOR_STAND("log-file.where.armor-stand"),
    ITEM_DISPLAY("log-file.where.item-display");

    private final String messageKey;

    Where(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
