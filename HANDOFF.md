# Space OS — Handoff

A custom Android launcher / system surface for a Samsung SM-G781B (Android 13,
SDK 33). 43 Java files, ~18,600 lines, no Gradle, no XML layouts, no third-party
libraries. Everything is drawn on a `Canvas`.

---

## 1. Building

There is no Gradle and no Android Studio. `build.sh` runs the whole pipeline by
hand out of `$HOME`:

```
aapt (resources) → javac → d8 (dex) → aapt add → zipalign → apksigner
```

```bash
cd ~/spaceos && ./build.sh
# → out/SpaceOS.apk, then copy to /sdcard/Download/ to install
```

Toolchain lives in `~/toolchain`:

| Path | What |
|---|---|
| `~/toolchain/jdk/bin/{java,javac,keytool}` | JDK |
| `~/toolchain/android.jar` | compile-time API 34 stubs |
| `~/toolchain/sdkroot/.../build-tools/debian/` | `aapt`, `d8`, `zipalign` |
| `~/toolchain/sdkroot/usr/share/java/apksigner.jar` | signing |

`LD_LIBRARY_PATH` must be set before invoking any build tool — `build.sh` does
it. Running `aapt` without it fails with a linker error, not a useful message.

### Why this shape

This is an aarch64 Kali PRoot with no root. There is **no aarch64 Android NDK**,
so there is no native code anywhere in this project — no `forkpty` (hence no
real pty in the terminal), no MediaPipe (hence the camera gesture work is
hand-rolled), no `llama.cpp`. Kali binaries such as `nmap` are glibc and cannot
run under Bionic, which is why `NetTools.java` reimplements scanning in Java.

### The one build-script rule

`build.sh` captures `javac`'s exit code explicitly:

```bash
set +e
"$JAVAC" ... > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
set -e
if [ $JAVAC_RC -ne 0 ]; then
    printf "\n*** COMPILE FAILED — no APK produced ***\n"; exit $JAVAC_RC
fi
```

An earlier version piped `javac` into `grep`, which swallowed the exit code and
happily shipped an APK built from stale `.class` files. **Do not reintroduce a
pipe there.**

### Verifying a build actually shipped

The APK is a compressed zip, so `grep` on it finds nothing — including strings
that are definitely present. Check the dex:

```bash
grep -a 'yourNewMethod' out/dex/classes.dex
```

Compile-time constants (`Notification.EXTRA_BIG_TEXT`, `RoleManager.ROLE_HOME`)
are **inlined**, so search for the literal value (`android.bigText`,
`android.app.role.HOME`), not the field name. Manifest permissions live in a
UTF-16 binary manifest; use `aapt dump permissions out/SpaceOS.apk`.

---

## 2. Architecture

### Rendering

Every screen is a single `View` subclass with a hand-written `onDraw`. There are
no XML layouts and no `findViewById`. Animation is a vsync `postOnAnimation`
loop carrying a delta time; nothing uses `ObjectAnimator`.

### `Skin.java` — the visual language

Colours are **non-final statics** (`Skin.PLASMA`, `Skin.VOID`, …). This matters:
`static final int` is inlined by the compiler at every use site, so making them
final would silently break runtime theming — every drawn colour would be frozen
at whatever the palette was when the class compiled.

`Skin.dressWindow(activity, fullscreen)` is the single place that sets window
flags, cutout mode, system-bar colours and light/dark icon flags. Call it rather
than touching `setSystemUiVisibility` directly; overriding it afterwards wipes
the light-palette icon flags and the cutout mode.

### `Config.java` — one instance, always

`Config.get(ctx)` is a **process-wide singleton** backed by a flat file in
`filesDir`. It was once per-component, and that caused a whole class of ghost
bugs: turning the neural link on inside `Neural` never reached `Bridge`'s copy,
so the voice overlay insisted you connect a model that was already connected.

The constructor also runs two migrations — `syncCustomPalette()` and
`chooseUnlockRoute()`. Migrations are keyed by a version marker
(`lockroute_v3`); bump the key when the meaning of a stored value changes.
Renumbering enum constants without bumping it means every existing user is
carrying a number that no longer means what it did.

### `Theme.java` — palettes and layouts

- **10 fixed palettes** + `CUSTOM` (index 10, the last row of `PALETTES`,
  written at runtime by `setCustom`).
- **10 layouts**: `BRIDGE ORBITAL TACTICAL MINIMAL MAINFRAME REACTOR HEXCELL
  CONSTELLATION HELIX CASCADE`.

They compose: any layout × any palette × any of 5 backdrops.

