package clre20.itemReplace.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 讀取設定檔並和 jar 內的預設檔比對：缺少的項目補回、已不使用的項目移除，
 * 使用者原本的設定值保留。有變動時舊檔備份成 .bak；格式壞掉時備份成 .broken-時間。
 */
public final class YamlFiles {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int LINE_WIDTH = 4096;

    private YamlFiles() {
    }

    /** @param name 資料夾內的檔名（固定字串，不接受外部輸入） */
    public static YamlConfiguration load(JavaPlugin plugin, String name) {
        YamlConfiguration defaults = loadDefaults(plugin, name);
        File file = new File(plugin.getDataFolder(), name);

        if (!file.isFile()) {
            plugin.saveResource(name, false);
            return defaults;
        }

        YamlConfiguration user = new YamlConfiguration();
        user.options().width(LINE_WIDTH);
        try {
            user.load(file);
        } catch (InvalidConfigurationException | IOException e) {
            // 這兩行警告寫在程式裡：此時 messages.yml 可能就是壞掉的那個檔案
            File broken = new File(plugin.getDataFolder(), name + ".broken-" + LocalDateTime.now().format(STAMP));
            try {
                Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.saveResource(name, false);
                plugin.getLogger().warning(name + " 格式錯誤，已備份為 " + broken.getName() + " 並重新產生預設檔。");
            } catch (IOException | RuntimeException io) {
                plugin.getLogger().log(Level.WARNING, name + " 格式錯誤且無法備份，這次先使用預設值。", io);
            }
            return defaults;
        }

        Set<String> defaultKeys = leafKeys(defaults);
        Set<String> userKeys = leafKeys(user);
        boolean changed = !userKeys.equals(defaultKeys);
        if (!changed) {
            for (String key : defaultKeys) {
                if (!compatible(defaults.get(key), user.get(key))) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            user.setDefaults(defaults);
            return user;
        }

        // 以預設檔（含註解）為底，把使用者原本的值填回去
        for (String key : defaultKeys) {
            if (user.contains(key, true) && !user.isConfigurationSection(key)
                    && compatible(defaults.get(key), user.get(key))) {
                defaults.set(key, user.get(key));
            }
        }
        try {
            File backup = new File(plugin.getDataFolder(), name + ".bak");
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            defaults.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, name + " 補齊後無法寫回檔案，這次先使用記憶體中的設定。", e);
        }
        return defaults;
    }

    private static YamlConfiguration loadDefaults(JavaPlugin plugin, String name) {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().width(LINE_WIDTH);
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "jar 內的 " + name + " 無法讀取。", e);
        }
        return defaults;
    }

    private static Set<String> leafKeys(YamlConfiguration config) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 使用者的值型別和預設值對得上才保留，避免之後讀取時出錯。 */
    private static boolean compatible(Object def, Object value) {
        if (value == null) {
            return false;
        }
        if (def instanceof List) {
            return value instanceof List;
        }
        if (def instanceof Boolean) {
            return value instanceof Boolean;
        }
        if (def instanceof Number) {
            return value instanceof Number;
        }
        if (def instanceof String) {
            return value instanceof String || value instanceof Number || value instanceof Boolean;
        }
        return true;
    }
}
