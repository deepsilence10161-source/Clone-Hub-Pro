# Clone Hub Pro — verified state

Current as of commit `ce6181a`. Everything below is a link to a run whose evidence is archived;
nothing here is a claim without a gate behind it, and what is **not** working says so in its own
section.

## The shipped APK

[`CloneHub-Pro.apk`](https://github.com/deepsilence10161-source/Clone-Hub-Pro/releases/latest/download/CloneHub-Pro.apk)
— **7,905,790 bytes**, published 2026-11-15 by `Publish latest verified APK` (run `43670062074`),
which only publishes when both native ARM64 gates are green on the current `main`. The release notes
name the commit: `ce6181a`. The published file was downloaded again afterwards and its `classes.dex`
contains the sign-in fix, so the artifact is the fixed build and not an earlier one
(sha256 starts `5d77299b…`).

## Gates green on `ce6181a`

| Gate | Run | Result |
|---|---|---|
| API compatibility matrix — API 27, 28, 30, 31, 34 (phone-UI), 35 (real ARM64 redroid images) | `43669108321` | success on every level |
| Deep clone testing — sign-in + cold restart + deep suite | `43669108293` | success — sign-in `OK (6 tests)` |
| Strict E2E — API 34 | `43669108324` | success |
| Real app compatibility smoke | `43669108378` | success |
| Publish latest verified APK | `43670062074` | success |

## Google sign-in inside a clone — proven on the device

The clone receives the device's Google account and an auth token through the platform, with the same
account name and the same token length the un-cloned app gets:

```
clone : google_accounts=1  names=clonehub.probe@example.com  result=TOKEN  token_length=64
normal: google_accounts=1  names=clonehub.probe@example.com  result=TOKEN  token_length=64
GOOGLE_SIGNIN_REPEAT_SUMMARY launches=6 answered=6 with_account=6
```

What made it work was measured, not guessed: the platform answers a clone's account query **as the
host package** (`caller_identity=uid=10087`, while the agent is `10088`), and that answer is empty
for the first ~0.9 s of a clone's process — sampled at 100 ms resolution, one process answered `0` at
+787 ms and `1` at +887 ms while twelve others answered `1` at their first sample. `AccountBridge`
now waits that window out (2 s, 2× the only late answer measured) instead of reporting `NO_ACCOUNT`,
and the probe reads the answer the same way.

An earlier version of this document said the opposite — that the runtime answers account calls
itself and sign-in could never work. That was wrong, and §22 of the audit records the measurement
that disproved it and the bookkeeping bug in the probe that had produced the false reading.

## Mobile-size UI — four geometries, screenshots read by hand

Run `43669108321` (API 34), `OK (4 tests)`: 320×640@2x, 360×720@2x, 411×891@3x and 720×360@2x
(landscape) all pass. Three real defects were found by reading the screenshots and each is now an
assertion: the empty-space sentence drew through the Add tile, the Add tile's label was clipped in
landscape, and — after the first fix — the grid collapsed to zero height in landscape so no tile was
drawn at all. Details in §25 of the audit.

## Not tested — stated, not implied

- A real Google OAuth consent screen and token against Google's servers, and Play Services / GMS on
  the device. The gates use a **seeded account of type `com.google`** answered by a test authenticator
  the instrumentation APK registers: the code path is real, the credential is fake and labelled.
- A physical phone. All device claims come from ARM64 redroid containers.
- Android 16 / API 36 (redroid 16.0.0 never finishes booting on this runner), OEM skins, tablets and
  foldables, font scale above 1.0, right-to-left locales.
- Notifications, camera, microphone, location, deep links, and performance budgets.
- How often a real cold start falls inside the <1 s account window. The window is measured; its
  frequency across devices is not, and the 2 s wait is a margin rather than a distribution.

## Failures during this stretch that were the harness, not the app

Each is fixed, and each is recorded because the first symptom looked like a product failure:
an emulator restarting mid-run after both sign-in suites had passed (run `43668222165`), an API 28
leg that went `device offline` before any test ran (`43668222149`), and an API 27 leg that read a
refused *reference* app install as "the app under test is missing" (`43592167654`). See §24.
