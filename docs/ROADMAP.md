# Roadmap

Status legend: **done** · **in progress** · **not started**

## Phase 0 — Infrastructure

* [x] Gradle 9.7 + AGP 9.4 (built-in Kotlin), single `:app` module, version catalog
* [x] Detekt + lint wired into `build.yml`
* [x] GPL-3.0 licence, `LICENSES.md` attribution
* [x] GitHub Actions: build, test, release on tag
* [x] Manifest permissions that the PRD omitted (see below)
* [ ] Release signing via `keystore.properties` / CI secrets
* [ ] Obtainium metadata

### Permissions the PRD never listed

Without these the corresponding PRD requirements silently do nothing:

| Permission | Needed by |
|---|---|
| `QUERY_ALL_PACKAGES` | FR-01, listing installed apps on API 30+ |
| `REQUEST_INSTALL_PACKAGES` | FR-09, installing the patched APK |
| `FOREGROUND_SERVICE_SPECIAL_USE` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | capture foreground service on API 34+ |
| `POST_NOTIFICATIONS` | the capture service notification on API 33+ |
| `ACCESS_LOCAL_NETWORK` | reaching the loopback proxy on API 36 |

## Phase 1 — Capture engine (highest risk, built first)

* [ ] zdtun vendored + NDK build for arm64-v8a / armeabi-v7a / x86_64
* [ ] `CaptureVpnService`: tun setup, `protect()` on all outbound sockets
* [ ] **Drop UDP/443** so QUIC/HTTP3 apps fall back to TCP — otherwise they bypass capture silently
* [ ] Loop guard: `addDisallowedApplication(self)` + `protect()` + loop-pattern heuristic
* [ ] HTTP/1.1 + CONNECT front-end on `AsioSelectorManager`
* [ ] TLS MITM: Conscrypt `SSLContext`, leaf certificate minted per SNI hostname
* [ ] OkHttp upstream (HTTP/2, gzip, brotli, pooling)
* [ ] Flow emission into Room, live UI stream

Exit criterion: capture one HTTPS request from Chrome into the flow list, end to end,
under 5 seconds.

## Phase 2 — Certificate wizard

* [ ] Root CA in the Android Keystore (StrongBox when present, RSA-2048 fallback)
* [ ] `KeyChain.createInstallIntent()` + verification
* [ ] Per-OEM manual instructions as fallback

## Phase 3 — APK resolve + analyse (read-only)

* [ ] `sourceDir` extraction, split detection, integrity check
* [ ] Split merge via ARSCLib / APKEditor logic
* [ ] Signature parse, DEX string scan
* [ ] Analysis report UI

## Phase 4 — Patch + sign + install

* [ ] NSC inject with `overridePins="true"`, preserving existing `<domain-config>`
* [ ] AXML re-encode
* [ ] Repack in our own zip writer (4-byte align + 16 KB align for `.so`)
* [ ] Sign v2 + v3 with apksig (v4 is adb/IncFS-only and not needed)
* [ ] `PackageInstaller` install + signature-conflict handler
* [ ] Optional: dexlib2 in-place method stub for `CertificatePinner` / `OkHostnameVerifier`

## Phase 5 — Inspector + storage + export

* [ ] Flow list (Paging 3), detail tabs, body viewer
* [ ] FTS4 search with match highlighting
* [ ] HAR 1.2 + cURL export
* [ ] Body dedup, gzip, ring buffer, storage dashboard

## Phase 6 — Flutter (P2, not a priority)

* [ ] On-device ELF patch for engines that retain `.symtab` (debug/profile/Shorebird)
* [ ] Engine swap for stripped stock release engines — deferred until there is demand
* [ ] Manual-proxy fallback for unsupported hashes

## Phase 7 — v1.1

Breakpoints, rewrite rules, mock responses, session save/load, HTTP/2.

---

## Deliberately not building

* Play Integrity / SafetyNet bypass — server-side, out of reach non-root
* Shorebird Flutter — private Dart fork, our engine cannot load its snapshots
* Encrypted database (SQLCipher) — not yet
* Per-feature Gradle modules — one module until build times force a split
* A JSON rewrite-rule DSL — nobody has asked for it yet
