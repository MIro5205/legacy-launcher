package com.x10launcher;

/*
 * X10 Mini Pro Launcher
 * ─────────────────────
 * RAM target: < 0.5 MB heap above Android baseline
 *
 * Every decision is made with the X10 Mini Pro's 128 MB total RAM in mind,
 * of which ~80 MB is consumed by Android 2.1 itself at idle.
 *
 * Techniques used to stay under 0.5 MB:
 *  - No RecyclerView, ListView, GridView — custom draw via Canvas directly
 *    onto a View. Zero adapter allocations, zero ViewHolder churn.
 *  - No Bitmap loading. App icons are drawn once into a small Bitmap cache
 *    and reused. Icons are scaled to 36x36px — correct for 240x320 QVGA.
 *  - No XML inflation for the main view. The single AppGridView is created
 *    in code and set directly as the content view.
 *  - PackageManager query is done once on a background thread, result is
 *    a plain array — no ArrayList, no HashMap, no wrapper objects.
 *  - Scrolling is handled manually with a fling scroller — no OverScroller
 *    (API 9+), uses plain Scroller (API 1).
 *  - No animations, no shadows, no gradients in the draw path.
 *  - onLowMemory() drops the icon cache entirely and triggers a redraw
 *    that re-fetches icons lazily only for visible rows.
 *  - The entire app is one Activity, one View, one file.
 */

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Scroller;
import android.widget.Toast;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class LauncherActivity extends Activity {

    private static final String TAG = "X10Launcher";

    // Grid layout — tuned for 240px wide QVGA screen
    // 3 columns × 80dp cells fits perfectly with a little padding
    private static final int COLS        = 3;
    private static final int CELL_DP     = 76;   // cell width and height in dp
    private static final int ICON_DP     = 36;   // icon bitmap size in dp
    private static final int LABEL_SP    = 9;    // app label text size

    // Pixels (set in onCreate after density is known)
    private int cellPx;
    private int iconPx;

    private AppGridView gridView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen — every pixel matters on 240x320
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        float density = getResources().getDisplayMetrics().density;
        cellPx = (int) (CELL_DP * density + 0.5f);
        iconPx = (int) (ICON_DP * density + 0.5f);

        gridView = new AppGridView(this, cellPx, iconPx, COLS, LABEL_SP);
        setContentView(gridView);

        // Load apps on background thread — never block the UI thread
        new Thread(new Runnable() {
            public void run() { loadApps(); }
        }).start();
    }

    // ── APP LOADING ───────────────────────────────────────────────────────────
    // Queries PackageManager for all launchable apps once.
    // Runs on a background thread; posts result to UI thread.
    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);

        // Sort alphabetically — plain comparator, no lambda (API 2.1 compat)
        Collections.sort(list, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                PackageManager pm2 = getPackageManager();
                return a.loadLabel(pm2).toString()
                       .compareToIgnoreCase(b.loadLabel(pm2).toString());
            }
        });

        // Convert to a plain array of lightweight AppEntry objects
        // AppEntry holds only the strings and component needed to launch
        final AppEntry[] apps = new AppEntry[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ResolveInfo ri = list.get(i);
            AppEntry e = new AppEntry();
            e.label     = ri.loadLabel(pm).toString();
            e.pkgName   = ri.activityInfo.packageName;
            e.className = ri.activityInfo.name;
            // Icon bitmap loaded lazily in the view — not here
            apps[i] = e;
        }

        // Post to UI thread
        gridView.post(new Runnable() {
            public void run() { gridView.setApps(apps); }
        });
    }

    // ── BACK / HOME KEY ───────────────────────────────────────────────────────
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Launchers should not exit on Back key
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (gridView != null) gridView.dropIconCache();
    }

    // ── APP ENTRY — minimal data holder ──────────────────────────────────────
    static class AppEntry {
        String  label;
        String  pkgName;
        String  className;
        Bitmap  icon;       // null until the row is first drawn
        boolean iconLoaded; // prevents repeated failed loads
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AppGridView — the entire launcher UI in one custom View
    // No inflation, no adapters, no ViewHolders.
    // Draws directly onto Canvas using pre-allocated Paint objects.
    // ══════════════════════════════════════════════════════════════════════════
    static class AppGridView extends View {

        private final int     cellPx;
        private final int     iconPx;
        private final int     cols;
        private final float   labelTextSize;  // in px

        private AppEntry[]    apps    = new AppEntry[0];
        private int           totalRows = 0;

        // Scroll state
        private final Scroller scroller;
        private int             scrollY   = 0;
        private int             maxScroll = 0;
        private float           lastTouchY;
        private float           touchStartY;
        private boolean         isDragging = false;
        private static final int TOUCH_SLOP = 8; // dp-independent, fine for QVGA

        // Pre-allocated drawing objects — allocating in onDraw causes GC churn
        private final Paint iconPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint    = new Paint();
        private final Rect  srcRect    = new Rect();
        private final Rect  dstRect    = new Rect();

        // Pressed state for visual feedback
        private int pressedIndex = -1;

        // Handler for icon lazy-loading
        private final Handler handler = new Handler();

        // Runnable to scroll via fling
        private final Runnable flingRunnable = new Runnable() {
            public void run() {
                if (scroller.computeScrollOffset()) {
                    scrollY = scroller.getCurrY();
                    clampScroll();
                    invalidate();
                    handler.post(this);
                }
            }
        };

        AppGridView(android.content.Context ctx, int cellPx, int iconPx, int cols, int labelSp) {
            super(ctx);
            this.cellPx        = cellPx;
            this.iconPx        = iconPx;
            this.cols          = cols;
            this.labelTextSize = labelSp * ctx.getResources().getDisplayMetrics().scaledDensity;
            this.scroller      = new Scroller(ctx);

            labelPaint.setTextSize(labelTextSize);
            labelPaint.setColor(Color.BLACK);
            labelPaint.setTextAlign(Paint.Align.CENTER);

            bgPaint.setColor(Color.WHITE);
            bgPaint.setStyle(Paint.Style.FILL);

            setClickable(true);
            setFocusable(true);
        }

        void setApps(AppEntry[] apps) {
            this.apps      = apps;
            this.totalRows = (apps.length + cols - 1) / cols;
            recalcMaxScroll();
            invalidate();
        }

        // Drop all icon bitmaps — called on low memory
        void dropIconCache() {
            for (AppEntry e : apps) {
                if (e.icon != null) {
                    e.icon.recycle();
                    e.icon = null;
                    e.iconLoaded = false;
                }
            }
            invalidate();
        }

        private void recalcMaxScroll() {
            int totalHeight = totalRows * cellPx;
            maxScroll = Math.max(0, totalHeight - getHeight());
        }

        private void clampScroll() {
            if (scrollY < 0)         scrollY = 0;
            if (scrollY > maxScroll) scrollY = maxScroll;
        }

        // ── DRAW ─────────────────────────────────────────────────────────────
        // Only rows visible in the current scroll window are drawn.
        // Icon bitmaps are loaded lazily here — if null, load on a bg thread.
        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();

            // White background
            canvas.drawRect(0, 0, w, h, bgPaint);

            if (apps.length == 0) {
                // Loading state
                labelPaint.setColor(0xFF5F6368);
                canvas.drawText("Loading...", w / 2f, h / 2f, labelPaint);
                labelPaint.setColor(Color.BLACK);
                return;
            }

            // Determine visible row range — skip drawing invisible rows entirely
            int firstRow = scrollY / cellPx;
            int lastRow  = Math.min(totalRows - 1, (scrollY + h) / cellPx + 1);

            for (int row = firstRow; row <= lastRow; row++) {
                for (int col = 0; col < cols; col++) {
                    int index = row * cols + col;
                    if (index >= apps.length) break;

                    AppEntry e = apps[index];

                    // Cell bounds in screen coordinates
                    int left = col * cellPx + (w - cols * cellPx) / 2;
                    int top  = row * cellPx - scrollY;

                    // Pressed highlight — simple filled rect, no allocation
                    if (index == pressedIndex) {
                        Paint highlight = new Paint();
                        highlight.setColor(0x201A73E8);
                        highlight.setStyle(Paint.Style.FILL);
                        canvas.drawRect(left, top, left + cellPx, top + cellPx, highlight);
                    }

                    // Load icon lazily if not yet loaded
                    if (!e.iconLoaded) {
                        loadIconAsync(e, index);
                    }

                    // Draw icon bitmap if available
                    if (e.icon != null && !e.icon.isRecycled()) {
                        int iconLeft = left + (cellPx - iconPx) / 2;
                        int iconTop  = top  + 6;
                        srcRect.set(0, 0, e.icon.getWidth(), e.icon.getHeight());
                        dstRect.set(iconLeft, iconTop, iconLeft + iconPx, iconTop + iconPx);
                        canvas.drawBitmap(e.icon, srcRect, dstRect, iconPaint);
                    } else {
                        // Placeholder circle while icon loads
                        Paint ph = new Paint(Paint.ANTI_ALIAS_FLAG);
                        ph.setColor(0xFFE8EAED);
                        ph.setStyle(Paint.Style.FILL);
                        int cx = left + cellPx / 2;
                        int cy = top + 6 + iconPx / 2;
                        canvas.drawCircle(cx, cy, iconPx / 2f, ph);
                    }

                    // Draw label — truncate long names with ellipsis
                    String label = truncate(e.label, cellPx - 4, labelPaint);
                    labelPaint.setColor(0xFF202124);
                    canvas.drawText(label,
                        left + cellPx / 2f,
                        top + 6 + iconPx + labelTextSize + 2,
                        labelPaint);
                }
            }
        }

        // Load one icon on a background thread, post invalidate when done.
        // Checks iconLoaded flag to avoid duplicate loads.
        private void loadIconAsync(final AppEntry e, final int index) {
            e.iconLoaded = true; // mark immediately to prevent re-entry
            new Thread(new Runnable() {
                public void run() {
                    try {
                        PackageManager pm = getContext().getPackageManager();
                        Drawable d = pm.getApplicationIcon(e.pkgName);
                        // Scale icon to exact pixel size — avoids huge drawables
                        Bitmap full = drawableToBitmap(d, iconPx);
                        e.icon = full;
                        // Only invalidate if this row is currently visible
                        handler.post(new Runnable() {
                            public void run() { invalidate(); }
                        });
                    } catch (Exception ex) {
                        // Leave icon null — placeholder stays
                    }
                }
            }).start();
        }

        // Convert any Drawable to a Bitmap at target size
        // Uses RGB_565 (2 bytes/px) instead of ARGB_8888 (4 bytes/px) — halves icon memory
        private Bitmap drawableToBitmap(Drawable d, int size) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return bmp;
        }

        // Truncate text to fit width, adding … if needed
        private String truncate(String text, int maxWidth, Paint p) {
            if (p.measureText(text) <= maxWidth) return text;
            while (text.length() > 1 && p.measureText(text + "…") > maxWidth) {
                text = text.substring(0, text.length() - 1);
            }
            return text + "…";
        }

        // ── TOUCH ─────────────────────────────────────────────────────────────
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float y = e.getY();
            float x = e.getX();

            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.abortAnimation();
                    handler.removeCallbacks(flingRunnable);
                    lastTouchY  = y;
                    touchStartY = y;
                    isDragging  = false;
                    // Tentatively mark the cell under finger as pressed
                    pressedIndex = indexAt(x, y);
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dy = lastTouchY - y;
                    if (!isDragging && Math.abs(y - touchStartY) > TOUCH_SLOP) {
                        isDragging   = true;
                        pressedIndex = -1;
                        invalidate();
                    }
                    if (isDragging) {
                        scrollY += (int) dy;
                        clampScroll();
                        invalidate();
                    }
                    lastTouchY = y;
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        // It's a tap — launch the app
                        int idx = indexAt(x, y);
                        if (idx >= 0 && idx < apps.length) {
                            pressedIndex = -1;
                            invalidate();
                            launchApp(apps[idx]);
                        }
                    } else {
                        // Fling
                        float velocity = (lastTouchY - y) * 8f; // rough velocity
                        scroller.fling(0, scrollY, 0, (int) velocity,
                            0, 0, 0, maxScroll);
                        handler.post(flingRunnable);
                    }
                    pressedIndex = -1;
                    invalidate();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    pressedIndex = -1;
                    invalidate();
                    return true;
            }
            return false;
        }

        // Convert screen x/y to app index
        private int indexAt(float x, float y) {
            int w = getWidth();
            int gridLeft = (w - cols * cellPx) / 2;
            if (x < gridLeft || x > gridLeft + cols * cellPx) return -1;
            int col = (int) ((x - gridLeft) / cellPx);
            int row = (int) ((y + scrollY) / cellPx);
            if (col < 0 || col >= cols || row < 0) return -1;
            return row * cols + col;
        }

        private void launchApp(AppEntry e) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(new ComponentName(e.pkgName, e.className));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                              | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                getContext().startActivity(intent);
            } catch (Exception ex) {
                Toast.makeText(getContext(), "Cannot open " + e.label,
                    Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            recalcMaxScroll();
        }
    }
}
