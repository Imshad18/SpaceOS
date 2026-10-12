package com.spaceos.os;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The voice interface. Speech in via {@link SpeechRecognizer}, speech out via
 * {@link TextToSpeech}.
 *
 * Recognition is not guaranteed to exist: it is provided by an installed
 * engine (usually the Google app) and most engines need a network round trip.
 * {@link #available()} reports the truth so the caller can fall back to the
 * system voice picker instead of pretending to listen.
 */
public class Voice {

    public static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, ERROR = 4;

    public interface Host {
        /** Live partial transcript while the user is still speaking. */
        void onPartial(String text);
        /** Final transcript — route this as a command. */
        void onHeard(String text);
        /** Microphone level, roughly 0..1, for the listening visual. */
        void onLevel(float level);
        /** State changed; use for the overlay's status line. */
        void onVoiceState(int state, String message);
    }

    private final Context ctx;
    private final Host host;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private int state = IDLE;

    /**
     * Continuous mode. SpeechRecognizer always stops after one result, so
     * "keep listening" means restarting it — but never while we are speaking,
     * or the microphone hears the reply and answers itself.
     */
    private boolean continuous;
    private boolean speaking;
    private boolean wantListening;
    private final Handler loop = new Handler(Looper.getMainLooper());

    public void setContinuous(boolean on) { continuous = on; }
    public boolean isContinuous() { return continuous; }

    /**
     * Whether the microphone is genuinely live right now — not merely that we
     * asked for it once. The watchdog needs the truth, because the recogniser
     * dies on its own and used to leave the pill waiting for a tap.
     */
    public boolean isListening() {
        // Handler.hasCallbacks is API 29 and this app supports 24, so the
        // pending restart is tracked explicitly.
        return wantListening && !speaking && (recognizer != null || restartPending);
    }

    private volatile boolean restartPending;

    /** Called by the host once it has finished handling a result. */
    public void resumeListening() {
        if (!continuous || !wantListening || speaking) return;
        loop.removeCallbacks(restart);
        restartPending = true;
        loop.postDelayed(restart, 350);
    }

    private final Runnable restart = new Runnable() {
        @Override public void run() {
            restartPending = false;
            if (!continuous || !wantListening || speaking) return;
            listen();
        }
    };

    public Voice(Context ctx, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
    }

    public int state() { return state; }

    public boolean available() {
        try { return SpeechRecognizer.isRecognitionAvailable(ctx); }
        catch (Throwable t) { return false; }
    }

    // =====================================================================
    // OUTPUT
    // =====================================================================

    /**
     * TextToSpeech takes a second or two to bind to its engine. Anything asked
     * for before that used to be dropped on the floor, which is why the very
     * first reply was silent. Now it is held and spoken the moment the engine
     * reports ready.
     */
    private String pendingUtterance;

    public boolean ttsReady() { return ttsReady; }

    public void initTts() {
        if (tts != null) return;
        try {
            tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    if (!ttsReady) {
                        set(ERROR, "no speech engine installed");
                        return;
                    }
                    try {
                        int lang = tts.setLanguage(Locale.US);
                        if (lang == TextToSpeech.LANG_MISSING_DATA
                                || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                            // engine exists but has no voice data for en-US
                            tts.setLanguage(Locale.getDefault());
                        }
                        tts.setPitch(0.88f);      // a shade below default
                        tts.setSpeechRate(1.02f);
                    } catch (Throwable ignored) {}

                    // flush anything that was asked for while we were binding
                    String held = pendingUtterance;
                    pendingUtterance = null;
                    if (held != null) say(held);
                }
            });
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    speaking = true;
                    set(SPEAKING, null);
                }
                @Override public void onDone(String id) { doneSpeaking(); }
                @Override public void onError(String id) { doneSpeaking(); }
            });
        } catch (Throwable ignored) {}
    }

    public void say(String text) {
        if (text == null || text.length() == 0) { doneSpeaking(); return; }

        if (tts == null) initTts();

        if (!ttsReady) {
            // Engine still binding. Hold the line rather than dropping it —
            // onInit will speak it. Only the newest reply is worth keeping.
            //
            // Critically, hand the microphone back too. Returning here without
            // resumeListening() stalled the whole continuous loop whenever the
            // speech engine was slow to bind, which looked exactly like the
            // mic having died.
            pendingUtterance = text;
            resumeListening();
            return;
        }
        try {
            stopListening();            // never record ourselves
            speaking = true;
            set(SPEAKING, null);
            int rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "spaceos");
            if (rc != TextToSpeech.SUCCESS) doneSpeaking();
        } catch (Throwable ignored) {
            doneSpeaking();
        }
    }

    /** Reply finished: hand the microphone back if we are in continuous mode. */
    private void doneSpeaking() {
        speaking = false;
        set(IDLE, null);
        resumeListening();
    }

    public void shutUp() {
        speaking = false;
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
    }

    // =====================================================================
    // INPUT
    // =====================================================================

    public void listen() {
        stopListening();
        wantListening = true;
        if (!available()) {
            set(ERROR, "no recognition engine");
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { set(LISTENING, "listening"); }
                @Override public void onBeginningOfSpeech() { set(LISTENING, "receiving"); }

                @Override public void onRmsChanged(float rms) {
                    // engines report roughly -2..10 dB; normalise for the visual
                    float level = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
                    host.onLevel(level);
                }

                @Override public void onBufferReceived(byte[] buf) {}
                @Override public void onEndOfSpeech() { set(THINKING, "parsing"); }

                @Override public void onError(int code) {
                    // Silence and no-match are normal in continuous mode —
                    // the user simply is not talking. Restart quietly rather
                    // than showing a fault every few seconds.
                    boolean benign = code == SpeechRecognizer.ERROR_NO_MATCH
                            || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    if (continuous && benign) {
                        set(LISTENING, "listening");
                        resumeListening();
                        return;
                    }
                    Journal.voice("asr", errorText(code));
                    set(ERROR, errorText(code));
                    if (continuous && code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        resumeListening();
                    }
                }

                @Override public void onResults(Bundle results) {
                    List<String> hits = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (hits != null && !hits.isEmpty()) {
                        set(THINKING, null);
                        host.onHeard(hits.get(0));
                    } else if (continuous) {
                        set(LISTENING, "listening");
                        resumeListening();
                    } else {
                        set(ERROR, "nothing heard");
                    }
                }

                @Override public void onPartialResults(Bundle partial) {
                    List<String> hits = partial.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (hits != null && !hits.isEmpty()) host.onPartial(hits.get(0));
                }

                @Override public void onEvent(int type, Bundle params) {}
            });

            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
            recognizer.startListening(i);
            set(LISTENING, "listening");
        } catch (Throwable t) {
            set(ERROR, "mic unavailable");
        }
    }

    public void stopListening() {
        try {
            if (recognizer != null) {
                recognizer.cancel();
                recognizer.destroy();
            }
        } catch (Throwable ignored) {}
        recognizer = null;
    }

    /** Fully leaves continuous mode — used when the overlay is dismissed. */
    public void standDown() {
        wantListening = false;
        restartPending = false;
        loop.removeCallbacks(restart);
        stopListening();
        shutUp();
        set(IDLE, null);
    }

    public void release() {
        wantListening = false;
        loop.removeCallbacks(restart);
        stopListening();
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        tts = null;
        ttsReady = false;
    }

    private void set(int s, String msg) {
        state = s;
        host.onVoiceState(s, msg);
    }

    private static String errorText(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio fault";
            case SpeechRecognizer.ERROR_CLIENT: return "client fault";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "mic denied";
            case SpeechRecognizer.ERROR_NETWORK: return "no network";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "not understood";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "engine busy";
            case SpeechRecognizer.ERROR_SERVER: return "server fault";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no speech";
            default: return "fault " + code;
        }
    }

    /** Fallback when no inline engine exists: hand off to the system picker. */
    public static Intent pickerIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "SPACE OS :: AWAITING ORDERS");
        return i;
    }

    public static List<String> resultsFrom(Intent data) {
        if (data == null) return new ArrayList<String>();
        List<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        return r == null ? new ArrayList<String>() : r;
    }
}
