package com.spaceos.os;

import android.content.Context;
import android.os.Build;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

/**
 * Cellular state for the status strip.
 *
 * Deliberately built without READ_PHONE_STATE. That is a runtime permission
 * with a prompt attached, and asking for it to put four little bars on a home
 * screen is not a fair trade — so this reads only what is public: the SIM
 * state, the operator name, and whatever a signal-strength listener hands over
 * unasked. Registering for signal strength has never required a permission;
 * calling getSignalStrength() directly does, which is why this listens instead.
 *
 * Everything is cached from callbacks, so reading it is free and can happen on
 * the draw path.
 */
public final class Cell {

    private Cell() {}

    /** 0..4 bars, or -1 before the first callback arrives. */
    private static volatile int level = -1;
    /** Whether the radio reports itself in service. */
    private static volatile boolean inService = true;
    private static volatile boolean started;

    private static Object callback;         // TelephonyCallback (API 31+)
    private static android.telephony.PhoneStateListener listener;

    public static int level() { return level; }
    public static boolean inService() { return inService; }

    /** Registers the listeners. Safe to call repeatedly. */
    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        try {
            final TelephonyManager tm = (TelephonyManager)
                    ctx.getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;

            if (Build.VERSION.SDK_INT >= 31) {
                callback = new Api31Callback();
                tm.registerTelephonyCallback(
                        ctx.getApplicationContext().getMainExecutor(),
                        (android.telephony.TelephonyCallback) callback);
            } else {
                listener = new android.telephony.PhoneStateListener() {
                    @Override public void onSignalStrengthsChanged(SignalStrength s) {
                        if (s != null) level = s.getLevel();
                    }
                    @Override public void onServiceStateChanged(ServiceState s) {
                        if (s != null) inService = s.getState() == ServiceState.STATE_IN_SERVICE;
                    }
                };
                tm.listen(listener,
                        android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                                | android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE);
            }
        } catch (Throwable t) {
            Journal.warn("cell", "cannot watch the radio: " + t.getMessage());
        }
    }

    private static class Api31Callback extends android.telephony.TelephonyCallback
            implements android.telephony.TelephonyCallback.SignalStrengthsListener,
                       android.telephony.TelephonyCallback.ServiceStateListener {
        @Override public void onSignalStrengthsChanged(SignalStrength s) {
            if (s != null) level = s.getLevel();
        }
        @Override public void onServiceStateChanged(ServiceState s) {
            if (s != null) inService = s.getState() == ServiceState.STATE_IN_SERVICE;
        }
    }

    /** NO SIM / NO SERVICE / the network generation, whichever applies. */
    public static String label(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager)
                    ctx.getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null || tm.getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
                return null;                       // tablet with no radio
            }
            int sim = tm.getSimState();
            if (sim == TelephonyManager.SIM_STATE_ABSENT) return "NO SIM";
            if (sim != TelephonyManager.SIM_STATE_READY) return "SIM LOCKED";
            if (!inService) return "NO SERVICE";

            String gen = generation(tm);
            if (gen != null) return gen;

            // getDataNetworkType needs READ_PHONE_STATE on newer releases, so
            // when it is refused fall back to the carrier's own name — which
            // is public, and is what the user actually recognises anyway.
            String op = tm.getNetworkOperatorName();
            if (op != null && op.trim().length() > 0) {
                return op.trim().toUpperCase(java.util.Locale.US);
            }
            return "CELL";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String generation(TelephonyManager tm) {
        try {
            int t = Build.VERSION.SDK_INT >= 24 ? tm.getDataNetworkType() : 0;
            switch (t) {
                case TelephonyManager.NETWORK_TYPE_NR:      return "5G";
                case TelephonyManager.NETWORK_TYPE_LTE:     return "LTE";
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_UMTS:    return "3G";
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS:    return "2G";
                default: return null;
            }
        } catch (Throwable t) {
            return null;     // SecurityException without READ_PHONE_STATE
        }
    }
}
