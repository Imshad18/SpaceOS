package com.spaceos.os;

/**
 * Palettes and layouts for the bridge.
 *
 * A palette is twelve colours; a layout is a completely different arrangement
 * of the same live data. They compose, so six palettes across six layouts is
 * thirty-six distinct home screens.
 */
public final class Theme {

    private Theme() {}

    // =====================================================================
    // PALETTES
    // =====================================================================

    /**
     * void, void2, grid, edge, primary, primaryDim, ok, warn, bad, second, text, textDim
     *
     * The last row is CUSTOM and is not a constant — {@link #setCustom} writes
     * into it from five knobs the user turns. Everything downstream indexes
     * this array by number and neither knows nor cares which rows are fixed.
     */
    public static final int[][] PALETTES = {
            // PLASMA — deep space, cold cyan instrument glow
            {0xFF000509, 0xFF03101A, 0xFF0B2733, 0xFF14485C, 0xFF35E8FF, 0xFF0E7C93,
             0xFF6BFF9E, 0xFFFFC24A, 0xFFFF5A6E, 0xFFD46BFF, 0xFFBDEEFF, 0xFF5C8A9B},

            // ION — phosphor green, old terminal CRT
            {0xFF000A04, 0xFF03160C, 0xFF0A2A16, 0xFF15542C, 0xFF4BFF88, 0xFF128040,
             0xFF9BFFC0, 0xFFE8FF5A, 0xFFFF6B5A, 0xFF5AFFD4, 0xFFC8FFDC, 0xFF4E9468},

            // SOLAR — amber monitor, warm and analogue
            {0xFF0B0602, 0xFF1A1004, 0xFF33220A, 0xFF5E3F12, 0xFFFFB03A, 0xFF9C6410,
             0xFFFFD98A, 0xFFFFE45A, 0xFFFF6640, 0xFFFF8A3A, 0xFFFFE2B8, 0xFF9C7440},

            // FLARE — alert red, hostile environment
            {0xFF0B0204, 0xFF1A040A, 0xFF360B14, 0xFF5E1524, 0xFFFF4560, 0xFF9C1028,
             0xFFFF8A9E, 0xFFFFB03A, 0xFFFFD84A, 0xFFFF7ACD, 0xFFFFD0D8, 0xFF9C5464},

            // SYNTH — magenta and violet, neon night
            {0xFF07030F, 0xFF120722, 0xFF241040, 0xFF3F1E66, 0xFFE050FF, 0xFF7A1CA8,
             0xFF64F0D0, 0xFFFFC44A, 0xFFFF4F8A, 0xFF50C8FF, 0xFFEFD4FF, 0xFF8A64A8},

            // MONO — no colour at all, pure instrument
            {0xFF050505, 0xFF101010, 0xFF1E1E1E, 0xFF3A3A3A, 0xFFE8E8E8, 0xFF787878,
             0xFFC8C8C8, 0xFFA0A0A0, 0xFFFFFFFF, 0xFFB4B4B4, 0xFFF0F0F0, 0xFF767676},

            // PAPER — light. Warm stock, dark ink, the first inverted palette.
            {0xFFF4F0E6, 0xFFEAE4D6, 0xFFDCD4C0, 0xFFB8AE96, 0xFF2A2620, 0xFF7A7060,
             0xFF3A6B45, 0xFF9A6B20, 0xFFA33A30, 0xFF6B4A8A, 0xFF1E1A14, 0xFF8A8272},

            // E-INK — cool grey, the Kindle read. Lower contrast, easy at night.
            {0xFFDDDDD8, 0xFFCECEC8, 0xFFBFBFB8, 0xFF9A9A94, 0xFF2E2E2C, 0xFF7C7C78,
             0xFF4A5A4A, 0xFF7A6A3A, 0xFF8A3A3A, 0xFF5A5A6A, 0xFF1C1C1A, 0xFF80807A},

            // SEPIA — paper after dark. Warm near-black stock, cream ink.
            {0xFF14100A, 0xFF1F1810, 0xFF2E2418, 0xFF4A3B28, 0xFFE8DCC0, 0xFF8A7A58,
             0xFFA8C088, 0xFFD8B060, 0xFFD07868, 0xFFC0A0D0, 0xFFF0E8D4, 0xFF8A8070},

            // SLATE — e-ink after dark. Neutral charcoal, soft grey text.
            {0xFF121214, 0xFF1C1C1F, 0xFF2A2A2E, 0xFF444448, 0xFFD8D8DC, 0xFF78787E,
             0xFF9AB49A, 0xFFC0A870, 0xFFC08080, 0xFF9098B8, 0xFFE8E8EC, 0xFF7C7C82},

            // CUSTOM — placeholder. setCustom() overwrites every entry.
            {0xFF000509, 0xFF03101A, 0xFF0B2733, 0xFF14485C, 0xFF35E8FF, 0xFF0E7C93,
             0xFF6BFF9E, 0xFFFFC24A, 0xFFFF5A6E, 0xFFD46BFF, 0xFFBDEEFF, 0xFF5C8A9B},
    };

