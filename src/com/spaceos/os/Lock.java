package com.spaceos.os;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * LOCK :: the Space OS face on the lock screen.
 *
 * Android does not let an ordinary app replace the secure keyguard, and it
 * should not — your PIN and biometrics stay exactly where they are. What this
 * does is show ON TOP of the lock screen via setShowWhenLocked, so the first
 * thing you see is Space OS rather than the stock clock.
 *
 * Swiping up asks the system to dismiss the keyguard. Android runs its own
 * unlock (PIN, fingerprint, face) and only then hands control back, at which
 * point we drop straight into the bridge.
 */
public class Lock extends Activity {

    private LockView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Skin.init(this);
        Theme.apply(Config.get(this).palette());

        // Appear over the keyguard — and nothing more.
        //
        // This used to ask to turn the screen on and to keep it on. Both were
        // wrong, and became actively hostile once the face started being built
        // during SCREEN_OFF so that waking would be instant: the activity
        // launching was itself lighting the panel, so locking the phone turned
        // it straight back on, and KEEP_SCREEN_ON then held it there. A lock
        // screen has no business waking a device or preventing it from
        // sleeping; the system decides when the panel lights.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        // The lock face always owns the whole screen. dressWindow already sets
        // the immersive flags and the light-icon flags for light palettes —
        // overriding setSystemUiVisibility afterwards would wipe both.
        Skin.dressWindow(this, true);

        Config cfg = Config.get(this);
        view = new LockView(this);
        view.setStyle(cfg.lockFaceStyle());
        view.setOnEnter(new Runnable() {
            @Override public void run() { enter(); }
        });

        // Say what the swipe will actually do, rather than promising an unlock
        // and handing over to a second screen.
        if (cfg.lockStyle() == Config.LOCK_BIOMETRIC
                && Biometrics.grade(this) != Biometrics.NONE) {
            view.unlockHint = Biometrics.grade(this) == Biometrics.WEAK
                    ? "SWIPE UP TO SCAN" : "SWIPE UP TO SCAN";
        } else if (cfg.lockStyle() == Config.LOCK_SYSTEM) {
            view.unlockHint = "SWIPE UP FOR THE KEYGUARD";
        }
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();

        // Second line of defence. The face may already have been showing when
        // the call arrived, in which case nothing launches it and the guard in
        // Island never runs — but it is still sitting on top of the call.
        if (Island.phoneBusy(this)) {
            Journal.info("lock", "call in progress — standing down");
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        view.setStyle(Config.get(this).lockFaceStyle());
        view.start();

        // The face is now built while the screen is still OFF, so that waking
        // it costs nothing. That means onResume can run in the dark, and
        // raising a fingerprint prompt at a black screen would be worse than
        // the delay it replaced. Only scan once the panel is actually lit.
        if (isInteractive()) armScan();
        registerWakeWatch();
    }

