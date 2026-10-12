package com.spaceos.os;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Map;

/**
 * A persistent Android shell session.
 *
 * One {@code sh} process is kept alive for the life of the terminal, so state
 * that a shell is expected to hold — working directory, exported variables,
 * history of what you just did — survives between commands. That is the whole
 * reason this is not a series of {@code exec()} calls.
 *
 * There is no pty. Building one needs {@code forkpty} from native code, so
 * full-screen curses programs (vi, top) and job control will not work; this is
 * a line-oriented shell. Everything else behaves normally.
 *
 * It runs as the app's own uid under SELinux {@code untrusted_app}, exactly
 * like any terminal app on an unrooted device. It can read what Space OS is
 * allowed to read and nothing more.
 */
public class Shell {

    /** Marks the end of a command's output and carries its exit code + cwd. */
    private static final String SENTINEL = "__SPACEOS_RC__";

    public interface Sink {
        /** A line of output from the shell. */
        void onLine(String line);
        /** A command finished: its exit code and the resulting working dir. */
        void onComplete(int exitCode, String cwd);
        /** The shell died or could not be started. */
        void onDead(String reason);
    }

    private Process proc;
    private Writer stdin;
    private Thread reader;
    private volatile boolean alive;
    private volatile boolean busy;

    private final Sink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public Shell(Sink sink) { this.sink = sink; }

    public boolean isAlive() { return alive; }
    public boolean isBusy() { return busy; }

    // =====================================================================

    public void start(Context ctx) {
        try {
            ProcessBuilder pb = new ProcessBuilder(shellBinary());
            pb.redirectErrorStream(true);          // one stream is enough for a console

            Map<String, String> env = pb.environment();
            env.put("PATH", "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin"
                    + ":/data/local/bin");
            env.put("HOME", ctx.getFilesDir().getAbsolutePath());
            env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
            env.put("TERM", "dumb");               // no pty, so claim no capabilities
            env.put("PS1", "");

            File start = new File("/sdcard");
            if (!start.isDirectory() || !start.canRead()) start = ctx.getFilesDir();
            pb.directory(start);

            proc = pb.start();
            stdin = new OutputStreamWriter(proc.getOutputStream());
            alive = true;

            reader = new Thread(new Runnable() {
                @Override public void run() { pump(); }
            }, "spaceos-shell-out");
            reader.setDaemon(true);
            reader.start();

        } catch (Throwable t) {
            alive = false;
            post(new Runnable() {
                @Override public void run() {
                    sink.onDead("cannot spawn shell: " + t.getClass().getSimpleName());
                }
            });
        }
    }

    private static String shellBinary() {
        String[] candidates = {"/system/bin/sh", "/bin/sh", "sh"};
        for (String c : candidates) {
            if (c.equals("sh")) return c;          // let PATH resolve it as a last resort
            if (new File(c).canExecute()) return c;
        }
        return "sh";
    }

    /** Reads the merged output stream and splits it on the sentinel. */
    private void pump() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(proc.getInputStream()), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(SENTINEL)) {
                    // __SPACEOS_RC__ <code> <cwd>
                    int code = -1;
                    String cwd = "";
                    try {
                        String rest = line.substring(SENTINEL.length()).trim();
                        int sp = rest.indexOf(' ');
                        if (sp > 0) {
                            code = Integer.parseInt(rest.substring(0, sp));
                            cwd = rest.substring(sp + 1).trim();
                        } else {
                            code = Integer.parseInt(rest);
                        }
                    } catch (Throwable ignored) {}
                    busy = false;
                    final int fc = code; final String fcwd = cwd;
                    post(new Runnable() {
                        @Override public void run() { sink.onComplete(fc, fcwd); }
                    });
                } else {
                    final String fl = line;
                    post(new Runnable() {
                        @Override public void run() { sink.onLine(fl); }
                    });
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
            alive = false;
            busy = false;
            post(new Runnable() {
                @Override public void run() { sink.onDead("shell exited"); }
            });
        }
    }

    /**
     * Runs one command. The sentinel echo is appended on its own line so that
     * it still fires when the command fails, and carries $? plus the working
     * directory back to the console.
     */
    public void exec(String cmd) {
        if (!alive || stdin == null) {
            post(new Runnable() {
                @Override public void run() { sink.onDead("no shell"); }
            });
            return;
        }
        busy = true;
        try {
            stdin.write(cmd);
            stdin.write("\n");
            stdin.write("echo \"" + SENTINEL + " $? $(pwd)\"\n");
            stdin.flush();
        } catch (Throwable t) {
            busy = false;
            post(new Runnable() {
                @Override public void run() { sink.onDead("write failed"); }
            });
        }
    }

    /** Ctrl-C equivalent: there is no pty, so the process tree is torn down. */
    public void interrupt() {
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
    }

    public void stop() {
        alive = false;
        try { if (stdin != null) { stdin.write("exit\n"); stdin.flush(); } } catch (Throwable ignored) {}
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
        try { if (reader != null) reader.interrupt(); } catch (Throwable ignored) {}
    }

    private void post(Runnable r) { ui.post(r); }

    // =====================================================================
    // ONE-SHOT
    // =====================================================================

    /**
     * Runs a single command to completion and returns its combined output.
     * Used by the agent, which wants a result rather than a live session.
     * Blocking, with a hard timeout so a hung command cannot wedge the agent.
     */
    public static String once(String cmd, long timeoutMs) {
        if (cmd == null || cmd.trim().length() == 0) return "ERROR: empty command";
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(shellBinary(), "-c", cmd);
            pb.redirectErrorStream(true);
            pb.directory(new File("/sdcard").isDirectory()
                    ? new File("/sdcard") : new File("/"));
            p = pb.start();

            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    BufferedReader r = null;
                    try {
                        r = new BufferedReader(
                                new InputStreamReader(proc.getInputStream()), 8192);
                        String line;
                        while ((line = r.readLine()) != null && sb.length() < 12000) {
                            sb.append(line).append('\n');
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (r != null) try { r.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "spaceos-once");
            reader.setDaemon(true);
            reader.start();

            boolean finished = waitFor(p, timeoutMs);
            reader.join(600);

            String out = sb.toString();
            if (!finished) {
                p.destroy();
                return (out.length() > 0 ? out : "") + "\n[TIMEOUT after " + timeoutMs + "ms]";
            }
            int rc = p.exitValue();
            if (out.length() == 0) out = "(no output)";
            return rc == 0 ? out : out + "\n[exit " + rc + "]";

        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName() + " " + String.valueOf(t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** Process.waitFor(timeout) is API 26+; poll so this still works on 24. */
    private static boolean waitFor(Process p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException notDone) {
                try { Thread.sleep(40); } catch (InterruptedException e) { return false; }
            }
        }
        return false;
    }
}
