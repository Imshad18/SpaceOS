package com.spaceos.os;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.List;
import java.util.Locale;

/**
 * Alternate bridge layouts.
 *
 * Each one arranges the same live telemetry and the same module set into a
 * completely different instrument. Geometry is written into the caller's rect
 * array so hit-testing keeps working without every layout re-implementing
 * touch.
 */
public final class Layouts {

    private Layouts() {}

    // =====================================================================
    // ORBITAL — modules orbit a world, telemetry rides the orbital rings
    // =====================================================================

    public static void layoutOrbital(RectF[] tiles, int w, int h, float top, float bottom) {
        float cx = w / 2f, cy = (top + bottom) / 2f;
        float span = Math.min(w, bottom - top);
        float inner = span * 0.30f, outer = span * 0.44f;
        float tile = Skin.dp(34);

        int n = tiles.length;
        int innerCount = Math.min(6, n);
        int outerCount = n - innerCount;

        for (int i = 0; i < innerCount; i++) {
            double a = Math.toRadians(-90 + i * (360.0 / innerCount));
            float x = cx + (float) Math.cos(a) * inner;
            float y = cy + (float) Math.sin(a) * inner;
            tiles[i].set(x - tile / 2, y - tile / 2, x + tile / 2, y + tile / 2);
        }
        for (int i = 0; i < outerCount; i++) {
            double a = Math.toRadians(-90 + (i + 0.5) * (360.0 / Math.max(1, outerCount)));
            float x = cx + (float) Math.cos(a) * outer;
            float y = cy + (float) Math.sin(a) * outer;
            tiles[innerCount + i].set(x - tile / 2, y - tile / 2, x + tile / 2, y + tile / 2);
        }
    }

    public static void drawOrbital(Canvas c, int w, int h, float top, float bottom,
                                   float phase, float load, Sys.Power power,
                                   Sys.Mem mem, Sys.Store store) {
        float cx = w / 2f, cy = (top + bottom) / 2f;
        float span = Math.min(w, bottom - top);
        float planet = span * 0.155f;

        // orbital paths
        Paint orbit = Skin.stroke(0.9f, Skin.alpha(Skin.EDGE, 0.85f));
        c.drawCircle(cx, cy, span * 0.30f, orbit);
        c.drawCircle(cx, cy, span * 0.44f, orbit);

        // telemetry as arcs hugging the planet
        arc(c, cx, cy, planet * 1.45f, 140, 250 * mem.frac, Skin.level(mem.frac), 4f);
        arc(c, cx, cy, planet * 1.62f, 140, 250 * store.frac, Skin.level(store.frac), 4f);
        float pf = power.level < 0 ? 0 : power.level / 100f;
        arc(c, cx, cy, planet * 1.79f, 140, 250 * pf, Skin.level(1f - pf), 4f);

        // the world: terminator shading plus latitude bands
        Paint body = Skin.fill(Skin.alpha(Skin.PLASMA_D, 0.30f));
        c.drawCircle(cx, cy, planet, body);
        c.drawCircle(cx, cy, planet, Skin.stroke(1.4f, Skin.PLASMA));

        c.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, planet, Path.Direction.CW);
        c.clipPath(clip);
        Paint band = Skin.stroke(1f, Skin.alpha(Skin.PLASMA, 0.32f));
        for (int i = -3; i <= 3; i++) {
            float y = cy + i * planet * 0.32f;
            c.drawArc(new RectF(cx - planet * 1.5f, y - planet * 0.4f,
                    cx + planet * 1.5f, y + planet * 0.4f), 195, 150, false, band);
        }
        // rotating terminator
        float term = (float) Math.sin(phase * 0.02f) * planet;
        c.drawArc(new RectF(cx - Math.abs(term), cy - planet,
                        cx + Math.abs(term), cy + planet),
                90, 180, false, Skin.stroke(1.2f, Skin.alpha(Skin.PLASMA, 0.5f)));
        c.restore();

        // core load in the middle of the world
        Paint big = Skin.text(20, Skin.level(load), true);
        Skin.textCenter(c, Sys.pct(load), cx, cy + Skin.dp(6), big);

