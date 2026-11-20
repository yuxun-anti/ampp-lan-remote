package ampp.remote;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class LanServer implements AutoCloseable {
    interface Roles {
        String status();
        String redirect();
        LanPeer relay();
        int command(String path);
    }
    interface Player {
        String state();
        int command(String name, long position);
        default int playCatalogSong(String id) { return 404; }
        default int playAlbumSong(String song, String album, int index) { return 404; }
        default byte[] artwork(String key) { return new byte[0]; }
    }

    private final ServerSocket listener = new ServerSocket();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2, 4, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Player player;
    private final Roles roles;
    private final Map<String, byte[]> assets;
    private final String token;
    private final String authority;

    static String newToken() {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    LanServer(Inet4Address bind, int port, String token, Player player, Map<String, byte[]> assets)
            throws IOException {
        this(bind, port, token, player, assets, null);
    }

    LanServer(Inet4Address bind, int port, String token, Player player, Map<String, byte[]> assets, Roles roles)
            throws IOException {
        this.player = player;
        this.assets = assets;
        this.token = token;
        this.roles = roles;
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bind, port), 12);
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
        authority = bind.getHostAddress() + ":" + listener.getLocalPort();
        new Thread(this::accept, "AM++ LAN Remote").start();
    }

    int port() { return listener.getLocalPort(); }

    String url() {
        return listener.isClosed() ? null : "http://" + authority + "/#" + token;
    }

    private void accept() {
        try {
            while (!listener.isClosed()) {
                Socket client = listener.accept();
                clients.add(client);
                try {
                    workers.execute(() -> serve(client));
                } catch (RejectedExecutionException ignored) {
                    clients.remove(client);
                    closeClient(client);
                }
            }
        } catch (IOException ignored) {
            close();
        }
    }

    private void serve(Socket client) {
        try (Socket connection = client) {
            connection.setSoTimeout(2000);
            Request request = read(new BufferedInputStream(connection.getInputStream()));
            if (request != null) dispatch(request, connection.getOutputStream());
        } catch (IOException | RuntimeException ignored) {
            // A malformed request or a disconnected client only affects this connection.
        } finally {
            clients.remove(client);
        }
    }

    private void dispatch(Request request, OutputStream out) throws IOException {
        if (!authority.equals(request.headers.get("host"))) {
            reply(out, 403);
            return;
        }
        String origin = request.headers.get("origin");
        if (origin != null && !("http://" + authority).equals(origin)) {
            reply(out, 403);
            return;
        }
        String path = request.path;
        if ("GET".equals(request.method) && assets.containsKey(path)) {
            String type = path.endsWith(".js") ? "text/javascript"
                    : path.endsWith(".css") ? "text/css" : "text/html";
            reply(out, 200, type, assets.get(path));
            return;
        }
        String auth = request.headers.get("authorization");
        if (auth == null || !MessageDigest.isEqual(bytes("Bearer " + token), bytes(auth))) {
            reply(out, 401);
            return;
        }
        if ("GET".equals(request.method) && "/api/state".equals(path)) {
            String redirect = roles == null ? null : roles.redirect();
            reply(out, 200, "application/json", bytes(redirect == null ? player.state() : redirect));
            return;
        }
        if (roles != null && "GET".equals(request.method) && "/api/role".equals(path)) {
            reply(out, 200, "application/json", bytes(roles.status()));
            return;
        }
        if (roles != null && "POST".equals(request.method) && path.startsWith("/api/role/")) {
            reply(out, roles.command(path.substring("/api/role/".length())));
            return;
        }
        LanPeer relay = roles == null ? null : roles.relay();
        if ("POST".equals(request.method) && path.startsWith("/api/catalog/album/")) {
            String[] selection = path.substring("/api/catalog/album/".length()).split("/", -1);
            if (selection.length != 3 || !CatalogPlayback.validId(selection[0])
                    || !CatalogPlayback.validId(selection[1]) || !selection[2].matches("[0-9]{1,4}")) {
                reply(out, 400);
                return;
            }
            int index = Integer.parseInt(selection[2]);
            reply(out, relay == null ? player.playAlbumSong(selection[0], selection[1], index)
                    : networkStatus(relay.playAlbumSong(selection[0], selection[1], index)));
            return;
        }
        if ("GET".equals(request.method) && path.startsWith("/api/art/")) {
            String key = path.substring(9);
            if (!key.matches("[0-9a-f-]{36}")) { reply(out, 404); return; }
            byte[] art = relay == null ? player.artwork(key) : relay.artwork(key);
            if (art.length == 0) reply(out, 404);
            else reply(out, 200, "image/jpeg", art);
            return;
        }
        if ("POST".equals(request.method) && path.startsWith("/api/catalog/play/")) {
            String id = path.substring("/api/catalog/play/".length());
            reply(out, !CatalogPlayback.validId(id) ? 400
                    : relay == null ? player.playCatalogSong(id) : networkStatus(relay.playCatalogSong(id)));
            return;
        }
        if ("POST".equals(request.method) && path.startsWith("/api/")) {
            String command = path.substring(5);
            long position = -1;
            int separator = command.indexOf('/');
            PlaybackMode mode = PlaybackMode.forRoute(separator < 0 ? command : command.substring(0, separator));
            if (mode != null) {
                String raw = separator < 0 ? "" : command.substring(separator + 1);
                if (raw.length() != 1 || !mode.accepts(raw.charAt(0) - '0')) {
                    reply(out, 400);
                } else {
                    int value = raw.charAt(0) - '0';
                    reply(out, relay == null ? player.command(mode.route, value)
                            : networkStatus(relay.command(mode.route, value)));
                }
                return;
            }
            if (command.startsWith("seek/")) {
                try {
                    position = Long.parseLong(command.substring(5));
                } catch (NumberFormatException ignored) {
                    reply(out, 400);
                    return;
                }
                command = "seek";
            }
            if ("play".equals(command) || "pause".equals(command) || "next".equals(command)
                    || "previous".equals(command) || ("seek".equals(command) && position >= 0)) {
                reply(out, relay == null ? player.command(command, position)
                        : networkStatus(relay.command(command, position)));
                return;
            }
        }
        reply(out, 404);
    }

    private static int networkStatus(int status) { return status == 0 ? 503 : status; }

    private static Request read(InputStream in) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        String line = readLine(in, deadline);
        if (line == null) return null;
        String[] first = line.split(" ", -1);
        if (first.length != 3 || !"HTTP/1.1".equals(first[2]) || !first[1].startsWith("/")) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        for (int count = 0; count < 32; count++) {
            line = readLine(in, deadline);
            if (line == null) return null;
            if (line.isEmpty()) {
                if (headers.containsKey("transfer-encoding")
                        || !"0".equals(headers.getOrDefault("content-length", "0"))) return null;
                return new Request(first[0], first[1], headers);
            }
            int separator = line.indexOf(':');
            if (separator <= 0) return null;
            String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
            if (headers.put(name, line.substring(separator + 1).trim()) != null) return null;
        }
        return null;
    }

    static String readLine(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < 4096 && System.nanoTime() < deadline) {
            int next = in.read();
            if (next < 0) return null;
            if (next == '\r') {
                return in.read() == '\n' ? line.toString("US-ASCII") : null;
            }
            if ((next < 32 && next != '\t') || next > 126) return null;
            line.write(next);
        }
        return null;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void reply(OutputStream out, int status) throws IOException {
        reply(out, status, "text/plain", new byte[0]);
    }

    private static void reply(OutputStream out, int status, String type, byte[] body) throws IOException {
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 204: reason = "No Content"; break;
            case 400: reason = "Bad Request"; break;
            case 401: reason = "Unauthorized"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 409: reason = "Conflict"; break;
            case 412: reason = "Precondition Failed"; break;
            case 422: reason = "Unprocessable Content"; break;
            default: reason = "Service Unavailable"; break;
        }
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Connection: close\r\nCache-Control: no-store\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; "
                + "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'\r\n\r\n";
        out.write(bytes(headers));
        out.write(body);
    }

    @Override public void close() {
        try { listener.close(); } catch (IOException ignored) {}
        for (Socket client : clients) closeClient(client);
        workers.shutdownNow();
    }

    private static void closeClient(Socket client) {
        try { client.close(); } catch (IOException ignored) {}
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;

        Request(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }
    }
}
