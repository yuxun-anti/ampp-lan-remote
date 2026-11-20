package ampp.remote;

import android.os.Handler;
import android.os.Looper;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginObservation;
import dev.amenhancer.plugin.api.PluginObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.function.Supplier;

final class NativeNextButton {
    private final PluginContext context;
    private final Supplier<Boolean> nextAvailable;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, WeakReference<Object>> models = new WeakHashMap<>();
    private final Method refresh;
    private final Method notifyChanged;
    private final Field hasNext;
    private final int property;

    NativeNextButton(PluginContext context, Supplier<Boolean> nextAvailable) throws ReflectiveOperationException {
        this.context = context;
        this.nextAvailable = nextAvailable;
        ClassLoader loader = context.getHostClassLoader();
        Class<?> model = loader.loadClass("com.apple.android.music.player.V0");
        Class<?> helper = loader.loadClass("com.apple.android.music.player.c1");
        refresh = helper.getMethod("j", loader.loadClass("z3.F"), model);
        hasNext = model.getField("f");
        if (hasNext.getType() != boolean.class) throw new NoSuchFieldException("V0.f");
        notifyChanged = model.getMethod("notifyPropertyChanged", int.class);
        property = loader.loadClass("com.apple.android.music.playback.BR").getField("hasNext").getInt(null);
        context.getHooks().observe(refresh, new PluginObserver() {
            @Override public void after(PluginObservation event) {
                if (event.getThrowable() != null || Looper.myLooper() != main.getLooper()) return;
                Boolean available = nextAvailable.get();
                if (available == null) return;
                Object[] args = event.getArguments();
                models.put(args[1], new WeakReference<>(args[0]));
                update(args[1], available);
            }
        });
    }

    void refresh() {
        main.post(() -> {
            Boolean available = nextAvailable.get();
            for (Object model : models.keySet()) {
                if (available != null) {
                    update(model, available);
                    continue;
                }
                Object player = models.get(model).get();
                if (player == null) continue;
                try {
                    refresh.invoke(null, player, model);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    context.log("Native next button refresh failed", failure);
                }
            }
            if (available == null) models.clear();
        });
    }

    private void update(Object model, boolean available) {
        try {
            if (hasNext.getBoolean(model) == available) return;
            hasNext.setBoolean(model, available);
            notifyChanged.invoke(model, property);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            context.log("Native next button update failed", failure);
        }
    }
}
