package com.spaceos.os;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A user-chosen image behind every layout.
 *
 * The picked image is COPIED into the app's own storage rather than kept as a
 * content:// URI. A URI from the gallery is a loan — it survives until the
 * user clears that app's data, moves the file, or revokes the grant, and then
 * the home screen silently loses its background. A copy is ours and keeps
 * working.
 *
 * Decoded once at roughly screen size and cached; a full-resolution camera
 * photo is 50 megabytes of bitmap and would be decoded on every layout pass.
 */
public final class Wallpaper {

    private Wallpaper() {}

    private static final String FILE = "wallpaper.img";

    private static Bitmap cached;
    private static long cachedStamp;
    private static int cachedW;

    public static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    public static boolean isSet(Context c) {
        File f = file(c);
        return f.exists() && f.length() > 0;
    }

    /** Copies the chosen image in. Returns false if it could not be read. */
    public static boolean adopt(Context c, Uri src) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(src);
            if (in == null) return false;
            out = new FileOutputStream(file(c));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            clear();                 // drop the old decode
            return true;
        } catch (Throwable t) {
            Journal.warn("wallpaper", "could not copy: " + t.getMessage());
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    public static void remove(Context c) {
        try { file(c).delete(); } catch (Throwable ignored) {}
        clear();
    }

    private static synchronized void clear() {
        cached = null; cachedStamp = 0; cachedW = 0;
    }

    /**
     * The decoded image, sized for a screen this wide. Null when none is set
     * or the file will not decode.
     */
    public static synchronized Bitmap bitmap(Context c, int screenW) {
        File f = file(c);
        if (!f.exists() || f.length() == 0) return null;

        long stamp = f.lastModified();
        if (cached != null && stamp == cachedStamp && cachedW == screenW) return cached;

        try {
            // measure first, then decode at a power-of-two subsample
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            if (probe.outWidth <= 0) return null;

            int sample = 1;
            while (probe.outWidth / (sample * 2) >= Math.max(1, screenW)) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory
            cached = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            cachedStamp = stamp;
            cachedW = screenW;
            return cached;
        } catch (Throwable t) {
            Journal.warn("wallpaper", "decode failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Draws it centre-cropped over the whole view, then a scrim.
     *
     * The scrim is not decoration. Every palette assumes a ground it controls
     * — a photo underneath can be any brightness anywhere, and without
     * something between it and the instrument text the readout disappears over
     * the light parts of the picture. dim is 0..100 and the user sets it.
     */
    public static boolean draw(Canvas c, Context ctx, int w, int h, int dim) {
        Bitmap bm = bitmap(ctx, w);
        if (bm == null) return false;

        float scale = Math.max(w / (float) bm.getWidth(), h / (float) bm.getHeight());
        float dw = bm.getWidth() * scale, dh = bm.getHeight() * scale;
        RectF dst = new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);

        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(bm, new Rect(0, 0, bm.getWidth(), bm.getHeight()), dst, p);

        float a = Math.max(0f, Math.min(100f, dim)) / 100f;
        if (a > 0f) c.drawColor(Skin.alpha(Skin.VOID, a));
        return true;
    }
}
