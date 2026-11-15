# Clone Hub Pro — verified state

Current as of commit `ec0d8a6`. Everything below is a link to a run whose evidence is archived;
nothing here is a claim without a gate behind it, and what is **not** working says so in its own
section.

## The shipped APK

[`CloneHub-Pro.apk`](https://github.com/deepsilence10161-source/Clone-Hub-Pro/releases/latest/download/CloneHub-Pro.apk)
— **7,905,790 bytes**, published 2026-11-15 by `Publish latest verified APK` (run `43670062074`),
which only publishes when both native ARM64 gates are green on the current `main`. The release notes
name the commit: `ce6181a`. The published file was downloaded again afterwards and its `classes.dex`
contains the sign-in fix, so the artifact is the fixed build and not an earlier one
(sha256 starts `5d77299b…`).

## Gates green on `ec0d8a6`

| Gate | Run | Result |
|---|---|---|
| Deep clone testing — sign-in, updater, **web sign-in parity**, cold restart | `43696006594` | success — native sign-in suite, `webview-signin`, updater gate and cold restart all green |
| API compatibility matrix | `43696006546` | success |
| Strict E2E — API 34 | `43696006660` | success |
| Real app compatibility smoke | `43696006581` | success |
| Publish latest verified APK | `43696451670` | success |

## Sign-in: what is proven, and what still fails

**The web path works inside a clone.** Deep gate `43696006594` compared a web-based "Continue with
Google" route un-cloned and inside a clone: same WebView provider, same settings, the HTTPS page
loads, the page is a secure context with `crypto.subtle`, a cookie survives a reload
(`cookie_roundtrip=true`), `accounts.google.com/gsi/client` is served (≈280 KB), and an https VIEW
intent resolves to `org.chromium.webview_shell.WebViewBrowserActivity` — inside the clone too.

**The native path still fails inside a clone.** In the same run every `AccountManager` query inside
the clone answers empty (`by_type=0`, `by_type_for_package=0`, `all=0`) while the host app lists the
device's account and receives a token. Any app that asks Android for the Google account before signing
in therefore sees "no account". That is the reproduced defect; the host-side bridge
(`AccountBridge` + `HostAccountProvider`) answers correctly from the host process, but the container's
dispatch is not routed through it yet.

**Not tested:** a real Google consent with a real Google account inside a clone (this image has no
Play services and its `com.google` account comes from the test authenticator, labelled as such); any
Play-services-dependent SDK (Games, Firebase Auth federated sign-in); and the app's own backend, which
no gate here can see.

## Gates green on `315c7e0` (the commit the in-app updater ships in)

| Gate | Run | Result |
|---|---|---|
| API compatibility matrix — API 27, 28, 30, 31, 34 (phone-UI), 35 (real ARM64 redroid images) | `43686358341` | success on every level |
| Deep clone testing — sign-in + updater + cold restart + deep suite | `43686358334` | success — sign-in `OK (6 tests)`, updater `OK (5 tests)` |
| Strict E2E — API 34 | `43686358429` | success |
| Real app compatibility smoke | `43686358368` | success |
| Publish latest verified APK | `43687313552` | success — release `build-2002`, `sha256:4bd0317…`, `size:7938150` |

Earlier green set on `ce6181a` (sign-in and phone-UI fixes): matrix `43669108321`, deep `43669108293`,
strict `43669108324`, smoke `43669108378`, publish `43670062074`.

## In-app updates — how a new build reaches an installed copy

The app updates itself from its own release, YMusic-style: **overflow menu → App updates**.

- **Channel:** `GET /releases/tags/latest-verified` — read by tag because the "latest" endpoint was
  measured pointing at an archive release. The release title carries `build-<versionCode>` and the
  body carries `sha256:` and `size:`, written by the publish workflow from the APK itself (`aapt2`),
  not from the gradle file.
- **Comparison:** `versionCode` only. A release without one is reported as "cannot tell", never as
  "up to date".
- **Download:** into `cache/updates`, checked against the published SHA-256; a mismatch deletes the
  file.
- **Install:** handed to Android's installer through a `FileProvider` limited to `cache/updates`. The
  app records whether the platform accepted the intent and switches to "open the verified APK" when it
  does not.
- **Signing:** every build is signed with the committed key at `.github/signing/clonehub-signing.p12`
  (fingerprint `E2:F0:84:3C…`), which the deep gate verifies on the device
  (`signing_key … matches=true`). Without a stable key Android refuses every update; three earlier CI
  runs were measured carrying three different keys, which is why the key is now committed and
  documented.

**Still owed:** a real update from an installed build to a newer one. Everything around it is measured
(versionCode, signature identity, digest, FileProvider, installer intent), but performing the update
replaces the binary under the running test — the end-to-end proof is the next gate run reading a
higher `versionCode` from the device after installing over the previous APK. The Android 14
intent-URL refusal is handled in code and has not been reproduced on a device yet, which is why the
panel reports `path=UNKNOWN` rather than claiming a working silent install.

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
