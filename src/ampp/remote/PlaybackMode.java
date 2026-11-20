package ampp.remote;

import org.json.JSONObject;

enum PlaybackMode {
    SHUFFLE("shuffle", "ShuffleMode", "ShuffleMode", 0, 1),
    REPEAT("repeat", "RepeatMode", "RepeatMode", 0, 2),
    AUTOPLAY("autoplay", "ContinuousPlaybackState", "ContinuousPlaybackMode", 1, 2);

    final String route;
    final String getter;
    final String capability;
    final String setter;
    final String changeSetter;
    final int minimum;
    final int maximum;

    PlaybackMode(String route, String state, String supported, int minimum, int maximum) {
        this.route = route;
        getter = "get" + state;
        capability = "canSet" + supported;
        setter = "set" + state;
        changeSetter = "set" + supported;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    static PlaybackMode forRoute(String route) {
        for (PlaybackMode mode : values()) if (mode.route.equals(route)) return mode;
        return null;
    }

    boolean accepts(long value) { return value >= minimum && value <= maximum; }

    boolean available(JSONObject state) { return state != null && state.optBoolean(route + "Available"); }

    int value(JSONObject state) {
        int value = state == null ? minimum : state.optInt(route, minimum);
        return accepts(value) ? value : minimum;
    }
}
