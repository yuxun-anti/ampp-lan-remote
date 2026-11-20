package ampp.remote;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginObservation;
import dev.amenhancer.plugin.api.PluginObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

final class NativeSessionProbe implements AutoCloseable, RoleHandoff.Playback {
    private static final String ROOT = "com.apple.android.music.";
    private static final String SERVICE = ROOT + "player.MediaPlaybackService";
    private static final String REMOTE = ROOT + "IS_REMOTE_PLAYBACK";
    private final PluginContext context;
    private final RemoteStore store;
    private final String localNode;
    private final ControllerIsolation isolation;
    private final ForegroundCardService foreground;
    private final Class<?> controller;
    private final Class<?> local;
    private final Field managerField;
    private final Field mediaManager;
    private final Field engineListeners;
    private final Method positionChanged;
    private final Constructor<?> positionInfo;
    private final Method stateUpdated;
    private final Method queueChanged;
    private final NativeNextButton nextButton;
    private final Method modesChanged;
    private final Constructor<?> modeChanges;
    private final EnumMap<PlaybackMode, NativeMode> playbackModes = new EnumMap<>(PlaybackMode.class);
    private final Method nativeMediaItem;
    private final NativeTrack nativeTrack;
    private final CatalogPlayback catalogPlayback;
    private final ArtworkCache coverCache;
    private final Method metadataChanged;
    private final java.util.concurrent.ExecutorService coverWorker = Executors.newSingleThreadExecutor();
    private final Method queueId;
    private final Method album;
    private final Method albumArtist;
    private final Method artwork;
    private final Method getPosition;
    private final Method getRate;
    private final Method canSeek;
    private final Method canNext;
    private final Method canPrevious;
    private final Method nativePrevious;
    private final Method nativeNext;
    private final Method nativeSeek;
    private final Method localPlay;
    private final Method localPause;
    private final Method restoreState;
    private final Method addControllerListener;
    private final Method removeControllerListener;
    private final Method allQueueItems;
    private final Method skipToQueueItem;
    private final Method engineDuration;
    private final Method containerStoreId;
    private final Field serviceHandler;
    private final Field currentField;
    private final Field switchScope;
    private final Method launch;
    private final Method setExtras;
    private final Method currentItem;
    private final Method currentState;
    private final Method mediaOf;
    private final Method storeId;
    private final Method title;
    private final Method artist;
    private final Method duration;
    private final Method stateChanged;
    private final Method currentChanged;
    private final Method newLocal;
    private final Constructor<?> switchJob;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoScan = this::scanPairedPlayback;
    private volatile WeakReference<Object> serviceInstance = new WeakReference<>(null);
    private volatile Bridge bridge;
    private volatile boolean closed;
    private boolean connecting;
    private boolean scanning;
    private volatile boolean autoSuspended;
    private volatile boolean takingPlayback;
    private RemoteStore.Device previousStageDevice;
    private volatile RoleHandoff roles;
    private boolean holdLocalService;
    private ServiceConnection serviceBinding;
    private volatile String lastReport = "";

