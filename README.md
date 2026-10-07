# PinCatcher

**Catch pinned traffic. No root required.**

Non-root Android app that extracts, analyses, patches and re-installs an APK, then
captures its HTTP/HTTPS traffic through a local VPN-based MITM proxy — all on-device,
no laptop, no terminal.

> PinCatcher is for analysing APKs you are authorised to analyse. Using it to access
> data without permission is illegal. Everything runs locally; nothing is uploaded.

---

## Status

**v0.1.0-alpha01 — scaffolding.** The app builds and the capture-storage layer is in
place. No feature is usable yet. See [docs/ROADMAP.md](docs/ROADMAP.md) for what is
actually done versus planned.

## Build

```bash
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:lintDebug :app:detekt
```

Requires JDK 17+ and Android SDK with platform 36 + build-tools 36.

## Toolchain

| | |
|---|---|
| Language / UI | Kotlin 2.4, Jetpack Compose (Material 3) |
| Build | Gradle 9.7, AGP 9.4, version catalog, built-in Kotlin |
| min / target SDK | 26 (Android 8.0) / 36 (Android 16) |
| DI | Koin |
| Storage | Room 2.8 on the platform SQLite, FTS4 for flow search |
| Upstream HTTP | OkHttp |
| APK signing | `com.android.tools.build:apksig` |

No server component. No telemetry. No account.

## Licence

GPL-3.0. See [LICENSE](LICENSE) and [LICENSES.md](LICENSES.md) for third-party
attribution.
