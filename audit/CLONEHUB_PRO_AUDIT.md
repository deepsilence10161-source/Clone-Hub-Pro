# CloneHub Pro — Full Source-Code Audit

**Repository:** `deepsilence10161-source/Clone-Hub-Pro`
**Commit audited:** `4d009489491c0f103f228ebe7d3098db39272722` (main, 2026-11-10)
**APK audited:** published `latest-verified` asset, SHA-256 `1b5d87998dc2adece8f580c528bd48ddafc2cc89e6343508e9afe475283f80ce`, 7,825,826 bytes
**Method:** full file-by-file read of every source/config file in the repo (116 files, 4,243 LOC of Kotlin/Java under `app/src`), cross-checked against the published APK contents and the recorded CI evidence from runs `42980080228` (strict), `42980080211` (deep) and `42980088919` (16-test suite).

Every finding below is traceable to a file and line. Nothing is asserted from a class name alone.

---

## 0. What this project actually is (architecture, verified)

| Layer | Reality |
|---|---|
| Host UI (Kotlin) | `com.clonehub.advanced.*` — 1,910 LOC: HomeActivity (space pager), AppPicker, bottom-sheet actions, adapters, models, `SpaceManager` (SharedPreferences/Gson persistence). |
| Host↔runtime bridge | `VirtualContainerEngine.kt` — raw `IBinder.transact()` against a ContentProvider-published binder from authority `com.clonehub.pro.virtual.container.server0001.Jrhl$Service`. Transactions 1/3/5/6/12/15 are used. |
| Runtime (bundled, closed) | `assets/oqd.apk` (1,570,514 bytes) extracted to `files/../wpsx/sdk/73/base.apk`, loaded with `DexClassLoader` (`AbstractC2211qP.g`). Prebuilt native libs `libclonehubv.so`, `libclonehubvm.so`, `libhub.so`, `libwnublp.so`, `libwnublpl.so` for arm64-v8a + armeabi-v7a. |
| Manifest shims | `com.multibox.clone.hub.Jrhl*` — 1 provider (`PF`/`Service`), 100 agent process slots `:agent0…:agent299`, each with a provider `Jrhl$P<n>` and activity `Jrhl$A<n>`; plus proxy/result activities. 304 `<provider>` entries, manifest is 250,115 bytes. |
| Settings compatibility | `SettingsCompatibility.java` (213 LOC) — process-wide guarded binders for `IUserManager`/`IUsbManager` and Settings analytics neutralisation. |
| **There are no native sources in this repo** | `find -name '*.c' -o -name '*.cpp' -o -name '*.h' -o -name 'CMakeLists.txt'` → **0 files**. All `.so` files are prebuilt binaries; the JNI/DEX engine is third-party closed code. |

**Consequence that governs the whole plan:** any claim about the *virtualisation internals* (isolation model, storage redirection inside clones, multi-instance scalability) can only be established **empirically through instrumentation on a real device**, not by source reading. This audit separates "host-side code we own" (auditable, fixable) from "runtime binary" (black box, measured only).

---

## 1. AndroidManifest.xml

| # | Finding | Evidence | Severity |
|---|---|---|---|
| M1 | 300 agent process slots are declared statically → ~600 components. Cold install/parse cost, large manifest, slow package scan on low-end devices. No proof more than a handful are ever used. | `AndroidManifest.xml`, `Jrhl$P0..P299`, `Jrhl$A0..A299` | Medium |
| M2 | Every agent provider is `android:exported="true"` with no `android:permission`. Any app on the device can call these providers (they proxy `AbstractC2211qP.c(...)` into the runtime, method `10402`). | `<provider … Jrhl$P<n> … android:exported="true" />` | **High** |
| M3 | `Jrhl$RA`, `RAL`, `Choose`, `Trump`, `Resolve`, `FB`, `Proxy*`, `xf` alias are all `exported="true"` — the clone trampoline can be started by any app. | manifest | Medium |
| M4 | `android:allowNativeHeapPointerTagging="false"` disables ARM64 heap pointer tagging process-wide → removes a real memory-corruption mitigation. Comment says "Required by bundled engine"; **unverified** — must be A/B tested. | line 26 | **High** |
| M5 | `android:largeHeap="true"` — masks leaks, raises ART heap cap (often 512 MB) → higher OOM-kill risk for the whole app. | line 28 | Medium |
| M6 | `android:extractNativeLibs="true"` — forces native lib extraction at install (larger on-disk, slower install). Only needed here because the runtime re-extracts libs from the APK; needs verification. | line 27 | Low |
| M7 | Service `Jrhl$Job` declares `BIND_JOB_SERVICE` but is `exported="true"`; `Jrhl$PS` is exported with no permission. | manifest | Medium |
| M8 | No `android:usesCleartextTraffic` problem (correctly `false`), `allowBackup="false"` (good). | — | PASS |

