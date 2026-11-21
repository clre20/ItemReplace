package clre20.itemReplace.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import clre20.itemReplace.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * messages.yml 的快取。變數寫成 {名稱}；變數的值一律當成純文字或元件插入，
 * 不會被解析成格式（避免名稱裡的符號影響顯示）。
 * 載入後內容不再變動，快取用 ConcurrentHashMap，背景執行緒（寫紀錄檔）也能安全讀取。
 */
public final class Messages {

    /** 直接以文字取代的變數值，只給插件自己的固定字串使用（例如網址）。 */
    public record Raw(String value) {
    }

    private final YamlConfiguration yaml;
    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final Map<String, List<String>> listTemplates = new ConcurrentHashMap<>();
    private final String prefix;

    public Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
        this.prefix = Text.legacyToMini(string("prefix"));
    }

    public static Raw raw(String value) {
        return new Raw(value);
    }

    private String string(String key) {
        String value = yaml.getString(key);
        return value != null ? value : key;
    }

    private String template(String key) {
        String cached = templates.get(key);
        if (cached == null) {
            cached = Text.legacyToMini(string(key));
            templates.put(key, cached);
        }
        return cached;
    }

    private List<String> listTemplate(String key) {
        List<String> cached = listTemplates.get(key);
        if (cached == null) {
            List<String> raw = yaml.getStringList(key);
            cached = new ArrayList<>(raw.size());
            for (String line : raw) {
                cached.add(Text.legacyToMini(line));
            }
            listTemplates.put(key, cached);
        }
        return cached;
    }

    /** 這則訊息是否被使用者清空（代表不要顯示）。 */
    public boolean isBlank(String key) {
        return string(key).isEmpty();
    }

    /** @param vars 成對的「變數名稱, 值」；值可以是文字、數字、Component 或 {@link Raw} */
    public Component get(String key, Object... vars) {
        return render(template(key), prefix, vars);
    }

    public List<Component> list(String key, Object... vars) {
        List<String> lines = listTemplate(key);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(Text.noItalic(render(line, prefix, vars)));
        }
        return out;
    }

    /** 物品名稱用：預設不要斜體。 */
    public Component itemName(String key, Object... vars) {
        return Text.noItalic(get(key, vars));
    }

    public void send(CommandSender to, String key, Object... vars) {
        if (to == null || isBlank(key)) {
            return;
        }
        to.sendMessage(get(key, vars));
    }

    /** 後台用：不加前綴（Logger 自己會加插件名稱）。 */
    public Component console(String key, Object... vars) {
        return render(template(key), "", vars);
    }

    /** 紀錄檔用：純文字取代，不解析格式。 */
    public String plain(String key, Object... vars) {
        String text = string(key);
        for (int i = 0; i + 1 < vars.length; i += 2) {
            text = text.replace("{" + vars[i] + "}", String.valueOf(vars[i + 1]));
        }
        return text;
    }

    private static Component render(String template, String prefix, Object... vars) {
        String text = template;
        if (text.contains("{prefix}")) {
            text = text.replace("{prefix}", prefix);
        }
        if (vars.length == 0 || text.indexOf('{') < 0) {
            return Text.parse(text);
        }
        TagResolver.Builder resolver = TagResolver.builder();
        for (int i = 0; i + 1 < vars.length; i += 2) {
            String name = String.valueOf(vars[i]);
            String token = "{" + name + "}";
            if (!text.contains(token)) {
                continue;
            }
            Object value = vars[i + 1];
            if (value instanceof Raw raw) {
                text = text.replace(token, raw.value());
                continue;
            }
            String tag = "v_" + name.replace('-', '_');
            text = text.replace(token, "<" + tag + ">");
            if (value instanceof ComponentLike component) {
                resolver.resolver(Placeholder.component(tag, component));
            } else {
                resolver.resolver(Placeholder.unparsed(tag, String.valueOf(value)));
            }
        }
        return Text.parse(text, resolver.build());
    }
}
