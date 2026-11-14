# Clone Hub Pro — verified state

Current as of commit `28a4641` (verified gate commit `6496d23` — the two later commits touch only the
publish workflow). Everything below is a link to a run whose evidence is archived; nothing here is a
claim without a gate behind it, and the parts that are **not** working say so in their own section.

## The shipped APK

[`CloneHub-Pro.apk`](https://github.com/deepsilence10161-source/Clone-Hub-Pro/releases/latest/download/CloneHub-Pro.apk)
— 7,903,006 bytes, published 2026-11-14 by `Publish latest verified APK` (run `43576154418`) only after
the native ARM64 gates passed on the same commit.

## Gates green on `6496d23`

| Gate | Run | Result |
|---|---|---|
| API compatibility matrix — API 27, 28, 30, 31, 34, 35 (real ARM64 redroid images) | `43574316405` | every level success; API 34 = phone-UI matrix, 4/4 configurations PASS |
| Strict E2E — API 34 | `43575172151` | success |
| Deep clone testing | `43575172112` | success — suite `OK (5 tests)`, sign-in `OK (5 tests)` (2 skipped, see below), cold restart `OK (1 test)` |
| Real app compatibility smoke | `43575172197` | success |
| Publish latest verified APK | `43576154418` | success |

## Google sign-in inside a clone — reproduced, part-fixed, still not working

This is the honest headline: **sign-in inside a clone is not fixed**, and the gates now say so
instead of passing on `0 == 0`.

Reproduced on device (deep run `43564391516`) with one `com.google` account present: the app process
got `result=TOKEN` with `accounts=1`; the same code inside the clone got `accounts=0`,
`result=NO_ACCOUNT`.

Where it was traced (runs `43566289721`, `43567494856`):

1. The device really has the account and the platform grants the hosting package visibility —
   `dumpsys account` shows `Account visibility: com.clonehub.pro, 1`.
2. The clone's account call never reaches the platform: identical probe, `by_type=1
   by_type_for_package=1 all=1` in the host process, `by_type=0 by_type_for_package=0 all=0` inside
   `com.clonehub.pro:agent0`, while `getAuthenticatorTypes()` is non-empty there — the bundled runtime
   answers the account API itself with "no accounts".
3. The runtime is a sealed asset: `assets/oqd.apk`'s `classes.dex` contains only `android/accounts/*`
   type descriptors (no `getAccountsByType`/`getAccounts` reference at all) and `libclonehubv.so`
   exposes no account-list entry point, so it cannot be patched or configured from this repository.

What **is** proven on device: the host-side bridge (`AccountBridge` + `HostAccountProvider`, signature
permission `com.clonehub.pro.permission.CLONE_ACCOUNT_BRIDGE`) reads the device's account list and mints a
token exactly as the platform does for the app — test `e_hostAccountBridgeAnswersLikeThePlatform` PASS
(`GOOGLE_SIGNIN_BRIDGE platform=[…] bridged=[…]`, `GOOGLE_SIGNIN_BRIDGE_TOKEN result=TOKEN length=64`).
Nothing inside a clone calls it, because the runtime never asks the host.

The clone-side assertions (`a`, `b`) are therefore **skipped** with a `GOOGLE_SIGNIN_KNOWN_LIMITATION`
message in logcat — never green, and they will assert again the moment a runtime forwards account
calls. Full write-up: audit §22.

**Not tested:** a real Google OAuth token/consent screen, Play Services on the device, a signed-in
physical device. Neither the fix nor the limitation hides that gap.

## What the phone-UI gate measures (API 34, four configurations)

320x640@2x, 360x720@2x, 411x891@3x portrait and 720x360@2x landscape; the display is set by the test,
not inherited from the emulator. On each one: real system-bar geometry from the platform's windows, the
Add action above the navigation bar, every named control inside the app window and inside the safe area,
48dp touch targets, the space card against the screen, the overflow menu, a grid tile holding a real
clone (the gate now walks to the space that holds it instead of assuming the first page), the empty-space
hint in both directions, the long-press sheet, Backup & restore, and the App Picker.

Two measurement bugs were found and fixed in this gate during this revision — both had been passing:

* the check measured whichever space happened to be on screen (`cloneTile=false`,
  `emptyHint` found with a clone present) until it started walking pages with the app's own chevrons;
* a `swipe()` on the pager reported only that the gesture was accepted (`swiped=true` with the page
  unchanged), which measured nothing.

Current evidence (run `43574316405`): `cloneTile present=true pageTaps=1`, `emptyHint withClone=absent
cloneTile=true`, `emptyHint landedOn=Space 78 bounds=Rect(…)`, 4/4 `outcome=PASS`.

## What changed in the UI

A space with no clone now shows one line — “Tap + to clone apps in this space” — inside the card, hidden
while a clone is present. Wider empty-state design (illustration, first-clone suggestion) is **NOT
DONE**, and the redundant second Add affordance (toolbar “+” and grid tile) is still there.

## Not tested, stated plainly

* Android 16 (API 36) — the redroid `16.0.0` image never finishes booting on this runner; recorded in
  the workflow, not listed as supported.
* OEM skins (MIUI / OneUI / ColorOS) and their background-kill behaviour.
* Physical hardware: every measurement here is an emulated ARM64 redroid container.
* Notifications, camera, microphone, location and deep links inside clones.
* Cloning beyond the three pinned real apps (Markor, Calculator, Aegis) and the test fixture.
* Performance benchmarks (cold start, clone time, memory) for this revision.

## How to read a failure

A level is retried automatically **only** when the evidence proves the Android runtime died before any
test ran (`system_server` SIGABRT, dead emulator, no instrumentation output). A level whose
instrumentation produced a result is never retried, so a real failure cannot be retried away. A test
reported as skipped is a known limitation being stated, not a pass.

The full audit, with the evidence numbers behind each claim, is in
[`audit/CLONEHUB_PRO_AUDIT.md`](audit/CLONEHUB_PRO_AUDIT.md) (§22 sign-in, §23 mobile UI for this
revision).