    private boolean isInteractive() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) { return true; }
    }

    private void armScan() {
        Config cfg = Config.get(this);
        if (wokeScan) return;
        if (!cfg.scanOnWake()) return;
        if (cfg.lockStyle() != Config.LOCK_BIOMETRIC) return;
        if (Biometrics.grade(this) == Biometrics.NONE) return;
        if (!keyguardLocked()) return;
        wokeScan = true;
        view.postDelayed(new Runnable() {
            @Override public void run() {
                // Re-check on the way in. The delay exists so the face draws
                // before a dialog covers it, and a lot can happen in it: the
                // system's own lock screen may have taken a face scan and let
                // the user through already. Firing regardless is how you end up
                // demanding a fingerprint from someone who has just unlocked.
                if (!keyguardLocked()) { openBridge(); finish(); return; }
                enter();
            }
        }, 350);
    }

    private boolean keyguardLocked() {
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) { return true; }
    }

    private boolean wokeScan;
    private android.content.BroadcastReceiver wakeWatch;

    /** Fires the scan when the panel lights, not when the activity resumes. */
    private void registerWakeWatch() {
        if (wakeWatch != null) return;
        try {
            wakeWatch = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) {
                        // THE KEYGUARD UNLOCKED ITSELF.
                        //
                        // The system's own face unlock stays armed the whole
                        // time the keyguard is showing, and it is still showing
                        // underneath us — our window sits over it, it is not
                        // replaced. So a face scan runs, succeeds, and drops
                        // the keyguard without either of us doing anything.
                        //
                        // That is the automatic face unlock, and it works with
                        // the Space OS face on screen: the two are not the
                        // either/or I claimed. All that was missing is this —
                        // noticing it happened and getting out of the way,
                        // rather than sitting there waiting for a swipe on a
                        // phone that is already open.
                        cancelScan();
                        wokeScan = true;
                        Journal.info("lock", "system unlocked underneath — standing down");
                        Config.get(Lock.this).setPendingEntry(false);
                        // Get out of the way and NOTHING else.
                        //
                        // Launching the bridge here threw away whatever you
                        // were doing: it reset the Space OS task back to its
                        // root, so opening SHELL, locking the phone and
                        // unlocking put you on the home screen with the shell
                        // gone. Finishing this activity uncovers whatever was
                        // underneath — the module you had open, or another app
                        // entirely — which is what unlocking a phone is
                        // supposed to do.
                        slideAwayAndFinish();
                        return;
                    }
                    armScan();
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(wakeWatch, f);
        } catch (Throwable ignored) {}
    }

    @Override protected void onStop() {
        super.onStop();
        cancelScan();          // never leave a prompt alive behind a dark screen
        wokeScan = false;
        if (wakeWatch != null) {
            try { unregisterReceiver(wakeWatch); } catch (Throwable ignored) {}
            wakeWatch = null;
        }
    }

    @Override protected void onPause()  { super.onPause();  view.stop(); }

    private android.os.CancellationSignal pendingScan;

    private void cancelScan() {
        if (pendingScan == null) return;
        try { pendingScan.cancel(); } catch (Throwable ignored) {}
        pendingScan = null;
    }

    /**
     * Back must not drop you behind the lock screen — but it MUST be able to
     * dismiss the scan prompt, which otherwise has no way out at all.
     */
    @Override public void onBackPressed() {
        if (pendingScan != null) { cancelScan(); return; }
        /* otherwise held */
    }

    /**
     * Hands the screen back to Android's real keyguard.
     *
     * This used to call requestDismissKeyguard(), which was wrong: that API
     * raises the CREDENTIAL challenge specifically — PIN, pattern or password
     * — and deliberately skips biometrics, because the fingerprint sensor is
     * armed by the keyguard's own flow. Drawing over the keyguard and then
     * calling it routed the user straight past their own fingerprint reader.
     *
     * So we simply get out of the way. Finishing this activity reveals the
     * genuine keyguard underneath with its fingerprint listener already live,
     * and Island's ACTION_USER_PRESENT watch opens the bridge once the system
     * reports a successful unlock — by whichever method the user chose.
     */
    private void enter() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        // Not actually locked — go straight through.
        if (km == null || !km.isKeyguardLocked()) { openBridge(); return; }

        // No credential set at all: the keyguard is a swipe and nothing more,
        // so we can dismiss it ourselves and the face becomes the only lock
        // screen the user ever sees. This is the one case that is seamless.
        boolean secure = Build.VERSION.SDK_INT < 23 || km.isDeviceSecure();
        if (!secure) {
            dismissAndEnter(km);
            return;
        }

        // A credential IS set, and only the keyguard may take it — no app can
        // stand in for that. So one system screen is unavoidable; all we get
        // to choose is which, and each route costs something different.
        Journal.info("lock", "unlock route: " + Config.get(this).lockStyleLabel()
                + " · biometrics " + Biometrics.status(this));

        switch (Config.get(this).lockStyle()) {
            case Config.LOCK_BIOMETRIC:
                promptBiometric(km);
                return;

            case Config.LOCK_SYSTEM:
                // Hand back to the stock keyguard. Its fingerprint listener is
                // already armed — but on most skins its own swipe comes first,
                // which is the second swipe nobody asked for.
                Config.get(this).setPendingEntry(true);
                slideAwayAndFinish();
                return;

            default:
                // Straight to the credential screen. One gesture here, then
                // the PIN pad: the same count as the stock lock screen, with
                // the Space OS face replacing the stock clock rather than
                // being stacked on top of it.
                dismissAndEnter(km);
        }
    }

    /**
     * Fingerprint or face, asked for by us, on our own screen.
     *
     * BiometricPrompt authenticates the user to THIS APP. It does not unlock
     * the device — nothing an app can call does — so a successful scan is
     * still followed by requestDismissKeyguard. On some builds the system
     * treats a fresh strong-biometric authentication as sufficient and drops
     * the keyguard without asking again; on others it raises the PIN pad
     * anyway, which is one prompt too many. That is a manufacturer decision we
     * cannot detect ahead of time, so this is a mode to try rather than the
     * default. If it asks twice on your phone, use PIN DIRECT.
     */
    private void promptBiometric(final KeyguardManager km) {
        if (Build.VERSION.SDK_INT < 28) { dismissAndEnter(km); return; }
        try {
            android.hardware.biometrics.BiometricPrompt.Builder b =
                    new android.hardware.biometrics.BiometricPrompt.Builder(this)
                            .setTitle("SPACE OS")
                            .setSubtitle("identity check");

            // BIOMETRIC_WEAK, not STRONG — see Biometrics for why that is what
            // makes face unlock appear at all.
            int mask = Biometrics.allowedMask(this);
            if (Build.VERSION.SDK_INT >= 30 && mask != 0) {
                b.setAllowedAuthenticators(mask);
            } else {
                b.setDeviceCredentialAllowed(true);
            }

            // NO CONFIRMATION TAP.
            //
            // For a passive biometric — a face, which authenticates without
            // you doing anything — Android defaults to requiring an explicit
            // confirm afterwards, on the reasoning that you might have been
            // looking at your phone without meaning to unlock it. That default
            // is why the scan succeeded and then sat there waiting for a
            // button. Turning it off is what makes face unlock behave the way
            // it does on the stock lock screen: look at it, and you are in.
            if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false);

            // KEEP THE HANDLE.
            //
            // Passing DEVICE_CREDENTIAL puts "Use PIN" in the dialog's negative
            // slot — which is the ONLY slot — so there is no Cancel button at
            // all. Scan, or type your PIN; nothing takes you back to the lock
            // face. Holding the signal is what makes back work as an escape.
            cancelScan();
            pendingScan = new android.os.CancellationSignal();
            b.build().authenticate(pendingScan,
                    getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt
                                        .AuthenticationResult r) {
                            pendingScan = null;
                            // A scan accepted HERE authenticates you to Space
                            // OS. It does not unlock the phone — the keyguard
                            // has no idea it happened and will still want a
                            // credential, which is why this route can end in
                            // "scanned, now enter your PIN".
                            Journal.info("lock", "biometric accepted by the app");
                            dismissAndEnter(km);
                        }
                        @Override public void onAuthenticationError(int code, CharSequence msg) {
                            pendingScan = null;
                            // Cancelled, or the sensor gave up. Stay on the
                            // face; do not re-raise, or it becomes a prompt
                            // that cannot be dismissed.
                            Journal.warn("lock", "biometric: " + msg);
                        }
                    });
        } catch (Throwable t) {
            Journal.warn("lock", "no biometric prompt: " + t.getMessage());
            dismissAndEnter(km);
        }
    }

    /** Asks the system to drop the keyguard, then continues into the bridge. */
    private void dismissAndEnter(KeyguardManager km) {
        if (Build.VERSION.SDK_INT < 26) {
            Config.get(this).setPendingEntry(true);
            slideAwayAndFinish();
            return;
        }
        Journal.info("lock", "requesting keyguard dismissal");
        try {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() {
                    Journal.info("lock", "keyguard dismissed");
                    openBridge();
                    finish();
                    overridePendingTransition(0, 0);
                }
                @Override public void onDismissCancelled() {
                    Journal.info("lock", "dismissal cancelled by the user");
                }
                @Override public void onDismissError() {
                    // This is the double swipe. The system refused to raise
                    // its own credential screen for us, so the only thing left
                    // is to get out of the way and let the stock lock screen
                    // do it — which is the second swipe the user is seeing.
                    Journal.warn("lock", "dismissal REFUSED by the system — "
                            + "falling back to the stock lock screen");
                    Config.get(Lock.this).setPendingEntry(true);
                    slideAwayAndFinish();
                }
            });
        } catch (Throwable t) {
            Journal.warn("lock", "dismissal threw: " + t.getMessage());
            Config.get(this).setPendingEntry(true);
            slideAwayAndFinish();
        }
    }

    /** Lifts the face out of the way so the keyguard is revealed cleanly. */
    private void slideAwayAndFinish() {
        view.animate()
                .translationY(-view.getHeight() * 0.35f)
                .alpha(0f)
                .setDuration(220)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        finish();
                        overridePendingTransition(0, 0);
                    }
                }).start();
    }

    private void openBridge() {
        try {
            // NEW_TASK only. RESET_TASK_IF_NEEDED clears the Space OS task
            // back to the bridge, discarding any module that was open when the
            // screen locked.
            Intent i = new Intent(this, Bridge.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {}
        finish();
        overridePendingTransition(R.anim.warp_in, R.anim.warp_out);
    }

    // =====================================================================

    /** The lock face: clock, a slim telemetry read, and a swipe affordance. */
    private static class LockView extends View {

        private final SimpleDateFormat clockFmt = new SimpleDateFormat(
                Config.get(getContext()).clock24() ? "HH:mm" : "h:mm", Locale.US);
        private final SimpleDateFormat dateFmt =
                new SimpleDateFormat("EEEE, d MMMM", Locale.US);

        private float phase;
        private boolean running;
        private long lastNs;

        private Sys.Power power = new Sys.Power();
        private Sys.Mem mem = new Sys.Mem();
        private Sys.Link link = new Sys.Link();
        private long lastSample;
        private volatile boolean sampling;

        private float dragY;            // 0..1 how far the swipe has travelled
        private float downY = -1;
        private Runnable onEnter;

        private final Handler ui = new Handler(Looper.getMainLooper());

        LockView(Context c) { super(c); sample(); }

        void setOnEnter(Runnable r) { onEnter = r; }

        void start() { if (!running) { running = true; lastNs = 0; postOnAnimation(tick); } }
        void stop()  { running = false; removeCallbacks(tick); }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                long now = System.nanoTime();
                float dt = lastNs == 0 ? 1f / 60f : (now - lastNs) / 1_000_000_000f;
                lastNs = now;
                phase += dt * 20f;
                if (downY < 0) dragY += (0f - dragY) * (1f - (float) Math.exp(-9f * dt));
                if (System.currentTimeMillis() - lastSample > 4000) sample();
                invalidate();
                postOnAnimation(this);
            }
        };

        private void sample() {
            lastSample = System.currentTimeMillis();
            if (sampling) return;
            sampling = true;
            final Context c = getContext().getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    final Sys.Power p = Sys.power(c);
                    final Sys.Mem m = Sys.mem(c);
                    final Sys.Link l = Sys.link(c);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            power = p; mem = m; link = l; sampling = false;
                        }
                    });
                }
            }, "lock-telemetry").start();
        }

        /** Which face is drawn. Six arrangements of the same live data. */
        private int style = 0;
        void setStyle(int s) { style = s; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            Date now = new Date();

            c.drawColor(Skin.VOID);
            Config wcfg = Config.get(getContext());
            boolean paper = wcfg.wallpaperOn() && wcfg.wallpaperOnLock()
                    && Wallpaper.draw(c, getContext(), w, h, wcfg.wallpaperDim());
            if (!paper && style != 3) Skin.stars(c, w, h, phase, style == 1 ? 40 : 70);
            Skin.vignette(c, w, h, 0.6f);

            // everything lifts slightly as you drag up
            c.save();
            c.translate(0, -dragY * Skin.dp(40));

            switch (style) {
                case 1: drawMinimalFace(c, w, h, now); break;
                case 2: drawReactorFace(c, w, h, now); break;
                case 3: drawTerminalFace(c, w, h, now); break;
                case 4: drawOrbitFace(c, w, h, now); break;
                case 5: drawStackFace(c, w, h, now); break;
                default: drawInstrumentFace(c, w, h, now);
            }

            c.restore();
            drawUnlockAffordance(c, w, h);
            Skin.scanlines(c, w, h, phase);
        }

        // =================================================================
        // INSTRUMENT — the original: clock, power ring, three readouts
        // =================================================================

        private void drawInstrumentFace(Canvas c, int w, int h, Date now) {
            float cx = w / 2f;

            // ---- clock ----
            Paint big = Skin.text(78, Skin.TEXT, false);
            big.setLetterSpacing(-0.04f);
            Skin.textCenter(c, clockFmt.format(now), cx, h * 0.30f, big);

            Paint date = Skin.text(11, Skin.alpha(Skin.PLASMA, 0.85f), false);
            date.setLetterSpacing(0.24f);
            Skin.textCenter(c, dateFmt.format(now).toUpperCase(Locale.US),
                    cx, h * 0.30f + Skin.dp(24), date);

            // ---- ring under the clock ----
            float r = Math.min(w, h) * 0.20f;
            float ry = h * 0.50f;
            Skin.halo(c, cx, ry, r * 1.5f, Skin.PLASMA, 0.5f);
            drawTicks(c, cx, ry, r, 72, -phase * 0.2f, Skin.alpha(Skin.EDGE, 0.8f));

            float pf = power.level < 0 ? 0 : power.level / 100f;
            RectF box = new RectF(cx - r, ry - r, cx + r, ry + r);
            c.drawArc(box, 132, 276, false,
                    Skin.stroke(3.5f, Skin.alpha(Skin.PLASMA, 0.10f)));
            Skin.bloomArc(c, box, 132, 276 * pf,
                    power.charging ? Skin.ION : Skin.level(1f - pf), 3.5f, 6f);

            Paint lvl = Skin.text(26, power.charging ? Skin.ION : Skin.TEXT, false);
            Skin.textCenter(c, (power.level < 0 ? "--" : String.valueOf(power.level)) + "%",
                    cx, ry + Skin.dp(6), lvl);
            Paint cap = Skin.text(6.5f, Skin.TEXT_DIM, false);
            cap.setLetterSpacing(0.4f);
            Skin.textCenter(c, power.charging ? "CHARGING" : "POWER CELL",
                    cx, ry + Skin.dp(20), cap);

            // ---- slim readout ----
            float sy = h * 0.68f;
            Paint k = Skin.text(6.5f, Skin.TEXT_DIM, false);
            k.setLetterSpacing(0.3f);
            Paint v = Skin.text(10, Skin.TEXT, false);

            float third = w / 3f;
            centreStat(c, third * 0.5f, sy, "UPLINK",
                    link.online ? link.type : "DARK", k, v);
            centreStat(c, third * 1.5f, sy, "MEMORY",
                    Math.round(mem.frac * 100) + "%", k, v);
            centreStat(c, third * 2.5f, sy, "UPTIME",
                    shortUp(), k, v);

            // ---- wordmark ----
            Paint mark = Skin.text(9, Skin.alpha(Skin.PLASMA, 0.65f), true);
            mark.setLetterSpacing(0.5f);
            Skin.textCenter(c, "SPACE OS", cx, h * 0.79f, mark);
        }

        // =================================================================
        // MINIMAL — the time, the date, and a hairline charge bar
        // =================================================================

        private void drawMinimalFace(Canvas c, int w, int h, Date now) {
            float cx = w / 2f;

            Paint big = Skin.text(104, Skin.TEXT, false);
            big.setLetterSpacing(-0.05f);
            Skin.textCenter(c, clockFmt.format(now), cx, h * 0.42f, big);

            Paint date = Skin.text(11, Skin.TEXT_DIM, false);
            date.setLetterSpacing(0.34f);
            Skin.textCenter(c, dateFmt.format(now).toUpperCase(Locale.US),
                    cx, h * 0.42f + Skin.dp(28), date);

            // one hairline, the width of the charge remaining
            float pf = power.level < 0 ? 0 : power.level / 100f;
            float bw = w * 0.34f, by = h * 0.42f + Skin.dp(50);
            c.drawLine(cx - bw / 2, by, cx + bw / 2, by,
                    Skin.stroke(1f, Skin.alpha(Skin.EDGE, 1f)));
            c.drawLine(cx - bw / 2, by, cx - bw / 2 + bw * pf, by,
                    Skin.stroke(2f, power.charging ? Skin.ION : Skin.level(1f - pf)));
        }

        // =================================================================
        // REACTOR — charge as containment, filling one enormous core
        // =================================================================

        private void drawReactorFace(Canvas c, int w, int h, Date now) {
            float cx = w / 2f, cy = h * 0.45f;
            float r = Math.min(w, h) * 0.26f;
            float pf = power.level < 0 ? 0 : power.level / 100f;

            for (int k = 0; k < 3; k++) {
                float rr = r * (1.08f + k * 0.10f);
                int ticks = 44 + k * 14;
                float rot = phase * (k % 2 == 0 ? 0.5f : -0.35f);
                Paint p = Skin.stroke(1f,
                        Skin.alpha(k == 0 ? Skin.PLASMA : Skin.EDGE, 0.85f));
                for (int i = 0; i < ticks; i++) {
                    double a = Math.toRadians(rot + i * (360.0 / ticks));
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    float len = i % 8 == 0 ? Skin.dp(8) : Skin.dp(3);
                    c.drawLine(cx + ca * rr, cy + sa * rr,
                            cx + ca * (rr + len), cy + sa * (rr + len), p);
                }
            }

            float pulse = 1f + 0.03f * (float) Math.sin(phase * 0.12f);
            c.drawCircle(cx, cy, r * pulse, Skin.fill(Skin.alpha(Skin.PLASMA, 0.08f)));
            c.drawCircle(cx, cy, r * 0.72f * pulse,
                    Skin.fill(Skin.alpha(Skin.PLASMA, 0.13f)));
            c.drawCircle(cx, cy, r, Skin.stroke(1.6f, Skin.PLASMA));

            RectF box = new RectF(cx - r * 0.88f, cy - r * 0.88f,
                    cx + r * 0.88f, cy + r * 0.88f);
            c.drawArc(box, 132, 276, false,
                    Skin.stroke(4f, Skin.alpha(Skin.PLASMA, 0.10f)));
            Skin.bloomArc(c, box, 132, 276 * pf,
                    power.charging ? Skin.ION : Skin.level(1f - pf), 4f, 7f);

            Paint big = Skin.text(52, Skin.TEXT, false);
            big.setLetterSpacing(-0.03f);
            Skin.textCenter(c, clockFmt.format(now), cx, cy + Skin.dp(10), big);

            Paint cap = Skin.text(7, Skin.TEXT_DIM, false);
            cap.setLetterSpacing(0.4f);
            Skin.textCenter(c, (power.level < 0 ? "--" : power.level + "%")
                            + (power.charging ? " CHARGING" : " CELL"),
                    cx, cy + Skin.dp(30), cap);

            Paint date = Skin.text(9.5f, Skin.alpha(Skin.PLASMA, 0.8f), false);
            date.setLetterSpacing(0.28f);
            Skin.textCenter(c, dateFmt.format(now).toUpperCase(Locale.US),
                    cx, cy + r + Skin.dp(40), date);
        }

        // =================================================================
        // TERMINAL — a boot readout with the cursor still blinking
        // =================================================================

        private void drawTerminalFace(Canvas c, int w, int h, Date now) {
            Skin.grid(c, w, h, 30);
            float x = Skin.dp(20), y = h * 0.26f, lh = Skin.dp(16);
            Paint mono = Skin.text(10, Skin.ION, false);

            String[] lines = {
                    "spaceos> auth --status",
                    "  time      " + clockFmt.format(now),
                    "  date      " + dateFmt.format(now).toLowerCase(Locale.US),
                    "  cell      " + (power.level < 0 ? "--" : power.level + "%")
                            + (power.charging ? " charging" : ""),
                    "  memory    " + Math.round(mem.frac * 100) + "%",
                    "  uplink    " + (link.online
                            ? link.type.toLowerCase(Locale.US) : "down"),
                    "  uptime    " + shortUp(),
                    "",
                    "  identity  LOCKED",
                    "spaceos> _",
            };
            for (int i = 0; i < lines.length; i++) {
                boolean prompt = lines[i].startsWith("spaceos>");
                boolean locked = lines[i].contains("LOCKED");
                mono.setColor(prompt ? Skin.PLASMA : locked ? Skin.SOLAR : Skin.ION);
                String t = lines[i];
                if (i == lines.length - 1 && ((int) (phase * 0.5f) % 2 == 0)) {
                    t = t.substring(0, t.length() - 1);
                }
                c.drawText(t, x, y + i * lh, mono);
            }
        }

        // =================================================================
        // ORBIT — a world on its rings, the clock riding above it
        // =================================================================

        private void drawOrbitFace(Canvas c, int w, int h, Date now) {
            float cx = w / 2f, cy = h * 0.56f;
            float span = Math.min(w, h * 0.6f);
            float planet = span * 0.17f;

            Paint big = Skin.text(64, Skin.TEXT, false);
            big.setLetterSpacing(-0.04f);
            Skin.textCenter(c, clockFmt.format(now), cx, h * 0.24f, big);

            Paint date = Skin.text(10, Skin.alpha(Skin.PLASMA, 0.85f), false);
            date.setLetterSpacing(0.28f);
            Skin.textCenter(c, dateFmt.format(now).toUpperCase(Locale.US),
                    cx, h * 0.24f + Skin.dp(22), date);

            Paint orbit = Skin.stroke(0.9f, Skin.alpha(Skin.EDGE, 0.85f));
            c.drawCircle(cx, cy, span * 0.30f, orbit);
            c.drawCircle(cx, cy, span * 0.42f, orbit);

            c.drawCircle(cx, cy, planet,
                    Skin.fill(Skin.alpha(Skin.PLASMA_D, 0.32f)));
            c.drawCircle(cx, cy, planet, Skin.stroke(1.4f, Skin.PLASMA));

            float pf = power.level < 0 ? 0 : power.level / 100f;
            RectF box = new RectF(cx - planet * 1.6f, cy - planet * 1.6f,
                    cx + planet * 1.6f, cy + planet * 1.6f);
            c.drawArc(box, 132, 276, false,
                    Skin.stroke(3f, Skin.alpha(Skin.PLASMA, 0.10f)));
            Skin.bloomArc(c, box, 132, 276 * pf,
                    power.charging ? Skin.ION : Skin.level(1f - pf), 3f, 5f);

            // two moons, different periods
            for (int i = 0; i < 2; i++) {
                double a = Math.toRadians(phase * (i == 0 ? 0.7 : -0.42) + i * 140);
                float rr = span * (i == 0 ? 0.42f : 0.30f);
                c.drawCircle(cx + (float) Math.cos(a) * rr,
                        cy + (float) Math.sin(a) * rr,
                        Skin.dp(i == 0 ? 3.5f : 2.5f),
                        Skin.fill(i == 0 ? Skin.SOLAR : Skin.MAGENTA));
            }

            Paint cap = Skin.text(6.5f, Skin.TEXT_DIM, false);
            cap.setLetterSpacing(0.4f);
            Skin.textCenter(c, (power.level < 0 ? "--" : power.level + "%")
                            + "  ·  " + (link.online ? link.type : "DARK"),
                    cx, cy + span * 0.52f, cap);
        }

        // =================================================================
        // STACK — left-aligned, oversized date, a charge bar across the foot
        // =================================================================

        private void drawStackFace(Canvas c, int w, int h, Date now) {
            float x = Skin.dp(24);
            float y = h * 0.34f;

            Paint big = Skin.text(88, Skin.TEXT, false);
            big.setLetterSpacing(-0.05f);
            c.drawText(clockFmt.format(now), x, y, big);

            SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE", Locale.US);
            SimpleDateFormat restFmt = new SimpleDateFormat("d MMMM", Locale.US);

            Paint day = Skin.text(26, Skin.PLASMA, false);
            day.setLetterSpacing(0.02f);
            c.drawText(dayFmt.format(now).toUpperCase(Locale.US), x, y + Skin.dp(38), day);

            Paint rest = Skin.text(13, Skin.TEXT_DIM, false);
            rest.setLetterSpacing(0.2f);
            c.drawText(restFmt.format(now).toUpperCase(Locale.US),
                    x, y + Skin.dp(58), rest);

            c.drawLine(x, y + Skin.dp(76), x + Skin.dp(48), y + Skin.dp(76),
                    Skin.stroke(2f, Skin.PLASMA));

            // three stacked readings
            String[][] rows = {
                    {"CELL", (power.level < 0 ? "--" : power.level + "%")
                            + (power.charging ? "  CHG" : "")},
                    {"MEMORY", Math.round(mem.frac * 100) + "%"},
                    {"UPLINK", link.online ? link.type : "DARK"},
            };
            Paint k = Skin.text(6.5f, Skin.TEXT_DIM, true);
            k.setLetterSpacing(0.32f);
            Paint v = Skin.text(12, Skin.TEXT, false);
            for (int i = 0; i < rows.length; i++) {
                float ry = y + Skin.dp(104) + i * Skin.dp(34);
                c.drawText(rows[i][0], x, ry, k);
                c.drawText(rows[i][1], x, ry + Skin.dp(16), v);
            }

            // charge bar across the foot
            float pf = power.level < 0 ? 0 : power.level / 100f;
            float by = h - Skin.dp(86);
            c.drawLine(x, by, w - x, by, Skin.stroke(1.5f, Skin.alpha(Skin.EDGE, 1f)));
            c.drawLine(x, by, x + (w - x * 2) * pf, by,
                    Skin.stroke(3f, power.charging ? Skin.ION : Skin.level(1f - pf)));
        }

        // =================================================================

        private void drawUnlockAffordance(Canvas c, int w, int h) {
            float cx = w / 2f;
            // ---- swipe affordance, pinned to the bottom ----
            float ay = h - Skin.dp(56) - dragY * Skin.dp(20);
            float bob = (float) Math.sin(phase * 0.10f) * Skin.dp(3);
            Paint arrow = Skin.stroke(1.8f,
                    Skin.alpha(Skin.PLASMA, 0.45f + 0.35f * dragY + 0.2f * Math.abs(bob / Skin.dp(3))));
            arrow.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 2; i++) {
                float ty = ay + bob + i * Skin.dp(9);
                c.drawLine(cx - Skin.dp(10), ty, cx, ty - Skin.dp(7), arrow);
                c.drawLine(cx, ty - Skin.dp(7), cx + Skin.dp(10), ty, arrow);
            }

            Paint hint = Skin.text(7.5f,
                    Skin.alpha(Skin.TEXT_DIM, 0.6f + 0.4f * dragY), true);
            hint.setLetterSpacing(0.4f);
            Skin.textCenter(c, unlockHint, cx, h - Skin.dp(26), hint);
        }

        /** Set by the activity so the face says how it will actually unlock. */
        String unlockHint = "SWIPE UP TO UNLOCK";

        private void centreStat(Canvas c, float cx, float y,
                                String label, String value, Paint k, Paint v) {
            Skin.textCenter(c, label, cx, y, k);
            Skin.textCenter(c, value, cx, y + Skin.dp(14), v);
        }

        private void drawTicks(Canvas c, float cx, float cy, float r,
                               int n, float rot, int color) {
            Paint p = Skin.stroke(1f, color);
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(rot + i * (360.0 / n));
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                float len = i % 9 == 0 ? Skin.dp(7) : Skin.dp(3);
                c.drawLine(cx + ca * (r + Skin.dp(9)), cy + sa * (r + Skin.dp(9)),
                        cx + ca * (r + Skin.dp(9) + len),
                        cy + sa * (r + Skin.dp(9) + len), p);
            }
        }

        private static String shortUp() {
            long s = Sys.uptimeMs() / 1000;
            long d = s / 86400, h = (s % 86400) / 3600;
            return d > 0 ? d + "d" : h + "h";
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (downY >= 0) {
                        float travel = Math.max(0, downY - e.getY());
                        dragY = Math.min(1f, travel / (getHeight() * 0.22f));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean commit = dragY > 0.55f;
                    downY = -1;
                    if (commit) {
                        performHapticFeedback(1);
                        if (onEnter != null) onEnter.run();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    downY = -1;
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