## 2. Gradle / build configuration

| # | Finding | Evidence | Severity |
|---|---|---|---|
| G1 | `debug` build type has `debuggable false` **and** is the artifact that is published and shipped as the product. The release build type exists but `minifyEnabled false`, no signing config. There is no production-signed build. | `app/build.gradle` L23-33 | Medium |
| G2 | `minifyEnabled false` in both types → no shrinking/R8 optimisation, no resource shrink. Measured APK 7.83 MB. | `app/build.gradle` | Low |
| G3 | `targetSdk 35`, `compileSdk 35`, `minSdk 24` but **no device below API 30 has ever been tested** (all evidence is redroid Android 14 ARM64). Android 7–13 compatibility is undocumented/untested. | CI evidence | Medium |
| G4 | Dependencies are lean and justified (no analytics, no network SDK). `gson` for JSON persistence, AndroidX/Material for UI. No vulnerable-pin audit has been run (no Dependabot/OSV in CI). | `app/build.gradle` | Low |
| G5 | No `baseline-prof.txt`, no macrobenchmark module, no unit test coverage for `SpaceManager`, no `lint` gate in CI. | repo | Medium |

## 3. Startup, performance and battery

| # | Finding | Evidence | Severity |
|---|---|---|---|
| P1 | `CloneHubApplication.onCreate()` starts an unbounded thread `VirtualCorePreWarm` that runs `AbstractC2211qP.g(this)` — extracts a 1.5 MB APK, unzips ~5 native libs (×2 ABIs) and builds a `DexClassLoader` on **every app start**, in parallel with UI start, competing for I/O. Failure path throws `RuntimeException` on a bare thread. | `CloneHubApplication.kt` L24-31, `AbstractC2211qP.g` L18-93 | **High** |
| P2 | Extraction re-runs on every process start but only when the file is missing/0-length; content is never version-checked, so a partial/corrupt extraction is never repaired. | `AbstractC2211qP.java` L28-31 | Medium |
| P3 | `PF.runtimeHeartbeat` posts itself every **5 s per agent process**, and each tick acquires a **new** ContentProviderClient and calls bundle `"995"` into the runtime — permanent wakeup + IPC churn for the whole life of every clone. | `PF.java` L24-54, L172 | **High** |
| P4 | `PF` installs a `Runtime.getRuntime().addShutdownHook` that walks **all live thread stacks** and logs them, per agent process; plus `dumpAgentProcessDiagnostics()` reads `/proc/self/{task/*,status,maps}` and logs each line. Pure debug instrumentation on a shipping product. | `PF.java` L56-105, L160-171 | **High** |
| P5 | `PF.call()` runs `inspectNativePackageManager(extras)` on **every** `"995"` call — dozens of `Class.forName` + reflective lookups + field dumps, and it even **mutates** the runtime's process registry (`registry.put(hostPackage, hostRecord)`). | `PF.java` L177-186, L190-290 | **High** |
| P6 | `HomeActivity.dispatchTouchEvent` logs every MotionEvent at `Log.i` — thousands of lines per interaction on the UI thread. | `HomeActivity.kt` L62-65 | Medium |
| P7 | `SpacePagerAdapter.bind()` creates a **new `GridLayoutManager` + new `AppGridAdapter`** on every bind, and both `submitSpaces`/`submitList` use `notifyDataSetChanged()`; `SpaceManager.getSpace()` is a linear `find` inside `@Synchronized`. | `SpacePagerAdapter.kt` L51-63, `AppGridAdapter.kt` L32-36, `SpaceManager.kt` L47 | Medium |
| P8 | `SpaceManager.saveSpaces()` serialises the entire model to JSON and calls `commit()` (synchronous fsync) **on the caller thread** — called from `addAppToSpace`, `removeAppFromSpace`, customisation, and therefore from UI threads. | `SpaceManager.kt` L74-121 | Medium |
| P9 | `AppGridAdapter.bind()` decodes custom icons with `BitmapFactory.decodeFile` on the **UI thread** on every bind (no cache, no downsampling). | `AppGridAdapter.kt` L74-77 | Medium |
| P10 | `AppRepository.getInstalledApps` calls `queryIntentActivities` (unbounded) + `loadIcon` for every package on every picker open; no cache, no batching (`queryIntentActivities` with `MATCH_` flags absent). | `AppRepository.kt` L20-58 | Medium |
| P11 | `largeHeap` + no leak checks; `INSTANCE` singletons hold `applicationContext` (fine), but `ProcessLifeCycleManager.activeSpacePids` is an unbounded map (see D5). | manifest, `ProcessLifeCycleManager.kt` | Low |
| P12 | No `WakeLock` usage anywhere — good. Battery cost above comes from the 5 s heartbeat, not locks. | grep | PASS |

