package clre20.itemReplace.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import clre20.itemReplace.ItemReplace;
import clre20.itemReplace.config.Messages;
import net.kyori.adventure.text.Component;

/**
 * 向 GitHub Releases 查詢最新版本。
 * <p>
 * 不輪詢：只在啟動、/itemreplace version、有權限的玩家登入時查；結果快取 10 分鐘（失敗 2 分鐘），
 * 同時間的多個查詢共用同一次請求。網路請求一律在背景執行緒，結果回到主執行緒再通知。
 */
public final class UpdateChecker {

    public static final String REPO = "clre20/ItemReplace";
    public static final String RELEASES_URL = "https://github.com/" + REPO + "/releases";

    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** 測試用：-Ditemreplace.update-api=網址 可改查詢位置。 */
    private static final String API_PROPERTY = "itemreplace.update-api";

    private static final long CACHE_OK_MS = 10L * 60L * 1000L;
    private static final long CACHE_FAIL_MS = 2L * 60L * 1000L;
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final Pattern TAG = Pattern.compile("[vV]?\\d+(\\.\\d+){0,3}([-+][0-9A-Za-z.-]{1,24})?");

    public enum State {
        NEWER, LATEST, DEV, NO_RELEASE, FAILED
    }

    /**
     * @param latest     GitHub 上的版本（去掉 v）；沒有時為 null
     * @param reasonKey  失敗原因的訊息鍵
     * @param httpCode   失敗時的 HTTP 狀態碼；不適用時為 0
     */
    public record Result(State state, String latest, String reasonKey, int httpCode) {
    }

    private final ItemReplace plugin;
    private final String currentVersion;

    /** 以下三個欄位只在主執行緒讀寫。 */
    private Result cached;
    private long cachedAt;
    private List<Consumer<Result>> waiting;

    public UpdateChecker(ItemReplace plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }

    public String currentVersion() {
        return currentVersion;
    }

    /**
     * 取得檢查結果（主執行緒呼叫，callback 也在主執行緒執行）。
     * 快取還有效就直接回傳；已經有請求在進行就一起等，不另外發請求。
     */
    public void check(Consumer<Result> callback) {
        long now = System.currentTimeMillis();
        if (cached != null) {
            long ttl = cached.state() == State.FAILED ? CACHE_FAIL_MS : CACHE_OK_MS;
            if (now - cachedAt < ttl) {
                callback.accept(cached);
                return;
            }
        }
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<>(2);
        waiting.add(callback);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result result = fetch();
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> deliver(result));
        });
    }

    private void deliver(Result result) {
        cached = result;
        cachedAt = System.currentTimeMillis();
        List<Consumer<Result>> callbacks = waiting;
        waiting = null;
        if (callbacks != null) {
            for (Consumer<Result> callback : callbacks) {
                callback.accept(result);
            }
        }
    }

    public void clear() {
        waiting = null;
        cached = null;
    }

    /** 把結果用 messages.yml 的文字告訴對方。 */
    public void report(CommandSender to, Result result) {
        Component text = describe(result, false);
        if (text != null) {
            to.sendMessage(text);
        }
    }

    /** 啟動時寫在後台（不加前綴，Logger 會自己標上插件名稱）。 */
    public void reportToConsole(Result result) {
        Component text = describe(result, true);
        if (text == null) {
            return;
        }
        if (result.state() == State.FAILED) {
            plugin.getComponentLogger().warn(text);
        } else {
            plugin.getComponentLogger().info(text);
        }
    }

    private Component describe(Result result, boolean console) {
        Messages messages = plugin.messages();
        String key = switch (result.state()) {
            case NEWER -> "update.newer";
            case LATEST -> "update.latest";
            case DEV -> "update.dev";
            case NO_RELEASE -> "update.no-release";
            case FAILED -> "update.failed";
        };
        if (messages.isBlank(key)) {
            return null;
        }
        String latest = result.latest() != null ? result.latest() : "-";
        String reason = result.reasonKey() != null
                ? messages.plain(result.reasonKey(), "code", result.httpCode()) : "-";
        Object[] vars = {
                "version", currentVersion, "latest", latest, "reason", reason,
                "url", Messages.raw(RELEASES_URL)
        };
        return console ? messages.console(key, vars) : messages.get(key, vars);
    }

    // ------------------------------------------------------------------ 背景執行緒

    private Result fetch() {
        HttpURLConnection connection = null;
        try {
            String url = System.getProperty(API_PROPERTY, API_URL);
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "ItemReplace/" + currentVersion);

            int code = connection.getResponseCode();
            if (code == 404) {
                return new Result(State.NO_RELEASE, null, null, 0);
            }
            if (code != 200) {
                return new Result(State.FAILED, null, "update.reason-http", code);
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                body = readLimited(in);
            }
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return new Result(State.FAILED, null, "update.reason-invalid", 0);
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement tagElement = object.get("tag_name");
            if (tagElement == null || !tagElement.isJsonPrimitive()) {
                return new Result(State.FAILED, null, "update.reason-invalid", 0);
            }
            String tag = tagElement.getAsString().trim();
            if (!TAG.matcher(tag).matches()) {
                return new Result(State.FAILED, null, "update.reason-invalid", 0);
            }
            String latest = stripPrefix(tag);
            int compared = compareVersions(latest, currentVersion);
            State state = compared > 0 ? State.NEWER : compared < 0 ? State.DEV : State.LATEST;
            return new Result(state, latest, null, 0);
        } catch (IOException | IllegalArgumentException e) {
            return new Result(State.FAILED, null, "update.reason-connect", 0);
        } catch (RuntimeException e) {
            return new Result(State.FAILED, null, "update.reason-invalid", 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("response too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 版本比對

    public static String stripPrefix(String version) {
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }
        return text;
    }

    /** 去掉開頭的 v 與 - 或 + 之後的後綴，再逐段比數字。 */
    public static int compareVersions(String a, String b) {
        int[] left = parts(a);
        int[] right = parts(b);
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int x = i < left.length ? left[i] : 0;
            int y = i < right.length ? right[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int[] parts(String version) {
        String text = stripPrefix(version);
        int cut = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        String[] pieces = text.substring(0, cut).split("\\.");
        int[] numbers = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                numbers[i] = Integer.parseInt(pieces[i].trim());
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }
}
