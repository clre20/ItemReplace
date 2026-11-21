package clre20.itemReplace.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** 文字工具：MiniMessage 解析，並相容 &amp; 色碼。 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String[] LEGACY_TAGS = new String[128];

    static {
        LEGACY_TAGS['0'] = "black";
        LEGACY_TAGS['1'] = "dark_blue";
        LEGACY_TAGS['2'] = "dark_green";
        LEGACY_TAGS['3'] = "dark_aqua";
        LEGACY_TAGS['4'] = "dark_red";
        LEGACY_TAGS['5'] = "dark_purple";
        LEGACY_TAGS['6'] = "gold";
        LEGACY_TAGS['7'] = "gray";
        LEGACY_TAGS['8'] = "dark_gray";
        LEGACY_TAGS['9'] = "blue";
        LEGACY_TAGS['a'] = "green";
        LEGACY_TAGS['b'] = "aqua";
        LEGACY_TAGS['c'] = "red";
        LEGACY_TAGS['d'] = "light_purple";
        LEGACY_TAGS['e'] = "yellow";
        LEGACY_TAGS['f'] = "white";
        LEGACY_TAGS['k'] = "obfuscated";
        LEGACY_TAGS['l'] = "bold";
        LEGACY_TAGS['m'] = "strikethrough";
        LEGACY_TAGS['n'] = "underlined";
        LEGACY_TAGS['o'] = "italic";
        LEGACY_TAGS['r'] = "reset";
    }

    private Text() {
    }

    /** 把 &amp;a、&amp;l、&amp;#RRGGBB 轉成 MiniMessage 標籤；顏色碼會先重設樣式（與原版行為一致）。 */
    public static String legacyToMini(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length() + 16);
        int length = input.length();
        for (int i = 0; i < length; i++) {
            char c = input.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < length) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                if (code == '#' && i + 7 < length && isHex(input, i + 2, 6)) {
                    out.append("<reset><#").append(input, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                String tag = code < LEGACY_TAGS.length ? LEGACY_TAGS[code] : null;
                if (tag != null) {
                    boolean color = (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
                    if (color) {
                        out.append("<reset>");
                    }
                    out.append('<').append(tag).append('>');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static Component parse(String mini, TagResolver resolver) {
        return MINI.deserialize(mini, resolver);
    }

    public static Component parse(String mini) {
        return MINI.deserialize(mini);
    }

    /** 物品名稱與 Lore 預設是斜體，沒有指定時改為非斜體。 */
    public static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
