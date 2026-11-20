package ampp.remote;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

final class CatalogPlayback {
    private static final String ROOT = "com.apple.android.music.";
    private static final String QUEUE = ROOT + "playback.queue.";
    private static final Pattern CATALOG_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final int SONG = 1;
    static final int REPLACE_QUEUE = 1;

    private final Method startIndex;
    private final Method containerType, containerStoreId;
    private final Method itemCount;
    private final Method getItem;
    private final Method mediaType;
    private final Method mediaStoreId;
    private final Method mediaAlbumId;
    private final Class<?> playbackItem;
    private final Method contentType;
    private final Method playbackStoreId;
    private final Method rowId;
    private final Field libraryItem;
    private final Field storeIds;
    private final Constructor<?> builder;
    private final Method explicitAllowed;
    private final Method setExplicitAllowed;
    private final Method build;
    private final Method prepare;
    private final Method albumStart;
    private final Class<?> collectionItem;

    CatalogPlayback(ClassLoader loader, Class<?> controller, Class<?> media) throws ReflectiveOperationException {
        Class<?> provider = loader.loadClass(QUEUE + "PlaybackQueueItemProvider");
        startIndex = provider.getMethod("getStartItemIndex");
        containerType = provider.getMethod("getContainerType");
        containerStoreId = provider.getMethod("getContainerStoreId");
        itemCount = provider.getMethod("getItemCount");
        getItem = provider.getMethod("getItemAtIndex", int.class);
        mediaType = media.getMethod("getType");
        mediaStoreId = media.getMethod("getSubscriptionStoreId");
        mediaAlbumId = media.getMethod("getAlbumSubscriptionStoreId");
        playbackItem = loader.loadClass(ROOT + "model.PlaybackItem");
        collectionItem = loader.loadClass(ROOT + "model.CollectionItemView");
        contentType = collectionItem.getMethod("getContentType");
        playbackStoreId = playbackItem.getMethod("getSubscriptionStoreId");
        rowId = collectionItem.getMethod("getId");
        libraryItem = field(loader.loadClass(QUEUE + "LibraryItemPlaybackQueueItemProvider"), "item");
        storeIds = field(loader.loadClass(QUEUE + "StorePlaybackQueueItemProvider"), "subscriptionItemIds");
        Class<?> storeBuilder = loader.loadClass(QUEUE + "StorePlaybackQueueItemProvider$Builder");
        builder = storeBuilder.getConstructor(String[].class);
        explicitAllowed = loader.loadClass("ja.U").getMethod("c");
        setExplicitAllowed = storeBuilder.getMethod("setIsExplicitAllowed", Boolean.class);
        build = storeBuilder.getMethod("build");
        albumStart = storeBuilder.getMethod("startItemIndex", int.class);
        prepare = controller.getMethod("prepare", provider, int.class, boolean.class);
    }

    static boolean validId(String id) {
        return id != null && CATALOG_ID.matcher(id).matches()
                && (id.length() < 19 || id.compareTo("9223372036854775807") <= 0);
    }

    static boolean requestsPlayback(Object[] args) {
        if (args == null || args.length < 2 || args[0] == null) return false;
        if (args.length == 2) return Boolean.TRUE.equals(args[1]);
        return Integer.valueOf(REPLACE_QUEUE).equals(args[1]) && Boolean.TRUE.equals(args[2]);
    }

    String selectedSong(Object provider) throws ReflectiveOperationException {
        int index = Math.max(0, (Integer) startIndex.invoke(provider));
        if (index < (Integer) itemCount.invoke(provider)) {
            Object item = getItem.invoke(provider, index);
            if (item != null && Integer.valueOf(SONG).equals(mediaType.invoke(item))) {
                return checked(mediaStoreId.invoke(item));
            }
            return null;
        }
        if (libraryItem.getDeclaringClass().isInstance(provider)) {
            return songFromRow(libraryItem.get(provider));
        } else if (storeIds.getDeclaringClass().isInstance(provider)) {
            String[] ids = (String[]) storeIds.get(provider);
            if (ids != null && ids.length == 1 && index == 0) return checked(ids[0]);
        }
        return null;
    }

    String songFromRow(Object row) throws ReflectiveOperationException {
        if (!playbackItem.isInstance(row) || !Integer.valueOf(SONG).equals(contentType.invoke(row))) return null;
        String storeId = checked(playbackStoreId.invoke(row));
        return storeId == null ? checked(rowId.invoke(row)) : storeId;
    }

    String albumFromRow(Object row) throws ReflectiveOperationException {
        return collectionItem.isInstance(row) && Integer.valueOf(3).equals(contentType.invoke(row))
                ? checked(rowId.invoke(row)) : null;
    }

    String albumFromProvider(Object provider) throws ReflectiveOperationException {
        if (Integer.valueOf(1).equals(containerType.invoke(provider))) {
            String album = checked(containerStoreId.invoke(provider));
            if (album != null) return album;
        }
        int index = selectedIndex(provider);
        if (index >= (Integer) itemCount.invoke(provider)) return null;
        Object item = getItem.invoke(provider, index);
        return item == null ? null : checked(mediaAlbumId.invoke(item));
    }

    int selectedIndex(Object provider) throws ReflectiveOperationException {
        return Math.max(0, (Integer) startIndex.invoke(provider));
    }

    void play(Object controller, String id) throws ReflectiveOperationException {
        prepare(controller, id, true);
    }

    void prepare(Object controller, String id, boolean playing) throws ReflectiveOperationException {
        prepare(controller, id, -1, playing);
    }

    void prepare(Object controller, String id, int index, boolean playing) throws ReflectiveOperationException {
        Object request = builder.newInstance((Object) new String[]{id});
        if (index >= 0) albumStart.invoke(request, index);
        setExplicitAllowed.invoke(request, explicitAllowed.invoke(null));
        Object provider = build.invoke(request);
        prepare.invoke(controller, provider, REPLACE_QUEUE, playing);
    }

    private static String checked(Object id) {
        return id instanceof String && validId((String) id) ? (String) id : null;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
