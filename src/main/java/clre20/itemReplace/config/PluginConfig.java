package clre20.itemReplace.config;

import java.util.Locale;

import org.bukkit.configuration.file.YamlConfiguration;

/** config.yml 的快取，所有數值都有範圍防呆與預設值。 */
public final class PluginConfig {

    public final boolean triggerPlayerInventory;
    public final boolean triggerContainerOpen;
    public final boolean triggerItemSpawn;
    public final boolean triggerEntitiesLoad;

    public final boolean nestedEnabled;
    public final int nestedMaxDepth;

    public final boolean overflowDrop;
    public final int overflowMaxDrops;

    public final int maxRules;
    public final int maxItemBytes;

    public final boolean logEnabled;
    public final boolean logConsole;
    public final int logFlushSeconds;
    public final int logRetentionDays;
    public final long logMaxFileBytes;

    public final int saveIntervalSeconds;

    public final boolean updateCheckOnStartup;
    public final boolean updateNotifyOnJoin;

    public PluginConfig(YamlConfiguration yaml) {
        triggerPlayerInventory = yaml.getBoolean("triggers.player-inventory", true);
        triggerContainerOpen = yaml.getBoolean("triggers.container-open", true);
        triggerItemSpawn = yaml.getBoolean("triggers.item-spawn", true);
        triggerEntitiesLoad = yaml.getBoolean("triggers.entities-load", true);

        nestedEnabled = yaml.getBoolean("nested.enabled", true);
        nestedMaxDepth = clamp(yaml.getInt("nested.max-depth", 3), 1, 5);

        String mode = String.valueOf(yaml.getString("overflow.mode", "DROP")).trim().toUpperCase(Locale.ROOT);
        overflowDrop = !mode.equals("DISCARD");
        overflowMaxDrops = clamp(yaml.getInt("overflow.max-drops", 64), 0, 1024);

        maxRules = clamp(yaml.getInt("limits.max-rules", 500), 1, 5000);
        maxItemBytes = clamp(yaml.getInt("limits.max-item-size-kb", 256), 1, 4096) * 1024;

        logEnabled = yaml.getBoolean("log.enabled", true);
        logConsole = yaml.getBoolean("log.console", false);
        logFlushSeconds = clamp(yaml.getInt("log.flush-interval-seconds", 10), 1, 3600);
        logRetentionDays = clamp(yaml.getInt("log.retention-days", 14), 1, 3650);
        logMaxFileBytes = clamp(yaml.getInt("log.max-file-size-mb", 10), 1, 1024) * 1024L * 1024L;

        saveIntervalSeconds = clamp(yaml.getInt("storage.save-interval-seconds", 10), 1, 3600);

        updateCheckOnStartup = yaml.getBoolean("update-checker.check-on-startup", true);
        updateNotifyOnJoin = yaml.getBoolean("update-checker.notify-on-join", true);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
