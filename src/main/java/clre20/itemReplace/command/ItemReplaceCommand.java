package clre20.itemReplace.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.config.Messages;
import clre20.itemReplace.gui.RuleEditGui;
import clre20.itemReplace.gui.RuleListGui;
import clre20.itemReplace.model.Rule;
import clre20.itemReplace.util.RuleText;

/** /itemreplace（別名 /ir）。每個子指令都有自己的權限節點。 */
public final class ItemReplaceCommand implements TabExecutor {

    /** 子指令名稱；權限節點為 itemreplace.<名稱>。 */
    private static final String[] SUBCOMMANDS = {
            "gui", "list", "create", "edit", "remove", "toggle", "reload", "version", "help"
    };
    private static final int MAX_SUGGESTIONS = 50;

    private final ItemReplace plugin;

    public ItemReplaceCommand(ItemReplace plugin) {
        this.plugin = plugin;
    }

    private static String permission(String sub) {
        return "itemreplace." + sub;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.messages();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!isSubcommand(sub)) {
            messages.send(sender, "general.unknown-command", "label", label);
            return true;
        }
        if (!sender.hasPermission(permission(sub))) {
            messages.send(sender, "general.no-permission");
            return true;
        }
        switch (sub) {
            case "help" -> help(sender, label);
            case "gui" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    new RuleListGui(plugin, 0).open(player);
                }
            }
            case "list" -> list(sender);
            case "create" -> create(sender, label, args);
            case "edit" -> edit(sender, label, args);
            case "remove" -> remove(sender, label, args);
            case "toggle" -> toggle(sender, label, args);
            case "reload" -> {
                plugin.reload();
                plugin.messages().send(sender, "general.reloaded", "count", plugin.rules().size());
            }
            case "version" -> {
                messages.send(sender, "update.checking", "version", plugin.updateChecker().currentVersion());
                plugin.updateChecker().check(result -> plugin.updateChecker().report(sender, result));
            }
            default -> {
            }
        }
        return true;
    }

    private static boolean isSubcommand(String sub) {
        for (String name : SUBCOMMANDS) {
            if (name.equals(sub)) {
                return true;
            }
        }
        return false;
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "general.player-only");
        return null;
    }

    /** 取得規則名稱參數；格式不對時回覆並回傳 null。 */
    private String idArgument(CommandSender sender, String label, String[] args, String usage) {
        Messages messages = plugin.messages();
        if (args.length != 2) {
            messages.send(sender, "general.usage", "label", label, "usage", usage);
            return null;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!Rule.isValidId(id)) {
            messages.send(sender, "general.invalid-id");
            return null;
        }
        return id;
    }

    private void help(CommandSender sender, String label) {
        Messages messages = plugin.messages();
        messages.send(sender, "help.header", "version", plugin.updateChecker().currentVersion());
        for (String sub : SUBCOMMANDS) {
            if (sender.hasPermission(permission(sub))) {
                messages.send(sender, "help." + sub, "label", label);
            }
        }
    }

    private void list(CommandSender sender) {
        Messages messages = plugin.messages();
        if (plugin.rules().size() == 0) {
            messages.send(sender, "list.empty");
            return;
        }
        messages.send(sender, "list.header", "count", plugin.rules().size());
        for (Rule rule : plugin.rules().all()) {
            messages.send(sender, "list.line",
                    "id", rule.id(),
                    "status", RuleText.status(messages, rule),
                    "mode", messages.get(rule.mode().messageKey()),
                    "target", rule.target().effectiveName(),
                    "replacements", RuleText.replacements(messages, rule));
        }
    }

    private void create(CommandSender sender, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        String id = idArgument(sender, label, args, "create <名稱>");
        if (id == null) {
            return;
        }
        Messages messages = plugin.messages();
        if (plugin.rules().get(id) != null) {
            messages.send(sender, "general.rule-exists", "id", id);
            return;
        }
        if (plugin.rules().size() >= plugin.settings().maxRules) {
            messages.send(sender, "general.max-rules", "max", plugin.settings().maxRules);
            return;
        }
        RuleEditGui.forNew(plugin, id, false).open(player);
    }

    private void edit(CommandSender sender, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        String id = idArgument(sender, label, args, "edit <名稱>");
        if (id == null) {
            return;
        }
        Rule rule = plugin.rules().get(id);
        if (rule == null) {
            plugin.messages().send(sender, "general.rule-not-found", "id", id);
            return;
        }
        RuleEditGui.forExisting(plugin, rule).open(player);
    }

    private void remove(CommandSender sender, String label, String[] args) {
        String id = idArgument(sender, label, args, "remove <名稱>");
        if (id == null) {
            return;
        }
        Messages messages = plugin.messages();
        if (!plugin.rules().remove(id)) {
            messages.send(sender, "general.rule-not-found", "id", id);
            return;
        }
        messages.send(sender, "rule.removed", "id", id);
    }

    private void toggle(CommandSender sender, String label, String[] args) {
        String id = idArgument(sender, label, args, "toggle <名稱>");
        if (id == null) {
            return;
        }
        Messages messages = plugin.messages();
        Rule rule = plugin.rules().get(id);
        if (rule == null) {
            messages.send(sender, "general.rule-not-found", "id", id);
            return;
        }
        Rule updated = plugin.rules().setEnabled(id, !rule.enabled());
        if (updated == null) {
            return;
        }
        messages.send(sender, updated.enabled() ? "rule.enabled" : "rule.disabled", "id", id);
        if (updated.conflictWith() != null) {
            messages.send(sender, "rule.conflict", "id", id, "other", updated.conflictWith());
        }
    }

    // ------------------------------------------------------------------ Tab 補全

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBCOMMANDS) {
                // 只補全有權限的子指令
                if (sub.startsWith(typed) && sender.hasPermission(permission(sub))) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean takesRule = sub.equals("edit") || sub.equals("remove") || sub.equals("toggle");
            if (!takesRule || !sender.hasPermission(permission(sub))) {
                return out;
            }
            String typed = args[1].toLowerCase(Locale.ROOT);
            for (Rule rule : plugin.rules().all()) {
                if (rule.id().startsWith(typed)) {
                    out.add(rule.id());
                    if (out.size() >= MAX_SUGGESTIONS) {
                        break;
                    }
                }
            }
        }
        return out;
    }
}
