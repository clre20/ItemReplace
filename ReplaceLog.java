package clre20.itemReplace.log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Location;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.config.Messages;
import clre20.itemReplace.config.PluginConfig;
import clre20.itemReplace.replace.Where;

/**
 * 替換紀錄。紀錄先放在記憶體，由定時的背景工作一次寫入 logs/replace-日期.log；
 * 過期的檔案會自動刪除，單一檔案有大小上限，待寫入的筆數也有上限。
 */
public final class ReplaceLog {

    private static final int MAX_PENDING = 5000;
    private static final Pattern FILE_NAME = Pattern.compile("replace-(\\d{4}-\\d{2}-\\d{2})\\.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ItemReplace plugin;
    private final File folder;

    private final Object queueLock = new Object();
    private List<String> pending = new ArrayList<>();
    private int dropped;

    private final Object ioLock = new Object();
    private LocalDate fullWarnedDay;
    private LocalDate cleanedDay;

    public ReplaceLog(ItemReplace plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "logs");
    }

    /** 主執行緒呼叫：只把一行文字放進記憶體。 */
    public void record(String ruleId, Where where, String playerName, Location location, int amount) {
        PluginConfig config = plugin.settings();
        if (!config.logEnabled && !config.logConsole) {
            return;
        }
        Messages messages = plugin.messages();
        String player = playerName != null ? playerName : messages.plain("log-file.no-player");
        String world = location != null && location.getWorld() != null ? location.getWorld().getName() : "-";
        int x = location != null ? location.getBlockX() : 0;
        int y = location != null ? location.getBlockY() : 0;
        int z = location != null ? location.getBlockZ() : 0;
        String whereName = messages.plain(where.messageKey());

        if (config.logConsole) {
            plugin.getComponentLogger().info(messages.console("console.log-line",
                    "rule", ruleId, "where", whereName, "player", player,
                    "world", world, "x", x, "y", y, "z", z, "amount", amount));
        }
        if (!config.logEnabled) {
            return;
        }
        String line = messages.plain("log-file.line",
                "time", LocalTime.now().format(TIME), "rule", ruleId, "where", whereName, "player", player,
                "world", world, "x", x, "y", y, "z", z, "amount", amount);
        synchronized (queueLock) {
            if (pending.size() >= MAX_PENDING) {
                dropped++;
            } else {
                pending.add(line);
            }
        }
    }

    /** 把記憶體中的紀錄寫入檔案。背景執行緒定時呼叫；關服時在主執行緒呼叫一次。 */
    public void flush() {
        List<String> batch;
        int lost;
        synchronized (queueLock) {
            batch = pending;
            lost = dropped;
            if (batch.isEmpty() && lost == 0) {
                batch = null;
            } else {
                pending = new ArrayList<>();
                dropped = 0;
            }
        }
        PluginConfig config = plugin.settings();
        Messages messages = plugin.messages();
        synchronized (ioLock) {
            LocalDate today = LocalDate.now();
            if (!today.equals(cleanedDay)) {
                cleanedDay = today;
                deleteExpired(today, config.logRetentionDays);
            }
            if (batch == null) {
                return;
            }
            if (lost > 0) {
                plugin.getComponentLogger().warn(messages.console("console.log-dropped", "count", lost));
            }
            if (batch.isEmpty() || !config.logEnabled) {
                return;
            }
            File file = new File(folder, "replace-" + today + ".log");
            try {
                Files.createDirectories(folder.toPath());
                if (file.length() >= config.logMaxFileBytes) {
                    if (!today.equals(fullWarnedDay)) {
                        fullWarnedDay = today;
                        plugin.getComponentLogger().warn(messages.console("console.log-full",
                                "max", config.logMaxFileBytes / (1024L * 1024L)));
                    }
                    return;
                }
                try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (String line : batch) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, messages.plain("console.save-failed",
                        "file", file.getName(), "reason", String.valueOf(e.getMessage())));
            }
        }
    }

    /** 只刪除本插件命名格式的紀錄檔，其他檔案不動。 */
    private void deleteExpired(LocalDate today, int retentionDays) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        LocalDate oldest = today.minusDays(retentionDays);
        for (File file : files) {
            Matcher matcher = FILE_NAME.matcher(file.getName());
            if (!file.isFile() || !matcher.matches()) {
                continue;
            }
            try {
                if (LocalDate.parse(matcher.group(1)).isBefore(oldest)) {
                    Files.deleteIfExists(file.toPath());
                }
            } catch (DateTimeParseException | IOException ignored) {
                // 日期不合法或刪不掉就留著，下次再試
            }
        }
    }
}
