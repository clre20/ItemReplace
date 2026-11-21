package clre20.itemReplace.listener;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.config.Messages;
import clre20.itemReplace.update.UpdateChecker;

/** 有新版本時，具備 itemreplace.version 權限的玩家登入 3 秒後收到提醒。 */
public final class UpdateNotifier implements Listener {

    private static final long DELAY_TICKS = 60L;

    private final ItemReplace plugin;

    public UpdateNotifier(ItemReplace plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.settings().updateNotifyOnJoin || !event.getPlayer().hasPermission("itemreplace.version")) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(uuid) == null) {
                return;
            }
            // 走快取：10 分鐘內不會重複向 GitHub 發請求
            plugin.updateChecker().check(result -> {
                if (result.state() != UpdateChecker.State.NEWER) {
                    return;
                }
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.hasPermission("itemreplace.version")) {
                    return;
                }
                plugin.messages().send(player, "update.join-notify",
                        "latest", result.latest(),
                        "version", plugin.updateChecker().currentVersion(),
                        "url", Messages.raw(UpdateChecker.RELEASES_URL));
            });
        }, DELAY_TICKS);
    }
}
