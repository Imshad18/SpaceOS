package com.spaceos.os;

import android.content.Context;

/**
 * Process-wide handle on the conversation engine and its memory.
 *
 * Both are expensive to build — the corpus is parsed and the whole history is
 * replayed into the n-gram chain — so they are constructed once and shared by
 * the bridge and the memory panel rather than rebuilt per screen.
 */
public final class Mind {

    private static Brain brain;
    private static Memory memory;
    private static boolean building;

    private Mind() {}

    public static synchronized Brain brain() { return brain; }
    public static synchronized Memory memory() { return memory; }
    public static synchronized boolean ready() { return brain != null && memory != null; }

    /** Builds on the calling thread. Call from a worker, never the main one. */
    public static void build(Context ctx) {
        synchronized (Mind.class) {
            if (brain != null || building) return;
            building = true;
        }
        Memory m = null;
        Brain b = null;
        try {
            m = new Memory(ctx.getApplicationContext());
            b = new Brain();
            b.load(ctx.getApplicationContext(), m);
        } catch (Throwable ignored) {}
        synchronized (Mind.class) {
            memory = m;
            brain = b;
            building = false;
        }
    }

    /** Builds in the background if it has not been built already. */
    public static void buildAsync(final Context ctx) {
        if (ready()) return;
        new Thread(new Runnable() {
            @Override public void run() { build(ctx); }
        }, "spaceos-mind").start();
    }

    public static void flush() {
        Memory m = memory();
        if (m == null) return;
        final Memory target = m;
        new Thread(new Runnable() {
            @Override public void run() { target.flush(); }
        }, "spaceos-mind-flush").start();
    }
}
