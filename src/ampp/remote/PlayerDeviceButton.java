package ampp.remote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginObservation;
import dev.amenhancer.plugin.api.PluginObserver;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.function.Consumer;

final class PlayerDeviceButton implements AutoCloseable {
    private static final String TAG = "ampp.remote.output";
    private final PluginContext plugin;
    private final Consumer<Context> open;
    private final java.util.function.Supplier<String> outputName;
    private final Constructor<?> params;
    // ConstraintLayout.LayoutParams names in Apple Music 1614.
    private final Field leftLeft, leftRight, rightLeft;
    private final Field startEnd, endStart, endEnd;
    private final Field goneStartMargin;
    private final int subtitleId, moreId;
    private final Field[] vertical;
    private final java.util.List<java.lang.ref.WeakReference<DeviceIcon>> installed = new java.util.ArrayList<>();
    private final java.util.Map<View, LayoutWatch> watched = new java.util.WeakHashMap<>();
    private volatile boolean closed;

    PlayerDeviceButton(PluginContext plugin, Consumer<Context> open, java.util.function.Supplier<String> outputName)
            throws ReflectiveOperationException {
        this.plugin = plugin;
        this.open = open;
        this.outputName = outputName;
        Class<?> type = plugin.getHostClassLoader().loadClass("androidx.constraintlayout.widget.ConstraintLayout$b");
        params = type.getConstructor(int.class);
        leftLeft = type.getField("e");
        leftRight = type.getField("f");
        rightLeft = type.getField("g");
        startEnd = type.getField("s");
        endStart = type.getField("u");
        endEnd = type.getField("v");
        goneStartMargin = type.getField("A");
        vertical = new Field[]{type.getField("i"), type.getField("j"), type.getField("k"), type.getField("l")};

        int mini = resource(plugin.getApplication(), "layout", "mini_player");
        int host = resource(plugin.getApplication(), "layout", "fragment_player_main");
        int full = resource(plugin.getApplication(), "layout", "music_player");
        moreId = resource(plugin.getApplication(), "id", "list_left_icon");
        subtitleId = resource(plugin.getApplication(), "id", "subtitle");
        plugin.getHooks().observe(LayoutInflater.class.getMethod("inflate", int.class, ViewGroup.class, boolean.class),
                new PluginObserver() {
                    @Override public void after(PluginObservation event) {
                        if (event.getThrowable() != null || !(event.getResult() instanceof View)) return;
                        Object[] arguments = event.getArguments();
                        int layout = (Integer) arguments[0];
                        if (layout != mini && layout != host && layout != full) return;
                        View view = (View) event.getResult();
                        view.post(() -> watch(view, layout == full));
                    }
                });
        Class<?> song = plugin.getHostClassLoader().loadClass(
                "com.apple.android.music.player.fragment.PlayerSongViewFragment");
        plugin.getHooks().observe(song.getDeclaredMethod("onViewCreated", View.class, Bundle.class),
                new PluginObserver() {
                    @Override public void after(PluginObservation event) {
                        if (event.getThrowable() == null) watch((View) event.getArguments()[0], true);
                    }
                });
    }

    private void watch(View view, boolean full) {
        if (closed) return;
        View root = find(view, full ? "player_container" : "mini_player_content");
        if (root == null || watched.containsKey(root)) return;
        LayoutWatch watch = new LayoutWatch(root, full);
        watched.put(root, watch);
        root.getViewTreeObserver().addOnGlobalLayoutListener(watch);
        root.addOnAttachStateChangeListener(watch);
        insertSafely(root, full);
    }

    private void insertSafely(View root, boolean full) {
        if (closed) return;
        try {
            insert(root, full);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.log("Playback device button could not join the player layout", failure);
        }
    }