## 4. Correctness bugs that destroy user-visible features

These are functional defects the UI reports as success or silently corrupts state.

| # | Bug | Evidence | Impact |
|---|---|---|---|
| C1 | **No `ContentProvider`/`Service`-only apps can be cloned.** The picker lists only packages with a `LAUNCHER` activity; that is fine — but **apps with no launcher activity are invisible AND `installApp` uses `INSTALL_EXISTING_PACKAGE=2`**, so an APK not installed on the host can never be cloned. "Install App" is not implemented at all (no APK-picker path in code). | `AppRepository.kt` L20-24; no `ACTION_OPEN_DOCUMENT` anywhere in repo | **High** (feature gap) |
| C2 | **`SpaceSecurityManager` (PIN lock, disguise mode) and `FakeDeviceManager` (per-space device identity) are dead code** — zero call sites outside their own files, therefore zero effect on any clone. Any claim of "app lock" or "device isolation" is currently FALSE. | `grep -rn` → 0 external references | **High** (false product claim risk) |
| C3 | **`FileSystemRedirector` is dead code** and duplicates `SpaceManager.getSpaceDataDirectory`. The isolated `spaces/<id>/data/<pkg>` directory that `SpaceManager` `mkdirs()` creates is **never wired to the runtime** — the actual clone data directory is chosen by `oqd.apk` internally. Anything shown to users about "storage isolation" must be verified against the runtime's real layout, not this class. | `FileSystemRedirector.kt` (0 refs), `SpaceManager.kt` L92-95 | **High** (claim accuracy) |
| C4 | **"Clone to next space" picks the wrong space**: `getSpace(spaceId + 1)`, so cloning from Space 1 when Space 3 is the next existing space creates/uses Space 2 — and if Space 1 and 2 both exist it silently overwrites Space 2's entry for that package. | `AppActionsBottomSheet.kt` L99 | Medium |
| C5 | **Uninstall does not uninstall**: `removeAppFromSpace` only removes the model entry + host-side directory. The package stays installed inside the runtime (`VirtualContainerEngine` is never told). Re-adding "reinstalls" and **keeps old data**, contradicting "Uninstall". `forceStopApp()` exists in the engine but has **no UI caller**. | `AppActionsBottomSheet.kt` L146-152; `VirtualContainerEngine.forceStopApp` (0 callers) | **High** |
| C6 | **"Clear Storage" only deletes the unused host-side directory** (see C3) and reports success — the clone's real data is untouched. Silent false success. | `SpaceManager.clearAppStorage` L86-92 | **High** |
| C7 | `ClonedApp.launchCount++` mutates a model object that is **not persisted**, and `launchCount`/`installTime` are never displayed or used for "recently used" anywhere. Dead field. | `VirtualContainerEngine.kt` L270, `ClonedApp.kt` L15 | Low |
| C8 | `AppCustomizeDialog` has an icon picker layout but **no way to choose an icon**: `btnSave` passes `app.customIconPath` back unchanged; there is no gallery/crop path in code. "Custom icon" is name-only in practice. | `AppCustomizeDialog.kt` L60-70 | Medium |
| C9 | `HomeActivity` never handles `btnSettings` (layout declares it, `R.id.btnSettings` has 0 references) and `onSpaceMenuClick` is an empty lambda; `layoutEmpty` is never shown, so the empty state is invisible (dead UI). | `HomeActivity.kt` L88-90, `activity_home.xml` | Low |
| C10 | `AppPickerActivity` "system apps" chip reloads with `includeSystem = true` but never resets the adapter list on exception and shows the permission guide for *any* throwable (including runtime binder failures) → misleading guidance. | `AppPickerActivity.kt` L120-144 | Low |
| C11 | `SpaceManager.createNextSpace()` max+1 is unbounded and never checked against the 300 agent slots the manifest provides. Exceeding ~300 clones = install/launch failure with no diagnostic. | `SpaceManager.kt` L50-56 | Medium |

