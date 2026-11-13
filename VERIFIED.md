# Clone Hub Pro — verified state

Current as of commit `91f0d00`. Everything below is a link to a run whose evidence is archived; nothing
here is a claim without a gate behind it.

## The shipped APK

[`CloneHub-Pro.apk`](https://github.com/deepsilence10161-source/Clone-Hub-Pro/releases/latest/download/CloneHub-Pro.apk)
— 7,898,446 bytes, published by the `Publish latest verified APK` workflow only after the native ARM64
gates passed on the same commit (`91f0d00`).

## Gates green on `91f0d00` / `20f0223`

| Gate | Run | Result |
|---|---|---|
| API compatibility matrix — API 27, 28, 30, 31, 34, 35 (real ARM64 redroid images) | `43267061891` | every level success (API 34 = phone-UI matrix, 4/4 configurations PASS) |
| Strict E2E — API 34 | `43267062057` | `OK (32 tests)`; Markor / Calculator / Aegis clones render (`COMPAT_VISUAL` frameDiff 0.0 / 1.6 / 0.0) |
| Deep clone testing | `43267061977` | suite `OK (5 tests)`, Google sign-in `OK (4 tests)`, cold restart `OK (1 test)` |
| Real app compatibility smoke | `43267062282` | success |
| Publish latest verified APK | `43267934661` | success |

## What the phone-UI gate measures (API 34, four configurations)

320x640@2x, 360x720@2x, 411x891@3x portrait and 720x360@2x landscape; the display is set by the test,
not inherited from the emulator. On each one: real system-bar geometry from the platform's windows,
the Add action above the navigation bar, every named control inside the app window and inside the safe
area (below the status bar, above/left of the navigation bar), 48dp touch targets, the space card
against the screen, the overflow menu, the long-press sheet, Backup & restore, and the App Picker
(title below the status bar, install button on screen, active filter visible). A failure dumps the
hierarchy and a screenshot.

## What the sign-in gate proves, and what it does not

Proven, with evidence: the clone's process (`com.clonehub.pro:agent0`) **may** read the Google account
list (`GET_ACCOUNTS: granted=true` in the platform's own `dumpsys` record), it sees the same account
list and the same token answer as the un-cloned app, and the platform's account chooser component is
reachable from inside the clone (`android/…ChooseTypeAndAccountActivity`). The gate can no longer pass
on a `0 == 0` parity while the app lacks the permission every Google sign-in depends on.

**Not tested:** the end-to-end Google flow — consent screen, a real OAuth token from Google, a
signed-in account inside a clone. That needs an image with Play Services and a real Google account, or
a signed-in physical device.

## Not tested, stated plainly

* Android 16 (API 36) — the redroid `16.0.0` image never finishes booting on this runner; the reason is
  recorded in the workflow instead of the level being listed as supported.
* Android 7–13 and 32-bit devices — the arm64 images in the app's own `minSdk` range are what run.
* OEM skins (MIUI / OneUI / ColorOS) and their background-kill behaviour.
* Physical hardware: every measurement here is an emulated ARM64 redroid container.
* Notifications, camera, microphone, location and deep links inside clones.
* Cloning beyond the three pinned real apps (Markor, Calculator, Aegis) and the test fixture.
* Performance benchmarks (cold start, clone time, memory) for this revision.
* Layout polish of the empty space card (a one-clone space leaves a large empty card) — a design
  question the gate cannot settle, so it is not claimed as done.

## How to read a failure

A level is retried automatically **only** when the evidence proves the Android runtime died before any
test ran (`system_server` SIGABRT, dead emulator, no instrumentation output). A level whose
instrumentation produced a result is never retried, so a real failure cannot be retried away.

The full audit, with the evidence numbers behind each claim, is in
[`audit/CLONEHUB_PRO_AUDIT.md`](audit/CLONEHUB_PRO_AUDIT.md) (§19–21 cover this revision).
