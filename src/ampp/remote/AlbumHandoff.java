package ampp.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

final class AlbumHandoff {
    final String id;
    final int index;
    final int repeat;
    private final String tail;

    private AlbumHandoff(String id, int index, int repeat, String tail) {
        this.id = id;
        this.index = index;
        this.repeat = repeat;
        this.tail = tail;
    }

    static AlbumHandoff parse(String id, String index, String repeat, String tail) {
        if (!CatalogPlayback.validId(id) || !index.matches("0|[1-9][0-9]{0,3}")
                || !repeat.matches("[012]") || !tail.matches("[0-9a-f]{64}")) return null;
        return new AlbumHandoff(id, Integer.parseInt(index), Integer.parseInt(repeat), tail);
    }

    static AlbumHandoff fromQueue(String id, int index, int repeat, int remaining, List<String> upcoming) {
        if (!CatalogPlayback.validId(id) || index < 0 || index > 9999 || remaining < 0
                || !PlaybackMode.REPEAT.accepts(repeat)) return null;
        if (upcoming.size() != remaining) return null;
        StringBuilder order = new StringBuilder().append(remaining);
        for (String song : upcoming) {
            if (!CatalogPlayback.validId(song)) return null;
            order.append('\n').append(song);
        }
        return new AlbumHandoff(id, index, repeat, fingerprint(order.toString()));
    }

    boolean matches(AlbumHandoff other) {
        return other != null && id.equals(other.id) && index == other.index
                && repeat == other.repeat && tail.equals(other.tail);
    }

    String route(String ticket, String song) {
        return "stage-album/" + ticket + "/" + song + "/" + id + "/" + index + "/" + repeat + "/" + tail;
    }

    private static String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(64);
            for (byte part : hash) {
                hex.append(Character.forDigit((part & 0xff) >>> 4, 16));
                hex.append(Character.forDigit(part & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
