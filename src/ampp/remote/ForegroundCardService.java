package ampp.remote;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import dev.amenhancer.plugin.api.PluginCall;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginHook;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class ForegroundCardService {
    private final PluginContext context;
    private final Method notificationManager;
    private final Field startIntent;
    private volatile Service owner;
    private boolean promoted;

    ForegroundCardService(PluginContext context) throws ReflectiveOperationException {
        this.context = context;
        ClassLoader loader = context.getHostClassLoader();
        Class<?> base = loader.loadClass("J4.d3");
        notificationManager = base.getDeclaredMethod("c");
        Class<?> manager = loader.loadClass("J4.k2");
        if (notificationManager.getReturnType() != manager) throw new NoSuchMethodException("Media3 manager");
        startIntent = manager.getDeclaredField("f");
        if (startIntent.getType() != Intent.class) throw new NoSuchFieldException("Media3 start intent");
        startIntent.setAccessible(true);
        Field managerService = manager.getDeclaredField("a");
        if (managerService.getType() != base) throw new NoSuchFieldException("Media3 service");
        managerService.setAccessible(true);
        Method hostUpdate = manager.getDeclaredMethod("f", loader.loadClass("J4.m2"),
                loader.loadClass("J4.b2"), boolean.class);

        context.getHooks().hook(hostUpdate, false, new PluginHook() {
            @Override public void before(PluginCall call) throws ReflectiveOperationException {
                Service active = owner;
                if (active != null && managerService.get(call.getReceiver()) == active) call.returnResult(null);
            }
        });
        PluginHook keep = new PluginHook() {
            @Override public void before(PluginCall call) {
                Service active = owner;
                if (active != null && call.getReceiver() == active) call.returnResult(null);
            }
        };
        context.getHooks().hook(Service.class.getMethod("stopForeground", int.class), false, keep);
        context.getHooks().hook(Service.class.getMethod("stopForeground", boolean.class), false, keep);
    }

    synchronized boolean show(Object host, int id, Notification notification) {
        if (!(host instanceof Service)) return false;
        Service service = (Service) host;
        if (owner != null && owner != service) return false;
        if (promoted) return true;
        owner = service;
        try {
            // Reuse the genuine Media3 service start intent to keep a started
            // service after the player activity unbinds. A normal start avoids
            // leaving a pending foreground-start deadline on fallback.
            Intent intent = (Intent) startIntent.get(notificationManager.invoke(service));
            if (intent == null || service.startService(new Intent(intent)) == null) {
                owner = null;
                return false;
            }
            service.startForeground(id, notification);
            promoted = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            owner = null;
            context.log("Foreground remote playback unavailable; using standard media notification", unavailable);
            return false;
        }
    }

    synchronized void end(Object host) {
        if (owner != host) return;
        Service service = owner;
        owner = null;
        if (promoted) {
            promoted = false;
            try {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } catch (RuntimeException gone) {
                context.log("Foreground remote playback already stopped", gone);
            }
        }
    }
}
