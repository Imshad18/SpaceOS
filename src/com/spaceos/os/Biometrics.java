package com.spaceos.os;

import android.content.Context;
import android.os.Build;

/**
 * What this phone will actually authenticate with, and why face was missing.
 *
 * BiometricPrompt was being built with BIOMETRIC_STRONG. That sounds like the
 * safe choice and it is the reason face unlock never appeared: Android grades
 * sensors, and STRONG means Class 3 — a fingerprint reader, or a face sensor
 * with real depth data. Samsung's face unlock is a plain front camera, so it
 * is graded WEAK (Class 2), and asking for STRONG deliberately excludes it.
 *
 * The fix is not to ask for both. BIOMETRIC_STRONG | BIOMETRIC_WEAK is not a
 * legal combination — the framework rejects it — because WEAK already means
 * "weak or better". Asking for WEAK is what enables the fingerprint AND the
 * face, and that is what this returns.
 *
 * A weak sensor is genuinely easier to fool than a fingerprint. That is the
 * trade the user is making by turning face unlock on at all, and they have
 * already made it at the system level; refusing to honour it here would just
 * mean their phone offers face and Space OS mysteriously does not.
 */
public final class Biometrics {

    private Biometrics() {}

    public static final int NONE = 0;
    /** Something is enrolled, but only at Class 2 — typically camera face. */
    public static final int WEAK = 1;
    /** Class 3: fingerprint, or face with depth sensing. */
    public static final int STRONG = 2;

    /** The best grade of biometric currently enrolled and usable. */
    public static int grade(Context ctx) {
        if (Build.VERSION.SDK_INT < 29) return NONE;
        try {
            android.hardware.biometrics.BiometricManager bm =
                    ctx.getSystemService(android.hardware.biometrics.BiometricManager.class);
            if (bm == null) return NONE;

            if (Build.VERSION.SDK_INT < 30) {
                // canAuthenticate() with no argument means "any biometric".
                return bm.canAuthenticate()
                        == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
                        ? STRONG : NONE;
            }
            int strong = android.hardware.biometrics.BiometricManager
                    .Authenticators.BIOMETRIC_STRONG;
            int weak = android.hardware.biometrics.BiometricManager
                    .Authenticators.BIOMETRIC_WEAK;
            if (bm.canAuthenticate(strong)
                    == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) {
                return STRONG;
            }
            if (bm.canAuthenticate(weak)
                    == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) {
                return WEAK;
            }
        } catch (Throwable ignored) {}
        return NONE;
    }