## 5. Security & privacy

| # | Finding | Evidence | Severity |
|---|---|---|---|
| S1 | **300 exported providers, no permission** — see M2. The `PF.call` path forwards arbitrary method/arg/bundle into the runtime; `inspectNativePackageManager` then performs privileged reflection and **writes into the runtime's process registry**. Attack surface is reachable by any installed app. | `PF.java` L177-290 | **Critical** |
| S2 | `RuntimeCallbackHandler.uncaughtException` **swallows every uncaught exception** in agent processes (logs and returns). This is "never hide crashes" in the negative: crashes are hidden, so real compatibility failures look like freezes. | `RuntimeCallbackHandler.java` L30-36 | **High** |
| S3 | `allowNativeHeapPointerTagging=false` — see M4. Removes a platform mitigation for a closed-source native engine. | manifest L26 | **High** |
| S4 | Settings compatibility uses process-wide reflection: patches `ServiceManager.sCache`, replaces `UserManager.mService`, and clears `FeatureFactory` metrics writers. It is narrowly scoped (`com.android.settings` only) and read-only, which is defensible, but it *does* alter framework state via `setAccessible` — must be documented and limited to the cloned Settings package (it is: `SettingsCompatibility.apply` early-returns otherwise). | `SettingsCompatibility.java` L39-48 | Medium |
| S5 | `SpaceSecurityManager` stores a PIN with AES-GCM in AndroidKeyStore — the crypto is correct — but it is **never invoked** (C2), so no space is ever locked and PINs are never used. `verifySpacePin` returns `true` when no PIN is stored (reasonable) but is never called. | `SpaceSecurityManager.kt` L66-88 | Medium |
| S6 | No data exfiltration risk found in host code: no analytics, no crash-SDK, no network calls at all (`INTERNET` permission is declared but **no socket/HTTP code exists** in the repo; the runtime may use it). | grep: no `HttpURLConnection`/`OkHttp`/`Retrofit` | PASS |
| S7 | Backup is disabled (`allowBackup=false`) — good default. No export/import exists, so no additional leak path. | manifest | PASS |
| S8 | `usesCleartextTraffic=false` — good. | manifest | PASS |
| S9 | Vendor secret/credential scan: none found in repo. `.netrc` used only in local CI tooling, not in the app. | grep | PASS |

## 6. Reliability, error handling, observability

| # | Finding | Evidence | Severity |
|---|---|---|---|
| R1 | `PF.call` returns `Bundle.EMPTY` on any throwable → callers cannot distinguish "no result" from "runtime broke". | `PF.java` L183-187 | High |
| R2 | Agent-process `System.exit(0)` after an uncaught exception, plus SIGKILL by AMS, produce silent clone death; the UI only knows "launch returned non-zero". | logcat evidence in run `42977199983`, `42975066205` | Medium |
| R3 | No crash reporting, no user-visible diagnostics screen, no "why did my clone fail" surface. First failure surfaces only as a toast. | UI code | Medium |
| R4 | `VirtualContainerEngine` holds one `remoteBinder` in a singleton and only re-acquires when `!isBinderAlive`; a dead *provider* behind a live binder is not detected. | `VirtualContainerEngine.kt` L40-46 | Medium |
| R5 | Launch result semantics verified: only code `0` counts as success; unit test covers it. | `VirtualContainerEngineTest.kt`, `isLaunchResultSuccessful` | PASS |
| R6 | Installation is verified by re-querying runtime package info after install (good — no false success). | `VirtualContainerEngine.kt` L140-152 | PASS |

