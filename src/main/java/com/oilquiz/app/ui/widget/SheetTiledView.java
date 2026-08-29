package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 瓦片式 2D 阅读 View（用于 Excel 整表网格浏览）：
 * 按需渲染/缓存可见瓦片，支持双向平移 + 双指缩放（绕焦点）。
 * 渲染工作由 TileRenderer 回调承担（内部调用 LibreOfficeKit renderRegion）。
 */
public class SheetTiledView extends View {

    /** 渲染一个瓦片：doc 坐标区域 [offsetX, offsetY, offsetX+tileW, offsetY+tileH] → 位图(pxW×pxH)。 */
    public interface TileRenderer {
        Bitmap renderTile(float offsetX, float offsetY, float tileW, float tileH, int pxW, int pxH);
    }

    private static final int TILE_PX = 512;          // 瓦片像素尺寸
    private static final int CACHE_MARGIN_TILES = 1; // 可视范围外多缓存一圈

    private TileRenderer renderer;
    private float docW = 1f;   // 文档宽（文档单位）
    private float docH = 1f;   // 文档高（文档单位）
    private float unitsPerPixel = 1f;   // 缩放：每像素对应的文档单位（越小越放大）
    private float minUpp = 0.05f;
    private float maxUpp = 8f;
    private float offsetX = 0f; // 视口左上角在"瓦片像素空间"中的 X
    private float offsetY = 0f; // 视口左上角在"瓦片像素空间"中的 Y

    private final Map<Long, Bitmap> cache = new HashMap<>();
    private final Set<Long> inFlight = new HashSet<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int generation = 0; // 工作表切换代际，丢弃旧表异步瓦片

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final ExecutorService tileExecutor = Executors.newSingleThreadExecutor();

    public SheetTiledView(Context context) {
        this(context, null);
    }

