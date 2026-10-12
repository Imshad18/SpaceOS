# Space OS

**[Download SpaceOS.apk](../../raw/main/SpaceOS.apk)** — install directly, no Play Store.

A science-fiction Android home screen. Replaces the launcher entirely: app
lattice, lock screen with biometric unlock, notification handling, contacts,
dialer, a signal seeker that plots Wi-Fi and Bluetooth strength on a compass,
and a theme engine that changes the layout rather than only the colours.

Every surface is drawn on a `Canvas`. No Material components, no AppCompat, no
third-party libraries at all.

## Build

```sh
./build.sh
```

`aapt → javac → d8 → zipalign → apksigner`, driven by one shell script. No
Gradle, no NDK. The APK lands in `out/`.

## Requirements

Android 8.0 (API 26) or newer.

---

**DESIGNED BY MOHAMMAD SHADAB, CODED WITH CLAUDE**
