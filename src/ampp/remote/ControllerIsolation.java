package ampp.remote;

import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import dev.amenhancer.plugin.api.PluginCall;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginHook;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class ControllerIsolation {
    private final PluginContext context;
    private final Field serviceSession;
    private final Field sessionImpl;
    private final Field legacyStub;
    private final Field compatSession;
    private final Field compatImpl;
    private final Field platformSession;
    private volatile Lease held;

    ControllerIsolation(PluginContext context, Class<?> service, Class<?> local)
            throws ReflectiveOperationException {
        this.context = context;
        ClassLoader loader = context.getHostClassLoader();
        Class<?> session = loader.loadClass("J4.m2");
        Class<?> implementation = loader.loadClass("J4.B2");
        Class<?> stub = loader.loadClass("J4.T2");
        Class<?> compat = loader.loadClass("K4.A");
        Class<?> compatImplementation = loader.loadClass("K4.A$f");
        Class<?> supportBase = loader.loadClass("K4.A$d");
        serviceSession = resolve(service, "y", loader.loadClass("J4.y1$b"));
        sessionImpl = resolve(session, "a", implementation);
        legacyStub = resolve(implementation, "h", stub);
        compatSession = resolve(stub, "l", compat);
        compatImpl = resolve(compat, "a", compatImplementation);
        platformSession = resolve(supportBase, "a", MediaSession.class);
        Method hardware = implementation.getDeclaredMethod("v", loader.loadClass("J4.m2$e"), Intent.class);
        Method focus = local.getDeclaredMethod("requestAudioFocus");
        Method state = MediaSession.class.getMethod("setPlaybackState", PlaybackState.class);
        Method active = MediaSession.class.getMethod("setActive", boolean.class);

        context.getHooks().hook(state, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                Lease lease = held;
                if (lease != null && call.getReceiver() == lease.session) call.getArguments()[0] = null;
            }
        });
        context.getHooks().hook(active, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                Lease lease = held;
                if (lease != null && call.getReceiver() == lease.session) call.getArguments()[0] = false;
            }
        });
        context.getHooks().hook(hardware, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                Lease lease = held;
                if (lease == null || call.getReceiver() != lease.implementation) return;
                Intent intent = (Intent) call.getArguments()[1];
                if (intent != null && Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
                    call.returnResult(true);
                }
            }
        });
        context.getHooks().hook(focus, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                Lease lease = held;
                if (lease != null && call.getReceiver() == lease.oldPlayer) call.returnResult(false);
            }
        });
    }

    Lease begin(Object service, Object oldPlayer) throws ReflectiveOperationException {
        Object holder = serviceSession.get(service);
        if (holder == null) throw new IllegalStateException("Native media session is missing");
        Object implementation = sessionImpl.get(holder);
        Object stub = legacyStub.get(implementation);
        Object compat = compatSession.get(stub);
        MediaSession session = (MediaSession) platformSession.get(compatImpl.get(compat));
        if (session == null || held != null) throw new IllegalStateException("Native media session is unavailable");
        Lease lease = new Lease(implementation, session, oldPlayer);
        held = lease;
        try {
            session.setActive(false);
            session.setPlaybackState(null);
            AudioManager audio = context.getApplication().getSystemService(AudioManager.class);
            if (audio != null && oldPlayer instanceof AudioManager.OnAudioFocusChangeListener) {
                audio.abandonAudioFocus((AudioManager.OnAudioFocusChangeListener) oldPlayer);
            }
            return lease;
        } catch (RuntimeException failure) {
            end(lease, true);
            throw failure;
        }
    }

    void end(Lease lease, boolean restoreSession) {
        if (held != lease) return;
        held = null;
        if (restoreSession && lease != null) {
            try {
                lease.session.setActive(lease.wasActive);
            } catch (RuntimeException gone) {
                context.log("Platform media session already gone", gone);
            }
        }
    }

    private static Field resolve(Class<?> owner, String name, Class<?> expected)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected) throw new NoSuchFieldException(owner.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }

    static final class Lease {
        final Object implementation;
        final MediaSession session;
        final Object oldPlayer;
        final boolean wasActive;

        Lease(Object implementation, MediaSession session, Object oldPlayer) {
            this.implementation = implementation;
            this.session = session;
            this.oldPlayer = oldPlayer;
            wasActive = session.isActive();
        }
    }
}