    public SheetTiledView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        GestureDetector.SimpleOnGestureListener gl = new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @Nullable MotionEvent e2,
                                    float dx, float dy) {
                if (scaleDetector.isInProgress()) {
                    return false;
                }
                offsetX -= dx;
                offsetY -= dy;
                clampOffsets();
                invalidate();
                return true;
            }
        };
        gestureDetector = new GestureDetector(context, gl);
    }

    // ---------------------------------------------------------------------------------
    // 对外接口
    // ---------------------------------------------------------------------------------

    public void setTileRenderer(TileRenderer r) {
        this.renderer = r;
    }

    public void setDocument(float w, float h) {
        this.docW = w > 0 ? w : 1f;
        this.docH = h > 0 ? h : 1f;
        // 默认整体适配（每像素覆盖整个文档的宽度/高度的较大者，保证整表可见）
        float viewW = Math.max(getWidth(), 1f);
        float viewH = Math.max(getHeight(), 1f);
        unitsPerPixel = Math.max(docW / viewW, docH / viewH);
        // 缩放上下限：可缩小到整表一半，最多放大到约 10x 整表（看清单元格）
        minUpp = unitsPerPixel * 2f;
        maxUpp = unitsPerPixel * 0.1f;
        offsetX = 0;
        offsetY = 0;
        cache.clear();
        inFlight.clear();
        generation++;
        clampOffsets();
        invalidate();
    }

    public void resetView() {
        setDocument(docW, docH);
    }

    /** 释放资源：回收所有瓦片位图并关闭渲染线程。 */
    public void release() {
        tileExecutor.shutdownNow();
        for (Bitmap b : cache.values()) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
        cache.clear();
        inFlight.clear();
    }

    // ---------------------------------------------------------------------------------
    // 手势
    // ---------------------------------------------------------------------------------

    private void zoomBy(float factor, float fx, float fy) {
        float newUpp = clamp(unitsPerPixel / factor, minUpp, maxUpp);
        if (newUpp == unitsPerPixel) {
            return;
        }
        // 保持焦点下的文档坐标不动
        float docUnderFx = (offsetX + fx) * unitsPerPixel;
        float docUnderFy = (offsetY + fy) * unitsPerPixel;
        unitsPerPixel = newUpp;
        offsetX = docUnderFx / unitsPerPixel - fx;
        offsetY = docUnderFy / unitsPerPixel - fy;
        // 瓦片按当前缩放渲染，缩放后需清空重渲染
        cache.clear();
        inFlight.clear();
        clampOffsets();
        invalidate();
    }

    private void clampOffsets() {
        float sheetPxW = docW / unitsPerPixel;
        float sheetPxH = docH / unitsPerPixel;
        float viewW = getWidth();
        float viewH = getHeight();
        offsetX = (sheetPxW <= viewW) ? (sheetPxW - viewW) / 2f : clamp(offsetX, 0f, sheetPxW - viewW);
        offsetY = (sheetPxH <= viewH) ? (sheetPxH - viewH) / 2f : clamp(offsetY, 0f, sheetPxH - viewH);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    // ---------------------------------------------------------------------------------
    // 绘制 / 瓦片
    // ---------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (renderer == null) {
            return;
        }
        float viewW = getWidth();
        float viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        // 可视像素范围
        float x0 = offsetX;
        float y0 = offsetY;
        float x1 = offsetX + viewW;
        float y1 = offsetY + viewH;
        int colStart = (int) Math.floor(x0 / TILE_PX) - CACHE_MARGIN_TILES;
        int colEnd = (int) Math.ceil(x1 / TILE_PX) + CACHE_MARGIN_TILES;
        int rowStart = (int) Math.floor(y0 / TILE_PX) - CACHE_MARGIN_TILES;
        int rowEnd = (int) Math.ceil(y1 / TILE_PX) + CACHE_MARGIN_TILES;
        colStart = Math.max(colStart, 0);
        rowStart = Math.max(rowStart, 0);

        for (int row = rowStart; row <= rowEnd; row++) {
            for (int col = colStart; col <= colEnd; col++) {
                long key = ((long) row << 32) | (col & 0xffffffffL);
                Bitmap bmp = cache.get(key);
                if (bmp == null) {
                    requestTile(row, col, key);
                    continue;
                }
                // 瓦片像素原点，相对视口
                float tx = col * TILE_PX - offsetX;
                float ty = row * TILE_PX - offsetY;
                canvas.drawBitmap(bmp, tx, ty, paint);
            }
        }
        // 回收远离可视区的瓦片
        recycleFar(colStart, colEnd, rowStart, rowEnd);
    }

    private void requestTile(int row, int col, long key) {
        if (inFlight.contains(key)) {
            return;
        }
        inFlight.add(key);
        final int gen = generation;
        // 该瓦片对应的文档坐标
        float tileDocX = col * TILE_PX * unitsPerPixel;
        float tileDocY = row * TILE_PX * unitsPerPixel;
        float tileDocW = TILE_PX * unitsPerPixel;
        float tileDocH = TILE_PX * unitsPerPixel;
        tileExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                if (renderer != null) {
                    bmp = renderer.renderTile(tileDocX, tileDocY, tileDocW, tileDocH, TILE_PX, TILE_PX);
                }
            } catch (Throwable ignored) {
            }
            final Bitmap finalBmp = bmp;
            post(() -> {
                inFlight.remove(key);
                // 若已切换工作表/代际变化，丢弃旧表瓦片
                if (gen != generation) {
                    return;
                }
                if (finalBmp != null) {
                    cache.put(key, finalBmp);
                    invalidate();
                }
            });
        });
    }

    private void recycleFar(int colStart, int colEnd, int rowStart, int rowEnd) {
        java.util.Iterator<Map.Entry<Long, Bitmap>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Bitmap> e = it.next();
            long key = e.getKey();
            int col = (int) (key & 0xffffffffL);
            int row = (int) (key >> 32);
            if (col < colStart || col > colEnd || row < rowStart || row > rowEnd) {
                Bitmap b = e.getValue();
                if (b != null && !b.isRecycled()) {
                    b.recycle();
                }
                it.remove();
            }
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(v, max));
    }
}
