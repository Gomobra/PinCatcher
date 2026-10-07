# PinCatcher

**Catch pinned traffic. No root required.**

Non-root Android app that extracts, analyses, patches and re-installs an APK, then
captures its HTTP/HTTPS traffic through a local VPN-based MITM proxy — all on-device,
no laptop, no terminal.

> PinCatcher is for analysing APKs you are authorised to analyse. Using it to access
> data without permission is illegal. Everything runs locally; nothing is uploaded.

---

## Status

**v0.1.0-alpha01 — scaffolding.** The storage layer and capture skeleton compile and
the pure-JVM tests pass. No feature is usable yet. `docs/ROADMAP.md` is the source of
truth for what is done versus planned.

Done so far:

* Capture-storage schema (`sessions`, `flows`, `bodies`, `rules`) with an FTS4
  external-content index over captured flows
* Body dedup + refcount accounting, ring-buffer trim, storage accounting
* IPv4/TCP header codec and a user-space `TcpSession` state machine, in pure
  Kotlin with tests that cover handshake, data, close and 32-bit sequence wrap
* `CaptureVpnService` with the tun parameters and the loop guard's
  `addDisallowedApplication(self)`
* App shell: Compose scaffold, theme, three languages, VPN consent manifest

## Build

```bash
./gradlew :core:test              # pure-JVM unit tests - runs anywhere
./gradlew :app:assembleDebug      # APK -> app/build/outputs/apk/debug/
./gradlew :app:lintDebug          # Android Lint
```

Requires JDK 17+ and Android SDK with platform 37.2 + build-tools 37.

### Building on a phone

Full builds work on-device. AGP resolves `aapt2` from Maven as a linux-x86_64 ELF
that cannot execute on Android/arm64, but Termux ships a native aarch64 build, so
`gradle.properties` points AGP at it:

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2   # pkg install aapt2
```

Delete that line on an x86_64 host and in CI - there the Maven artifact is correct.
Expect a debug build to take ~6 minutes on a phone against ~1 minute on CI.

Build sizes: debug 14 MB, release 958 KB after R8 (no code is wired up yet, so the
release figure will grow; the PRD budget is 200 MB).

## Toolchain

| | |
|---|---|
| Language / UI | Kotlin 2.4, Jetpack Compose (Material 3) |
| Build | Gradle 9.7, AGP 9.4 built-in Kotlin, version catalog |
| min / compile / target SDK | 26 (Android 8.0) / 37 / 36 |
| Storage | platform SQLite via `SQLiteOpenHelper`, FTS4 for flow search |
| DI | Koin |
| Upstream HTTP | OkHttp |
| APK signing | `com.android.tools.build:apksig` |
| Static analysis | Android Lint |

No server component. No telemetry. No account.

## Licence

GPL-3.0. See [LICENSE](LICENSE) and [LICENSES.md](LICENSES.md) for third-party
attribution.