    /** Whether a PIN, pattern or password is set. */
    public static boolean hasCredential(Context ctx) {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && Build.VERSION.SDK_INT >= 23 && km.isDeviceSecure();
        } catch (Throwable t) { return false; }
    }

    /**
     * The authenticator set to hand setAllowedAuthenticators.
     *
     * Always WEAK rather than STRONG when any biometric is enrolled, because
     * WEAK admits both grades and STRONG admits only one. Returns 0 when the
     * caller should not set an explicit mask at all.
     */
    public static int allowedMask(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) return 0;
        int weak = android.hardware.biometrics.BiometricManager
                .Authenticators.BIOMETRIC_WEAK;
        int cred = android.hardware.biometrics.BiometricManager
                .Authenticators.DEVICE_CREDENTIAL;
        // DEVICE_CREDENTIAL may be combined with exactly one biometric class.
        return grade(ctx) == NONE ? cred : (weak | cred);
    }

    /** One line for the settings panel, so the user can see what is on offer. */
    public static String describe(Context ctx) {
        if (Build.VERSION.SDK_INT < 28) return "not supported below Android 9";
        int g = grade(ctx);
        boolean cred = hasCredential(ctx);
        switch (g) {
            case STRONG:
                return cred ? "fingerprint or face, PIN as fallback"
                            : "fingerprint or face enrolled";
            case WEAK:
                return cred ? "face (camera-grade), PIN as fallback"
                            : "face enrolled, camera-grade";
            default:
                return cred ? "nothing enrolled — PIN or pattern only"
                            : "no biometrics and no PIN set";
        }
    }

    /**
     * The full truth about this phone's sensors, decoded.
     *
     * Worth having as a report rather than a yes/no, because "face unlock is
     * missing" has two completely different causes and only one of them is
     * fixable. Either nothing is enrolled — the user's problem, one tap to
     * solve — or the hardware exists and enrolled and the framework still
     * refuses to expose it to apps, which several manufacturers do with their
     * camera-based face unlock: it works on their own lock screen and is
     * simply not offered through BiometricPrompt to anyone else. HAS FACE
     * HARDWARE next to a NONE ENROLLED for the weak class is that second case,
     * and no amount of asking differently will change it.
     */
    public static String report(Context ctx) {
        StringBuilder sb = new StringBuilder();
        android.content.pm.PackageManager pm = ctx.getPackageManager();

        sb.append("HARDWARE\n");
        sb.append("  fingerprint  ")
          .append(has(pm, "android.hardware.fingerprint")).append('\n');
        sb.append("  face         ")
          .append(has(pm, "android.hardware.biometrics.face")).append('\n');
        sb.append("  iris         ")
          .append(has(pm, "android.hardware.biometrics.iris")).append('\n');

        sb.append("\nOFFERED TO APPS\n");
        if (Build.VERSION.SDK_INT < 29) {
            sb.append("  needs Android 10 or newer\n");
        } else {
            try {
                android.hardware.biometrics.BiometricManager bm =
                        ctx.getSystemService(android.hardware.biometrics.BiometricManager.class);
                if (bm == null) {
                    sb.append("  no biometric service\n");
                } else if (Build.VERSION.SDK_INT < 30) {
                    sb.append("  any          ").append(decode(bm.canAuthenticate())).append('\n');
                } else {
                    int strong = android.hardware.biometrics.BiometricManager
                            .Authenticators.BIOMETRIC_STRONG;
                    int weak = android.hardware.biometrics.BiometricManager
                            .Authenticators.BIOMETRIC_WEAK;
                    int cred = android.hardware.biometrics.BiometricManager
                            .Authenticators.DEVICE_CREDENTIAL;
                    sb.append("  class 3      ").append(decode(bm.canAuthenticate(strong))).append('\n');
                    sb.append("  class 2      ").append(decode(bm.canAuthenticate(weak))).append('\n');
                    sb.append("  PIN/pattern  ").append(decode(bm.canAuthenticate(cred))).append('\n');
                }
            } catch (Throwable t) {
                sb.append("  query failed: ").append(t.getMessage()).append('\n');
            }
        }

        sb.append("\nVERDICT\n  ");
        boolean faceHw = pm.hasSystemFeature("android.hardware.biometrics.face");
        int g = grade(ctx);
        if (g == STRONG) {
            sb.append("fingerprint or depth face is available to Space OS");
        } else if (g == WEAK) {
            sb.append("a camera-grade face IS available to Space OS");
        } else if (faceHw) {
            sb.append("this phone has face hardware but does not offer it to "
                    + "apps. Samsung and some others keep camera face unlock "
                    + "for their own lock screen only — no third-party app can "
                    + "reach it. Enrol a fingerprint and it will appear.");
        } else {
            sb.append("nothing enrolled that Space OS may use");
        }
        return sb.toString();
    }

    private static String has(android.content.pm.PackageManager pm, String feature) {
        try { return pm.hasSystemFeature(feature) ? "present" : "absent"; }
        catch (Throwable t) { return "unknown"; }
    }

    private static String decode(int code) {
        switch (code) {
            case 0:   return "AVAILABLE";
            case 1:   return "busy right now";
            case 11:  return "nothing enrolled";
            case 12:  return "no hardware for this class";
            case 15:  return "needs a security update";
            case -1:  return "unknown";
            case -2:  return "not supported";
            default:  return "code " + code;
        }
    }

    /** Short status word for the row's value column. */
    public static String status(Context ctx) {
        switch (grade(ctx)) {
            case STRONG: return "CLASS 3";
            case WEAK:   return "CLASS 2";
            default:     return hasCredential(ctx) ? "PIN ONLY" : "NONE";
        }
    }
}
