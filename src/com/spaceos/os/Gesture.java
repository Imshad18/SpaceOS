package com.spaceos.os;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GESTURE :: touchless scrolling from the front camera.
 *
 * HONEST ABOUT WHAT THIS IS. Real skeletal hand tracking — knowing where the
 * index fingertip is — needs MediaPipe Hands or ML Kit, both of which ship
 * native .so libraries and resolve through Gradle. Neither is buildable with
 * the hand-rolled aapt/javac/d8 pipeline this app is built with, and there is
 * no aarch64 NDK to compile one.
 *
 * So this is classical motion tracking, not landmark detection:
 *
 *   1. Front camera streams tiny 176x144 luminance frames.
 *   2. Each frame is differenced against the last; pixels that changed by
 *      more than a threshold are "motion".
 *   3. The centroid of that motion is tracked across frames.
 *   4. A consistent vertical travel above a distance threshold, completed
 *      quickly enough, fires a swipe.
 *
 * In practice: wave your hand up or down in front of the camera and the page
 * scrolls. It cannot tell an index finger from a palm, and it will not try to.
 */
public class Gesture {

    public interface Listener {
        /** direction: +1 down (content scrolls down), -1 up. */
        void onSwipe(int direction);
    }

    private static final int W = 176, H = 144;          // tiny on purpose
    private static final int DIFF_THRESHOLD = 26;       // per-pixel luminance
    private static final int MIN_MOTION_PIXELS = 260;   // ignore camera noise
    private static final float MIN_TRAVEL = 0.26f;      // fraction of frame height
    private static final long GESTURE_WINDOW_MS = 900;
    private static final long COOLDOWN_MS = 700;

    private final Context ctx;
    private final Listener listener;

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;

    private byte[] previous;
    private final List<float[]> track = new ArrayList<float[]>();  // {y, timeMs}
    private long lastGestureAt;

    public Gesture(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() { return running; }

    // =====================================================================

    public void start() {
        if (running) return;
        thread = new HandlerThread("spaceos-gesture");
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String front = frontCamera(cm);
            if (front == null) {
                Journal.warn("gesture", "no front camera on this device");
                stop();
                return;
            }

            reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(onFrame, handler);

            cm.openCamera(front, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    startSession();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    Journal.warn("gesture", "camera disconnected");
                    stop();
                }
                @Override public void onError(CameraDevice device, int error) {
                    Journal.error("gesture", "camera error " + error);
                    stop();
                }
            }, handler);

            running = true;
            Journal.info("gesture", "touchless control armed");

        } catch (SecurityException se) {
            Journal.error("gesture", "camera permission not granted");
            stop();
        } catch (Throwable t) {
            Journal.error("gesture", "start failed: " + t.getMessage());
            stop();
        }
    }

    public void stop() {
        running = false;
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        try { if (camera != null) camera.close(); } catch (Throwable ignored) {}
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) {}
        session = null; camera = null; reader = null; thread = null; handler = null;
        previous = null;
        track.clear();
    }

    private String frontCamera(CameraManager cm) throws CameraAccessException {
        for (String id : cm.getCameraIdList()) {
            Integer facing = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        // fall back to whatever exists
        String[] ids = cm.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private void startSession() {
        try {
            final Surface surface = reader.getSurface();
            camera.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(surface);
                                b.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                s.setRepeatingRequest(b.build(), null, handler);
                            } catch (Throwable t) {
                                Journal.error("gesture", "request failed: " + t.getMessage());
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            Journal.error("gesture", "session configure failed");
                            stop();
                        }
                    }, handler);
        } catch (Throwable t) {
            Journal.error("gesture", "session failed: " + t.getMessage());
            stop();
        }
    }

    // =====================================================================
    // FRAME PROCESSING
    // =====================================================================

    private final ImageReader.OnImageAvailableListener onFrame =
            new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader r) {
            Image image = null;
            try {
                image = r.acquireLatestImage();
                if (image == null) return;
                process(image);
            } catch (Throwable ignored) {
            } finally {
                if (image != null) try { image.close(); } catch (Throwable ignored) {}
            }
        }
    };

    /**
     * Only the Y (luminance) plane is read — colour is irrelevant to motion
     * and skipping it keeps this cheap enough to run continuously.
     */
    private void process(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();

        byte[] current = new byte[W * H];
        for (int y = 0; y < H; y++) {
            int src = y * rowStride;
            if (src + W > buf.limit()) break;
            buf.position(src);
            buf.get(current, y * W, W);
        }

        if (previous == null) { previous = current; return; }

        long sumY = 0;
        int count = 0;
        for (int y = 0; y < H; y++) {
            int row = y * W;
            for (int x = 0; x < W; x++) {
                int d = (current[row + x] & 0xFF) - (previous[row + x] & 0xFF);
                if (d < 0) d = -d;
                if (d > DIFF_THRESHOLD) { sumY += y; count++; }
            }
        }
        previous = current;

        long now = System.currentTimeMillis();

        if (count < MIN_MOTION_PIXELS) {
            // nothing moving — let a half-formed gesture expire
            if (!track.isEmpty()
                    && now - (long) track.get(track.size() - 1)[1] > 350) {
                track.clear();
            }
            return;
        }

        float cy = (float) sumY / count / H;      // 0..1 down the frame
        track.add(new float[]{cy, now});
        while (track.size() > 24) track.remove(0);

        evaluate(now);
    }

    /** Fires when the tracked centroid has travelled far enough, fast enough. */
    private void evaluate(long now) {
        if (track.size() < 4) return;
        if (now - lastGestureAt < COOLDOWN_MS) return;

        float[] first = track.get(0);
        float[] last = track.get(track.size() - 1);

        if (now - (long) first[1] > GESTURE_WINDOW_MS) {
            track.remove(0);
            return;
        }

        float travel = last[0] - first[0];
        if (Math.abs(travel) < MIN_TRAVEL) return;

        // require the motion to be mostly monotonic, so a wobble is not a swipe
        int forward = 0;
        for (int i = 1; i < track.size(); i++) {
            float step = track.get(i)[0] - track.get(i - 1)[0];
            if (travel > 0 ? step > 0 : step < 0) forward++;
        }
        if (forward < (track.size() - 1) * 0.6f) return;

        // The front camera mirrors you: your hand moving UP appears as the
        // centroid moving DOWN the frame. Invert so it feels natural.
        int direction = travel > 0 ? -1 : 1;

        lastGestureAt = now;
        track.clear();
        Journal.info("gesture", "swipe " + (direction > 0 ? "down" : "up")
                + " (travel " + String.format(java.util.Locale.US, "%.2f", travel) + ")");
        if (listener != null) listener.onSwipe(direction);
    }
}
