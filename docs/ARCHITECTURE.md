# Architecture

## The one rule

**Module `:core` owns the package prefix `com.pincatcher.core.*`. Module `:app`
owns everything else.**

That is the whole boundary. `:core` is plain Kotlin/JVM with no Android
dependency, so anything that needs `Context`, `SQLiteOpenHelper`,
`PackageManager`, `VpnService` or Compose lives in `:app` and never in `:core`.

```
core/src/{main,test}/kotlin/com/pincatcher/core/
  capture/   Ip.kt  PacketWriter.kt  TcpSession.kt   packet codec + TCP state machine
  domain/    StoragePolicy.kt                      ring-buffer budget

app/src/main/kotlin/com/pincatcher/
  PinCatcherApp.kt      Application subclass
  MainActivity.kt
  data/                 PincatcherDatabase.kt  Flow.kt  FlowStore.kt  BodyStore.kt
  apk/                  InstalledApps.kt
  capture/              CaptureVpnService.kt
  ui/
    AppShell.kt         nav + scaffold
    component/          one file per composable
    home/ capture/ inspector/ settings/
    theme/              Token.kt  Theme.kt
```

Both modules use `src/<set>/kotlin`, never `src/<set>/java`. Kotlin is the only
language in the project; there is no Java source root to keep in sync.

## Why this boundary and not a feature-first one

A feature-first layout (`capture/`, `patching/`, `cert/`) looks tidier and is
the wrong shape here. The capture engine has a hard split down the middle: the
protocol logic is pure and testable off-device, the socket and `VpnService`
plumbing is not. A feature-first layout either leaks `android.*` imports into
the pure half or splits one directory across two modules with no boundary
anyone can state. Naming by dependency boundary makes the split self-evident.

The first version of this tree had `com.pincatcher.core.data.db` inside module
`:app` while a module literally called `:core` also existed, and two classes
both called `PinCatcherApp` in sibling packages. Both are gone.

## Component conventions

**One public composable per file, named after it.** `ui/component/Stat.kt`
contains `Stat`. There is no `Common.kt` - a file that accumulates unrelated
declarations stops being navigable.

**No raw colour or dp outside `ui/theme/Token.kt`.** Every value that would
otherwise be inlined is lifted into a named token first. This is checkable:

```bash
grep -rn "0xFF[0-9A-Fa-f]\{6\}\|[0-9]\+\.dp" app/src core/src --include=*.kt \
  | grep -v theme/Token.kt     # must print nothing
```

Contrast figures in `Token.kt` are measured with the WCAG 2.1 relative
luminance formula, not asserted. Worst case across the set is 5.94:1 against a
4.5:1 body-text floor.

## Database

`SQLiteOpenHelper`, not Room. Room's compiler verifies queries through its
bundled xerial sqlite-jdbc, a glibc-linked native library that cannot load on
Android's bionic libc — which is where this project is developed. Its support
for FTS4 *external content* tables is also only visible in generated code, and
the sync triggers are precisely the part of this schema worth auditing by eye.

The flow index is FTS4 for the same reason FTS5 is unusable: Android's platform
SQLite is not compiled with FTS5, and `BundledSQLiteDriver` would mean shipping
our own SQLite. FTS4 is present on every supported API level.

Two details are load-bearing and easy to get wrong:

- `flows.url` is a real column, not a concatenation. An external-content FTS
  table resolves its columns against the content table by name, so a
  concat-only URL is unreachable from the FTS side.
- The sync trigger pair is `BEFORE UPDATE` + `AFTER UPDATE`. In
  external-content mode FTS4 re-reads the content table to decide which tokens
  to drop, so index removal has to happen while the row still holds its old
  text. Deleting from an `AFTER UPDATE` reads the *new* text and leaves the old
  tokens behind, and the flow then matches both its old and its new URL.

## Local build environment

Two things make an on-device checkout work that do not apply to CI:

| | |
|---|---|
| `android.aapt2FromMavenOverride` | AGP resolves aapt2 as a linux-x86_64 ELF; Termux packages a native aarch64 build (`pkg install aapt2`). Drop the property in CI and on x86_64. |
| `PINCATCHER_BUILD_DIR` | Android shared storage is a FUSE mount that cannot service Gradle's task-output cleanup. Point the build directory at internal storage, or `mergeDebugResources` fails with "Unable to delete directory". |

```bash
export PINCATCHER_BUILD_DIR=$PREFIX/tmp/pc-build
./gradlew :app:assembleDebug
```

## Build hygiene

Delete the Gradle cache after a build. Note the cost: `caches/modules-2` is the
downloaded dependencies (~580 MB) and re-downloading it is minutes of every
build, while `caches/<version>/transforms-*` is per-build AAR extraction and is
what actually grew to 3.1 GB over six builds. Deleting only the transforms
reclaims the space for free.

```bash
rm -rf "${GRADLE_USER_HOME:-$HOME/.gradle}"/caches/*/transforms-*
```

`org.gradle.daemon=false` also helps: a lingering daemon pins its own cache
open and holds heap across builds.