package clre20.itemReplace.model;

import java.util.Locale;

/** 規則的比對方式。 */
public enum MatchMode {

    /** 材質與所有資料元件（NBT）都相同才算。 */
    EXACT("mode.exact"),

    /** 只看材質，不管 NBT。 */
    MATERIAL("mode.material");

    private final String messageKey;

    MatchMode(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }

    public MatchMode next() {
        return this == EXACT ? MATERIAL : EXACT;
    }

    public static MatchMode parse(String text) {
        if (text != null && text.trim().toUpperCase(Locale.ROOT).equals("MATERIAL")) {
            return MATERIAL;
        }
        return EXACT;
    }
}