`setCustom` takes five knobs (accent hue, saturation, base hue, lightness,
contrast) rather than twelve colour pickers, because twelve pickers lets you
build dark-on-dark in four taps and the OS disappears.

Two non-obvious rules live in there, both found by testing:

1. **The lightness knob skips the midtones** (2–27% or 72–97%). With the ground
   at 48%, dim text at 44% is within one percent of it and simply vanishes.
2. **Accents and ink are placed by LUMINANCE, not lightness.** HSL lightness is
   not perceived brightness — a saturated blue at L=56 has luminance ~60, a
   saturated yellow at the same L is near 200. `atLuminance()` bisects for the
   lightness that hits a target luminance. Before this, `accent=240` on a
   mid-dark ground produced 60.3 against 60.5: an accent invisible on its own
   background.

### `HudView.java` — the home screen

One view, 10 layouts, 16 module tiles. Geometry per layout is computed in
`Layouts.java` into a shared `RectF[]`, so hit-testing works without every
layout reimplementing touch.

**`hit()` uses nearest-centre for the scattered layouts** (ORBITAL, TACTICAL,
REACTOR, HEXCELL, CONSTELLATION, HELIX) and `contains()` for the grid ones. A
honeycomb's cells only nest *because* their bounding boxes overlap, so
`contains()` gives the tap to whichever box is first in index order. Nearest
centre is exactly right for a hex grid — a honeycomb **is** the Voronoi diagram
of its centres — and it gives every tile its full cell as a touch target.

### Modules

`Module.java` is one activity that renders 17 different panels, selected by
`EXTRA_ID`. `SYNC_MODULES` lists the ones cheap enough to build on the main
thread; everything else builds on a worker and shows `SCANNING` first.

`refresh()` vs `load()`: `refresh()` rebuilds rows **in place**, preserving
scroll and skipping the arrival cascade. Settings rows must call `refresh()`.
Calling `load()` resets `firstPaint`, which replays the row-by-row fade for the
whole panel and drops the list back to the top — so flipping anything below the
fold threw the page at you and lost your place.

### Services

| Component | Type | Purpose |
|---|---|---|
| `Island` | foreground service | floating pill, screen watch, camera gestures |
| `Ears` | `NotificationListenerService` | the notification feed |
| `Hands` | `AccessibilityService` | global actions, screen reading for the agent |

`Island` is **headless-capable** — it runs for whichever of {pill, lock screen,
gestures} is switched on, and the pill is optional. Its foreground-service type
must include `microphone` (manifest), or Android 11+ silently denies background
mic access. That single missing flag was the cause of about five rounds of "the
voice doesn't work without touching it".

---

## 3. Things that will bite you

These are all real bugs that shipped. They are listed because every one of them
is invisible in the default case and only wrong off the happy path.

### Android will not let you do these

- **An app cannot unlock the phone.** `BiometricPrompt` authenticates the user
  to *your app*; the keyguard has no idea it happened and still wants a
  credential. Only the keyguard may take a credential.
- **`BiometricPrompt` is system UI.** There is no API to run a face or
  fingerprint scan silently behind your own screen.
- **`adjustResize` is ignored when a window lays out fullscreen.** Every Space
  OS screen draws behind the system bars, so the manifest flag does nothing.
  `Keyboard.liftAboveIme()` reads the IME inset and pads the content instead.
- **SSID needs PRECISE location.** Since Android 12 the permission dialog offers
  Precise or Approximate; Approximate grants COARSE only, which reads as
  "granted" in settings but is not enough for a network name. Once chosen,
  `requestPermissions` stops showing the dialog — the only route out is App
  Info, and the user has to be told that. See `Seeker.ssidBlocker()`.
- **Face unlock may be Class 2.** Ask for `BIOMETRIC_WEAK`, never
  `BIOMETRIC_STRONG`. STRONG means Class 3 and *excludes* camera-based face
  unlock. `STRONG | WEAK` is not a legal combination — WEAK already means "weak
  or better". See `Biometrics.java`.
- **Passing `DEVICE_CREDENTIAL` removes the Cancel button** — "Use PIN" takes
  the only negative-button slot. Hold the `CancellationSignal` so back can
  dismiss the prompt, or the user is trapped.

### The lock screen

`Lock` shows over the keyguard via `setShowWhenLocked`. It must **not** request
`TURN_SCREEN_ON` or `KEEP_SCREEN_ON` — the face is built during `SCREEN_OFF` so
that waking is instant, and those flags meant locking the phone turned the panel
straight back on and held it there.

