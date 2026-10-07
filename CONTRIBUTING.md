# Contributing

Thanks for looking at PinCatcher.

## Ground rules

* **Only analyse APKs you own or are authorised to analyse.** PRs, issues and
  discussions that assume otherwise get closed.
* Everything runs on-device. A contribution that introduces a server, telemetry
  or an upload path will not be merged.

## Workflow

1. Fork, branch off `main`.
2. Make the change, keeping the diff as small as it can be.
3. `./gradlew :core:test :app:assembleDebug :app:lintDebug` must pass.
4. Open a PR describing what changed and why.

## Style

* Kotlin official style guide.
* Android Lint is the gate; do not suppress a finding without a comment saying why.
* Unit tests for logic that does not need Android belong in `:core`, not `:app`,
  so they run without the SDK and without `aapt2`.
* Prefer the platform or an existing dependency over a new one. If you add a
  dependency, add a row to `LICENSES.md`.

## Scope

`docs/ROADMAP.md` is the source of truth for what is planned. The section
"Deliberately not building" is a decision, not a to-do list — opening a PR for
anything on it needs a discussion first.