        // a moon on the outer ring
        double ma = Math.toRadians(phase * 0.7);
        float mx = cx + (float) Math.cos(ma) * span * 0.44f;
        float my = cy + (float) Math.sin(ma) * span * 0.44f;
        c.drawCircle(mx, my, Skin.dp(3.5f), Skin.fill(Skin.SOLAR));
    }

    // =====================================================================
    // TACTICAL — range rings, modules as contacts on bearings
    // =====================================================================

    public static void layoutTactical(RectF[] tiles, int w, int h, float top, float bottom) {
        float cx = w / 2f, cy = (top + bottom) / 2f;
        float span = Math.min(w, bottom - top);
        float tile = Skin.dp(32);
        int n = tiles.length;

        // three concentric rings, denser further out — reads like a scope
        int[] ringCounts = {4, 6, n - 10 < 0 ? 0 : n - 10};
        float[] radii = {span * 0.17f, span * 0.30f, span * 0.43f};
        int idx = 0;
        for (int r = 0; r < 3 && idx < n; r++) {
            int count = r == 2 ? n - idx : Math.min(ringCounts[r], n - idx);
            if (count <= 0) continue;
            for (int i = 0; i < count && idx < n; i++, idx++) {
                double a = Math.toRadians(-90 + r * 18 + i * (360.0 / count));
                float x = cx + (float) Math.cos(a) * radii[r];
                float y = cy + (float) Math.sin(a) * radii[r];
                tiles[idx].set(x - tile / 2, y - tile / 2, x + tile / 2, y + tile / 2);
            }
        }
    }

    public static void drawTactical(Canvas c, int w, int h, float top, float bottom,
                                    float phase, float load, Sys.Link link) {
        float cx = w / 2f, cy = (top + bottom) / 2f;
        float span = Math.min(w, bottom - top);
        float maxR = span * 0.48f;

        // range rings
        Paint ring = Skin.stroke(0.9f, Skin.alpha(Skin.EDGE, 0.9f));
        for (int i = 1; i <= 4; i++) c.drawCircle(cx, cy, maxR * i / 4f, ring);

        // bearing spokes
        Paint spoke = Skin.stroke(0.7f, Skin.alpha(Skin.EDGE, 0.6f));
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30);
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * maxR,
                    cy + (float) Math.sin(a) * maxR, spoke);
        }

        // sweep with a decaying wake
        float sweepDeg = phase * 2.4f % 360f;
        for (int t = 0; t < 26; t++) {
            float a = sweepDeg - t * 2.4f;
            float alpha = (1f - t / 26f) * 0.5f;
            Paint p = Skin.stroke(1.6f, Skin.alpha(Skin.PLASMA, alpha));
            double r = Math.toRadians(a);
            c.drawLine(cx, cy, cx + (float) Math.cos(r) * maxR,
                    cy + (float) Math.sin(r) * maxR, p);
        }

        // bearing labels
        Paint lab = Skin.text(7, Skin.TEXT_DIM, true);
        String[] marks = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(-90 + i * 90);
            Skin.textCenter(c, marks[i],
                    cx + (float) Math.cos(a) * (maxR + Skin.dp(10)),
                    cy + (float) Math.sin(a) * (maxR + Skin.dp(10)) + Skin.dp(3), lab);
        }

        // centre readout
        Paint big = Skin.text(17, Skin.level(load), true);
        Skin.textCenter(c, Sys.pct(load), cx, cy - Skin.dp(2), big);
        Paint sub = Skin.text(6.5f, Skin.TEXT_DIM, false);
        sub.setLetterSpacing(0.2f);
        Skin.textCenter(c, link.online ? link.type : "NO LINK", cx, cy + Skin.dp(10), sub);
    }

    // =====================================================================
    // MINIMAL — the time, and almost nothing else
    // =====================================================================

    public static void layoutMinimal(RectF[] tiles, int w, int h, float top, float bottom) {
        // only the first eight modules get a slot; the rest are unreachable
        // here by design — that is what makes it minimal
        float pad = Skin.dp(16);
        int cols = 4, rows = 2;
        float gap = Skin.dp(9);
        float gridTop = bottom - Skin.dp(150);
        float tw = (w - pad * 2 - gap * (cols - 1)) / cols;
        float th = Skin.dp(60);

        for (int i = 0; i < tiles.length; i++) {
            if (i >= cols * rows) { tiles[i].setEmpty(); continue; }
            int r = i / cols, cc = i % cols;
            float x = pad + cc * (tw + gap);
            float y = gridTop + r * (th + gap);
            tiles[i].set(x, y, x + tw, y + th);
        }
    }

    public static void drawMinimal(Canvas c, int w, int h, float top, float bottom,
                                   String clock, String date, float load,
                                   Sys.Power power, Sys.Mem mem, Sys.Store store) {
        float cx = w / 2f;
        float cy = top + (bottom - top) * 0.26f;

        Paint big = Skin.text(72, Skin.TEXT, false);
        big.setLetterSpacing(-0.02f);
        Skin.textCenter(c, clock, cx, cy, big);

        Paint d = Skin.text(10, Skin.TEXT_DIM, false);
        d.setLetterSpacing(0.34f);
        Skin.textCenter(c, date.toUpperCase(Locale.US), cx, cy + Skin.dp(22), d);

        // three hairline readouts, no chrome at all
        float y = cy + Skin.dp(66);
        float barW = w * 0.56f;
        thinBar(c, cx - barW / 2, y, barW, "PWR",
                power.level < 0 ? 0 : power.level / 100f,
                power.level < 0 ? "--" : power.level + "%");
        thinBar(c, cx - barW / 2, y + Skin.dp(22), barW, "MEM", mem.frac,
                Math.round(mem.frac * 100) + "%");
        thinBar(c, cx - barW / 2, y + Skin.dp(44), barW, "DSK", store.frac,
                Math.round(store.frac * 100) + "%");
    }

    private static void thinBar(Canvas c, float x, float y, float w,
                                String label, float frac, String value) {
        Paint l = Skin.text(7, Skin.TEXT_DIM, true);
        l.setLetterSpacing(0.2f);
        c.drawText(label, x, y + Skin.dp(3), l);

        float barX = x + Skin.dp(28);
        float barW = w - Skin.dp(28) - Skin.dp(38);
        c.drawLine(barX, y, barX + barW, y, Skin.stroke(1f, Skin.alpha(Skin.EDGE, 1f)));
        c.drawLine(barX, y, barX + barW * Math.max(0f, Math.min(1f, frac)), y,
                Skin.stroke(1.6f, Skin.PLASMA));

        Paint v = Skin.text(7.5f, Skin.TEXT, true);
        Skin.textRight(c, value, x + w, y + Skin.dp(3), v);
    }

    // =====================================================================
    // MAINFRAME — a scrolling readout, modules as a numbered menu
    // =====================================================================

    public static void layoutMainframe(RectF[] tiles, int w, int h,
                                       float top, float bottom) {
        float pad = Skin.dp(14);
        float rowH = Skin.dp(21);
        int perCol = (int) Math.max(1, (bottom - top) / rowH);
        int cols = (int) Math.ceil(tiles.length / (float) perCol);
        float colW = (w - pad * 2) / Math.max(1, cols);

        for (int i = 0; i < tiles.length; i++) {
            int col = i / perCol, r = i % perCol;
            float x = pad + col * colW;
            float y = top + r * rowH;
            tiles[i].set(x, y, x + colW - Skin.dp(6), y + rowH - Skin.dp(3));
        }
    }

    public static void drawMainframeHeader(Canvas c, int w, float top, float phase,
                                           float load, Sys.Power power, Sys.Mem mem,
                                           Sys.Store store, Sys.Link link, float thermal) {
        Paint mono = Skin.text(8.5f, Skin.ION, false);
        float x = Skin.dp(14);
        float y = top;
        float lh = Skin.dp(12);

        String[] lines = {
                "SPACEOS> diagnostic --all",
                "  core.load    " + bar20(load) + " " + Sys.pct(load),
                "  memory       " + bar20(mem.frac) + " " + Sys.bytes(mem.used)
                        + "/" + Sys.bytes(mem.total),
                "  archive      " + bar20(store.frac) + " " + Sys.bytes(store.free) + " free",
                "  power        " + bar20(power.level < 0 ? 0 : power.level / 100f)
                        + " " + (power.level < 0 ? "--" : power.level + "%")
                        + (power.charging ? " CHG" : ""),
                "  thermal      " + (Float.isNaN(thermal) ? "sealed" : Math.round(thermal) + "C"),
                "  uplink       " + (link.online ? link.type.toLowerCase(Locale.US) : "down")
                        + "  " + link.ip,
                "  uptime       " + Sys.duration(Sys.uptimeMs()),
                "SPACEOS> _",
        };
        for (int i = 0; i < lines.length; i++) {
            boolean prompt = lines[i].startsWith("SPACEOS>");
            mono.setColor(prompt ? Skin.PLASMA : Skin.ION);
            String text = lines[i];
            if (i == lines.length - 1 && ((int) (phase * 0.5f) % 2 == 0)) {
                text = text.substring(0, text.length() - 1);
            }
            c.drawText(text, x, y + i * lh, mono);
        }
    }

    private static String bar20(float f) {
        f = Math.max(0f, Math.min(1f, f));
        int lit = Math.round(f * 20);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) sb.append(i < lit ? '#' : '.');
        return sb.append(']').toString();
    }

    // =====================================================================
    // REACTOR — one enormous core, modules ringed around the rim
    // =====================================================================

    public static void layoutReactor(RectF[] tiles, int w, int h, float top, float bottom) {
        float cx = w / 2f, cy = (top + bottom) / 2f;
        float r = Math.min(w, bottom - top) * 0.455f;
        float tile = Skin.dp(30);
        int n = tiles.length;
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(-90 + i * (360.0 / n));
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            tiles[i].set(x - tile / 2, y - tile / 2, x + tile / 2, y + tile / 2);
        }
    }

    public static void drawReactor(Canvas c, int w, int h, float top, float bottom,
                                   float phase, float load, Sys.Power power,
                                   Sys.Mem mem, Sys.Store store, float thermal) {
        float cx = w / 2f, cy = (top + bottom) / 2f;
        float r = Math.min(w, bottom - top) * 0.33f;

        // containment rings, counter-rotating
        for (int k = 0; k < 3; k++) {
            float rr = r * (1.06f + k * 0.09f);
            int ticks = 48 + k * 16;
            float rot = phase * (k % 2 == 0 ? 0.5f : -0.35f);
            Paint p = Skin.stroke(1f, Skin.alpha(k == 0 ? Skin.PLASMA : Skin.EDGE, 0.85f));
            for (int i = 0; i < ticks; i++) {
                double a = Math.toRadians(rot + i * (360.0 / ticks));
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                float len = i % 8 == 0 ? Skin.dp(8) : Skin.dp(3);
                c.drawLine(cx + ca * rr, cy + sa * rr,
                        cx + ca * (rr + len), cy + sa * (rr + len), p);
            }
        }

        // plasma core: nested pulsing discs
        float pulse = 1f + 0.03f * (float) Math.sin(phase * 0.12f);
        c.drawCircle(cx, cy, r * pulse, Skin.fill(Skin.alpha(Skin.PLASMA, 0.07f)));
        c.drawCircle(cx, cy, r * 0.80f * pulse, Skin.fill(Skin.alpha(Skin.PLASMA, 0.10f)));
        c.drawCircle(cx, cy, r * 0.55f * pulse, Skin.fill(Skin.alpha(Skin.PLASMA, 0.16f)));
        c.drawCircle(cx, cy, r, Skin.stroke(1.6f, Skin.PLASMA));

        // containment arcs driven by the three main gauges
        arc(c, cx, cy, r * 0.90f, 150, 240 * load, Skin.level(load), 5f);
        arc(c, cx, cy, r * 0.74f, 150, 240 * mem.frac, Skin.level(mem.frac), 4f);
        float pf = power.level < 0 ? 0 : power.level / 100f;
        arc(c, cx, cy, r * 0.58f, 150, 240 * pf, Skin.level(1f - pf), 4f);

        // energy filaments
        Paint fil = Skin.stroke(1f, Skin.alpha(Skin.PLASMA, 0.5f));
        for (int i = 0; i < 9; i++) {
            double a0 = Math.toRadians(phase * 1.1 + i * 40);
            double a1 = a0 + Math.PI * (0.55 + 0.35 * Math.sin(phase * 0.05 + i));
            Path pth = new Path();
            pth.moveTo(cx + (float) Math.cos(a0) * r * 0.5f,
                    cy + (float) Math.sin(a0) * r * 0.5f);
            pth.quadTo(cx, cy,
                    cx + (float) Math.cos(a1) * r * 0.5f,
                    cy + (float) Math.sin(a1) * r * 0.5f);
            c.drawPath(pth, fil);
        }

        Paint big = Skin.text(46, Skin.level(load), true);
        Skin.textCenter(c, Sys.pct(load), cx, cy + Skin.dp(10), big);
        Paint cap = Skin.text(8, Skin.TEXT_DIM, false);
        cap.setLetterSpacing(0.36f);
        Skin.textCenter(c, "REACTOR OUTPUT", cx, cy + Skin.dp(28), cap);
        Paint th = Skin.text(9, Float.isNaN(thermal) ? Skin.TEXT_DIM : Skin.level(thermal / 90f), true);
        Skin.textCenter(c, Float.isNaN(thermal) ? "THERMAL SEALED"
                : Math.round(thermal) + "C CORE", cx, cy - Skin.dp(34), th);
    }

    // =====================================================================
    // HEXCELL — honeycomb of module cells under a row of hex gauges
    // =====================================================================

    /** Height of the gauge band reserved above the comb. */
    private static float hexHeader() { return Skin.dp(62); }

    public static void layoutHexcell(RectF[] tiles, int w, int h, float top, float bottom) {
        int n = tiles.length;
        float pad = Skin.dp(12);
        float combTop = top + hexHeader();
        float fw = w - pad * 2, fh = bottom - combTop;
        int cols = 4;

        // rows alternate 4 / 3, which is what makes a comb rather than a grid
        int rows = 0, placed = 0;
        while (placed < n) { placed += (rows % 2 == 0) ? cols : cols - 1; rows++; }

        // A pointy-top hex of radius s is s*sqrt(3) wide and 2s tall, and rows
        // nest at 1.5s. Take whichever constraint binds first and keep the
        // cells REGULAR — stretching them vertically to swallow leftover space
        // filled the field and made every icon look squashed, which is a worse
        // trade than a little air at the edges.
        float size = Math.min(fw / cols / 1.7320508f,
                              fh / (2f + 1.5f * (rows - 1)));
        float hexW = size * 1.7320508f;
        float rowStep = size * 1.5f;

        float y0 = combTop + (fh - size * (2f + 1.5f * (rows - 1))) / 2f + size;
        float x0 = pad + (fw - cols * hexW) / 2f;

        int idx = 0;
        for (int r = 0; r < rows && idx < n; r++) {
            int count = (r % 2 == 0) ? cols : cols - 1;
            float rowX = x0 + ((r % 2 == 0) ? hexW / 2f : hexW);
            for (int i = 0; i < count && idx < n; i++, idx++) {
                float cx = rowX + i * hexW;
                float cy = y0 + r * rowStep;
                // bounding box of the hex, so hit-testing needs no extra state
                tiles[idx].set(cx - hexW / 2f, cy - size, cx + hexW / 2f, cy + size);
            }
        }
        for (; idx < n; idx++) tiles[idx].setEmpty();
    }

    /** Pointy-top hexagon inscribed in the given bounding box. */
    public static Path hexPath(RectF r) {
        float cx = r.centerX(), cy = r.centerY();
        float ry = r.height() / 2f, rx = r.width() / 2f;
        Path p = new Path();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(-90 + i * 60);
            float x = cx + (float) Math.cos(a) * rx * 1.1547f;  // 1/cos(30)
            float y = cy + (float) Math.sin(a) * ry;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    public static void drawHexcell(Canvas c, int w, int h, float top, float bottom,
                                   float phase, float load, Sys.Power power,
                                   Sys.Mem mem, Sys.Store store) {
        float pad = Skin.dp(12);
        float band = hexHeader();
        float cy = top + band * 0.46f;
        float cellW = (w - pad * 2) / 3f;
        float rad = Math.min(cellW * 0.30f, band * 0.34f);

        float pf = power.level < 0 ? 0 : power.level / 100f;
        float[] vals = {load, mem.frac, pf};
        int[] cols = {Skin.level(load), Skin.level(mem.frac), Skin.level(1f - pf)};
        String[] names = {"CORE", "MEM", "PWR"};
        String[] reads = {Sys.pct(load), Math.round(mem.frac * 100) + "%",
                power.level < 0 ? "--" : power.level + "%"};

        for (int i = 0; i < 3; i++) {
            float hx = pad + cellW * (i + 0.5f);
            RectF box = new RectF(hx - rad * 0.866f, cy - rad,
                    hx + rad * 0.866f, cy + rad);

            // the hex fills from the bottom in proportion to the reading
            c.save();
            c.clipPath(hexPath(box));
            float fillTop = box.bottom - box.height() * Math.max(0f, Math.min(1f, vals[i]));
            c.drawRect(box.left, fillTop, box.right, box.bottom,
                    Skin.fill(Skin.alpha(cols[i], 0.30f)));
            c.restore();

            c.drawPath(hexPath(box), Skin.stroke(1.3f, cols[i]));
            Skin.textCenter(c, reads[i], hx, cy + Skin.dp(3),
                    Skin.text(9.5f, cols[i], true));
            Paint n = Skin.text(6.5f, Skin.TEXT_DIM, false);
            n.setLetterSpacing(0.28f);
            Skin.textCenter(c, names[i], hx, cy + rad + Skin.dp(11), n);
        }

        // a slow shimmer travelling across the comb, so it is not inert
        float sweep = (phase * 0.9f) % (w + Skin.dp(160)) - Skin.dp(80);
        Paint gl = Skin.stroke(Skin.dp(30), Skin.alpha(Skin.PLASMA, 0.035f));
        c.drawLine(sweep, top + band, sweep - Skin.dp(50), bottom, gl);
    }

    // =====================================================================
    // CONSTELLATION — modules as stars, wired into a figure
    // =====================================================================

    public static void layoutConstellation(RectF[] tiles, int w, int h,
                                           float top, float bottom) {
        int n = tiles.length;
        float pad = Skin.dp(20);
        float fieldTop = top + Skin.dp(30);
        int cols = 3;
        int rows = (int) Math.ceil(n / (float) cols);
        float cw = (w - pad * 2) / cols;
        float ch = (bottom - fieldTop) / rows;
        float tile = Math.min(Skin.dp(30), Math.min(cw, ch) * 0.52f);

        // jittered off a grid: scattered enough to read as a sky, constrained
        // enough that two stars can never land on top of each other
        for (int i = 0; i < n; i++) {
            int r = i / cols, cc = i % cols;
            long seed = (i + 1) * 2654435761L;
            float jx = (Math.abs(seed >> 7) % 1000) / 1000f - 0.5f;
            float jy = (Math.abs(seed >> 19) % 1000) / 1000f - 0.5f;
            float cx = pad + cc * cw + cw / 2f + jx * (cw - tile) * 0.8f;
            float cy = fieldTop + r * ch + ch / 2f + jy * (ch - tile) * 0.8f;
            tiles[i].set(cx - tile / 2, cy - tile / 2, cx + tile / 2, cy + tile / 2);
        }
    }

    public static void drawConstellation(Canvas c, int w, int h, float top, float bottom,
                                         RectF[] tiles, float phase, float load,
                                         Sys.Mem mem, Sys.Power power, Sys.Link link) {
        // the figure: consecutive stars wired together, plus a few long chords
        Paint line = Skin.stroke(0.9f, Skin.alpha(Skin.PLASMA, 0.30f));
        for (int i = 1; i < tiles.length; i++) {
            if (tiles[i].isEmpty() || tiles[i - 1].isEmpty()) continue;
            c.drawLine(tiles[i - 1].centerX(), tiles[i - 1].centerY(),
                    tiles[i].centerX(), tiles[i].centerY(), line);
        }
        Paint chord = Skin.stroke(0.7f, Skin.alpha(Skin.MAGENTA, 0.22f));
        for (int i = 0; i + 5 < tiles.length; i += 5) {
            c.drawLine(tiles[i].centerX(), tiles[i].centerY(),
                    tiles[i + 5].centerX(), tiles[i + 5].centerY(), chord);
        }

        // a pulse chasing along the wire, one segment at a time
        if (tiles.length > 1) {
            float t = (phase * 0.06f) % (tiles.length - 1);
            int seg = (int) t;
            float f = t - seg;
            RectF a = tiles[seg], b = tiles[Math.min(seg + 1, tiles.length - 1)];
            c.drawCircle(a.centerX() + (b.centerX() - a.centerX()) * f,
                    a.centerY() + (b.centerY() - a.centerY()) * f,
                    Skin.dp(2.4f), Skin.fill(Skin.alpha(Skin.PLASMA, 0.85f)));
        }

        // one readout line above the sky
        Paint hdr = Skin.text(8, Skin.TEXT_DIM, false);
        hdr.setLetterSpacing(0.16f);
        String s = "CORE " + Sys.pct(load)
                + "  ·  MEM " + Math.round(mem.frac * 100) + "%"
                + "  ·  PWR " + (power.level < 0 ? "--" : power.level + "%")
                + "  ·  " + (link.online ? link.type : "NO LINK");
        Skin.textCenter(c, s, w / 2f, top + Skin.dp(16), hdr);
    }

    // =====================================================================
    // HELIX — twin strands winding down the screen, modules on the coil
    // =====================================================================

    private static float helixAmp(int w) {
        return Math.min(w * 0.34f, w / 2f - Skin.dp(30));
    }

    /**
     * How many turns the coil makes, chosen rather than assumed.
     *
     * For any fixed turn count there is a phase at which two consecutive beads
     * share an x — the coil is at a turnaround and both sit on the same side —
     * and how badly that bites depends on exactly where the sixteen sample
     * points land. Rather than pick a number that looks right and discover on
     * a small screen that two beads are 20dp apart, try the candidates and
     * keep whichever spreads them furthest. It is a few hundred comparisons
     * once per layout pass, and it is the difference between a claim and a
     * measurement.
     *
     * Cached for drawHelix, which must trace the same coil the beads sit on.
     */
    private static float helixTurns = 2.5f;

    private static float chooseTurns(int n, float amp, float span) {
        float best = 2.5f, bestGap = -1f;
        for (float turns = 1.5f; turns <= 4.01f; turns += 0.1f) {
            float gap = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float ti = n == 1 ? 0.5f : i / (float) (n - 1);
                float xi = (float) Math.sin(ti * Math.PI * 2.0 * turns) * amp;
                float yi = ti * span;
                for (int j = i + 1; j < n; j++) {
                    float tj = n == 1 ? 0.5f : j / (float) (n - 1);
                    float xj = (float) Math.sin(tj * Math.PI * 2.0 * turns) * amp;
                    float yj = tj * span;
                    float dx = xi - xj, dy = yi - yj;
                    gap = Math.min(gap, dx * dx + dy * dy);
                }
            }
            if (gap > bestGap) { bestGap = gap; best = turns; }
        }
        return best;
    }

    public static void layoutHelix(RectF[] tiles, int w, int h, float top, float bottom) {
        int n = tiles.length;
        float cx = w / 2f;
        float amp = helixAmp(w);
        float fieldTop = top + Skin.dp(28);

        float pitch = (bottom - fieldTop) / Math.max(1, n);
        float provisional = Math.min(Skin.dp(34), pitch);
        float span = bottom - fieldTop - provisional;

        helixTurns = chooseTurns(n, amp, span);

        // Size the bead from the closest that any two beads actually come, not
        // from the vertical pitch. Pitch alone is not the binding constraint:
        // the coil doubles back, so two beads a third of a turn apart can sit
        // at nearly the same x, and one full turn later the coil returns to
        // the same side. Measuring the real minimum is the only way to state
        // that nothing overlaps and mean it.
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0.5f : i / (float) (n - 1);
            ys[i] = fieldTop + provisional / 2f + t * span;
            xs[i] = cx + (float) Math.sin(t * Math.PI * 2.0 * helixTurns) * amp;
        }

        float closest = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                closest = Math.min(closest, (float) Math.sqrt(dx * dx + dy * dy));
            }
        }

        // Beads are round, so touching diagonally is fine; the square tile is
        // only a hit box, and on the helix hit() uses nearest centre anyway.
        //
        // Capped at the provisional size as well as the measured gap: the bead
        // centres were laid out assuming that size, so a bead that grew past
        // it would hang the last one over the bottom of the field.
        float tile = Math.min(provisional, closest * 0.72f);
        for (int i = 0; i < n; i++) {
            tiles[i].set(xs[i] - tile / 2, ys[i] - tile / 2,
                    xs[i] + tile / 2, ys[i] + tile / 2);
        }
    }

    public static void drawHelix(Canvas c, int w, int h, float top, float bottom,
                                 float phase, float load, Sys.Mem mem, Sys.Power power) {
        float cx = w / 2f;
        float amp = helixAmp(w);
        float fieldTop = top + Skin.dp(28);
        float span = bottom - fieldTop;
        int steps = 90;

        Path a = new Path(), b = new Path();
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float y = fieldTop + t * span;
            double ang = t * Math.PI * 2.0 * helixTurns;
            float xa = cx + (float) Math.sin(ang) * amp;
            float xb = cx + (float) Math.sin(ang + Math.PI) * amp;
            if (i == 0) { a.moveTo(xa, y); b.moveTo(xb, y); }
            else { a.lineTo(xa, y); b.lineTo(xb, y); }
        }
        c.drawPath(a, Skin.stroke(1.5f, Skin.alpha(Skin.PLASMA, 0.75f)));
        c.drawPath(b, Skin.stroke(1.2f, Skin.alpha(Skin.MAGENTA, 0.45f)));

        // rungs, brightest where the strands are furthest apart
        for (int i = 0; i <= 44; i++) {
            float t = i / 44f;
            float y = fieldTop + t * span;
            double ang = t * Math.PI * 2.0 * helixTurns;
            float xa = cx + (float) Math.sin(ang) * amp;
            float xb = cx + (float) Math.sin(ang + Math.PI) * amp;
            float open = Math.abs(xa - xb) / (2f * amp);
            // a lit band travelling down the coil
            float chase = 1f - Math.min(1f, Math.abs(((phase * 0.012f) % 1f) - t) * 6f);
            int col = chase > 0.02f ? Skin.PLASMA : Skin.EDGE;
            c.drawLine(xa, y, xb, y,
                    Skin.stroke(1f, Skin.alpha(col, 0.10f + open * 0.28f + chase * 0.5f)));
        }

        Paint hdr = Skin.text(8, Skin.TEXT_DIM, false);
        hdr.setLetterSpacing(0.16f);
        Skin.textCenter(c, "CORE " + Sys.pct(load)
                + "  ·  MEM " + Math.round(mem.frac * 100) + "%"
                + "  ·  PWR " + (power.level < 0 ? "--" : power.level + "%"),
                cx, top + Skin.dp(14), hdr);
    }

    // =====================================================================
    // CASCADE — wide strips under a live waveform
    // =====================================================================

    private static float waveBand() { return Skin.dp(56); }

    public static void layoutCascade(RectF[] tiles, int w, int h, float top, float bottom) {
        int n = tiles.length;
        float pad = Skin.dp(12);
        float listTop = top + waveBand() + Skin.dp(8);
        float gap = Skin.dp(5);
        int cols = 2;
        int rows = (int) Math.ceil(n / (float) cols);
        float cw = (w - pad * 2 - gap) / cols;
        float rowH = Math.min(Skin.dp(46),
                (bottom - listTop - gap * (rows - 1)) / rows);

        for (int i = 0; i < n; i++) {
            int r = i / cols, cc = i % cols;
            float x = pad + cc * (cw + gap);
            float y = listTop + r * (rowH + gap);
            tiles[i].set(x, y, x + cw, y + rowH);
        }
    }

    public static void drawCascade(Canvas c, int w, int h, float top, float bottom,
                                   float phase, float load, Sys.Mem mem,
                                   Sys.Store store, Sys.Power power) {
        float pad = Skin.dp(12);
        float band = waveBand();
        float baseY = top + band * 0.62f;
        float amp = band * 0.34f;

        c.drawLine(pad, baseY, w - pad, baseY, Skin.stroke(0.8f, Skin.alpha(Skin.EDGE, 0.9f)));

        // A waveform, not a graph of anything — there is no sample history to
        // plot. Its height tracks load so it is honest about being a level
        // meter with a scrolling shape, rather than pretending to be a trace.
        Path wave = new Path();
        float x0 = pad, x1 = w - pad;
        for (float x = x0; x <= x1; x += Skin.dp(2)) {
            float u = (x - x0) / (x1 - x0);
            double k = u * 26.0 - phase * 0.09;
            float v = (float) (Math.sin(k) * 0.6 + Math.sin(k * 2.7 + 1.3) * 0.28
                    + Math.sin(k * 5.1 + 0.4) * 0.12);
            float y = baseY - v * amp * (0.25f + load * 1.5f);
            if (x == x0) wave.moveTo(x, y); else wave.lineTo(x, y);
        }
        c.drawPath(wave, Skin.stroke(1.6f, Skin.level(load)));

        Paint l = Skin.text(7, Skin.TEXT_DIM, true);
        l.setLetterSpacing(0.26f);
        c.drawText("CORE " + Sys.pct(load), pad, top + Skin.dp(9), l);
        Skin.textRight(c, "MEM " + Math.round(mem.frac * 100) + "%  ·  DSK "
                        + Math.round(store.frac * 100) + "%  ·  PWR "
                        + (power.level < 0 ? "--" : power.level + "%"),
                w - pad, top + Skin.dp(9), l);
    }

    // =====================================================================
    // BACKDROPS
    // =====================================================================

    /** The backdrop is scenery. It does not react to touch. */
    public static void backdrop(Canvas c, int w, int h, int kind, float phase) {
        switch (kind) {
            case Theme.BG_GRID:
                Skin.grid(c, w, h, 26);
                break;
            case Theme.BG_RAIN:
                codeRain(c, w, h, phase);
                break;
            case Theme.BG_NEBULA:
                nebula(c, w, h, phase);
                Skin.stars(c, w, h, phase, 50);
                break;
            case Theme.BG_VOID:
                break;
            default:
                Skin.stars(c, w, h, phase, 90);
                Skin.grid(c, w, h, 26);
        }
    }

    /**
     * Falling glyph columns, seeded so each column keeps its own speed.
     *
     * Deliberately NOT interactive. Position here is derived as
     * phase × speed × multiplier, so touching a column to change its speed
     * changed the computed position instantly and the whole streak teleported
     * up the screen. Making that smooth would mean integrating each column's
     * position over time and carrying per-column state across frames — real
     * work for a background effect nobody asked to fiddle with. Steady rain.
     */
    private static void codeRain(Canvas c, int w, int h, float phase) {
        Paint p = Skin.text(9, Skin.ION, false);
        float colW = Skin.dp(13);
        int cols = (int) (w / colW);

        for (int i = 0; i < cols; i++) {
            long seed = i * 2654435761L;
            float speed = 40f + (Math.abs(seed >> 8) % 90);
            float offset = (Math.abs(seed >> 16) % 1000) / 1000f * h;
            float headY = ((phase * speed * 0.35f) + offset) % (h + 200) - 100;

            for (int k = 0; k < 12; k++) {
                float y = headY - k * Skin.dp(13);
                if (y < -20 || y > h + 20) continue;
                float a = (1f - k / 12f) * 0.5f;
                p.setColor(Skin.alpha(k == 0 ? Skin.TEXT : Skin.ION, a));
                long g = seed + k * 31 + (long) (phase * 0.2f);
                char ch = (char) ('0' + Math.abs(g) % 10);
                c.drawText(String.valueOf(ch), i * colW, y, p);
            }
        }
    }

    /** Soft drifting clouds, built from overlapping translucent discs. */
    private static void nebula(Canvas c, int w, int h, float phase) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        long seed = 0x9E3779B9L;
        for (int i = 0; i < 14; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float bx = ((seed >>> 16) % 1000) / 1000f * w;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float by = ((seed >>> 16) % 1000) / 1000f * h;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float rad = Skin.dp(60 + ((seed >>> 16) % 120));

            float drift = (float) Math.sin(phase * 0.006f + i) * Skin.dp(18);
            int col = (i % 3 == 0) ? Skin.MAGENTA : (i % 3 == 1) ? Skin.PLASMA : Skin.PLASMA_D;
            p.setColor(Skin.alpha(col, 0.05f));
            c.drawCircle(bx + drift, by - drift * 0.5f, rad, p);
        }
    }

    // =====================================================================

    private static void arc(Canvas c, float cx, float cy, float r,
                            float start, float sweep, int color, float widthDp) {
        RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(box, start, 360f * 0 + sweepClamp(sweep), false,
                Skin.stroke(widthDp, Skin.alpha(color, 0.18f)));
        Paint live = Skin.stroke(widthDp, color);
        live.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(box, start, sweepClamp(sweep), false, live);
    }

    private static float sweepClamp(float s) {
        return Math.max(0f, Math.min(359f, s));
    }
}