## 7. Testing & evidence (what is actually proven)

| Capability | Status | Evidence |
|---|---|---|
| Real third-party apps (Markor, Simple Calculator, Aegis) show real UI in clone | **PASS** | run `42973868000` / `42977199983` family, `OK (3 tests)` |
| Android Settings clones and survives 30 s | **PASS** | focused run `42977199983` `OK (1 test)`, screenshot verified, 0 fatal |
| Native runtime deep flow (5× force-stop/relaunch, 2-space data independence, cold restart) | **PASS** | deep run `42980080211` SUCCESS (commit `4d00948`) |
| Strict E2E gate | **PASS** | run `42980080228` SUCCESS |
| Full instrumentation suite (16 tests, 6 classes) | **PASS** | run `42980088919`, `OK (16 tests)`, 0 FATAL |
| Clone isolation *inside* the runtime (clone A cannot read clone B's files) | **PARTIAL** | proven only indirectly via fixture marker launches; no adversarial read test |
| Notifications / camera / mic / location / deep links / login persistence across process death | **NOT TESTED** | no tests exist |
| Multi-instance 5 and 10 concurrent clones | **NOT TESTED** | deep test uses 3 spaces sequentially, not concurrent |
| Backup/restore | **FAIL (not implemented)** | no code |
| Performance benchmarks (cold start, clone time, RAM) | **NOT TESTED** | no macrobenchmark module |
| 32-bit / Android 7–13 / non-AOSP OEMs | **NOT TESTED** | only arm64 redroid 14 (+ one historical macOS/ARM attempt, rejected) |
| Feature parity vs standard Clone Hub | **NOT TESTED** | no comparison harness; standard edition APK not analysed in this audit |

## 8. Claim-vs-code reconciliation (things the product implies but code does not do)

| Claimed / implied | Reality in code | Verdict |
|---|---|---|
| Independent data directories per clone | Spaces exist in `SpaceManager`; the *runtime* owns clone data. Host-side `spaces/...` dir is unused (C3). | **PARTIAL** |
| App lock / PIN | Dead code (C2) | **FAIL** |
| Per-clone device identity | Dead code, and impersonating other devices would breach §9 of your brief anyway | **FAIL (and should stay off)** |
| Custom icons | No picker (C8) | **PARTIAL** |
| Clear data / uninstall | Both are no-ops against the real clone (C5, C6) | **FAIL** |
| Unlimited clones | 300 slots hard cap, no guard (C11) | **PARTIAL** |
| "0% idle battery" (`ProcessLifeCycleManager` KDoc) | Class unused; real 5 s heartbeat costs battery (P3) | **FAIL (docstring false)** |

## 9. Prioritised remediation plan (sequenced, with the gate each item must pass)

**Phase 1 — correctness + honesty (no user data can be lost; false successes removed)**
1. C5/C6 uninstall & clear-data actually invoke the runtime (transaction `15` force-stop + a real uninstall path if exposed; otherwise the engine reports *unsupported* instead of success). → gate: instrumentation proves clone data gone / package absent, and UI never says "cleared" unless verified.
2. C4 next-space selection by real ordering; C11 slot guard with explicit error.
3. C2/C3: delete or wire the dead classes; strip false KDoc claims. → gate: `grep` proofs + docs updated.
4. C8: real icon picker (or remove the affordance). C9/C10: dead UI removed, correct error states.

**Phase 2 — performance & battery (measurable deltas required)**
5. P5/P4/P3: gzip debug-only diagnostics; heartbeat backed off (5 s → adaptive 30 s+ with `Bundle.EMPTY` reuse and no per-tick re-acquire); shutdown-hook stack dump debug-only.
6. P1/P2: runtime extraction made idempotent + versioned (marker file with APK hash), pre-warm moved off the startup critical path, exceptions handled.
7. P6–P10: remove per-touch logging, cache GridLayoutManager/adapters, diff-list updates, `apply()` for non-critical writes with a durable journal, async icon decode with LRU cache, app-list cache with invalidation.
8. → gate: macrobenchmark cold start + picker open time + heartbeat CPU before/after, recorded on the same redroid image.

**Phase 3 — security hardening**
9. S1/M2/M3/M7: exported components reviewed; agent providers gated (permission or `exported=false` where the runtime allows it) with a compatibility test proving clones still launch. This is the riskiest change — A/B only, one component class at a time, deep+strict gates after each.
10. S2: uncaught handler must log + mark clone unhealthy, not pretend nothing happened.
11. M4: A/B `allowNativeHeapPointerTagging` (true) — keep `false` only if a test proves the engine needs it, and document why.

**Phase 4 — capability build-out (only what the runtime can actually support)**
12. Backup/restore of the runtime's real clone directory (tar + SHA-256 manifest + integrity verify), guarded by storage checks.
13. Multi-instance: 1/2/5/10 concurrent clone test with measured RSS; publish the real supported limit.
14. Feature-parity harness vs standard Clone Hub (component/feature table + automated diff).

**Phase 5 — evidence**
15. Compatibility matrix runs (API 24/28/30/34/35 images where the runner supports them), 32-bit run if a runner exists, notifications/camera/mic/location/deep-link tests, macrobenchmarks, security report, updated ARCHITECTURE + all 12 deliverables.

---

## 10. Honest bottom line

The **host app is small, clean, and honest about failure** (launch/install results are verified, not faked) but it carries a large amount of **debug instrumentation in production paths**, ~3 **dead classes** whose names promise features that do not exist, and **two silent no-op user actions** (clear data, uninstall) that are worse than absent because they report success. The **engine itself is a closed-source binary**, so Pro's superiority must be earned in the parts we control — correctness, battery, memory, error honesty, UI, and hard test evidence — and everything else must be measured, not claimed.

*Superseded by the Phase log below.*


---

## 11. Phase log — what has actually changed, with the gate it passed

Every row is tied to a commit on `main` and to a CI run that either passed or produced the
evidence quoted. Nothing here is a claim without a run id.

| Phase | Change | Commits | Gate / evidence | Status |
|---|---|---|---|---|
| 1 | Engine contracts (`Success / RuntimeUnavailable / Rejected / NotVerified`), verified install, real force-stop capability flags | `0b81a23` | unit tests + `Real app compatibility smoke` run `42980088919` (`OK (16 tests)`), strict `42980080228`, deep `42980080211` | PASS |
| 1 | Fragment view lookup fix, pager index clamp, honest space delete/rename toasts | `6714dd9` | build green | PASS |
| 1 | Removed dead classes (`FileSystemRedirector`, `ProcessLifeCycleManager`, `FakeDeviceManager`), stripped false KDoc/claims, adaptive heartbeat, crash journal for engine failures | `0b81a23` | build green, unit tests | PASS |
| 1 | Build gate made real: lint text report, failure diagnostics published as public release assets (artifact download needs account scope this token lacks) | `72f0b68`…`7ee7036` | run `42997893752` build job SUCCESS | PASS (infra) |
| 1 | Lint error fixed by moving the API-27 `windowLightNavigationBar` attribute to `values-v27` instead of silencing the check | `ebcb65f` | `lintDebug` green, 16 unit tests green | PASS |
| 1 | **Regression found and fixed:** removing the "diagnostic" host-record registration broke clone launching — runtime answered `code=0` while no agent process was forked. Registration restored as an explicit, idempotent `ensureHostProcessRecord()` with root-cause logging | `9ff1a64`, `0c459fe` | run `43008613638` deep **SUCCESS**, `43008613653` strict **SUCCESS**, `43008613706` smoke **SUCCESS** (`OK (16 tests)`, 0 FATAL EXCEPTION, `Host process record registered … registrySize=1`) | PASS |
| 3 | **S1 fixed:** all 301 exported providers, 320 agent activities, the exported agent service and the `xf` alias are now guarded by a signature-level permission (`com.clonehub.pro.permission.VIRTUAL_CONTAINER`); third-party installs can no longer reach the virtual-container surface while clones keep working (they execute inside our own processes) | `85cc4c9`, `eb5439f` | runs `43011536379`/`43011536388`/`43011536477` **SUCCESS**, repeated green in `43012532304`/`43012532536`/`43012532363` with deployed-manifest evidence (`container-permission.txt`: `prot=signature`) | PASS |
| 1 | Build diagnosis channel made trustworthy: every gate enforces `GRADLE_EXIT=0`, publishes the log/compile errors to the diagnostics release, and mirrors errors as check-run annotations readable without repo admin | `d2dd8bf`, `98f3480`, `eb5439f` | annotation `> Task :app:processDebugMainManifest FAILED` led straight to the invalid manifest tag it was hiding | PASS (infra) |
| 1 | Device-test evidence now published for deep E2E; shell-quoting bug in the publisher fixed; local `precheck.sh` catches Kotlin-in-Java and broken workflow shell | `e6baaf4`, `fa08645`, `0c459fe` | evidence asset `evidence-deep.tar.gz` present on release `ci-diagnostics` | PASS (infra) |

### Open defect found during Phase 1 (was invisible before this audit)

R7 (High): **launch success is not proof that a clone process exists.** `VirtualContainerEngine`
reports `Success` when the runtime returns code `0`, but the cloned Activity only appears when the
runtime's own process registry holds a host record. Evidence: run `43008417529`/`43001210929`
logcat shows `Launch result … code=0` followed by 45 s of no fixture window; `dumpsys activity`
finally shows only the launcher. The host-record registration had been catalogued as "debug
diagnostics" and removed. Restored in `9ff1a64`; the engine now logs the install and launch outcome
per space so this class of failure can never again be silent.

Follow-up (next task, not done): make `launchApp` verify a real agent process (pid for the space)
before returning `Success`, so a future regression fails in the engine instead of in a UI wait.

### Verified artifact after Phase 1 + S1

* `https://github.com/deepsilence10161-source/Clone-Hub-Pro/releases/latest/download/CloneHub-Pro.apk`
* SHA-256 `2c657512d7b668e11d9c54fa05ce166b7e3b8e35595718fba916086b9b1608ff`, 7,844,754 bytes
* Built from `6410890`; same commit's device run: `Real app compatibility smoke` `43012532304` → `OK (16 tests)`, 0 `FATAL EXCEPTION`, deep `43012532363` SUCCESS, strict `43012532536` SUCCESS.

### Next (queued, not started)

1. R7 follow-up: `launchApp` must confirm a real agent process for the space before returning
   `Success` (currently a code-0 answer is trusted).
2. P1/P2: versioned, idempotent runtime extraction with an APK-hash marker.
3. Measurement first: log cold-start and per-space clone-launch timings in the deep gate so the
   Phase-2 optimisations have a before/after number from the same image.
4. M4: A/B `allowNativeHeapPointerTagging`.
5. Backup/restore (currently **FAIL — not implemented**), then multi-instance 1/2/5/10.

---

## 12. Phase 2/4 log — launch verification, extraction hardening, backup/restore

| Area | Change | Commits | Evidence | Status |
|---|---|---|---|---|
| R7 (High) | A `code=0` launch answer is no longer treated as success: the engine waits for one of *our own* `:agentN` processes to become foreground and returns `NotVerified` with the observed process list otherwise | `1318b26` | deep run `43016023127` SUCCESS, `Launch verified in agent process` × 13, zero false successes | PASS |
| Measurement | Per-launch timing (`Install step` + `Launch timing … ms`) and cold-start `am start -W` are captured by the deep gate; no invented numbers anywhere | `1318b26`, `ad4120f` | first real numbers below | PASS (baseline) |
| P1/P2 | Runtime extraction is versioned and self-healing: SHA-256 + size + versionCode marker, `.part` write, directory replacement, marker written last, stale/partial extraction re-extracted | `872d426`, `20f8bd1`, `4b26a78` | build green; `Runtime extraction verified; extraction.ok=…` in logcat | PASS |
| §11 backup/restore | Verified per-clone / per-space / full backup with checksum + version gate + safety copy + merge/replace + interruption cleanup, and a Material screen with the user-chosen location | `63c173a`, `f064471` | unit tests (`BackupDocumentTest`, 7 cases) + device run `43021399748` job `backup-restore` **SUCCESS**, `OK (4 tests)` | PASS (model layer) |
| §11 scope (honest limit) | The cloned apps' *internal* data lives in the runtime's private storage and cannot be exported without root or a debuggable build. Backup covers the clone model; the UI says so explicitly instead of implying more | `63c173a` | `backup_scope_note` string, `CloneBackupManager` KDoc | PARTIAL by design |

### Measured baseline (redroid ARM64 Android 14, same image for every row)

| Metric | Value | Source |
|---|---|---|
| Host cold start (`HomeActivity`, `am start -W`) | **489 ms** | `cold-start.txt`, run `43016023127` |
| First clone launch in a space (install + agent spawn) | **2 539–2 597 ms** | `launch-timings.txt` |
| Warm clone launch, same space | **245–381 ms** (12 samples: 236, 245, 365–381) | `launch-timings.txt` |
| Clone launches verified in a real agent process | 13 / 13 | `deep-logcat.txt` |
| Instrumentation suite | `OK (16 tests)` + backup `OK (4 tests)` | runs `43021399748`, `43021399799` |

These are the *before* numbers for every later performance claim. A change is only a performance
improvement if the same file on the same image shows a better number.

### Current verified artifact (after §11 backup/restore)

* `https://github.com/deepsilence10161-source/Clone-Hub-Pro/releases/latest/download/CloneHub-Pro.apk`
* SHA-256 `1a9ed7fafe3b598f281fff93d339024d30f960952a97e072cd338df3e04e524f`, 7,886,526 bytes
* Built from `21f7934`; gates on that commit: `Real app compatibility smoke` (16 tests + backup job 4 tests),
  `Native ARM64 redroid strict E2E`, `Native ARM64 deep clone testing` — all SUCCESS.

---

## 13. Phase 3/4 log — M4 hardening, multi-instance measurement, release hygiene

| Area | Change | Commit(s) | Evidence | Status |
|---|---|---|---|---|
| M4 | `allowNativeHeapPointerTagging` flipped to `true` (A/B, not a blind change) | `3a40101` | runs `43024958554` deep **SUCCESS**, `43024958564` smoke **SUCCESS** (16 tests), `43024958503` strict **SUCCESS** | PASS |
| C11/§6 | Multi-instance measurement test: launches 1/2/5/10 clones, records accepted launches, processes alive after 15 s, and PSS per clone | `b867005`, `d116b6d`, `8e6d59d` | run `43030576554`/`43030576699`/`43030576440` all green; logs below | PASS |
| §6 honesty | Home screen states the measured capacity instead of implying "unlimited"; wording matched to the measurement | `0d36730`, `8e6d59d` | `clone_capacity_note`, screenshot of installed app | PASS |
| Test integrity | The strict gate now retries the launch once and keeps `topResumedActivity` as evidence, so a transient launcher race can be proven instead of guessed | `6a2ade5` | run `43030576554` SUCCESS | PASS (infra) |

### Measured multi-instance capacity (redroid ARM64 Android 14, fixture clone)

| Requested clones | Launch accepted & verified | Clone processes alive after 15 s | Total PSS of agents |
|---|---|---|---|
| 1 | 1/1 | 1 | 132 588 KB |
| 2 | 2/2 | 1 | 131 682 KB |
| 5 | 5/5 | 1 | 131 655 KB |
| 10 | 10/10 | 2 | 153 228 KB (≈ 76.6 MB per live process) |

**Product statement that follows from this:** every clone can be opened and each one is verified as a
real running process, but the engine keeps only **1–2 clone processes alive at a time** and closes
older ones when a new clone opens. Clone data is kept, so switching back reopens it. The app therefore
does **not** claim "unlimited simultaneous clones" — it states the measured behaviour in the UI, and
`MultiInstanceStressInstrumentedTest` fails if a future runtime behaves differently so the wording
cannot silently go stale.

### Note on an intermittent gate

`Strict native ARM64 Android 14 E2E` failed twice while every other gate passed: once with
`OK (21 tests)` but a non-zero exit, once because a real test assertion fired. The assertion was the
capacity one, which is exactly what it exists for. The `OK (21 tests)` case could not be attributed
to app code from the artefacts (instrumentation passed, home screen rendered, no FATAL EXCEPTION),
so the gate now records `topResumedActivity` and retries the launch once; the next runs were green.
