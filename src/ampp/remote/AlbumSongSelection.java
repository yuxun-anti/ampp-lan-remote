package ampp.remote;

import dev.amenhancer.plugin.api.PluginCall;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginHook;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

final class AlbumSongSelection {
    private static final String PLAYER = "com.apple.android.music.player.";

    interface Selection { boolean send(String song, String album, int index); }

    static void install(PluginContext context, CatalogPlayback catalog, BooleanSupplier attached,
            Selection send) throws ReflectiveOperationException {
        // playContainerWithStartItemAndShuffle keeps the actual row in c.
        hook(context, catalog, attached, send, PLAYER + "I0$g$a", "c", "e", "f");
        // playItemCollectionWithShuffle keeps its start item in d.
        hook(context, catalog, attached, send, PLAYER + "N0$a", "d", "b");
    }

    private static void hook(PluginContext context, CatalogPlayback catalog, BooleanSupplier attached,
            Selection send, String className, String rowField, String... modeFields)
            throws ReflectiveOperationException {
        Class<?> owner = context.getHostClassLoader().loadClass(className);
        Field row = accessibleField(owner, rowField);
        Field container = "c".equals(rowField) ? accessibleField(owner, "b") : null;
        Field position = container == null ? null : accessibleField(owner, "d");
        Field[] modes = new Field[modeFields.length];
        for (int i = 0; i < modes.length; i++) modes[i] = accessibleField(owner, modeFields[i]);

        // These callbacks run after the app's playback eligibility checks and
        // before it builds the queue. A tapped row can exist before that queue does.
        context.getHooks().hook(owner.getDeclaredMethod("accept", Object.class), false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (!attached.getAsBoolean() || Boolean.TRUE.equals(call.getArguments()[0])) return;
                Object receiver = call.getReceiver();
                try {
                    for (Field mode : modes) {
                        if (mode.getBoolean(receiver)) return;
                    }
                    String id = catalog.songFromRow(row.get(receiver));
                    if (id == null) return;
                    String album = container == null ? null : catalog.albumFromRow(container.get(receiver));
                    int index = position == null ? -1 : position.getInt(receiver);
                    if (send.send(id, album, index)) call.returnResult(null);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    context.log("Album row selection unavailable: " + owner.getSimpleName(), failure);
                }
            }
        });
    }

    private static Field accessibleField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
