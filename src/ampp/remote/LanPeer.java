package ampp.remote;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class LanPeer implements LanServer.Player {
    private static final Pattern IPV4 = Pattern.compile("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32}");
    final String address;
    final String authority;
    private final InetAddress host;
    private final int port;
    private final String token;

    private LanPeer(URI uri, InetAddress host) {
        address = uri.toString();
        authority = uri.getRawAuthority();
        this.host = host;
        port = uri.getPort();
        token = uri.getRawFragment();
    }

    static LanPeer parse(String address) throws IOException {
        try {
            URI uri = URI.create(address.trim());
            String hostname = uri.getHost();
            String path = uri.getRawPath();
            String token = uri.getRawFragment();
            int port = uri.getPort();
            if (!"http".equals(uri.getScheme()) || hostname == null || !IPV4.matcher(hostname).matches()
                    || port < 1 || port > 65535 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || !("/".equals(path) || path.isEmpty()) || token == null
                    || !TOKEN.matcher(token).matches() || !uri.getRawAuthority().equals(hostname + ":" + port)) {
                throw new IOException("Invalid pairing address");
            }

            String[] parts = hostname.split("\\.");
            byte[] octets = new byte[4];
            for (int i = 0; i < octets.length; i++) {
                int number = Integer.parseInt(parts[i]);
                if (number > 255 || !parts[i].equals(Integer.toString(number))) {
                    throw new IOException("Invalid local address");
                }
                octets[i] = (byte) number;
            }
            InetAddress host = InetAddress.getByAddress(octets);
            if (!host.isSiteLocalAddress()) throw new IOException("Local IPv4 required");
            return new LanPeer(uri, host);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid pairing address", invalid);
        }
    }

    @Override public String state() {
        try {
            Reply reply = request("GET", "/api/state", 1100);
            if (reply.status != 200) throw new Unavailable(reply.status);
            return new String(reply.body, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new Unavailable(0);
        }
    }

    org.json.JSONObject role() {
        try {
            Reply reply = request("GET", "/api/role", 1100);
            if (reply.status == 200) return new org.json.JSONObject(new String(reply.body, StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException unavailable) {
            // Older plugins and unreachable peers have no role information.
        }
        return null;
    }

    int changeRole(String route, int timeout) {
        return post("/api/role/" + route, timeout);
    }

    @Override public byte[] artwork(String key) {
        if (!key.matches("[0-9a-f-]{36}")) return new byte[0];
        try {
            Reply reply = request("GET", "/api/art/" + key, 1100);
            return reply.status == 200 ? reply.body : new byte[0];
        } catch (IOException unavailable) {
            return new byte[0];
        }
    }

    @Override public int command(String name, long position) {
        PlaybackMode mode = PlaybackMode.forRoute(name);
        if (mode != null) return mode.accepts(position) ? post("/api/" + name + "/" + position) : 400;
        final String route;
        switch (name) {
            case "seek":
                if (position < 0) return 400;
                route = "seek/" + position;
                break;
            case "play":
            case "pause":
            case "previous":
            case "next":
                route = name;
                break;
            default:
                return 404;
        }

        return post("/api/" + route);
    }

    @Override public int playCatalogSong(String id) {
        return CatalogPlayback.validId(id) ? post("/api/catalog/play/" + id) : 400;
    }

    @Override public int playAlbumSong(String song, String album, int index) {
        if (!CatalogPlayback.validId(song) || !CatalogPlayback.validId(album) || index < 0 || index > 9999) return 400;
        return post("/api/catalog/album/" + song + "/" + album + "/" + index, 6800);
    }

    private int post(String route) {
        return post(route, 1100);
    }

    private int post(String route, int timeout) {
        try {
            return request("POST", route, timeout).status;
        } catch (IOException error) {
            return 0;
        }
    }

    private Reply request(String method, String path, int timeout) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            socket.setSoTimeout(timeout);
            String request = method + " " + path + " HTTP/1.1\r\n"
                    + "Host: " + authority + "\r\nAuthorization: Bearer " + token + "\r\n"
                    + "Connection: close\r\nContent-Length: 0\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return readReply(new BufferedInputStream(socket.getInputStream()), timeout);
        }
    }

    private static Reply readReply(InputStream in, int timeout) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout + 700L);
        String first = LanServer.readLine(in, deadline);
        if (first == null || !first.matches("HTTP/1\\.1 [1-5][0-9][0-9] .+")) {
            throw new IOException("Invalid response");
        }
        int status = Integer.parseInt(first.substring(9, 12));
        int size = -1;
        for (int count = 0; count < 32; count++) {
            String header = LanServer.readLine(in, deadline);
            if (header == null) break;
            if (header.isEmpty()) {
                if (size < 0 || size > 240000) break;
                byte[] body = new byte[size];
                int read = 0;
                while (read < size) {
                    if (System.nanoTime() >= deadline) throw new IOException("Response timed out");
                    int next = in.read(body, read, size - read);
                    if (next < 0) throw new IOException("Incomplete response");
                    read += next;
                }
                return new Reply(status, body);
            }

            int separator = header.indexOf(':');
            if (separator <= 0) break;
            String key = header.substring(0, separator).toLowerCase(Locale.ROOT);
            if ("transfer-encoding".equals(key)) break;
            if ("content-length".equals(key)) {
                String value = header.substring(separator + 1).trim();
                if (size != -1 || !value.matches("[0-9]{1,6}")) break;
                size = Integer.parseInt(value);
            }
        }
        throw new IOException("Invalid response headers");
    }

    static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        Unavailable(int status) { this.status = status; }
    }

    private static final class Reply {
        final int status;
        final byte[] body;
        Reply(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }
}