    NativeSessionProbe(PluginContext context, RemoteStore store, String localNode)
            throws ReflectiveOperationException {
        this.context = context;
        this.store = store;
        this.localNode = localNode;
        Class<?> service = type(SERVICE);
        Class<?> manager = type(ROOT + "player.k0");
        controller = type(ROOT + "playback.controller.MediaPlayerController");
        local = type(ROOT + "playback.controller.LocalMediaPlayerController");
        isolation = new ControllerIsolation(context, service, local);
        ForegroundCardService foregroundService;
        try {
            foregroundService = new ForegroundCardService(context);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            context.log("Foreground service binding unavailable", unavailable);
            foregroundService = null;
        }
        foreground = foregroundService;
        Class<?> listener = type(ROOT + "playback.controller.MediaPlayerController$Listener");
        Class<?> modeContainer = type(ROOT + "playback.queue.PlaybackModeContainer");
        modeChanges = modeContainer.getConstructor();
        modesChanged = listener.getMethod("onPlaybackModeChanged", controller, modeContainer);
        for (PlaybackMode mode : PlaybackMode.values()) {
            playbackModes.put(mode, new NativeMode(controller.getMethod(mode.getter),
                    controller.getMethod(mode.capability), controller.getMethod(mode.setter,
                            mode == PlaybackMode.AUTOPLAY ? boolean.class : int.class),
                    modeContainer.getMethod(mode.changeSetter, Integer.class)));
        }
        Class<?> queue = type(ROOT + "playback.model.PlayerQueueItem");
        Class<?> media = type(ROOT + "playback.model.PlayerMediaItem");
        Class<?> playbackManager = type(ROOT + "player.N");
        Class<?> position = type("z3.F$d");
        Class<?> engineItem = type("z3.w");
        mediaManager = field(service, "M", playbackManager);
        engineListeners = field(playbackManager, "c", java.util.ArrayList.class);
        positionChanged = type("z3.F$c").getMethod("onPositionDiscontinuity", position, position, int.class);
        positionInfo = position.getConstructor(Object.class, int.class, engineItem, Object.class,
                int.class, long.class, long.class, int.class, int.class);
        nativeMediaItem = type(ROOT + "player.M").getMethod("e", controller, queue);
        nativeTrack = new NativeTrack(context.getHostClassLoader(), media);
        catalogPlayback = new CatalogPlayback(context.getHostClassLoader(), controller, media);
        coverCache = new ArtworkCache(context.getCacheDirectory());
        Class<?> continuation = type("kotlin.coroutines.Continuation");
        managerField = field(service, "Q", manager);
        serviceHandler = field(service, "x", Handler.class);
        currentField = field(manager, "h", controller);
        switchScope = field(manager, "z", type("Vj.d"));
        manager.getDeclaredMethod("h", controller, boolean.class, type("hi.c"));
        switchJob = type(ROOT + "player.U").getConstructor(service, controller, continuation);
        launch = type("Oj.g").getMethod("c", type("Oj.F"), type("fi.f"), type("Oj.H"), type("pi.p"), int.class);
        setExtras = manager.getMethod("j", Bundle.class);
        newLocal = type(ROOT + "playback.controller.MediaPlayerControllerFactory")
                .getMethod("createLocalController", Handler.class);
        currentItem = controller.getMethod("getCurrentItem");
        currentState = controller.getMethod("getPlaybackState");
        localPlay = controller.getMethod("play");
        localPause = controller.getMethod("pause");
        restoreState = controller.getMethod("restoreState", boolean.class, boolean.class);
        addControllerListener = controller.getMethod("addListener", listener);
        removeControllerListener = controller.getMethod("removeListener", listener);
        allQueueItems = controller.getMethod("getAllQueueItems");
        skipToQueueItem = controller.getMethod("skipToQueueItemWithId", long.class);
        mediaOf = queue.getMethod("getItem");
        queueId = queue.getMethod("getPlaybackQueueId");
        getPosition = controller.getMethod("getCurrentPosition");
        getRate = controller.getMethod("getPlaybackRate");
        canSeek = controller.getMethod("canSeek");
        canNext = controller.getMethod("canSkipToNextItem");
        canPrevious = controller.getMethod("canSkipToPreviousItem");
        engineDuration = controller.getMethod("getDuration");
        containerStoreId = controller.getMethod("getCurrentContainerStoreId");
        nativePrevious = playbackManager.getMethod("w");
        nativeNext = playbackManager.getMethod("y");
        nativeSeek = playbackManager.getMethod("seekTo", long.class);
        album = media.getMethod("getAlbumTitle");
        albumArtist = media.getMethod("getAlbumArtistName");
        artwork = media.getMethod("getArtworkUrl");
        storeId = media.getMethod("getSubscriptionStoreId");
        title = media.getMethod("getTitle");
        artist = media.getMethod("getArtistName");
        duration = media.getMethod("getDuration");
        stateChanged = listener.getMethod("onPlaybackStateChanged", controller, int.class, int.class);
        currentChanged = listener.getMethod("onCurrentItemChanged", controller, queue, queue);
        metadataChanged = listener.getMethod("onMetadataUpdated", controller, queue);
        stateUpdated = listener.getMethod("onPlaybackStateUpdated", controller);
        queueChanged = listener.getMethod("onPlaybackQueueChanged", controller, List.class);

        PluginObserver record = new PluginObserver() {
            @Override public void after(PluginObservation event) {
                if (event.getThrowable() == null && service.isInstance(event.getReceiver())) {
                    serviceInstance = new WeakReference<>(event.getReceiver());
                }
            }
        };
        context.getHooks().observe(service.getDeclaredMethod("onCreate"), record);
        context.getHooks().observe(service.getDeclaredMethod("onStartCommand", Intent.class, int.class, int.class), record);
        context.getHooks().observe(service.getDeclaredMethod("j", type("J4.m2$e")), record);
        context.getHooks().observe(service.getSuperclass().getDeclaredMethod("onBind", Intent.class), record);
        context.getHooks().observe(service.getDeclaredMethod("onDestroy"), new PluginObserver() {
            @Override public void before(PluginObservation event) {
                if (serviceInstance.get() != event.getReceiver()) return;
                serviceInstance.clear();
                Bridge attached = bridge;
                if (attached != null && attached.service == event.getReceiver()) {
                    attached.armed = false;
                    attached.closeCard();
                    if (attached.poll != null) attached.poll.cancel(false);
                    isolation.end(attached.lease, false);
                    bridge = null;
                    if (attached.exitRequested || attached.detaching) {
                        main.post(NativeSessionProbe.this::finishAttempt);
                    }
                }
                if (!connecting) unbindService();
            }
        });
        try {
            AlbumSongSelection.install(context, catalogPlayback, this::armedBridge, this::forwardSong);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            context.log("Album row selection hooks unavailable", unavailable);
        }
        NativeNextButton navigation = null;
        try {
            navigation = new NativeNextButton(context, () -> {
                Bridge active = bridge;
                return active == null || !active.armed ? null
                        : (active.snapshot.actions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
            });
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            context.log("Native next button hook unavailable", unavailable);
        }
        nextButton = navigation;
    }

    private boolean armedBridge() {
        Bridge active = bridge;
        return !closed && active != null && active.armed;
    }

    private boolean forwardSong(String id, String album, int index) {
        Bridge active = bridge;
        return active != null && active.enqueueSong(id, album, index);
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return context.getHostClassLoader().loadClass(name);
    }

    private static Field field(Class<?> owner, String name, Class<?> expected) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected) throw new NoSuchFieldException(owner.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }

