package clre20.itemReplace;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import clre20.itemReplace.command.ItemReplaceCommand;
import clre20.itemReplace.config.Messages;
import clre20.itemReplace.config.PluginConfig;
import clre20.itemReplace.config.YamlFiles;
import clre20.itemReplace.gui.GuiItems;
import clre20.itemReplace.listener.GuiListener;
import clre20.itemReplace.listener.ReplaceListener;
import clre20.itemReplace.listener.UpdateNotifier;
import clre20.itemReplace.log.ReplaceLog;
import clre20.itemReplace.replace.ReplaceEngine;
import clre20.itemReplace.rule.RuleManager;
import clre20.itemReplace.update.UpdateChecker;

/** ItemReplace 主類別。 */
public final class ItemReplace extends JavaPlugin {

    /** 設定與訊息在重載時整個換新；背景執行緒（寫檔）也會讀，所以是 volatile。 */
    private volatile PluginConfig settings;
    private volatile Messages messages;

    private ReplaceEngine engine;
    private RuleManager rules;
    private ReplaceLog replaceLog;
    private GuiItems guiItems;
    private UpdateChecker updateChecker;
    private ReplaceListener replaceListener;

    private BukkitTask saveTask;
    private BukkitTask logTask;

    @Override
    public void onEnable() {
        loadFiles();

        engine = new ReplaceEngine(this);
        engine.configure(settings);
        replaceLog = new ReplaceLog(this);
        guiItems = new GuiItems(this);
        updateChecker = new UpdateChecker(this);
        rules = new RuleManager(this, engine);
        rules.load();

        replaceListener = new ReplaceListener(this, engine);
        Bukkit.getPluginManager().registerEvents(replaceListener, this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(), this);
        Bukkit.getPluginManager().registerEvents(new UpdateNotifier(this), this);

        PluginCommand command = getCommand("itemreplace");
        if (command != null) {
            ItemReplaceCommand executor = new ItemReplaceCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        startTasks();
        getComponentLogger().info(messages.console("console.loaded",
                "count", rules.size(), "active", rules.activeCount()));

        if (getServer().getPluginManager().isPluginEnabled("HttpHub") && clre20.httpHub.api.HttpHubAPI.isAvailable()) {
            clre20.httpHub.api.HttpHubAPI.registerUpdateCheck(this, "clre20/ItemReplace", getPluginMeta().getVersion(), "itemreplace.version", null);
        } else if (settings.updateCheckOnStartup) {
            updateChecker.check(updateChecker::reportToConsole);
        }
    }

    @Override
    public void onDisable() {
        stopTasks();
        GuiListener.closeAll();
        if (replaceListener != null) {
            replaceListener.clear();
        }
        if (engine != null) {
            engine.clear();
        }
        if (rules != null) {
            rules.saveNow();
        }
        if (replaceLog != null) {
            replaceLog.flush();
        }
        if (updateChecker != null) {
            updateChecker.clear();
        }
        Bukkit.getScheduler().cancelTasks(this);
    }

    /** /itemreplace reload：先把還沒寫入的資料寫完，再重新讀取並補齊設定檔。 */
    public void reload() {
        stopTasks();
        GuiListener.closeAll();
        rules.saveNow();
        replaceLog.flush();

        loadFiles();
        engine.configure(settings);
        rules.load();
        startTasks();
    }

    private void loadFiles() {
        settings = new PluginConfig(YamlFiles.load(this, "config.yml"));
        messages = new Messages(YamlFiles.load(this, "messages.yml"));
    }

    private void startTasks() {
        long saveTicks = settings.saveIntervalSeconds * 20L;
        // 主執行緒只檢查一個布林值；真的有變動才產生內容，寫檔交給背景執行緒
        saveTask = Bukkit.getScheduler().runTaskTimer(this, () -> rules.saveIfDirtyAsync(), saveTicks, saveTicks);
        long logTicks = settings.logFlushSeconds * 20L;
        logTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> replaceLog.flush(), logTicks, logTicks);
    }

    private void stopTasks() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (logTask != null) {
            logTask.cancel();
            logTask = null;
        }
    }

    public PluginConfig settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public RuleManager rules() {
        return rules;
    }

    public ReplaceLog replaceLog() {
        return replaceLog;
    }

    public GuiItems guiItems() {
        return guiItems;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }
}
