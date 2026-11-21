package clre20.itemReplace.rule;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.model.MatchMode;
import clre20.itemReplace.model.Rule;
import clre20.itemReplace.replace.ReplaceEngine;

/**
 * 規則的記憶體快取與存檔。
 * <p>
 * 讀取只在啟動與重載時做一次；之後所有查詢都走記憶體。
 * 寫入採用「髒資料標記＋定時非同步寫入」，關服與重載時同步寫完；寫檔先寫 .tmp 再取代原檔。
 */
public final class RuleManager {

    private static final String FILE_NAME = "rules.yml";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ItemReplace plugin;
    private final ReplaceEngine engine;
    private final File file;

    /** 主執行緒專用。 */
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    /** 有變動尚未產生存檔內容。寫檔失敗時背景執行緒會重新標記，所以是 volatile。 */
    private volatile boolean dirty;

    /** 最新一份要寫入的內容與它的版本；版本只會遞增，確保舊內容不會蓋掉新內容。 */
    private String latestContent;
    private long snapshotVersion;

    private final Object ioLock = new Object();
    /** 已成功寫入的版本（只在 ioLock 內讀寫）。 */
    private long writtenVersion;

    public RuleManager(ItemReplace plugin, ReplaceEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    // ------------------------------------------------------------------ 查詢

    public Rule get(String id) {
        return id == null ? null : rules.get(id);
    }

    public Collection<Rule> all() {
        return Collections.unmodifiableCollection(rules.values());
    }

    public List<String> ids() {
        return new ArrayList<>(rules.keySet());
    }

    public int size() {
        return rules.size();
    }

    public int activeCount() {
        int count = 0;
        for (Rule rule : rules.values()) {
            if (rule.active()) {
                count++;
            }
        }
        return count;
    }

    /** 產生下一個沒用過的 rule-數字。 */
    public String nextAutoId() {
        for (int i = 1; ; i++) {
            String id = "rule-" + i;
            if (!rules.containsKey(id)) {
                return id;
            }
        }
    }

    // ------------------------------------------------------------------ 修改

    /**
     * 由介面的草稿建立規則物件（序列化在這裡做一次，之後存檔不用再算）。
     *
     * @return null = 有物品的資料超過大小上限
     */
    public Rule create(String id, boolean enabled, MatchMode mode, ItemStack target, List<ItemStack> replacements) {
        int maxBytes = plugin.settings().maxItemBytes;
        byte[] targetBytes = target.serializeAsBytes();
        if (targetBytes.length > maxBytes) {
            return null;
        }
        List<String> replacementData = new ArrayList<>(replacements.size());
        for (ItemStack item : replacements) {
            byte[] bytes = item.serializeAsBytes();
            if (bytes.length > maxBytes) {
                return null;
            }
            replacementData.add(Base64.getEncoder().encodeToString(bytes));
        }
        return new Rule(id, enabled, mode, target, replacements,
                Base64.getEncoder().encodeToString(targetBytes), replacementData);
    }

    public void put(Rule rule) {
        rules.put(rule.id(), rule);
        changed();
    }

    public boolean remove(String id) {
        if (rules.remove(id) == null) {
            return false;
        }
        changed();
        return true;
    }

    /** @return 更新後的規則；找不到時為 null */
    public Rule setEnabled(String id, boolean enabled) {
        Rule rule = rules.get(id);
        if (rule == null) {
            return null;
        }
        Rule updated = rule.withEnabled(enabled);
        rules.put(id, updated);
        changed();
        return updated;
    }

    private void changed() {
        dirty = true;
        rebuild();
    }

    /** 重新計算循環並建立材質索引。規則有變動時才會呼叫。 */
    public void rebuild() {
        Map<Material, Rule[]> enabledIndex = buildIndex(false);
        for (Rule rule : rules.values()) {
            rule.setConflictWith(null);
            if (!rule.enabled()) {
                continue;
            }
            for (ItemStack replacement : rule.replacements()) {
                Rule hit = ReplaceEngine.matchDeep(enabledIndex, replacement);
                if (hit != null) {
                    rule.setConflictWith(hit.id());
                    break;
                }
            }
        }
        engine.setIndex(buildIndex(true));
    }

    private Map<Material, Rule[]> buildIndex(boolean activeOnly) {
        Map<Material, List<Rule>> grouped = new HashMap<>();
        // 完全相符的規則排在前面，同材質時優先於「只比材質」
        for (MatchMode mode : MatchMode.values()) {
            for (Rule rule : rules.values()) {
                if (rule.mode() != mode || !(activeOnly ? rule.active() : rule.enabled())) {
                    continue;
                }
                grouped.computeIfAbsent(rule.target().getType(), key -> new ArrayList<>(2)).add(rule);
            }
        }
        Map<Material, Rule[]> index = new HashMap<>(Math.max(16, grouped.size() * 2));
        for (Map.Entry<Material, List<Rule>> entry : grouped.entrySet()) {
            index.put(entry.getKey(), entry.getValue().toArray(new Rule[0]));
        }
        return index;
    }

    // ------------------------------------------------------------------ 讀取

    /** 啟動與重載時在主執行緒讀一次（物品還原需要伺服器的註冊資料）。 */
    public void load() {
        rules.clear();
        dirty = false;
        if (file.isFile()) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
                readRules(yaml);
            } catch (IOException | InvalidConfigurationException e) {
                String backup = backupBroken();
                plugin.getComponentLogger().warn(plugin.messages().console("console.rule-load-failed",
                        "id", FILE_NAME, "backup", backup, "reason", String.valueOf(e.getMessage())));
            }
        }
        rebuild();
        for (Rule rule : rules.values()) {
            if (rule.conflictWith() != null) {
                plugin.getComponentLogger().warn(plugin.messages().console("console.rule-conflict",
                        "id", rule.id(), "other", rule.conflictWith()));
            }
        }
    }

    private void readRules(YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("rules");
        if (section == null) {
            return;
        }
        int maxRules = plugin.settings().maxRules;
        String backup = null;
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            String reason = null;
            Rule rule = null;
            if (entry == null || !Rule.isValidId(id)) {
                reason = "invalid id";
            } else if (rules.size() >= maxRules) {
                reason = "max-rules";
            } else {
                try {
                    rule = readRule(id, entry);
                } catch (RuntimeException e) {
                    reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
            if (rule != null) {
                rules.put(id, rule);
                continue;
            }
            // 讀不出來的規則之後存檔會消失，所以先把原檔留一份
            if (backup == null) {
                backup = copyBroken();
            }
            plugin.getComponentLogger().warn(plugin.messages().console("console.rule-load-failed",
                    "id", id, "backup", backup, "reason", String.valueOf(reason)));
        }
    }

    private Rule readRule(String id, ConfigurationSection entry) {
        String targetData = entry.getString("target");
        if (targetData == null || targetData.isEmpty()) {
            throw new IllegalArgumentException("missing target");
        }
        ItemStack target = decode(targetData);
        if (target.isEmpty()) {
            throw new IllegalArgumentException("empty target");
        }
        List<String> rawList = entry.getStringList("replacements");
        if (rawList.size() > Rule.MAX_REPLACEMENTS) {
            throw new IllegalArgumentException("too many replacements");
        }
        List<ItemStack> replacements = new ArrayList<>(rawList.size());
        List<String> replacementData = new ArrayList<>(rawList.size());
        for (String raw : rawList) {
            ItemStack item = decode(raw);
            if (item.isEmpty()) {
                continue;
            }
            replacements.add(item);
            replacementData.add(raw);
        }
        return new Rule(id, entry.getBoolean("enabled", true), MatchMode.parse(entry.getString("match")),
                target.asOne(), replacements, targetData, replacementData);
    }

    private ItemStack decode(String base64) {
        // Base64 長度約為原始大小的 4/3，先擋掉明顯過大的資料再解碼
        if (base64.length() > plugin.settings().maxItemBytes / 3L * 4L + 8L) {
            throw new IllegalArgumentException("item data too large");
        }
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
    }

    private String backupBroken() {
        File broken = new File(plugin.getDataFolder(), FILE_NAME + ".broken-" + LocalDateTime.now().format(STAMP));
        try {
            Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, FILE_NAME, e);
        }
        return broken.getName();
    }

    private String copyBroken() {
        File broken = new File(plugin.getDataFolder(), FILE_NAME + ".broken-" + LocalDateTime.now().format(STAMP));
        try {
            Files.copy(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, FILE_NAME, e);
        }
        return broken.getName();
    }

    // ------------------------------------------------------------------ 寫入

    /** 定時工作（主執行緒）：有變動才產生內容，交給背景執行緒寫檔。 */
    public void saveIfDirtyAsync() {
        if (!dirty) {
            return;
        }
        String content = snapshot();
        long version = snapshotVersion;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(content, version));
    }

    /** 關服與重載前（主執行緒）：把最新內容寫完才返回，包含已排程但還沒執行的背景寫入。 */
    public void saveNow() {
        if (dirty) {
            snapshot();
        }
        if (latestContent != null) {
            write(latestContent, snapshotVersion);
        }
    }

    private String snapshot() {
        dirty = false;
        latestContent = serialize();
        snapshotVersion++;
        return latestContent;
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().width(Integer.MAX_VALUE);
        yaml.options().setHeader(List.of(
                "ItemReplace 規則資料（由插件自動寫入）",
                "請用 /itemreplace 的介面編輯；物品以 Base64 保存，手動修改容易讓規則讀不出來。"));
        ConfigurationSection section = yaml.createSection("rules");
        for (Rule rule : rules.values()) {
            ConfigurationSection entry = section.createSection(rule.id());
            entry.set("enabled", rule.enabled());
            entry.set("match", rule.mode().name());
            entry.set("target", rule.targetData());
            entry.set("replacements", rule.replacementData());
        }
        return yaml.saveToString();
    }

    private void write(String content, long version) {
        synchronized (ioLock) {
            if (version <= writtenVersion) {
                return;
            }
            Path target = file.toPath();
            Path temp = target.resolveSibling(FILE_NAME + ".tmp");
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                writtenVersion = version;
            } catch (IOException e) {
                dirty = true; // 下次定時工作再試一次
                plugin.getLogger().log(Level.WARNING, plugin.messages().plain("console.save-failed",
                        "file", FILE_NAME, "reason", String.valueOf(e.getMessage())));
            }
        }
    }
}
