package com.spaceos.os;

import android.graphics.drawable.Drawable;

/** One line of readout in a module console. */
public class Row {
    public String title = "";
    public String sub = "";
    public String value = "";
    public Drawable icon;
    public int accent = Skin.PLASMA;
    public Runnable action;
    /** 0..1 — draws an inline gauge under the row when >= 0. */
    public float gauge = -1f;

    public Row(String title) { this.title = title; }

    public Row(String title, String value) { this.title = title; this.value = value; }

    public Row sub(String s) { this.sub = s; return this; }
    public Row value(String s) { this.value = s; return this; }
    public Row icon(Drawable d) { this.icon = d; return this; }
    public Row accent(int c) { this.accent = c; return this; }
    public Row gauge(float g) { this.gauge = g; return this; }
    public Row on(Runnable r) { this.action = r; return this; }
}