The system's own face unlock **stays armed underneath our window** and drops the
keyguard by itself. `USER_PRESENT` is the signal that this happened; on it,
`Lock` finishes and gets out of the way. It must not launch `Bridge`, and it
must never carry `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` — that clears the Space OS
task back to its root and destroys whatever module was open.

**`raiseLockFace` refuses while the phone is ringing** (`Island.phoneBusy()`,
read from `AudioManager.getMode()` so no `READ_PHONE_STATE` is needed). A call
turns the screen on, which fires the screen-on watch, which put the lock face on
top of the ringing screen — the phone rang and there was no way to answer it.

### Performance

Two loaders had the same shape of bug, and it is worth recognising:

```java
// WRONG — loadLabel() hits the package manager and parses resources,
// and a sort calls the comparator O(n log n) times, twice each.
Collections.sort(apps, new ApplicationInfo.DisplayNameComparator(pm));
```

500 packages meant roughly 9,000 label lookups instead of 500. Resolve labels
once into a map, sort the strings. Icons decode on a worker thread *after* the
rows are on screen, cached in `Module.ICONS` for the process.

### Coordinate bookkeeping

`PresetView` stored rects in content coordinates but hit-tested with
`y + scrollY` added twice, so every tap drifted by exactly the scroll distance —
tap E-INK, select SYNTH. If you store a rect and test it somewhere else, write a
test for the round trip.

---

## 4. Testing

There is a desktop-JVM harness that compiles the **real** `Layouts.java`,
`Theme.java` and `Gestures.java` against hand-written stubs for
`android.graphics.*`, `Skin` and `Sys`. It is not in this archive (it lives in a
scratch directory) but it is 60 lines of stubs and worth rebuilding — it caught
five real bugs that would otherwise have shipped.

What it asserts:

- **Layout geometry** on 5 screen sizes: all 16 tiles placed, none off-screen,
  none outside its field, no overlaps in grid layouts, and for scattered
  layouts every tile owns a distinct touch region of at least 20dp² under
  nearest-centre routing. (3009 checks.)
- **Palette contrast** across the full five-knob sweep: worst-case luminance
  separation of body text, dim text and accent from the ground.
- **Slider round trip**: the value drawn at position *x* reads back as the same
  value.
- **Gesture arithmetic** (`Gestures.java` has no Android types precisely so this
  is possible): every angle 0–90° off each edge, pure vertical scrolls at six
  speeds, drifting scrolls, outward drags, short drags, and hooked strokes that
  scroll then turn sideways. (126 checks.)

`Gestures.java` exists **only** so gesture decisions can be tested. Keep new
gesture maths in it rather than inline in a touch handler.

---

## 5. Known incomplete

- `haptics()` is stored and displayed but not honoured — press feedback still
  calls `performHapticFeedback` unconditionally.
- `Sheet` (the themed dialog) has no list or text-input variant, so the dock
  slot picker, the assistant rename box and the Neural console dialogs still use
  stock `AlertDialog` and do not match the palette.
- Play Store: the manifest carries inline `PLAY STORE BLOCKER` comments on
  `READ_SMS`, `READ_CALL_LOG` and `MANAGE_EXTERNAL_STORAGE`. A `--play` build
  flag that strips them was offered but never built.
- The mini-LLM (`Brain` + `Memory` + `Mind`) is IDF-weighted retrieval over a
  corpus plus a bigram Markov chain. It is **not** a language model and should
  not be described as one.

---

## 6. File map

| File | Lines | What |
|---|---|---|
| `Module.java` | ~2000 | every panel: apps, files, comms, sensors, config… |
| `HudView.java` | ~1700 | the home screen |
| `Bridge.java` | ~1400 | HOME activity, voice host, gesture dispatch |
| `Neural.java` | ~900 | LLM provider setup and agent console |
| `Layouts.java` | ~830 | geometry + drawing for 9 alternate layouts |
| `Island.java` | ~740 | foreground service: pill, screen watch, gestures |
| `Lock.java` | ~660 | lock face, 6 designs, unlock routing |
| `Terminal.java` | ~600 | shell |
| `PresetView.java` | ~530 | theme picker with the custom colour builder |
| `Theme.java` | ~330 | palettes, layouts, the custom palette solver |
| `Skin.java` | ~310 | drawing primitives and window dressing |
| `Config.java` | ~450 | settings, singleton, migrations |
| others | | `Sheet` `Wallpaper` `Cell` `Biometrics` `Keyboard` `EdgeBack` `Gestures` `Seeker` `Scope` `NetTools` `Ears` `Hands` `Quick` `Agent` `Tools` `LlmClient` `Journal` `Sys` `Row` `RowView` … |

---

*Archived 2026-09-08.*
