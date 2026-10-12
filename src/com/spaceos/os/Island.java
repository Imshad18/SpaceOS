package com.spaceos.os;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * ISLAND :: a floating pill that lives above every app.
 *
 * The point is that talking to Space OS should not require leaving whatever
 * you are doing. This sits on top of Instagram, your keyboard, anything —
 * tap it and speak. It expands while it listens and collapses when done.
 *
 * Implemented as a TYPE_APPLICATION_OVERLAY window, which needs the
 * "Display over other apps" grant. Draggable, and it remembers where you
 * put it.
 */
public class Island extends Service implements Voice.Host {

    private static final String CHANNEL = "spaceos_island";
    private static volatile boolean running;

    public static boolean isRunning() { return running; }

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private Pill pill;
    private Voice voice;
    private Agent agent;
    private Config cfg;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Whether the floating pill is actually on screen. */
    private boolean pillShown;

    // collapsed | listening | working | replying
    private int mode = 0;
    private String transcript = "", reply = "";
    private float level;

    // =====================================================================

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        cfg = Config.get(this);
        Theme.apply(cfg.palette());
        Skin.init(this);

        startForegroundSafely();

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        pill = new Pill(this);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                (int) Skin.dp(COLLAPSED_W), (int) Skin.dp(COLLAPSED_H),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = cfg.islandX();
        lp.y = cfg.islandY();

        // HEADLESS MODE.
        //
        // This service also hosts the screen watch that raises the lock face,
        // and the camera loop for touchless scroll. Neither has anything to do
        // with the floating pill, but both used to require it — so LOCK SCREEN
        // FACE silently did nothing unless you had also granted the overlay
        // permission. The pill is now optional; the service runs for whichever
        // feature is switched on.
        if (cfg.islandEnabled()) {
            try {
                wm.addView(pill, lp);
                pill.start();
                pillShown = true;
            } catch (Throwable t) {
                // overlay grant missing — carry on without the pill
                Journal.warn("island", "no overlay permission; running headless");
            }
        }
        running = true;

        if (!pillShown && !cfg.lockFace() && !cfg.gestures()) {
            // nothing left for this service to do
            stopSelf();
            return;
        }

        voice = new Voice(this, this);
        voice.initTts();

        // Hands free means hands free. The watchdog below is what actually
        // makes that true — firing once at startup was not enough, because
        // flipping the setting while the island was already running never
        // reached it, and a recogniser that died stayed dead.
        ui.postDelayed(watchdog, 900);