    /** Index of the user-built palette. Always the last row. */
    public static final int CUSTOM = PALETTES.length - 1;

    public static final String[] PALETTE_NAMES = {
            "PLASMA", "ION", "SOLAR", "FLARE", "SYNTH", "MONO",
            "PAPER", "E-INK", "SEPIA", "SLATE", "CUSTOM"
    };

    public static final String[] PALETTE_DESC = {
            "cold cyan · deep space",
            "phosphor green · CRT",
            "amber · analogue warmth",
            "red alert · hostile",
            "magenta neon · night",
            "greyscale · pure instrument",
            "warm stock · dark ink",
            "cool grey · easy reading",
            "paper after dark · cream ink",
            "charcoal e-ink · night reading",
            "yours · five knobs"
    };

    // =====================================================================
    // CUSTOM PALETTE
    // =====================================================================

    /**
     * Builds the whole twelve-colour palette from five knobs.
     *
     * Handing the user twelve individual colour pickers would let them build
     * something unreadable in about four taps — dark text on a dark ground and
     * the OS is gone. So the knobs describe intent (what colour is the accent,
     * how dark is the room) and the derivation guarantees the contrast
     * relationships that the rest of the drawing code assumes hold.
     *
     * @param accentHue 0..359, the primary instrument glow
     * @param sat       0..100, how saturated the accents are
     * @param bgHue     0..359, the tint of the background stock
     * @param light     0..100, background lightness — above ~50 inverts to a
     *                  light theme and the ink flips to dark automatically
     * @param contrast  0..100, how far the ink separates from the ground
     */
    public static void setCustom(int accentHue, int sat, int bgHue,
                                 int light, int contrast) {
        accentHue = ((accentHue % 360) + 360) % 360;
        bgHue = ((bgHue % 360) + 360) % 360;
        sat = clamp(sat, 0, 100);
        light = clamp(light, 0, 100);
        contrast = clamp(contrast, 0, 100);

        // The lightness knob deliberately skips the midtones.
        //
        // Every other colour here is placed at an absolute lightness on the
        // assumption the ground is near one end. Let the ground sit at 48% and
        // the body text lands at 78% — readable — but the dim text at 44% is
        // within one percent of the background and simply vanishes, and the
        // accent can match it exactly. So the knob runs 2..27% below halfway
        // and 72..97% above it, and crossing halfway inverts the theme. Mud
        // grey is not a background anyone wanted; it is only what a linear
        // slider happens to pass through.
        boolean dark = light < 50;
        float bgL = dark ? 2f + light * 0.50f            // 2%..27%
                         : 72f + (light - 50) * 0.50f;   // 72%..97%
        float k = dark ? 1f : -1f;               // which way the steps travel
        float c = contrast / 100f;
        float s = sat / 100f;

        int[] p = PALETTES[CUSTOM];
        // ground: four steps away from the stock, toward the ink
        p[0] = hsl(bgHue, 8 + s * 6, bgL);
        p[1] = hsl(bgHue, 10 + s * 7, bgL + k * 4.5f);
        p[2] = hsl(bgHue, 12 + s * 8, bgL + k * 10f);
        p[3] = hsl(bgHue, 15 + s * 10, bgL + k * (18f + c * 10f));

        // Everything from here on is placed by LUMINANCE, not by lightness.
        //
        // HSL lightness is not perceived brightness. A saturated blue at L=56
        // has a luminance of about 60; a saturated yellow at the same L is
        // near 200. Setting the accent to "L=56 because the ground is dark"
        // therefore works for warm hues and fails completely for blue — at
        // accent=240 on a mid-dark ground the two came out at 60.3 and 60.5,
        // an accent literally invisible against its own background. Asking for
        // a luminance and solving for the lightness that produces it makes the
        // separation hold for every hue on the wheel.
        int gl = lum601(p[0]);
        int dir = dark ? 1 : -1;

        p[4] = atLuminance(accentHue, 30 + s * 65, gl + dir * (int) (70 + c * 45));
        p[5] = atLuminance(accentHue, 26 + s * 55, gl + dir * (int) (32 + c * 18));

        // semantic three: recognisably green/amber/red, pulled a quarter of
        // the way toward the accent so they belong to the same family
        int sTarget = gl + dir * (int) (66 + c * 34);
        p[6] = atLuminance(mixHue(142, accentHue, 0.25f), 28 + s * 50, sTarget);
        p[7] = atLuminance(mixHue(42, accentHue, 0.20f), 34 + s * 55, sTarget);
        p[8] = atLuminance(mixHue(354, accentHue, 0.15f), 34 + s * 55, sTarget);
        p[9] = atLuminance((accentHue + 160) % 360, 30 + s * 55, sTarget);

        p[10] = atLuminance(bgHue, 6 + s * 6, gl + dir * (int) (140 + c * 60));
        p[11] = atLuminance(bgHue, 6 + s * 6, gl + dir * (int) (62 + c * 28));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * The colour of this hue and saturation that lands on the requested
     * luminance. Luminance rises monotonically with HSL lightness for a fixed
     * hue and saturation — L=0 is black and L=100 is white whatever else you
     * ask for — so a bisection always converges, and the full 0..255 range is
     * reachable from any hue. A hue that cannot be bright enough while staying
     * saturated simply comes back paler, which is the right trade: a washed
     * accent you can see beats a vivid one you cannot.
     */
    private static int atLuminance(float hue, float sat, int target) {
        target = clamp(target, 6, 250);
        float lo = 0f, hi = 100f;
        int out = hsl(hue, sat, 50);
        for (int i = 0; i < 20; i++) {
            float mid = (lo + hi) / 2f;
            out = hsl(hue, sat, mid);
            if (lum601(out) < target) lo = mid; else hi = mid;
        }
        return out;
    }

    private static int lum601(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** Interpolates between two hues the short way round the wheel. */
    private static float mixHue(float a, float b, float t) {
        float d = ((b - a) % 360f + 540f) % 360f - 180f;
        return ((a + d * t) % 360f + 360f) % 360f;
    }

    /** HSL with s and l as percentages, to an opaque ARGB int. */
    public static int hsl(float h, float sPct, float lPct) {
        float s = Math.max(0f, Math.min(1f, sPct / 100f));
        float l = Math.max(0f, Math.min(1f, lPct / 100f));
        h = ((h % 360f) + 360f) % 360f;

        float ch = (1f - Math.abs(2f * l - 1f)) * s;
        float x = ch * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = l - ch / 2f;

        float r, g, b;
        if (h < 60)       { r = ch; g = x;  b = 0;  }
        else if (h < 120) { r = x;  g = ch; b = 0;  }
        else if (h < 180) { r = 0;  g = ch; b = x;  }
        else if (h < 240) { r = 0;  g = x;  b = ch; }
        else if (h < 300) { r = x;  g = 0;  b = ch; }
        else              { r = ch; g = 0;  b = x;  }

        return 0xFF000000
                | (Math.round((r + m) * 255) << 16)
                | (Math.round((g + m) * 255) << 8)
                | Math.round((b + m) * 255);
    }

    // =====================================================================

    /**
     * True for the light palettes. The whole system was drawn assuming a dark
     * ground — white stars, dark panel fills — so anything that hardcoded a
     * light or dark value has to ask. Read off the actual void colour rather
     * than a list of indices, because CUSTOM can be either.
     */
    public static boolean isLight(int paletteIndex) {
        return luminance(swatch(paletteIndex, 0)) > 127;
    }

    public static boolean currentIsLight() {
        return luminance(Skin.VOID) > 127;
    }

    private static int luminance(int v) {
        int r = (v >> 16) & 255, g = (v >> 8) & 255, b = v & 255;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    // =====================================================================
    // LAYOUTS
    // =====================================================================

    public static final int BRIDGE = 0;
    public static final int ORBITAL = 1;
    public static final int TACTICAL = 2;
    public static final int MINIMAL = 3;
    public static final int MAINFRAME = 4;
    public static final int REACTOR = 5;
    public static final int HEXCELL = 6;
    public static final int CONSTELLATION = 7;
    public static final int HELIX = 8;
    public static final int CASCADE = 9;

    public static final String[] LAYOUT_NAMES = {
            "BRIDGE", "ORBITAL", "TACTICAL", "MINIMAL", "MAINFRAME", "REACTOR",
            "HEXCELL", "CONSTELLATION", "HELIX", "CASCADE"
    };

    public static final String[] LAYOUT_DESC = {
            "reactor core, gauges, 4x4 lattice",
            "modules orbit a planet",
            "radar sweep, modules as contacts",
            "giant clock, almost nothing else",
            "scrolling text readout, numbered menu",
            "one enormous core, modules ringed",
            "honeycomb cells, hex telemetry",
            "star chart, modules wired as stars",
            "twin strands, modules on the coil",
            "full-width strips, live waveform"
    };

    // =====================================================================
    // BACKDROPS
    // =====================================================================

    public static final int BG_STARS = 0;
    public static final int BG_GRID = 1;
    public static final int BG_RAIN = 2;
    public static final int BG_NEBULA = 3;
    public static final int BG_VOID = 4;

    public static final String[] BACKDROP_NAMES = {
            "STARFIELD", "GRID", "CODE RAIN", "NEBULA", "EMPTY"
    };

    // =====================================================================

    /** Pushes a palette into Skin. Everything drawn after this uses it. */
    public static void apply(int paletteIndex) {
        int i = Math.max(0, Math.min(PALETTES.length - 1, paletteIndex));
        int[] p = PALETTES[i];
        Skin.VOID = p[0];
        Skin.VOID_2 = p[1];
        Skin.GRID = p[2];
        Skin.EDGE = p[3];
        Skin.PLASMA = p[4];
        Skin.PLASMA_D = p[5];
        Skin.ION = p[6];
        Skin.SOLAR = p[7];
        Skin.FLARE = p[8];
        Skin.MAGENTA = p[9];
        Skin.TEXT = p[10];
        Skin.TEXT_DIM = p[11];
    }

    /** Reads one colour from a palette without applying it — for previews. */
    public static int swatch(int paletteIndex, int slot) {
        int i = Math.max(0, Math.min(PALETTES.length - 1, paletteIndex));
        return PALETTES[i][Math.max(0, Math.min(11, slot))];
    }
}
