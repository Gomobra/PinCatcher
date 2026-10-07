# Third-party licences

PinCatcher is GPL-3.0. Components listed below are **planned** dependencies, not all
of which are wired up yet — the table tracks intent, and each row is marked with its
status as of the commit that added it.

## Why GPL-3.0

The two reference implementations for the hard parts of this project are GPL-3.0:

| Project | Licence | Used for |
|---|---|---|
| [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) (`zdtun`) | GPL-3.0 | userspace TCP/IP + DNS stack for the VPN tunnel |
| [reFlutter](https://github.com/Impact-I/reFlutter) | GPL-3.0 | Flutter engine patch reference (Phase 6, optional) |

Apache-2.0 is one-way incompatible with GPLv3: you cannot link GPLv3 code into an
Apache-2.0 work. Since reusing a decade-old, battle-tested TCP stack is worth far more
than keeping the licence permissive, PinCatcher is GPL-3.0.

Consequence to be aware of: contributors employed by companies with a
GPL-incompatible policy cannot contribute. That trade is deliberate.

## Permissive components

| Component | Licence | Used for |
|---|---|---|
| [ARSCLib](https://github.com/REAndroid/ARSCLib) | Apache-2.0 | AXML + `resources.arsc` encode/decode, split-APK merge |
| [APKEditor](https://github.com/REAndroid/APKEditor) | Apache-2.0 | split-APK merge logic reference |
| [OkHttp](https://square.github.io/okhttp/) | Apache-2.0 | upstream HTTP client (HTTP/2, gzip, brotli, pooling) |
| [Koin](https://insert-koin.io) | Apache-2.0 | DI |
| [Jetpack Compose](https://developer.android.com/jetpack) | Apache-2.0 | UI |
| [apksig](https://github.com/google/apksig) | Apache-2.0 | APK signing (v2 + v3) |
| [dexlib2](https://github.com/smali/dexlib2) | Apache-2.0 | DEX read/patch |

Persistence uses the platform's own `android.database.sqlite` — no ORM dependency.

## Rejected, and why

| Component | Problem |
|---|---|
| Room | Its compiler verifies queries by opening an in-memory database through xerial sqlite-jdbc, a **glibc**-linked native library. It cannot load on Android/bionic, and glibc's libc cannot be mixed into a bionic process. We develop on-device, so Room was unbuildable — and its handling of an FTS4 external-content table is the part of this schema that most needed to be readable and auditable anyway. Replaced with `SQLiteOpenHelper`. |
| FTS5 | Not compiled into Android's platform SQLite. Requires `BundledSQLiteDriver` (own SQLite, NDK, ~2 MB). FTS4 covers the same use case at zero cost. |
| detekt | 1.23.8 (latest) crashes reading Kotlin 2.4 annotation metadata — `AnnotationSuppressorFactory` casts a `Boolean` to `Iterable`. Not a config problem; it reproduces with the stock default config. Android Lint is the static-analysis gate instead. |
| Ktor CIO | An application server. No forward-proxy/CONNECT semantics, no per-host dynamic TLS serving, no HTTP/2. Wrong shape for a byte-level MITM. |
| smali / baksmali | ~4 MB and a full disassemble/reassemble round-trip per APK, which is what blows the "patch under 3 minutes" budget. DEX method bodies are patched in place instead. |
| Bundled `zipalign` binary | zipalign is padding in the local file header extra field — the zip writer we already have can do it. Also lets us honour the 16 KB page alignment Android 15+ requires. |

## Binary artefacts

* **Patched Flutter engines** (optional, Phase 6) are built from a BSD-3 Flutter tree
  with a small patch of our own. They are **not** reFlutter's binaries.
* **zdtun** is vendored source, built with the NDK as part of this project.
