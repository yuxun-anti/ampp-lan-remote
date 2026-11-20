package ampp.remote;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.Collections;
import org.json.JSONObject;

public final class PlaybackModeTest {
    public static void main(String[] args) throws Exception {
        JSONObject oldReceiver = new JSONObject("{\"ready\":true}");
        for (PlaybackMode mode : PlaybackMode.values()) {
            check(!mode.available(oldReceiver), "Older peers do not advertise mode support");
        }
        Realm player = new Realm();
        Inet4Address loopback = (Inet4Address) InetAddress.getByName("127.0.0.1");
        String token = LanServer.newToken();
        try (LanServer server = new LanServer(loopback, 0, token, player, Collections.emptyMap())) {
            check(post(server, token, "shuffle/1") == 204 && player.shuffle == 1, "Enable shuffle");
            check(post(server, token, "shuffle/0") == 204 && player.shuffle == 0, "Disable shuffle");
            check(post(server, token, "repeat/2") == 204 && player.repeat == 2, "Repeat all");
            check(post(server, token, "repeat/1") == 204 && player.repeat == 1, "Repeat one");
            check(post(server, token, "repeat/0") == 204 && player.repeat == 0, "Turn off repeat");
            check(post(server, token, "autoplay/2") == 204 && player.autoplay == 2, "Enable autoplay");
            check(post(server, token, "autoplay/1") == 204 && player.autoplay == 1, "Disable autoplay");
            check(post(server, token, "repeat/3") == 400, "Reject unknown repeat value");
            check(post(server, token, "autoplay/0") == 400, "Reject unknown autoplay value");
            check(post(server, token, "shuffle/01") == 400, "Reject malformed shuffle value");
            check(post(server, token, "next") == 204 && player.next == 1, "Preserve ordinary transport");
        }
    }

    private static int post(LanServer server, String token, String path) throws Exception {
        URL address = new URL("http://127.0.0.1:" + server.port() + "/api/" + path);
        HttpURLConnection connection = (HttpURLConnection) address.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Realm implements LanServer.Player {
        int shuffle, repeat, autoplay, next;
        @Override public String state() { return "{}"; }

        @Override public int command(String name, long value) {
            switch (name) {
                case "shuffle": shuffle = (int) value; break;
                case "repeat": repeat = (int) value; break;
                case "autoplay": autoplay = (int) value; break;
                case "next": next++; break;
                default: return 404;
            }
            return 204;
        }
    }
}