    private void insert(View root, boolean full) throws ReflectiveOperationException {
        View panel = root.findViewById(resource(root.getContext(), "id", "mini_player_content"));
        View queue = panel == null ? null : find(panel, "queue_button");
        if (queue == null && panel != null) queue = find(panel, "overflow_button");
        View anchor = full ? find(root, "list_favorite_icon")
                : queue != null ? queue : find(root, "mini_player_play_btn");
        if (!(anchor instanceof ImageView) || !(anchor.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) anchor.getParent();
        if (!params.getDeclaringClass().isInstance(anchor.getLayoutParams())) return;
        Context context = anchor.getContext();
        int fullSize = dp(context, 44);
        android.content.res.Configuration window = context.getResources().getConfiguration();
        boolean wide = full && landscapeControls(window.screenWidthDp, window.screenHeightDp);
        boolean foregroundButton = full && prioritizeDeviceButton(window.screenWidthDp, window.screenHeightDp);
        boolean favoriteMeasured = wide && anchor.getVisibility() == View.VISIBLE;
        int fullWidth = favoriteMeasured && anchor.getWidth() > 0 ? anchor.getWidth() : fullSize;
        int fullHeight = favoriteMeasured && anchor.getHeight() > 0 ? anchor.getHeight() : fullSize;
        int gap = wide ? deviceGap((ImageView) anchor, parent.findViewById(moreId), fullWidth) : 0;
        DeviceIcon existing = parent.findViewWithTag(TAG);
        if (existing != null) {
            updateOutputLabel(existing);
            if (existing.favoriteSpace != null) {
                existing.favoriteSpace.apply(fullWidth + gap);
                if (alignWithFavorite(existing.getLayoutParams(), anchor.getId(), fullWidth, fullHeight, gap)) {
                    existing.requestLayout();
                }
                updateInteraction(existing, foregroundButton);
                if (existing.getVisibility() != View.VISIBLE) existing.setVisibility(View.VISIBLE);
            }
            return;
        }

        ViewGroup.LayoutParams anchorParams = anchor.getLayoutParams();
        int width = full ? fullWidth : dp(context, 40);
        ViewGroup.LayoutParams buttonParams = (ViewGroup.LayoutParams) params.newInstance(width);
        if (!full) {
            buttonParams.height = anchorParams.height;
            for (Field constraint : vertical) constraint.setInt(buttonParams, constraint.getInt(anchorParams));
        }
        TextView label = root.findViewById(resource(context, "id", full ? "title" : "mini_player_title"));
        DeviceIcon button = new DeviceIcon(context, (ImageView) anchor, label);
        button.setId(View.generateViewId());
        button.setTag(TAG);
        updateOutputLabel(button);
        button.setOnHoverListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_ENTER) updateOutputLabel(view);
            return false;
        });
        button.setFocusable(true);
        button.feedback(false);
        button.setOnClickListener(clicked -> open.accept(clicked.getContext()));

        if (queue != null && !full) {
            // Extend the tablet's existing relative chain past Queue.
            int tail = endEnd.getInt(anchorParams);
            if (tail == -1) return;
            startEnd.setInt(buttonParams, anchor.getId());
            endEnd.setInt(buttonParams, tail);
            button.link(endEnd, anchor, -1);
            button.link(endStart, anchor, button.getId());
        } else if (full) {
            // Keep the host's horizontal chain intact. A start margin reserves a
            // slot even when the optional emoji view in that chain is gone.
            alignWithFavorite(buttonParams, anchor.getId(), width, fullHeight, gap);
            button.favoriteSpace = new FavoriteSpace(anchor);
            button.favoriteSpace.apply(width + gap);
            View subtitle = root.findViewById(subtitleId);
            if (subtitle != null && subtitle.getParent() == parent) button.subtitle = subtitle;
            updateInteraction(button, foregroundButton);
        } else {
            int previous = leftRight.getInt(anchorParams);
            if (previous != -1) {
                View upstream = parent.findViewById(previous);
                if (upstream == null || upstream.getParent() != parent) return;
                button.link(rightLeft, upstream, button.getId());
                leftRight.setInt(buttonParams, previous);
                upstream.requestLayout();
            } else {
                leftLeft.setInt(buttonParams, leftLeft.getInt(anchorParams));
            }
            rightLeft.setInt(buttonParams, anchor.getId());
            button.link(leftLeft, anchor, -1);
            button.link(leftRight, anchor, button.getId());
        }
        int index = parent.indexOfChild(anchor) + (queue != null && !full ? 1 : 0);
        parent.addView(button, index, buttonParams);
        LayoutWatch watch = watched.get(root);
        if (watch != null) watch.button = new java.lang.ref.WeakReference<>(button);
        installed.removeIf(reference -> reference.get() == null);
        installed.add(new java.lang.ref.WeakReference<>(button));
    }

    private boolean alignWithFavorite(ViewGroup.LayoutParams layout, int favoriteId, int width, int height, int gap)
            throws IllegalAccessException {
        // Fixed size and top-to-top/bottom-to-bottom of Favorite center the
        // button on the real control, including after the host relays out.
        boolean changed = layout.width != width || layout.height != height;
        layout.width = width;
        layout.height = height;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) layout;
        if (margins.getMarginEnd() != gap) {
            margins.setMarginEnd(gap);
            changed = true;
        }
        changed |= setConstraint(endStart, layout, favoriteId);
        changed |= setConstraint(vertical[0], layout, favoriteId);
        changed |= setConstraint(vertical[1], layout, -1);
        changed |= setConstraint(vertical[2], layout, -1);
        changed |= setConstraint(vertical[3], layout, favoriteId);
        return changed;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void updateOutputLabel(View button) {
        String device = outputName.get();
        String label = device == null ? "播放设备" : "播放设备 · 在 " + device + " 上播放";
        if (!label.equals(button.getContentDescription())) {
            button.setContentDescription(label);
            button.setTooltipText(label);
        }
    }

    static boolean landscapeControls(int width, int height) {
        return width >= 600 && height > 0 && width >= height * 1.15f;
    }

    static boolean prioritizeDeviceButton(int width, int height) {
        // Use the same overlap handling in near-square windows, including small popups.
        return width > 0 && height > 0 && (landscapeControls(width, height)
                || (width >= height * 0.85f && width < height * 1.15f));
    }

    private static int deviceGap(ImageView favorite, View more, int buttonWidth) {
        if (!(more instanceof ImageView) || more.getParent() != favorite.getParent()
                || favorite.getWidth() <= 0 || more.getWidth() <= 0 || more.getVisibility() != View.VISIBLE) {
            return 0;
        }
        float favoriteCenter = iconCenter(favorite);
        float pitch = Math.abs(iconCenter((ImageView) more) - favoriteCenter);
        float starInset = favorite.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? favorite.getRight() - favoriteCenter : favoriteCenter - favorite.getLeft();
        return matchingGap(pitch, starInset, buttonWidth);
    }

    static int matchingGap(float pitch, float starInset, int buttonWidth) {
        return Math.max(0, Math.round(pitch - starInset - buttonWidth / 2f));
    }

    private static float iconCenter(ImageView image) {
        android.graphics.drawable.Drawable icon = image.getDrawable();
        if (icon == null || icon.getBounds().isEmpty()) return image.getLeft() + image.getWidth() / 2f;
        android.graphics.RectF bounds = new android.graphics.RectF(icon.getBounds());
        image.getImageMatrix().mapRect(bounds);
        return image.getLeft() + image.getPaddingLeft() + bounds.centerX();
    }

    private void updateInteraction(DeviceIcon button, boolean foregroundButton) {
        button.feedback(foregroundButton);
        if (!foregroundButton) {
            if (button.getTranslationZ() != 0) button.setTranslationZ(0);
            return;
        }
        float titleZ = button.label == null ? 0 : button.label.getZ();
        float subtitleZ = button.subtitle == null ? 0 : button.subtitle.getZ();
        float priority = Math.max(titleZ, subtitleZ) + dp(button.getContext(), 1) - button.getElevation();
        if (button.getTranslationZ() != priority) button.setTranslationZ(priority);
    }

    private static boolean setConstraint(Field field, ViewGroup.LayoutParams layout, int target)
            throws IllegalAccessException {
        if (field.getInt(layout) == target) return false;
        field.setInt(layout, target);
        return true;
    }

    private static View find(View root, String name) {
        return root.findViewById(resource(root.getContext(), "id", name));
    }

    private static int resource(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    @Override public void close() {
        closed = true;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (LayoutWatch watch : watched.values()) watch.close();
            watched.clear();
            for (java.lang.ref.WeakReference<DeviceIcon> reference : installed) {
                DeviceIcon icon = reference.get();
                if (icon != null) icon.remove();
            }
            installed.clear();
        });
    }

    private final class LayoutWatch implements ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        private final java.lang.ref.WeakReference<View> root;
        private final boolean full;
        private java.lang.ref.WeakReference<DeviceIcon> button;

        LayoutWatch(View root, boolean full) {
            this.root = new java.lang.ref.WeakReference<>(root);
            this.full = full;
        }

        @Override public void onGlobalLayout() {
            View view = root.get();
            if (view == null) return;
            DeviceIcon icon = button == null ? null : button.get();
            if (icon != null && icon.getParent() == null) {
                icon.remove();
                button = null;
            }
            insertSafely(view, full);
        }

        @Override public void onViewAttachedToWindow(View view) {
            view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            view.getViewTreeObserver().addOnGlobalLayoutListener(this);
            view.post(() -> insertSafely(view, full));
        }

        @Override public void onViewDetachedFromWindow(View view) {
            view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        }

        void close() {
            View view = root.get();
            if (view == null) return;
            onViewDetachedFromWindow(view);
            view.removeOnAttachStateChangeListener(this);
        }
    }

    private final class FavoriteSpace {
        private final View anchor;
        private int originalStart;
        private int originalGone;
        private int appliedStart;
        private int appliedGone;
        private boolean applied;

        FavoriteSpace(View anchor) {
            this.anchor = anchor;
        }

        void apply(int width) throws IllegalAccessException {
            ViewGroup.MarginLayoutParams layout = (ViewGroup.MarginLayoutParams) anchor.getLayoutParams();
            int start = layout.getMarginStart();
            int gone = goneStartMargin.getInt(layout);
            if (!applied || start != appliedStart) originalStart = start;
            if (!applied || gone != appliedGone) originalGone = gone;
            applied = true;
            appliedStart = originalStart + width;
            appliedGone = (originalGone == Integer.MIN_VALUE ? originalStart : originalGone) + width;
            if (start == appliedStart && gone == appliedGone) return;
            layout.setMarginStart(appliedStart);
            goneStartMargin.setInt(layout, appliedGone);
            anchor.requestLayout();
        }

        void restore() {
            if (!applied || !params.getDeclaringClass().isInstance(anchor.getLayoutParams())) return;
            ViewGroup.MarginLayoutParams layout = (ViewGroup.MarginLayoutParams) anchor.getLayoutParams();
            try {
                if (layout.getMarginStart() == appliedStart) layout.setMarginStart(originalStart);
                if (goneStartMargin.getInt(layout) == appliedGone) goneStartMargin.setInt(layout, originalGone);
                anchor.requestLayout();
            } catch (IllegalAccessException ignored) {
                // The host replaced the player layout.
            }
        }
    }

    private static final class DeviceIcon extends ImageButton {
        private final ImageView sibling;
        private final TextView label;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final java.util.List<Runnable> restoreLinks = new java.util.ArrayList<>();
        private FavoriteSpace favoriteSpace;
        private View subtitle;
        private Boolean boundedFeedback;

        DeviceIcon(Context context, ImageView sibling, TextView label) {
            super(context);
            this.sibling = sibling;
            this.label = label;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.65f);
        }

        void feedback(boolean bounded) {
            if (Boolean.valueOf(bounded).equals(boundedFeedback)) return;
            boundedFeedback = bounded;
            TypedValue ripple = new TypedValue();
            Context context = getContext();
            if (bounded) {
                GradientDrawable mask = new GradientDrawable();
                mask.setShape(GradientDrawable.OVAL);
                mask.setColor(Color.WHITE);
                android.content.res.TypedArray colors = context.obtainStyledAttributes(
                        new int[]{android.R.attr.colorControlHighlight});
                try {
                    ColorStateList tint = colors.getColorStateList(0);
                    setBackground(new RippleDrawable(tint == null ? ColorStateList.valueOf(0x33000000) : tint,
                            null, mask));
                } finally {
                    colors.recycle();
                }
            } else if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                    ripple, true)) {
                setBackgroundResource(ripple.resourceId);
            }
        }

        void link(Field field, View view, int target) throws IllegalAccessException {
            ViewGroup.LayoutParams layout = view.getLayoutParams();
            int original = field.getInt(layout);
            field.setInt(layout, target);
            view.requestLayout();
            restoreLinks.add(() -> {
                Object current = view.getLayoutParams();
                if (!field.getDeclaringClass().isInstance(current)) return;
                try {
                    if (field.getInt(current) == target) field.setInt(current, original);
                    view.requestLayout();
                } catch (IllegalAccessException ignored) {
                    // The layout has already been replaced.
                }
            });
        }

        void remove() {
            if (favoriteSpace != null) favoriteSpace.restore();
            for (Runnable restore : restoreLinks) restore.run();
            restoreLinks.clear();
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            ColorStateList tint = sibling.getImageTintList();
            paint.setColor(tint != null ? tint.getDefaultColor() : label == null ? 0xff888888
                    : label.getCurrentTextColor());
            float size = getResources().getDisplayMetrics().density;
            canvas.save();
            canvas.translate((getWidth() - 24 * size) / 2, (getHeight() - 24 * size) / 2);
            canvas.scale(size, size);
            canvas.drawRoundRect(2, 4, 20, 16, 2, 2, paint);
            canvas.drawLine(6, 20, 13, 20, paint);
            canvas.drawLine(9.5f, 16, 9.5f, 20, paint);
            canvas.drawRoundRect(15, 10, 23, 22, 1.6f, 1.6f, paint);
            canvas.restore();
        }
    }
}