        registerScreenWatch();
        if (cfg.gestures()) startGestures();
    }

    // =====================================================================
    // LOCK FACE
    // =====================================================================

    private android.content.BroadcastReceiver screenWatch;

    /**
     * Whether the phone is ringing or on a call.
     *
     * Read from the audio mode rather than TelephonyManager.getCallState,
     * which needs READ_PHONE_STATE on recent releases. The mode is public and
     * says everything needed: RINGTONE while a call comes in, IN_CALL and
     * IN_COMMUNICATION while one is up.
     */
    static boolean phoneBusy(Context c) {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            int mode = am.getMode();
            return mode == android.media.AudioManager.MODE_RINGTONE
                    || mode == android.media.AudioManager.MODE_IN_CALL
                    || mode == android.media.AudioManager.MODE_IN_COMMUNICATION;
        } catch (Throwable t) { return false; }
    }

    /** Brings the lock face up. singleTask means a second call just re-shows it. */
    private void raiseLockFace(Context c) {
        // NEVER over an incoming call.
        //
        // A call turns the screen on, which fired the screen-on watch, which
        // put the lock face on top of the ringing screen about a second after
        // it appeared — so the phone rang and there was no way to answer it.
        // Nothing this app does is worth covering a call.
        if (phoneBusy(c)) {
            Journal.info("lock", "call in progress — not raising the face");
            return;
        }
        try {
            Intent lock = new Intent(c, Lock.class);
            lock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            c.startActivity(lock);
        } catch (Throwable t) {
            Journal.warn("lock", "could not show face: " + t.getMessage());
        }
    }

    /**
     * ACTION_SCREEN_ON cannot be declared in the manifest since API 26, so the
     * long-lived service registers it at runtime. This is the only component
     * alive early enough to catch the screen sleeping and waking.
     */
    private void registerScreenWatch() {
        try {
            screenWatch = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String action = i.getAction();
                    if (action == null) return;

                    // Screen slept without unlocking — drop the armed swipe so
                    // the next unlock does not jump into the bridge unasked.
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        cfg.setPendingEntry(false);
                        // Build the lock face NOW, while the screen is dark.
                        //
                        // Starting it on SCREEN_ON meant the user watched the
                        // stock lock screen for a second or two while a
                        // broadcast was dispatched, an activity launched, a
                        // window laid out and the first frame drawn. None of
                        // that has to happen after the screen lights: the
                        // activity can be up and rendered behind a dark panel,
                        // so waking is just a matter of it becoming visible.
                        if (cfg.lockFace()) raiseLockFace(c);
                        return;
                    }

                    // Screen woke: make sure the face is there. Normally it
                    // already is, from SCREEN_OFF above; this covers a first
                    // run, or the activity having been reclaimed since.
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        if (!cfg.lockFace()) return;
                        raiseLockFace(c);
                        return;
                    }

                    // Unlocked — by PIN, pattern, fingerprint or face, we do
                    // not care which and never see it. Only continue into the
                    // bridge if the user actually swiped up on the face.
                    if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        // Only on a deliberate swipe. The automatic case — the
                        // system's own face unlock dropping the keyguard — is
                        // handled inside Lock, which just gets out of the way
                        // and leaves you where you were. Forcing the bridge
                        // here as well would undo that.
                        if (!cfg.lockFace() || !cfg.pendingEntry()) return;
                        cfg.setPendingEntry(false);
                        try {
                            Intent bridge = new Intent(c, Bridge.class);
                            // No RESET: bring the task forward as it was
                            // rather than clearing it back to the bridge.
                            bridge.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            c.startActivity(bridge);
                            Journal.info("lock", "unlocked — entering the bridge");
                        } catch (Throwable t) {
                            Journal.warn("lock", "entry failed: " + t.getMessage());
                        }
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenWatch, f);
        } catch (Throwable t) {
            Journal.warn("lock", "screen watch failed: " + t.getMessage());
        }
    }

    // =====================================================================
    // TOUCHLESS GESTURES
    // =====================================================================

    private Gesture gesture;

    public void startGestures() {
        if (gesture != null && gesture.isRunning()) return;
        gesture = new Gesture(this, new Gesture.Listener() {
            @Override public void onSwipe(int direction) {
                Hands h = Hands.get();
                if (h == null) {
                    Journal.warn("gesture", "swipe ignored — accessibility service is off");
                    return;
                }
                h.scroll(direction > 0);
            }
        });
        gesture.start();
    }

    public void stopGestures() {
        if (gesture != null) { gesture.stop(); gesture = null; }
    }

    /** Action used by the setup screen to flip gestures without a restart. */
    public static final String ACTION_SYNC = "com.spaceos.os.SYNC";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SYNC.equals(intent.getAction())) {
            Theme.apply(cfg.palette());
            if (cfg.gestures()) startGestures(); else stopGestures();
            syncPill();
        }
        return START_STICKY;
    }

    /** Adds or removes the floating pill without restarting the service. */
    private void syncPill() {
        try {
            if (cfg.islandEnabled() && !pillShown) {
                wm.addView(pill, lp);
                pill.start();
                pillShown = true;
            } else if (!cfg.islandEnabled() && pillShown) {
                pill.stop();
                wm.removeView(pill);
                pillShown = false;
            }
        } catch (Throwable t) {
            Journal.warn("island", "pill sync: " + t.getMessage());
        }
    }

    @Override public void onDestroy() {
        running = false;
        try { if (pillShown && pill != null) { pill.stop(); wm.removeView(pill); } } catch (Throwable ignored) {}
        try { if (voice != null) voice.release(); } catch (Throwable ignored) {}
        try { if (screenWatch != null) unregisterReceiver(screenWatch); } catch (Throwable ignored) {}
        stopGestures();
        releaseWake();
        super.onDestroy();
    }


    /** Which switched-on feature is keeping this service alive. */
    private String runningBecause() {
        StringBuilder sb = new StringBuilder();
        if (cfg.islandEnabled()) sb.append("floating pill");
        if (cfg.lockFace()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("lock screen");
        }
        if (cfg.gestures()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("touchless scroll");
        }
        if (sb.length() == 0) return "Running in the background";
        return "Required by: " + sb;
    }

    /** A foreground service is what keeps the overlay alive across apps. */
    private void startForegroundSafely() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        "Space OS Island", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, Bridge.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    Build.VERSION.SDK_INT >= 23
                            ? PendingIntent.FLAG_IMMUTABLE : 0);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            // Say what is actually running, and why this cannot be dismissed.
            //
            // It read "Island active — tap the pill to speak" whatever the
            // service was doing, so someone who had only switched on the lock
            // screen was told a floating microphone was live. Android will not
            // let a foreground service hide its notification; the least it can
            // do is be honest about which feature is holding it open.
            Notification n = b.setContentTitle("Space OS")
                    .setContentText(runningBecause())
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .build();
            startForeground(42, n);
        } catch (Throwable ignored) {}
    }

    // =====================================================================
    // VOICE
    // =====================================================================

    /**
     * Opens the mic and — in continuous mode — keeps it open indefinitely.
     * Speak, it acts, it answers, and the mic comes straight back without
     * another tap. Tap the pill again to stand it down.
     */
    private void beginListening() {
        standDownByUser = false;
        if (voice == null) voice = new Voice(this, this);
        transcript = ""; reply = "";
        mode = 1;
        expand();
        voice.setContinuous(cfg.continuousListening());
        openFloorUntil = System.currentTimeMillis() + 9000;
        voice.listen();
        acquireWake();
    }

    /**
     * Keeps hands-free actually hands-free.
     *
     * Android's SpeechRecognizer stops on its own for a dozen reasons — engine
     * busy, transient network fault, the OS reclaiming the mic — and once it
     * stopped, the pill sat there waiting for a tap. This re-arms it. It also
     * picks up the setting being switched on after the service has started,
     * which the one-shot at onCreate could never do.
     */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                // The island is a long-lived service and outlives any theme
                // change made in the app, so it re-reads the palette rather
                // than keeping whatever was current when it started.
                Theme.apply(cfg.palette());

                boolean shouldListen = cfg.autoListen() && !standDownByUser;
                boolean actuallyListening = voice != null && voice.isListening();

                if (shouldListen && !actuallyListening && !busyWithAgent) {
                    Journal.info("island", "watchdog re-arming the microphone");
                    beginListening();
                }
                if (!shouldListen && actuallyListening && !userStartedManually) {
                    voice.standDown();
                }
                // gestures can be flipped the same way
                if (cfg.gestures() && (gesture == null || !gesture.isRunning())) {
                    startGestures();
                } else if (!cfg.gestures() && gesture != null && gesture.isRunning()) {
                    stopGestures();
                }
            } catch (Throwable t) {
                Journal.warn("island", "watchdog: " + t.getMessage());
            }
            ui.postDelayed(this, 6000);
        }
    };

    /** True while the user has explicitly stood the island down. */
    private boolean standDownByUser;
    private boolean userStartedManually;
    private volatile boolean busyWithAgent;

    private void endListening() {
        standDownByUser = true;
        userStartedManually = false;
        if (voice != null) voice.standDown();
        if (agent != null) agent.cancel();
        releaseWake();
        ui.removeCallbacks(collapse);
        collapse.run();
    }

    // ---- wake lock ---------------------------------------------------------

    private android.os.PowerManager.WakeLock wake;

    /** Long autonomous runs die the moment the CPU sleeps without this. */
    private void acquireWake() {
        if (!cfg.keepAwake()) return;
        try {
            if (wake == null) {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "spaceos:island");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire(60 * 60 * 1000L);   // one hour ceiling
        } catch (Throwable ignored) {}
    }

    private void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) {}
    }

    private void repaint() { if (pillShown && pill != null) pill.postInvalidate(); }

    @Override public void onPartial(String text) {
        transcript = text == null ? "" : text;
        repaint();
    }

    @Override public void onLevel(float l) {
        level = Math.max(level, l);
        repaint();
    }

    @Override public void onVoiceState(int state, String message) {
        if (state == Voice.THINKING) mode = 2;
        if (state == Voice.ERROR) {
            reply = message == null ? "fault" : message;
            mode = 3;
            collapseSoon(2200);
        }
        repaint();
    }

    @Override public void onHeard(final String text) {
        // Same addressing rules as the bridge — otherwise a permanently open
        // mic would act on every word spoken anywhere near the phone.
        String lower = text == null ? "" : text.toLowerCase(Locale.US).trim();
        int addrMode = cfg.addressingMode();
        boolean invited = System.currentTimeMillis() < openFloorUntil;

        if (!invited && addrMode != Config.ADDRESS_ALWAYS) {
            boolean addressed = addrMode == Config.ADDRESS_NAME
                    ? Addressing.containsWord(lower, cfg.assistantName())
                    : Addressing.judge(lower, cfg.assistantName(), false,
                            Mind.brain() == null ? 0f
                                    : Mind.brain().confidence(lower)).addressed;
            if (!addressed) {
                transcript = "";
                mode = 1;
                repaint();
                if (voice != null) voice.resumeListening();
                return;
            }
        }
        openFloorUntil = 0;

        transcript = text;
        mode = 2;
        repaint();

        // Local first here too — no network call for "open Instagram".
        Quick.Result quick = Quick.run(this, cfg, text);
        if (quick != null) {
            Journal.info("quick", text + " -> " + quick.text);
            answer(quick.text, quick.speak);
            return;
        }

        if (cfg.enabled()) {
            if (agent == null) agent = new Agent(this, cfg);
            busyWithAgent = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    agent.ask(text, new Agent.Trace() {
                        @Override public void onThought(String t) { show(t, 2); }
                        @Override public void onToolCall(String n, String a) {
                            show("· " + n, 2);
                        }
                        @Override public void onToolResult(String n, String r) { }
                        @Override public void onProgress(int step, int limit) {
                            show("step " + step + " of " + limit, 2);
                        }
                        @Override public void onAnswer(String a) { busyWithAgent = false; answer(a); }
                        @Override public void onFailure(String m) {
                            busyWithAgent = false;
                            String local = Quick.tryLocal(Island.this, cfg, text);
                            answer(local != null ? local : Bridge.humanFault(m), true);
                        }
                    });
                }
            }, "island-agent").start();
        } else {
            answer("Connect a model in Space OS to use me from here.");
        }
    }

    private void show(final String s, final int m) {
        ui.post(new Runnable() {
            @Override public void run() { reply = s; mode = m; pill.invalidate(); }
        });
    }

    private void answer(final String a) { answer(a, true); }

    private void answer(final String a, final boolean speak) {
        ui.post(new Runnable() {
            @Override public void run() {
                reply = a;
                mode = 3;
                repaint();
                if (voice != null) {
                    if (speak) voice.say(a);
                    else voice.resumeListening();   // silent, but keep listening
                }

                openFloorUntil = System.currentTimeMillis() + 9000;
                if (cfg.continuousListening()) {
                    // stay open and keep listening — the whole point is that
                    // you do not have to touch the phone again
                    mode = 1;
                } else {
                    collapseSoon(Math.min(9000, 2600 + a.length() * 45));
                }
            }
        });
    }

    /** Speech within this window needs no wake word. */
    private long openFloorUntil;

    private void collapseSoon(long ms) {
        ui.removeCallbacks(collapse);
        ui.postDelayed(collapse, ms);
    }

    private final Runnable collapse = new Runnable() {
        @Override public void run() {
            mode = 0;
            transcript = ""; reply = "";
            resize(COLLAPSED_W, COLLAPSED_H);
            repaint();
        }
    };

    private void expand() {
        resize(EXPANDED_W, EXPANDED_H);
    }

    private void resize(float wDp, float hDp) {
        try {
            if (!pillShown) return;
            lp.width = (int) Skin.dp(wDp);
            lp.height = (int) Skin.dp(hDp);
            wm.updateViewLayout(pill, lp);
        } catch (Throwable ignored) {}
    }

    // A small orb at rest. The old wide pill was a slab of chrome sitting on
    // top of whatever you were reading, and the expanded card crammed three
    // pieces of text into a shape too small for any of them.
    private static final float COLLAPSED_W = 52, COLLAPSED_H = 52;
    private static final float EXPANDED_W = 292, EXPANDED_H = 132;

    // =====================================================================
    // THE PILL
    // =====================================================================

    private class Pill extends View {
        private float phase;
        private boolean anim;
        private long lastNs;

        private float downX, downY;
        private int startX, startY;
        private boolean dragged;

        Pill(Context c) { super(c); }

        void start() { if (!anim) { anim = true; lastNs = 0; postOnAnimation(tick); } }
        void stop() { anim = false; removeCallbacks(tick); }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!anim) return;
                long now = System.nanoTime();
                float dt = lastNs == 0 ? 1f / 60f : (now - lastNs) / 1_000_000_000f;
                lastNs = now;
                phase += dt * 60f;
                level *= (1f - Math.min(1f, dt * 2.4f));
                invalidate();
                postOnAnimation(this);
            }
        };

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            int accent = mode == 2 ? Skin.SOLAR : mode == 3 ? Skin.ION : Skin.PLASMA;
            if (mode == 0) drawOrb(c, w, h, accent);
            else drawCard(c, w, h, accent);
        }

        /** At rest: a single small orb. Minimal footprint over your content. */
        private void drawOrb(Canvas c, int w, int h, int accent) {
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - Skin.dp(4);

            Skin.halo(c, cx, cy, r * 1.7f, accent, 0.9f);
            c.drawCircle(cx, cy, r, Skin.fill(Skin.alpha(Skin.VOID, 0.94f)));
            c.drawCircle(cx, cy, r, Skin.stroke(1.4f, Skin.alpha(accent, 0.9f)));

            // breathing inner ring so it reads as live, not a dead button
            float pulse = 0.45f + 0.55f * (float) Math.abs(Math.sin(phase * 0.04f));
            c.drawCircle(cx, cy, r * 0.62f,
                    Skin.stroke(1f, Skin.alpha(accent, 0.35f + 0.3f * pulse)));

            drawMic(c, cx, cy, r * 0.46f, Skin.alpha(accent, 0.7f + 0.3f * pulse));
        }

        /** Expanded: a proper card with real padding and a text column. */
        private void drawCard(Canvas c, int w, int h, int accent) {
            RectF body = new RectF(Skin.dp(3), Skin.dp(3), w - Skin.dp(3), h - Skin.dp(3));
            float radius = Skin.dp(20);

            c.drawRoundRect(body, radius, radius, Skin.fill(Skin.alpha(Skin.VOID, 0.96f)));
            c.drawRoundRect(body, radius, radius, Skin.stroke(1.4f, Skin.alpha(accent, 0.85f)));

            float padL = Skin.dp(14), padT = Skin.dp(14);
            float orbR = Skin.dp(17);
            float ocx = body.left + padL + orbR;
            float ocy = body.top + padT + orbR;

            // level ring
            c.drawCircle(ocx, ocy, orbR * (1f + 0.18f * level),
                    Skin.stroke(1.5f, accent));
            for (int i = 0; i < 24; i++) {
                double a = Math.toRadians(i * 15 - phase * 1.4);
                float amp = 0.25f + 0.75f * level;
                float r0 = orbR + Skin.dp(3.5f);
                float r1 = r0 + Skin.dp(1.5f) + amp * Skin.dp(6);
                c.drawLine(ocx + (float) Math.cos(a) * r0, ocy + (float) Math.sin(a) * r0,
                        ocx + (float) Math.cos(a) * r1, ocy + (float) Math.sin(a) * r1,
                        Skin.stroke(1.2f, Skin.alpha(accent, 0.7f)));
            }
            drawMic(c, ocx, ocy, orbR * 0.5f, accent);

            // text column, clear of the orb
            float tx = ocx + orbR + Skin.dp(14);
            float tw = body.right - Skin.dp(14) - tx;

            Paint st = Skin.text(7, accent, true);
            st.setLetterSpacing(0.28f);
            String label = mode == 1 ? "LISTENING" : mode == 2 ? "WORKING" : "DONE";
            c.drawText(label, tx, body.top + padT + Skin.dp(8), st);

            Paint tp = Skin.text(9.5f, Skin.TEXT, false);
            String heard = transcript.length() > 0 ? transcript : "speak now";
            String[] hl = wrap(heard, tp, tw, 2);
            for (int i = 0; i < hl.length; i++) {
                c.drawText(hl[i], tx, body.top + padT + Skin.dp(23) + i * Skin.dp(12), tp);
            }

            // reply gets the full width beneath a hairline
            if (reply.length() > 0) {
                float ry = body.top + padT + Skin.dp(46);
                c.drawLine(body.left + padL, ry, body.right - padL, ry,
                        Skin.stroke(0.8f, Skin.alpha(Skin.EDGE, 0.9f)));

                Paint rp = Skin.text(9, Skin.ION, false);
                String[] rl = wrap(reply, rp, body.width() - padL * 2, 4);
                for (int i = 0; i < rl.length; i++) {
                    c.drawText(rl[i], body.left + padL,
                            ry + Skin.dp(15) + i * Skin.dp(12), rp);
                }
            }
        }

        private void drawMic(Canvas c, float cx, float cy, float s, int color) {
            Paint p = Skin.stroke(1.5f, color);
            p.setStrokeCap(Paint.Cap.ROUND);
            RectF cap = new RectF(cx - s * 0.34f, cy - s * 0.9f,
                    cx + s * 0.34f, cy + s * 0.16f);
            c.drawRoundRect(cap, s * 0.34f, s * 0.34f, p);
            c.drawArc(new RectF(cx - s * 0.62f, cy - s * 0.45f,
                    cx + s * 0.62f, cy + s * 0.58f), 0, 180, false, p);
            c.drawLine(cx, cy + s * 0.58f, cx, cy + s * 0.9f, p);
        }

        private String clipTo(String s, Paint p, float max) {
            if (p.measureText(s) <= max) return s;
            int n = p.breakText(s, true, max - p.measureText("…"), null);
            return n <= 0 ? "" : s.substring(0, n) + "…";
        }

        private String[] wrap(String s, Paint p, float max, int maxLines) {
            java.util.List<String> out = new java.util.ArrayList<String>();
            String rest = s;
            while (rest.length() > 0 && out.size() < maxLines) {
                int n = p.breakText(rest, true, max, null);
                if (n <= 0) break;
                if (n < rest.length()) {
                    int sp = rest.lastIndexOf(' ', n);
                    if (sp > 8) n = sp;
                }
                out.add(rest.substring(0, n).trim());
                rest = rest.substring(n).trim();
            }
            if (rest.length() > 0 && out.size() == maxLines) {
                out.set(maxLines - 1, out.get(maxLines - 1) + "…");
            }
            return out.toArray(new String[0]);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = lp.x; startY = lp.y;
                    dragged = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > Skin.dp(6) || Math.abs(dy) > Skin.dp(6)) {
                        dragged = true;
                        lp.x = (int) (startX + dx);
                        lp.y = (int) (startY + dy);
                        try { wm.updateViewLayout(this, lp); } catch (Throwable ignored) {}
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (dragged) {
                        cfg.setIslandPos(lp.x, lp.y);
                        return true;
                    }
                    performHapticFeedback(1);
                    if (mode == 0) { userStartedManually = true; beginListening(); }
                    else endListening();
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
