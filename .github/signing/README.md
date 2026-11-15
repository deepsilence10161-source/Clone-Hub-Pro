# The update signing key

`clonehub-signing.p12` is the key **every** build is signed with from now on, and it is committed to
this repository on purpose.

## Why a committed key, and why this is not a mistake

Android installs an update only when it is signed with the *same key* as the installed app. That was
measured, not assumed: three separate CI runs of this project produced three different signing keys —

| build | SHA-256 prefix of the signing certificate |
|---|---|
| strict run `43668222178` (commit `c90418d`) | `f4de4c14cbac15a36d1b7bf494e18a26` |
| published APK of `ce6181a` | `7d45e14ccb62d19f8168006b50d20ae7` |
| deep gate `43684094881` (`versionCode 2002`) | `aaa69af9f1544039ae1fa849333b9476` |

Gradle generates that debug keystore once per machine, so the key changed with the runner. An
in-app updater cannot work under those rules — Android would answer `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
for every update it offered.

This key fixes that by being the same bytes on every machine:

- created here on 2026-11-15, RSA 2048, valid until 2056;
- `PKCS12`, alias `clonehub`, store and key password `clonehub` (stated in `app/build.gradle`);
- certificate fingerprint `E2:F0:84:3C:8F:5C:C9:E8:DC:67:A7:AF:86:08:15:D6:AF:2A:E9:F0:BC:7C:6C:A0:11:F5:8D:75:6F:B4:81:B8`.

It is a **debug-grade key**: it is public, anyone can sign an APK with it, and it must never guard
anything that matters. That is exactly the trust level this app had before — the previous keys were
public defaults too, only *unstable* public defaults. What changed is that the key is now stable, which
is the one property an updater needs.

## What a production build would do differently

A release build should sign with a key kept out of the repository (a CI secret or a Play App Signing
key) and the updater would keep working unchanged, because the rule it depends on is "same key as the
installed app", not "this particular key". Until this project has a release pipeline, this key is the
honest choice: a stable key, documented, and rotated consciously.

If the key is ever replaced, every installed copy must be reinstalled by hand once — Android treats a
new key as a different app. The update screen shows the fingerprint it is running with, so the change
is visible instead of mysterious.