    JSONObject sampleLocal() {
        Object service = serviceInstance.get();
        if (closed || service == null || bridge != null) return null;
        try {
            Handler handler = (Handler) serviceHandler.get(service);
            if (handler == null) return null;
            if (Looper.myLooper() == handler.getLooper()) return localState(service);
            java.util.concurrent.FutureTask<JSONObject> read = new java.util.concurrent.FutureTask<>(
                    () -> localState(service));
            if (!handler.post(read)) return null;
            try {
                return read.get(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                read.cancel(false);
            }
        } catch (Exception unavailable) {
            return null;
        }
    }

    void roles(RoleHandoff roles) { this.roles = roles; }

    RemoteStore.Device remoteDevice() {
        Bridge active = bridge;
        return active == null ? null : active.target;
    }

    String remoteName() {
        Bridge active = bridge;
        if (active == null) return null;
        JSONObject state = active.snapshot.data;
        return state == null ? active.target.name : state.optString("outputName", active.target.name);
    }

    @Override public JSONObject local() { return sampleLocal(); }

    @Override public boolean setPlaying(boolean playing) {
        Integer command = callLocal((service, engine) -> {
            (playing ? localPlay : localPause).invoke(engine);
            return 204;
        });
        return Integer.valueOf(204).equals(command) && awaitState(
                state -> state.optBoolean("playing") == playing, 2200) != null;
    }

    @Override public boolean supportsStaging() { return true; }

    @Override public int take(String id, long position, boolean playing) {
        int status = stage(id);
        if (status == 204) status = commit(id, position, playing);
        if (status != 204) cancelStage();
        return status;
    }

    @Override public int stage(String id) {
        previousStageDevice = remoteDevice();
        takingPlayback = true;
        try {
            java.util.concurrent.CompletableFuture<Boolean> localReady = new java.util.concurrent.CompletableFuture<>();
            main.post(() -> {
                if (closed || connecting) {
                    localReady.complete(false);
                    return;
                }
                holdLocalService = true;
                ensureService(service -> {
                    if (service == null) {
                        localReady.complete(false);
                        return;
                    }
                    Bridge active = bridge;
                    if (active == null) localReady.complete(true);
                    else if (!active.handler.post(() -> detachService(active,
                            message -> localReady.complete(bridge == null)))) localReady.complete(false);
                }, true);
            });
            if (!localReady.get(8, TimeUnit.SECONDS)) return 409;
            Integer prepared = callLocal((service, engine) -> {
                if (Integer.valueOf(1).equals(currentState.invoke(engine))) return 409;
                catalogPlayback.prepare(engine, id, false);
                return 204;
            });
            if (!Integer.valueOf(204).equals(prepared)) return 409;
            return awaitState(state -> id.equals(state.optString("mediaId"))
                    && !state.optBoolean("playing") && state.optLong("duration") > 0, 5500) != null ? 204 : 422;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return 503;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            return 503;
        }
    }

    @Override public int commit(String id, long position, boolean playing) {
        JSONObject loaded = sampleLocal();
        if (loaded == null || !id.equals(loaded.optString("mediaId"))) return 422;
        if (loaded.optBoolean("playing")) return 503;
        long seek = Math.min(position, Math.max(0L, loaded.optLong("duration") - 1));
        if (seek > 0 || loaded.optLong("position") > 500) {
            if (awaitState(state -> id.equals(state.optString("mediaId")) && !state.optBoolean("playing")
                    && (state.optLong("actions") & PlaybackState.ACTION_SEEK_TO) != 0, 1300) == null) return 422;
            if (!Integer.valueOf(204).equals(transportLocal("seek", seek))) return 422;
            if (awaitState(state -> id.equals(state.optString("mediaId")) && !state.optBoolean("playing")
                    && Math.abs(state.optLong("position", -1000) - seek) <= 500, 2100) == null) return 422;
        }
        if (playing && !setPlaying(true)) return 503;
        takingPlayback = false;
        previousStageDevice = null;
        return 204;
    }

    @Override public void cancelStage() {
        takingPlayback = false;
        RemoteStore.Device previous = previousStageDevice;
        previousStageDevice = null;
        if (bridge == null && previous != null) follow(previous);
    }

    private JSONObject awaitState(java.util.function.Predicate<JSONObject> ready, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            JSONObject state = sampleLocal();
            if (state != null && ready.test(state)) return state;
            try {
                Thread.sleep(100);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (!closed && SystemClock.elapsedRealtime() < deadline);
        return null;
    }

    @Override public void follow(RemoteStore.Device destination) {
        main.post(() -> follow(destination, 10));
    }

    private void follow(RemoteStore.Device destination, int attempts) {
        if (closed || takingPlayback) return;
        if (connecting) {
            if (attempts > 0) main.postDelayed(() -> follow(destination, attempts - 1), 300);
            return;
        }
        Bridge active = bridge;
        if (active == null) {
            attach(destination, unused -> {});
        } else if (!active.target.peer.authority.equals(destination.peer.authority)) {
            connecting = true;
            if (!active.handler.post(() -> detachService(active, message -> {
                if (bridge == null) attach(destination, unused -> {});
            }))) connecting = false;
        }
    }

    private JSONObject localState(Object service) throws Exception {
        if (bridge != null || serviceInstance.get() != service) return null;
        Object engine = currentField.get(managerField.get(service));
        if (!local.isInstance(engine)) return null;
        Object queue = currentItem.invoke(engine);
        Object media = queue == null ? null : mediaOf.invoke(queue);
        if (media == null) return null;
        int state = (Integer) currentState.invoke(engine);
        if (state == 0) return null;
        long length = (Long) duration.invoke(media);
        if (length <= 0L) length = (Long) engineDuration.invoke(engine);
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
        if (Boolean.TRUE.equals(canSeek.invoke(engine))) actions |= PlaybackState.ACTION_SEEK_TO;
        if (Boolean.TRUE.equals(canNext.invoke(engine))) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        if (Boolean.TRUE.equals(canPrevious.invoke(engine))) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        JSONObject result = new JSONObject()
                .put("ready", true)
                .put("mediaId", storeId.invoke(media))
                .put("queueEntry", String.valueOf(queueId.invoke(queue)))
                .put("containerId", containerStoreId.invoke(engine))
                .put("title", title.invoke(media))
                .put("artist", artist.invoke(media))
                .put("album", album.invoke(media))
                .put("albumArtist", albumArtist.invoke(media))
                .put("_artworkSource", artwork.invoke(media))
                .put("duration", length)
                .put("position", getPosition.invoke(engine))
                .put("speed", getRate.invoke(engine))
                .put("playing", state == 1)
                .put("actions", actions);
        for (PlaybackMode mode : PlaybackMode.values()) {
            NativeMode nativeMode = playbackModes.get(mode);
            result.put(mode.route, nativeMode.read.invoke(engine));
            result.put(mode.route + "Available", nativeMode.supported.invoke(engine));
        }
        return result;
    }

    private static final class NativeMode {
        final Method read;
        final Method supported;
        final Method write;
        final Method event;

        NativeMode(Method read, Method supported, Method write, Method event) {
            this.read = read;
            this.supported = supported;
            this.write = write;
            this.event = event;
        }
    }

    Integer transportLocal(String command, long position) {
        PlaybackMode mode = PlaybackMode.forRoute(command);
        if (mode != null) {
            if (!mode.accepts(position)) return 400;
            Integer status = callLocal((service, engine) -> {
                NativeMode nativeMode = playbackModes.get(mode);
                if (!Boolean.TRUE.equals(nativeMode.supported.invoke(engine))) return 409;
                Object value = mode == PlaybackMode.AUTOPLAY ? Boolean.valueOf(position == 2)
                        : Integer.valueOf((int) position);
                nativeMode.write.invoke(engine, value);
                return 204;
            });
            return status == null ? 409 : status;
        }
        if (!"previous".equals(command) && !"next".equals(command) && !"seek".equals(command)) return null;
        return callLocal((service, engine) -> {
            Object player = mediaManager.get(service);
            switch (command) {
                case "previous":
                    nativePrevious.invoke(player);
                    return 204;
                case "next":
                    if (!Boolean.TRUE.equals(canNext.invoke(engine))) return 409;
                    nativeNext.invoke(player);
                    return 204;
                default:
                    long length = (Long) engineDuration.invoke(engine);
                    if (!Boolean.TRUE.equals(canSeek.invoke(engine)) || position < 0L
                            || length <= 0L || position > length) return 409;
                    nativeSeek.invoke(player, position);
                    return 204;
            }
        });
    }

    int playLocalCatalogSong(String id) {
        if (!CatalogPlayback.validId(id)) return 400;
        Integer status = callLocal((service, engine) -> {
            List<?> existing = (List<?>) allQueueItems.invoke(engine);
            if (existing.size() > 1 && playQueuedSong(service, engine, id)) return 204;
            catalogPlayback.play(engine, id);
            return 204;
        });
        return status == null ? 409 : status;
    }

    private boolean queueSong(Object row, String id) throws ReflectiveOperationException {
        Object media = row == null ? null : mediaOf.invoke(row);
        return media != null && id.equals(storeId.invoke(media));
    }

    int playLocalAlbumSong(String song, String album, int index) {
        Integer prepared = callLocal((service, engine) -> {
            if (album.equals(containerStoreId.invoke(engine)) && playQueuedSong(service, engine, song)) return 200;
            catalogPlayback.prepare(engine, album, index, false);
            return 204;
        });
        if (Integer.valueOf(200).equals(prepared)) return 204;
        if (!Integer.valueOf(204).equals(prepared)) return prepared == null ? 409 : prepared;
        if (awaitState(state -> !state.optBoolean("playing") && (song.equals(state.optString("mediaId"))
                || album.equals(state.optString("containerId"))), 4300) == null) {
            return 422;
        }
        Integer playing = callLocal((service, engine) -> playQueuedSong(service, engine, song) ? 204 : 422);
        return playing == null ? 409 : playing;
    }

    private boolean playQueuedSong(Object service, Object engine, String song) throws ReflectiveOperationException {
        if (queueSong(currentItem.invoke(engine), song)) {
            if (Boolean.TRUE.equals(canSeek.invoke(engine))) {
                nativeSeek.invoke(mediaManager.get(service), 0L);
            }
            localPlay.invoke(engine);
            return true;
        }
        for (Object item : (List<?>) allQueueItems.invoke(engine)) {
            if (!queueSong(item, song)) continue;
            // The native controller starts playback when selecting an existing queue item.
            skipToQueueItem.invoke(engine, queueId.invoke(item));
            return true;
        }
        return false;
    }

    private interface LocalCall {
        int run(Object service, Object engine) throws ReflectiveOperationException;
    }

    private Integer callLocal(LocalCall command) {
        Object service = serviceInstance.get();
        if (closed || service == null || bridge != null) return null;
        try {
            Handler handler = (Handler) serviceHandler.get(service);
            if (handler == null) return null;
            java.util.concurrent.FutureTask<Integer> call = new java.util.concurrent.FutureTask<>(() -> {
                if (closed || bridge != null || serviceInstance.get() != service) return 409;
                Object engine = currentField.get(managerField.get(service));
                if (!local.isInstance(engine)) return 409;
                return command.run(service, engine);
            });
            if (!handler.post(call)) return 503;
            try {
                return call.get(350, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 503;
            } finally {
                call.cancel(false);
            }
        } catch (ReflectiveOperationException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException unavailable) {
            context.log("Native LAN playback command failed", unwrap(unavailable));
            return 503;
        }
    }

    boolean connected() { return bridge != null; }

    String report() { return lastReport; }

    void startAutomatic() {
        main.removeCallbacks(autoScan);
        if (!closed && store.autoJoin()) main.post(autoScan);
    }

    void automaticChanged() {
        autoSuspended = false;
        startAutomatic();
    }

    private boolean canAutoJoin() {
        RoleHandoff handoff = roles;
        if (closed || takingPlayback || (handoff != null && handoff.busy())
                || autoSuspended || !store.autoJoin()) return false;
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private void scanPairedPlayback() {
        if (closed || autoSuspended || !store.autoJoin()) return;
        main.postDelayed(autoScan, 2500);
        if (bridge != null || connecting || scanning || !canAutoJoin()) return;
        List<RemoteStore.Device> devices = store.devices();
        if (devices.isEmpty()) return;
        scanning = true;
        scanner.execute(() -> {
            JSONObject current = sampleLocal();
            PairedPlayback playing = current != null && current.optBoolean("playing")
                    ? null : PairedPlayback.playing(devices, localNode);
            main.post(() -> {
                scanning = false;
                if (playing == null || connecting || bridge != null || !canAutoJoin()) return;
                for (RemoteStore.Device saved : store.devices()) {
                    if (saved.peer.address.equals(playing.device.peer.address)) {
                        findAndAttach(saved, playing.state.optString("node"), unused -> {});
                        break;
                    }
                }
            });
        });
    }

    void attach(RemoteStore.Device target, Consumer<String> report) {
        if (closed) return;
        if (connecting) {
            report.accept("正在接入播放设备。");
            return;
        }
        if (bridge != null) {
            report.accept(report());
            return;
        }
        findAndAttach(target, "", report);
    }

    private void findAndAttach(RemoteStore.Device target, String autoNode, Consumer<String> report) {
        connecting = true;
        worker.execute(() -> {
            JSONObject remote = PairedPlayback.read(target);
            main.post(() -> {
                if (closed) return;
                if (!PairedPlayback.hasTrack(remote) || localNode.equals(remote.optString("node"))) {
                    result(report, "目标设备需在线，且已在 Apple Music 中载入曲目。");
                } else if (!autoNode.isEmpty() && (!canAutoJoin() || !PairedPlayback.followable(remote)
                        || !autoNode.equals(remote.optString("node")))) {
                    finishAttempt();
                } else {
                    ensureService(service -> dispatchAttach(service, target, remote, autoNode, report));
                }
            });
        });
    }

    private void ensureService(Consumer<Object> ready) {
        ensureService(ready, false);
    }

    private void ensureService(Consumer<Object> ready, boolean retain) {
        Object service = serviceInstance.get();
        if (service != null && (!retain || serviceBinding != null)) {
            ready.accept(service);
            return;
        }
        Context app = context.getApplication();
        class PlaybackBinding implements ServiceConnection {
            private boolean delivered;

            void finish(Object service) {
                if (serviceBinding != this || delivered || closed) return;
                delivered = true;
                ready.accept(service);
            }

            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                finish(serviceInstance.get());
            }

            @Override public void onServiceDisconnected(ComponentName name) {}
            @Override public void onNullBinding(ComponentName name) { finish(null); }
            @Override public void onBindingDied(ComponentName name) { finish(null); }
        }
        PlaybackBinding binding = new PlaybackBinding();
        Intent intent = new Intent("androidx.media3.session.MediaSessionService")
                .setClassName(app.getPackageName(), SERVICE);
        try {
            if (app.bindService(intent, binding, Context.BIND_AUTO_CREATE)) {
                serviceBinding = binding;
                main.postDelayed(() -> binding.finish(null), 3000);
                return;
            }
        } catch (RuntimeException failure) {
            context.log("Native playback service binding failed", failure);
        }
        ready.accept(null);
    }

    private void dispatchAttach(Object service, RemoteStore.Device target, JSONObject remote, String autoNode,
            Consumer<String> report) {
        try {
            Handler handler = service == null ? null : (Handler) serviceHandler.get(service);
            if (handler == null || !handler.post(() -> attachService(service, target, remote,
                    !autoNode.isEmpty(), handler, report))) {
                result(report, "无法连接原生播放服务。");
            }
        } catch (ReflectiveOperationException error) {
            context.log("Native playback service lookup failed", error);
            result(report, "无法访问原生播放服务。");
        }
    }

    private void attachService(Object service, RemoteStore.Device target, JSONObject remote,
            boolean automatic, Handler handler, Consumer<String> report) {
        if (closed || serviceInstance.get() != service) {
            result(report, "播放服务已退出。");
            return;
        }
        if (automatic && !canAutoJoin()) {
            main.post(this::finishAttempt);
            return;
        }
        ControllerIsolation.Lease lease = null;
        try {
            Object manager = managerField.get(service);
            Object original = currentField.get(manager);
            if (!local.isInstance(original) || Integer.valueOf(1).equals(currentState.invoke(original))) {
                if (automatic) main.post(this::finishAttempt);
                else result(report, "请先暂停本机播放或退出当前投放。");
                return;
            }
            Object queue = nativeTrack.item(remote, 1L);
            lease = isolation.begin(service, original);
            Bridge attached = new Bridge(service, manager, handler, target, queue, remote, lease);
            Object adapter = Proxy.newProxyInstance(controller.getClassLoader(), new Class<?>[]{controller}, attached);
            attached.adapter = adapter;
            // Use the host's coroutine and service dispatcher for the same controller
            // switch used by its playback service. No view listeners are replaced.
            launch(service, manager, adapter);
            bridge = attached;
            handler.postDelayed(() -> confirm(attached, report, 30), 150);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (lease != null && bridge == null) isolation.end(lease, true);
            context.log("Native controller attach failed", unwrap(failure));
            result(report, "原生会话接入失败。请重启 Apple Music 后再试。");
        }
    }

    private void launch(Object service, Object manager, Object target)
            throws ReflectiveOperationException {
        Object job = switchJob.newInstance(service, target, null);
        launch.invoke(null, switchScope.get(manager), null, null, job, 3);
    }

    private void confirm(Bridge entry, Consumer<String> report, int attempts) {
        if (closed || bridge != entry) return;
        try {
            if (currentField.get(entry.manager) != entry.adapter) {
                if (attempts > 0 && serviceInstance.get() == entry.service) {
                    entry.handler.postDelayed(() -> confirm(entry, report, attempts - 1), 150);
                } else {
                    if (serviceInstance.get() != entry.service) {
                        isolation.end(entry.lease, false);
                        bridge = null;
                    }
                    result(report, "原生播放器未确认切换。请重启 Apple Music。");
                }
                return;
            }
            entry.wasAttached = true;
            entry.armed = true;
            Bundle extras = new Bundle();
            extras.putBoolean(REMOTE, true);
            setExtras.invoke(entry.manager, extras);
            entry.startCard();
            entry.publish(true);
            result(report, "已接入「" + entry.target.name + "」。可在原生播放页"
                    + (entry.card == null ? "" : "或通知栏") + "操作。");
            entry.poll = worker.scheduleWithFixedDelay(entry::poll, 160, 650, TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            context.log("Native controller confirmation failed", unwrap(failure));
            result(report, "播放服务未确认完整状态。可退出测试或重启 Apple Music。");
        }
    }

    void detach(Consumer<String> report) {
        autoSuspended = true;
        main.removeCallbacks(autoScan);
        if (connecting) {
            report.accept("正在等待原生播放服务确认。");
            return;
        }
        RoleHandoff handoff = roles;
        if (handoff != null) handoff.localSelected();
        Bridge entry = bridge;
        if (entry == null) {
            report.accept("当前未接入原生会话。");
            return;
        }
        connecting = true;
        entry.exitRequested = true;
        worker.execute(() -> {
            boolean stopped = PairedPlayback.pauseAndConfirm(entry.target.peer);
            Consumer<String> completion = message -> report.accept(!stopped && bridge == null
                    ? "已恢复本机。未能确认原播放设备已暂停，若它仍在播放，请先在那台设备暂停。"
                    : message);
            if (!entry.handler.post(() -> detachService(entry, completion))) {
                result(report, "播放服务已退出。重新打开 Apple Music 即可。");
            }
        });
    }

    private void detachService(Bridge entry, Consumer<String> report) {
        if (bridge != entry || entry.detaching) {
            main.post(() -> report.accept(bridge == null ? "已返回本机播放。" : "正在返回本机播放…"));
            return;
        }
        entry.detaching = true;
        try {
            entry.armed = false;
            entry.closeCard();
            if (entry.poll != null) entry.poll.cancel(false);
            Object current = currentField.get(entry.manager);
            if (current == entry.adapter) {
                Object replacement = newLocal.invoke(null, entry.handler);
                addControllerListener.invoke(replacement, field(entry.manager.getClass(), "k",
                                type(ROOT + "playback.RecentlyPlayedMediaProvider")).get(entry.manager));
                launch(entry.service, entry.manager, replacement);
                entry.handler.postDelayed(() -> confirmDetached(entry, replacement, report, 30), 150);
            } else if (local.isInstance(current) && (current != entry.lease.oldPlayer || entry.wasAttached)) {
                restoreLocalState(entry, current, report);
            } else {
                detachFailed(entry, report, "播放服务尚未完成切换。请重启 Apple Music。");
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            context.log("Native controller detach failed", unwrap(failure));
            detachFailed(entry, report, "本机播放器恢复失败。请重启 Apple Music。");
        }
    }

    private void confirmDetached(Bridge entry, Object replacement, Consumer<String> report, int attempts) {
        try {
            if (bridge != entry) return;
            if (currentField.get(entry.manager) == replacement) {
                restoreLocalState(entry, replacement, report);
            } else if (attempts > 0 && serviceInstance.get() == entry.service) {
                entry.handler.postDelayed(() -> confirmDetached(entry, replacement, report, attempts - 1), 150);
            } else {
                detachFailed(entry, report, "尚未恢复本机控制器。请重启 Apple Music。");
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            context.log("Native controller restore confirmation failed", unwrap(failure));
            detachFailed(entry, report, "尚未恢复本机控制器。请重启 Apple Music。");
        }
    }

    private void restoreLocalState(Bridge entry, Object engine, Consumer<String> report)
            throws ReflectiveOperationException {
        Class<?> listener = stateChanged.getDeclaringClass();
        // The controller reports readiness on the service handler after loading its saved queue.
        class RestoreListener implements InvocationHandler {
            Runnable timeout;

            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                switch (method.getName()) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "AM++ local restore listener";
                    case "onPlayerStateRestored":
                        if (args[0] == engine) {
                            entry.handler.removeCallbacks(timeout);
                            try {
                                removeControllerListener.invoke(engine, proxy);
                                if (bridge == entry && serviceInstance.get() == entry.service
                                        && currentField.get(entry.manager) == engine) {
                                    Bundle extras = new Bundle();
                                    extras.putBoolean(REMOTE, false);
                                    setExtras.invoke(entry.manager, extras);
                                    restoreLocal(entry, report);
                                } else if (bridge == entry) {
                                    detachFailed(entry, report, "本机播放器已切换，请重新打开播放页。");
                                }
                            } catch (ReflectiveOperationException | RuntimeException failure) {
                                context.log("Local playback state could not be restored", unwrap(failure));
                                detachFailed(entry, report, "本机播放器恢复失败。请重启 Apple Music。");
                            }
                        }
                        return null;
                    default: return null;
                }
            }
        }
        RestoreListener completion = new RestoreListener();
        Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, completion);
        completion.timeout = () -> {
            try {
                removeControllerListener.invoke(engine, proxy);
            } catch (ReflectiveOperationException failure) {
                context.log("Local restore listener cleanup failed", unwrap(failure));
            }
            if (bridge == entry) detachFailed(entry, report, "本机队列恢复超时。请重试退出遥控。");
        };
        addControllerListener.invoke(engine, proxy);
        entry.handler.postDelayed(completion.timeout, 2500);
        try {
            restoreState.invoke(engine, false, false);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            entry.handler.removeCallbacks(completion.timeout);
            removeControllerListener.invoke(engine, proxy);
            throw failure;
        }
    }

    private void detachFailed(Bridge entry, Consumer<String> report, String message) {
        entry.detaching = false;
        entry.exitRequested = false;
        result(report, message);
    }

    private void restoreLocal(Bridge entry, Consumer<String> report) {
        isolation.end(entry.lease, true);
        bridge = null;
        if (nextButton != null) nextButton.refresh();
        result(report, "已恢复本机播放器。");
    }

    private void recoverOffline(Bridge entry) {
        if (closed || bridge != entry || !entry.armed || entry.exitRequested || takingPlayback) return;
        RoleHandoff handoff = roles;
        if (handoff != null) handoff.localSelected();
        detachService(entry, message -> {});
    }

    private void result(Consumer<String> report, String message) {
        main.post(() -> {
            finishAttempt();
            lastReport = message;
            if (!closed) report.accept(message);
        });
    }

    private void finishAttempt() {
        connecting = false;
        if (bridge == null && !holdLocalService) unbindService();
    }

    private void unbindService() {
        ServiceConnection binding = serviceBinding;
        serviceBinding = null;
        holdLocalService = false;
        if (binding != null) context.getApplication().unbindService(binding);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof InvocationTargetException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(autoScan);
        main.post(this::unbindService);
        Bridge entry = bridge;
        if (entry != null) {
            entry.closeCard();
            entry.handler.post(() -> detachService(entry, unused -> {}));
        }
        coverWorker.shutdownNow();
        scanner.shutdownNow();
        worker.shutdownNow();
    }

    private final class Bridge implements InvocationHandler {
        final Object service;
        final Object manager;
        final Handler handler;
        final RemoteStore.Device target;
        volatile Object queue;
        final ControllerIsolation.Lease lease;
        volatile RemoteMediaCard card;
        final CopyOnWriteArraySet<Object> listeners = new CopyOnWriteArraySet<>();
        volatile TrackSnapshot snapshot;
        private long nextQueueId = 2L;
        private JSONObject lastTrack;
        private long lastRefresh;
        private long nextRoleLookup;
        private final RemoteReachability reachability = new RemoteReachability();
        private long artworkEpoch;
        private String artworkKey = "";
        private String shownArtwork;
        private boolean artworkPending;
        private long nextArtworkTry;
        private int artworkAttempts;
        volatile boolean armed;
        volatile boolean exitRequested;
        boolean detaching;
        boolean wasAttached;
        volatile java.util.concurrent.ScheduledFuture<?> poll;
        Object adapter;
        int oldState = -1;

        Bridge(Object service, Object manager, Handler handler, RemoteStore.Device target, Object queue,
                JSONObject snapshot, ControllerIsolation.Lease lease) {
            this.service = service;
            this.manager = manager;
            this.handler = handler;
            this.target = target;
            this.queue = queue;
            this.lease = lease;
            this.lastTrack = snapshot;
            this.snapshot = new TrackSnapshot(snapshot, SystemClock.elapsedRealtime());
        }

        void startCard() {
            try {
                RemoteMediaCard.Foreground sessionService = foreground == null ? null : new RemoteMediaCard.Foreground() {
                    @Override public boolean show(int id, android.app.Notification notification) {
                        return foreground.show(service, id, notification);
                    }
                    @Override public void end() { foreground.end(service); }
                };
                card = new RemoteMediaCard(context.getApplication(), handler, target.name, this::send, sessionService);
            } catch (RuntimeException unavailable) {
                context.log("Remote notification unavailable", unavailable);
            }
        }

        void closeCard() {
            RemoteMediaCard current = card;
            card = null;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException unavailable) {
                    context.log("Remote notification cleanup failed", unavailable);
                }
            }
        }

        void updateCard() {
            RemoteMediaCard current = card;
            if (current == null) return;
            try {
                current.update(snapshot, shownArtwork);
            } catch (RuntimeException unavailable) {
                context.log("Remote notification update failed", unavailable);
                closeCard();
            }
        }

        void poll() {
            if (closed || !armed || exitRequested || serviceInstance.get() != service) return;
            JSONObject state = PairedPlayback.readRaw(target);
            boolean reachable = PairedPlayback.outputReachable(state);
            long now = SystemClock.elapsedRealtime();
            if (reachability.shouldRecover(reachable, now)) {
                handler.post(() -> recoverOffline(this));
                return;
            }
            RoleHandoff currentRoles = roles;
            if (currentRoles != null && reachable) {
                String destination = state.optString("following");
                if (!destination.isEmpty() && !destination.equals(localNode) && now >= nextRoleLookup) {
                    nextRoleLookup = now + 3000;
                    RemoteStore.Device nextDevice = currentRoles.device(destination);
                    if (nextDevice != null) main.post(() -> {
                        if (bridge == this && armed && !exitRequested && !connecting) follow(nextDevice, 0);
                    });
                }
            }
            JSONObject data = state != null && state.optBoolean("ready") ? state : null;
            TrackSnapshot next = new TrackSnapshot(data, now);
            handler.post(() -> accept(next));
        }

        void accept(TrackSnapshot next) {
            if (!armed) return;
            TrackSnapshot old = snapshot;
            Object previousQueue = queue;
            Object replacement = queue;
            boolean trackChanged = next.data != null && !TrackSnapshot.sameTrack(lastTrack, next.data);
            if (trackChanged) {
                try {
                    // Use the host's own metadata and queue-item model. The receiver
                    // keeps ownership of the actual queue and playback order.
                    long id = nextQueueId++;
                    if (id == (Long) queueId.invoke(previousQueue)) id = nextQueueId++;
                    replacement = nativeTrack.item(next.data, id);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    context.log("Native remote metadata unavailable", unwrap(failure));
                    next = new TrackSnapshot(null, next.sampledAt);
                    trackChanged = false;
                }
            }
            if (trackChanged) {
                queue = replacement;
                shownArtwork = NativeTrack.appleArtwork(next.data.optString("artworkUrl"));
                resetArtwork(next.data.optString("art"));
            }
            if (next.data != null && (trackChanged || !next.data.optString("queueEntry").isEmpty())) {
                lastTrack = next.data;
            }
            snapshot = next;
            notifyNative(false, trackChanged, previousQueue, old);
            updateCard();
            if (next.data != null) updateArtwork(next.data);
        }

        void publish(boolean initial) {
            notifyNative(initial, false, null, null);
            updateCard();
            if (snapshot.data != null) updateArtwork(snapshot.data);
        }

        private void resetArtwork(String key) {
            artworkKey = key;
            artworkEpoch++;
            artworkPending = false;
            artworkAttempts = 0;
            nextArtworkTry = 0L;
        }

        private void updateArtwork(JSONObject data) {
            String key = data.optString("art");
            if (!ArtworkCache.validKey(key)) return;
            if (!key.equals(artworkKey)) resetArtwork(key);
            String online = NativeTrack.appleArtwork(data.optString("artworkUrl"));
            if (online != null && shownArtwork == null) publishArtwork(data, online);
            long now = SystemClock.elapsedRealtime();
            if (closed || artworkPending || artworkAttempts >= 9 || now < nextArtworkTry
                    || (shownArtwork != null && shownArtwork.startsWith("file:"))) return;
            artworkPending = true;
            long epoch = artworkEpoch;
            try {
                coverWorker.execute(() -> {
                    String url = coverCache.url(target.peer, key);
                    handler.post(() -> {
                        if (closed || !armed || epoch != artworkEpoch) return;
                        artworkPending = false;
                        artworkAttempts++;
                        nextArtworkTry = SystemClock.elapsedRealtime() + 3600L;
                        JSONObject current = snapshot.data;
                        if (url != null && current != null && key.equals(current.optString("art"))
                                && TrackSnapshot.sameTrack(data, current)) {
                            publishArtwork(current, url);
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                artworkPending = false;
            }
        }

        private void publishArtwork(JSONObject data, String url) {
            if (url.equals(shownArtwork)) return;
            try {
                long id = (Long) queueId.invoke(queue);
                Object updated = nativeTrack.item(data, id, url);
                queue = updated;
                shownArtwork = url;
                updateCard();
                for (Object listener : listeners) {
                    try {
                        metadataChanged.invoke(listener, adapter, updated);
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        context.log("Native cover callback rejected", unwrap(failure));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                context.log("Native artwork metadata unavailable", unwrap(failure));
            }
        }

        private void notifyNative(boolean initial, boolean trackChanged, Object previousQueue, TrackSnapshot old) {
            if (!armed) return;
            TrackSnapshot next = snapshot;
            int state = next.playing() ? 1 : 2;
            int previousState = oldState < 0 ? 2 : oldState;
            boolean playbackChanged = oldState != state;
            boolean jumped = TrackSnapshot.jumped(old, next);
            boolean refresh = initial || trackChanged || jumped || next.sampledAt - lastRefresh >= 1800L;
            if (refresh || playbackChanged) lastRefresh = next.sampledAt;
            oldState = state;
            if (nextButton != null && (initial || old != null
                    && ((old.actions() ^ next.actions()) & PlaybackState.ACTION_SKIP_TO_NEXT) != 0)) {
                nextButton.refresh();
            }
            publishModes(initial, old, next);
            for (Object listener : listeners) {
                try {
                    if (initial || trackChanged) {
                        currentChanged.invoke(listener, adapter, previousQueue, queue);
                        queueChanged.invoke(listener, adapter, Collections.emptyList());
                    }
                    if (initial || playbackChanged) stateChanged.invoke(listener, adapter, previousState, state);
                    else if (refresh || old != null && old.actions() != next.actions()) {
                        stateUpdated.invoke(listener, adapter);
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    context.log("Native playback callback rejected", unwrap(error));
                }
            }
            if (jumped || trackChanged) notifyPosition(previousQueue, old, next, trackChanged);
        }

        private void publishModes(boolean initial, TrackSnapshot old, TrackSnapshot next) {
            JSONObject previous = old == null ? null : old.data;
            boolean changed = initial || old != null && old.actions() != next.actions();
            PlaybackMode[] modes = PlaybackMode.values();
            int updated = 0;
            for (PlaybackMode mode : modes) {
                if (initial || mode.value(previous) != mode.value(next.data)
                        || mode.available(previous) != mode.available(next.data)) {
                    updated |= 1 << mode.ordinal();
                }
            }
            if (!changed && updated == 0) return;
            try {
                Object event = modeChanges.newInstance();
                for (PlaybackMode mode : modes) {
                    playbackModes.get(mode).event.invoke(event,
                            (updated & (1 << mode.ordinal())) != 0 ? Integer.valueOf(mode.value(next.data)) : null);
                }
                setExtras.invoke(manager, new Bundle());
                for (Object listener : listeners) modesChanged.invoke(listener, adapter, event);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                context.log("Native playback mode update failed", unwrap(failure));
            }
        }

        private void notifyPosition(Object previousQueue, TrackSnapshot old, TrackSnapshot next, boolean changed) {
            try {
                Object manager = mediaManager.get(service);
                long before = Math.max(0L, old.position(next.sampledAt));
                long after = Math.max(0L, next.position(next.sampledAt));
                Object beforeItem = nativeMediaItem.invoke(null, adapter, previousQueue);
                Object afterItem = changed ? nativeMediaItem.invoke(null, adapter, queue) : beforeItem;
                Object from = positionInfo.newInstance(null, 0, beforeItem, null, 0, before, before, -1, -1);
                Object to = positionInfo.newInstance(null, 0, afterItem, null, 0, after, after, -1, -1);
                List<?> clients = new java.util.ArrayList<>((List<?>) engineListeners.get(manager));
                for (Object client : clients) positionChanged.invoke(client, from, to, changed ? 0 : 1);
            } catch (ReflectiveOperationException | RuntimeException error) {
                context.log("Native seek notification rejected", unwrap(error));
            }
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            TrackSnapshot current = snapshot;
            JSONObject data = current.data;
            long actions = current.actions();
            for (PlaybackMode mode : PlaybackMode.values()) {
                String name = method.getName();
                if (mode.getter.equals(name)) return mode.value(data);
                if (mode.capability.equals(name)) return mode.available(data);
                if (mode.setter.equals(name)) {
                    int value = mode == PlaybackMode.AUTOPLAY ? (Boolean.TRUE.equals(args[0]) ? 2 : 1)
                            : (Integer) args[0];
                    if (mode.available(data) && mode.accepts(value)) send(mode.route, value);
                    return null;
                }
            }
            switch (method.getName()) {
                case "toString": return "AM++ native LAN controller";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "addListener": if (args[0] != null) listeners.add(args[0]); return null;
                case "removeListener": listeners.remove(args[0]); return null;
                case "getCurrentItem": return queue;
                case "getQueueItems":
                case "getAllQueueItems": return Collections.singletonList(queue);
                case "getPlaybackQueueItemCount":
                case "getPlaybackQueueRepeatableItemCount": return 1;
                case "prepare": selectSong(args); return null;
                case "getPlaybackState": return current.playing() ? 1 : 2;
                case "getCurrentPosition":
                case "getSavedPlaybackPosition":
                case "getBufferedPosition": return current.position(SystemClock.elapsedRealtime());
                case "getDuration": return data == null ? -1L : data.optLong("duration", -1);
                case "getPlaybackRate": return current.playing() ? 1f : 0f;
                case "canSeek": return (actions & PlaybackState.ACTION_SEEK_TO) != 0;
                case "canSkipToNextItem": return (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
                case "canSkipToPreviousItem": return (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0;
                case "skipToNextItem": send("next", -1); return null;
                case "skipToPreviousItem": send("previous", -1); return null;
                case "play": send("play", -1); return null;
                case "pause": send("pause", -1); return null;
                case "seekToPosition": if (armed && args[0] instanceof Long) send("seek", (Long) args[0]); return null;
                case "release": armed = false; closeCard(); if (poll != null) poll.cancel(false); return null;
                default:
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == float.class) return 0f;
                    if (type == long.class) return 0L;
                    if (type == java.util.Set.class) return Collections.emptySet();
                    if (type == List.class) return Collections.emptyList();
                    return null;
            }
        }

        private void selectSong(Object[] args) {
            if (!armed || closed || !CatalogPlayback.requestsPlayback(args)) return;
            String id;
            String album = null;
            int index = -1;
            try {
                id = catalogPlayback.selectedSong(args[0]);
                if (id != null) {
                    index = catalogPlayback.selectedIndex(args[0]);
                    album = catalogPlayback.albumFromProvider(args[0]);
                }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                context.log("Native selected catalog track unavailable", unwrap(unavailable));
                id = null;
            }
            if (id == null) {
                context.log("No catalog ID in playback provider " + args[0].getClass().getName(), null);
                selectionMessage("这项内容无法在远端直接播放。可从 Apple Music 搜索结果里点单首歌曲。");
                return;
            }
            enqueueSong(id, album, index);
        }

        private boolean enqueueSong(String id, String album, int index) {
            if (!armed || closed || bridge != this) return false;
            try {
                worker.execute(() -> {
                    if (!armed || exitRequested || closed || bridge != this) return;
                    int status = album != null && index >= 0 && index <= 9999
                            ? target.peer.playAlbumSong(id, album, index) : 404;
                    if (status == 404 || status == 422) status = target.peer.playCatalogSong(id);
                    if (status != 204) {
                        selectionMessage(status == 404
                                ? "请将播放设备上的局域网遥控插件也更新至 0.5.1。"
                                : "未能在「" + target.name + "」播放所选歌曲。");
                    }
                    poll();
                });
                return true;
            } catch (java.util.concurrent.RejectedExecutionException closing) {
                return false;
            }
        }

        private void selectionMessage(String message) {
            main.post(() -> {
                if (armed && !closed && bridge == this) {
                    android.widget.Toast.makeText(context.getApplication(), message,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        private void send(String action, long position) {
            TrackSnapshot requested = snapshot;
            JSONObject requestedTrack = requested.data;
            if (!armed || closed || requestedTrack == null || worker.isShutdown()) return;
            boolean restart = "previous".equals(action) && requested.restartsOnPrevious();
            String remoteAction = restart ? "seek" : action;
            long remotePosition = restart ? 0 : position;
            try {
                worker.execute(() -> {
                    if (!armed || exitRequested || snapshot.data == null || ("seek".equals(remoteAction)
                            && !TrackSnapshot.sameTrack(requestedTrack, snapshot.data))) return;
                    int result = target.peer.command(remoteAction, remotePosition);
                    lastReport = "收到原生 " + action + " 指令 · 接收端返回 " + result;
                    context.log(lastReport, null);
                    poll();
                    if (result == 204 && ("seek".equals(action) || "next".equals(action) || "previous".equals(action)
                            || PlaybackMode.forRoute(action) != null)) {
                        worker.schedule(this::poll, 170, TimeUnit.MILLISECONDS);
                        worker.schedule(this::poll, 440, TimeUnit.MILLISECONDS);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException closing) {
                // The probe was disabled while the host was sending a command.
            }
        }
    }
}
