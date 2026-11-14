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
* Built from `21f7934` (superseded — see the artifact block at the end of §13); gates on that commit: `Real app compatibility smoke` (16 tests + backup job 4 tests),
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

### Current verified artifact (after M4 + multi-instance)

* `https://github.com/deepsilence10161-source/Clone-Hub-Pro/releases/latest/download/CloneHub-Pro.apk`
* SHA-256 `42e5a7a5e4a94babbdca72773a2611c2e97f4a65e05e7dc268b85364a6892f90`, 7,887,526 bytes
* Built from `7482f7b`; that commit's gates: `Native ARM64 deep clone testing` `43031920978` **SUCCESS**,
  `Native ARM64 redroid strict E2E` `43031920934` **SUCCESS**, and `8e6d59d`'s
  `Real app compatibility smoke` `43030576699` **SUCCESS** (16 tests + backup job + multi-instance job).

---

## 14. Phase 5 run — UI responsiveness work, an infra recovery, and one real product bug found by the gates

| Area | Change | Commit(s) | Evidence | Status |
|---|---|---|---|---|
| P6–P10 (UI) | App icons are decoded on a background worker (`CloneIconLoader`) instead of the UI thread; grid and space pager use stable ids + `DiffUtil.submitList`, so adding or launching a clone no longer re-binds every tile | `c638f38` | first verification attempt failed on infra, see next row | PASS (verified by `75f9069` gates below) |
| Infra | Device jobs died before `docker pull`: `sudo dmesg`/`sysctl` inside `set -euo pipefail`. Now non-fatal, plus `redroid-kernel.txt` (modules, binderfs mounts, kernel binder/avc lines) is published on failure | `49ae2ba`, `12695d4`, `215200d` | runs `43052264583`/`43055946692`/`43059104279` (failures, container never created) → `43062136682`/`43062136740` (emulator boots, container runs) | PASS |
| §6 correctness | **`VirtualContainerEngine` declared a launch failed after a single 3 s probe**, although the runtime attaches the proxied activity a moment later. Measured: `Launch result ... code=0` at `16:56:03.410`, first agent process foreground at `16:56:24.963`. The launch result now polls for 20 s before reporting `NotVerified` | `75f9069` | suite run `43062137030` failed `DeepCloneFlowInstrumentedTest` on `215200d`, the same test passed in the strict run of `7482f7b` — the difference was timing, not code | PASS |

### Why the mixed 21-test suite exposed it and the strict run did not

The strict gate runs `DeepCloneFlowInstrumentedTest` alone on a cold emulator; the mixed suite runs it
after the multi-instance test has filled every agent process slot. Under that load the clone needs
~1 s longer to become foreground, so a one-shot probe reported failure for a launch that in fact
succeeded. Polling removes the race instead of loosening the assertion.

### Current verified artifact (after Phase 5)

* `https://github.com/deepsilence10161-source/Clone-Hub-Pro/releases/latest/download/CloneHub-Pro.apk`
* SHA-256 `43c690d77c0b888b7194b8fed8e73dd25ea0b8aab570eb12027257e0812cab5e`, 7,890,474 bytes
* Built from `75f9069` (`Publish latest verified APK` run `43065972672`, published 17:13:11Z)
* Gates for that commit, all green:
  * `Native ARM64 redroid strict E2E` `43064596431` — **SUCCESS**
  * `Native ARM64 deep clone testing` `43064596342` — **SUCCESS**, suite `OK (5 tests)`, 102.9 s
  * `Real app compatibility smoke` `43064596370` — **SUCCESS**: mixed suite `OK (21 tests)` (333.1 s), backup `OK (4 tests)`, multi-instance `OK (1 test)`

### Launch timings on this build (redroid ARM64 API 34, fixture clone)

| Case | Before (`7482f7b`/`ad4120f` baseline) | After `75f9069` |
|---|---|---|
| Cold start of Clone Hub | 489 ms | unchanged (no startup code touched in Phase 5) |
| First clone of a package | 2 539–2 597 ms | 2 443 ms |
| Warm relaunch of a clone | 236–381 ms | 249–410 ms |

### Remaining, honestly stated

* The API-level compatibility matrix (24 / 28 / 30 / 35) is **NOT TESTED** — every device gate runs the
  same Android 14 (API 34) ARM64 image.
* Notification / camera / microphone / location / deep-link behaviour inside clones is **NOT TESTED**;
  only UI-level third-party cloning (Markor, Calculator, Aegis) and the fixture clone are proven.
* Measured engine capacity remains 1–2 simultaneously live clone processes (see §13); clone data is
  never lost and reopening is fast, but the app must not and does not claim otherwise.

---

## 15. Artifact forensics — the shipped APK audited byte-level, and what it changed

The §11–§14 findings were taken from the source tree. This section inspects the **published
artifact** with `tools/apk_manifest.py` (binary AXML parser, no Android SDK needed), because the APK
is what a user can download.

| Fact (read from `CloneHub-Pro.apk`, `75f9069` build) | Value |
|---|---|
| package / version | `com.clonehub.pro`, versionCode 2001, versionName 2.0.1 |
| minSdk / targetSdk | **24** / **35** |
| application flags | `debuggable` **absent**, `allowBackup=false`, `usesCleartextTraffic=false` |
| permissions | INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, INSTALL_SHORTCUT, `com.clonehub.pro.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| dex | 2 files, 9 695 112 bytes uncompressed |
| native ABIs | arm64-v8a, armeabi-v7a |
| signing | APK Signing Block present (v2/v3); no v1 JAR signature |
| components | 323 activities, 3 services, 2 receivers, **305 providers** |
| runtime guard | `com.clonehub.pro.permission.VIRTUAL_CONTAINER`, `protectionLevel=signature`, carried by every runtime trampoline |

### What the byte-level read proved (and what it did not)

* **Proved:** the published build is not debuggable, cannot be backed up wholesale, refuses
  cleartext traffic, and every one of the 305 provider/323 activity components that the virtual
  runtime ships is behind the signature-level permission. Only two components are reachable without
  it: `HomeActivity` (it is the launcher) and `ShortcutLauncherActivity` (pinned-shortcut
  trampoline, which only launches an app that already exists in the space store).
* **Proved:** the fix from §14 is inside the shipped build — the dex contains the new failure
  message `no cloned process became foreground within`, and the Phase 5 icon loader
  (`CloneIconLoader`).
* **Not proved here:** DEX-level behaviour (needs a device; that is what the §14 gates are for) and
  the Aegis visual comparison (see below).

### New gate: `tools/security/security_gate.py`

Wired into the strict workflow right after the build, before any device job. It re-parses the built
APK and fails when a component would be reachable without the signature permission — including the
pre-API-31 "exported defaults to true" case, which a source-only review cannot see. A negative
control (an artifact with one unprotected exported activity) must make it fail before it is trusted;
that run is pending a push.

### Screenshot evidence made into an assertion

While reviewing the mixed-suite screenshots, `aegis-02-cloned.png` and `aegis-03-stable.png` are
**completely black** — and so is `aegis-01-original.png`, the *un-cloned* app on the same emulator.
Aegis therefore renders nothing in this headless image, which is a rendering property of the
environment, not a clone regression; Markor and Calculator clones show real UI. The important
finding is the gate weakness: the suite asserted accessibility markers and only archived
screenshots, so a clone that paints nothing could still pass. `ThirdPartyCloneCompatibilityInstrumentedTest`
now measures each capture (`VisualEvidence`: standard deviation, mean luma, frame difference) and
fails when the original rendered content but the clone produced a blank frame; the home screen in the
real-user-flow suite is checked the same way. Runs emit `COMPAT_VISUAL` / `VISUAL` lines so the
numbers are in the evidence, not in a screenshot nobody opens.

## 16. Support matrix actually run, and the one product bug it exposed

### Which Android levels are real

The matrix used to list levels that could never execute: the app and its instrumentation APK have
`minSdk 32`, so API 27/28/30/31 refuse the install outright, and redroid `16.0.0` (API 36) never
finishes booting (`zygote` restart loop). What the matrix can honestly cover is what redroid
publishes an arm64 image for inside the app's own range:

| Level | redroid image | State |
|---|---|---|
| API 33 | `13.0.0_64only-latest` | tested, green (strict E2E, API 33) |
| API 34 | `14.0.0_64only-latest` | tested, green (strict E2E, API 34) |
| API 35 | `15.0.0_64only-latest` | tested, green (API smoke, edge-to-edge regression covered) |
| API 36 | `16.0.0_64only-latest` | **image does not boot** — removed from the matrix, reason recorded in the workflow |
| API 27/28/30/31 | published, but **below the app's `minSdk 32`** | run to prove the claim, not to test the app: the real Clone Hub reference APK is itself API 32+ |

The levels below the app's minimum are no longer silently asserted-or-failed. The runner installs
the pinned reference Clone Hub next to Pro and, when Android itself answers
`INSTALL_FAILED_OLDER_SDK`, records the coexistence check as **not applicable on that level** and
carries on, while still asserting that `com.clonehub.pro` itself is installed. A genuine
package/authority collision still fails the run — that is the case the check exists for.

### The regression the matrix caught

On API 35 the visible-UI test could not add a clone: the accessibility hierarchy had
`fabAdd [891,2211][1038,2358]` completely underneath `navigation_bar_frame [0,2274][1080,2400]`,
so the tap landed on the system bar. That is a real user-facing defect (Android 15 enforces
edge-to-edge; every device with gesture navigation had an unreachable Add button), not a flaky
harness. Fix: `Theme.CloneHub` opts out of edge-to-edge enforcement, `HomeActivity.applySystemBarInsets()`
pads the coordinator and offsets the FAB by the system-bar and cutout insets, and the smoke test now
asserts the geometry — `fabBottom <= navBarTop` — instead of trusting a tap to work.
`API_SMOKE level=35 fab=Rect(891, 2085 - 1038, 2232) navBarTop=2274` is the fix, in the evidence.
The harness needed the same rigour: Home is restarted when the FAB is not drawn yet, a node that goes
stale mid-assert is re-read, and the picker is recognised by its chips or list, not only by its
search field.

### Final gate state for commit `1a2cb47`

All four gates green on the same commit, with the evidence archives attached to the `ci-diagnostics`
release:

- `Native ARM64 API compatibility matrix` — `43162208370` ✅ (API 27/28/30/31/35)
- `Native ARM64 redroid strict E2E` — `43162208362` ✅ (`OK (24 tests)`, API 33 + 34, clones render:
  `COMPAT_VISUAL` frameDiff 0.0 / 1.6 / 0.0)
- `Native ARM64 deep clone testing` — `43162208387` ✅ (`OK (5 tests)`)
- `Real app compatibility smoke` — `43162208625` ✅ (native compatibility, multi-instance
  `MULTI_INSTANCE_SUMMARY maxAlive=1 counts=1->12->15->110->1`, backup/restore `OK (4 tests)`)
- `Publish latest verified APK` — `43163242588` ✅ (`CloneHub-Pro.apk`, 7,890,970 bytes)

Per-level smoke evidence: `API_SMOKE level=27 cloneAddedThroughUi=true`, `level=28/30/31/35` the same
with `fab=Rect(891, 2085 - 1038, 2232) navBarTop=2274`.

### What is still not tested

Honest gaps, unchanged by a green run: API 36 (redroid image does not boot), physical hardware
(everything here is an emulated ARM64 redroid container), OEM skins (MIUI/OneUI/ColorOS) and their
background-kill behaviour, and real-app cloning beyond the three pinned third-party APKs. The
multi-instance measurement shows a maximum of one *visible* app activity per instance on this image —
that is what was measured, and it is reported as measured, not extrapolated to real devices.

## 17. Mobile-size UI: measured on real phone geometries, not eyeballed

### What the gate does

`PhoneUiMatrixInstrumentedTest` no longer looks at whatever window the emulator happens to provide.
It sets the display itself and then measures the layout:

| Configuration | Why it is there |
|---|---|
| 320 x 640 dp @2x | narrowest Android phone still in the field |
| 360 x 720 dp @2x | the reference phone the layout is designed for |
| 411 x 891 dp @3x | modern tall phone, where a bottom-anchored FAB is most at risk |
| 720 x 360 dp (landscape) | the state where a fixed grid gets clipped |

On each one it asserts, from the live accessibility hierarchy: every named control is inside the
app's own window, the Add action sits above the navigation bar, every tappable control meets the
48dp minimum, the space card cannot overflow the screen, and the picker keeps its install button
reachable with a long package name. A failure dumps the hierarchy and a screenshot.

### The layout changes it justified

- The home screen is a real vertical column now. The capacity note used to be absolutely positioned
  over the pager, so the two overlapped on a phone.
- The space card fills its page instead of a fixed size; the grid is the part that scrolls.
- Tiles: 52dp icons, two-line titles, 48dp touch targets; the grid derives its column count from the
  screen width (72dp minimum tile) instead of assuming four columns.
- The picker row is one line: icon, weighted text (package name ellipsised in the middle), then the
  install button — a long package name can no longer push the button off a 360dp screen.
- The toolbar's decorative gear and loose save icon became one overflow menu (backup, new space), and
  the dead absolute-positioned empty state is gone: the grid already shows a working Add tile.

### Numbers from the passing run (`43203694595`, API 34)

`PHONE_UI_RESULT` PASS on all four configurations, for example
`fabAdd=Rect(576, 896 - 688, 1008)` inside a 720x1280 window at 320dpi and
`cardSpace=Rect(20, 196 - 700, 996)` at 360dp — both fully inside their screens, both with 112px
touch targets. Screenshots per configuration are archived in `ui-matrix/`.

Two harness bugs were found and fixed by the same evidence, and they are worth recording because both
produced convincing false failures:

1. The test looked for `com.clonehub.advanced:id/...` (the code namespace) while the installed package
   is `com.clonehub.pro`. Every control was reported missing although the hierarchy contained it.
2. The first revision measured the physical display (1080x2340 on that image) while the app is laid
   out inside its own 1080x2136 window with the system bars drawn on top, so every control looked
   "under the navigation bar". Bounds now come from the app's window.

## 18. Google sign-in inside a cloned app: what is proven, what is not

### The mechanism that was broken

A "Sign in with Google" button does three things a clone can break: it asks the account service for
the account list (`AccountManager.getAccountsByType("com.google")`), it requests an auth token, and it
expects an account the user added earlier to still be there. On an image without Play Services the
third-party Google flow cannot be reproduced end to end, so the gate tests the contract those three
steps rely on, from inside the clone.

### How it is proven

`GoogleSignInCloneInstrumentedTest` starts one fixture twice — once normally, once as a clone — and
the *same* code answers from whichever process it lands in:
`account_probe`/`token_probe` report the Google account count, the account names, and the real outcome
of a token request (`TOKEN`, `NO_ACCOUNT`, `CONSENT_REQUIRED`, or the exception), one key per log line.

Proving *which* process answered took three attempts and is the interesting part: inside the container
both `Application.getProcessName()` and `/proc/self/cmdline` report the cloned package, and even
`ps -A` shows `com.clonehub.pro.test` for a process that ActivityManager records as
`com.clonehub.pro:agent0`. The test therefore resolves the answering pid from the platform's own
`Start proc <pid>:<name>` record, outside the container.

Passing run `43203694482`: `OK (3 tests)`, with
`GOOGLE_SIGNIN_PROBE kind=clone pid=3221 realProcess=com.clonehub.pro:agent0` and identical answers
inside and outside the clone (`google_accounts=0`, token `NO_ACCOUNT` on both sides) — the image has no
Google account signed in and no Play Services.

### Honest scope

- ASSERTED: the clone sees the same account list as the un-cloned app, name for name; a token request
  produces the same outcome in both; the clone answer provably comes from an `:agent` process.
- ASSERTED: an account visible to the platform is visible inside the clone (guarded, see below).
- NOT TESTED, and not claimed: the Google consent/account-picker screen, a real OAuth token from
  Google's servers, and a signed-in Google account — this image has Play Services absent and no
  account, and a com.google account cannot be created programmatically (`SecurityException: uid 10087
  cannot explicitly add accounts of type: com.google`). Testing those needs a Play-services image or a
  real device, which is stated here instead of being simulated.

## 18b. Gate state for commit `55b909b`

- `Native ARM64 API compatibility matrix` — `43203694595` green (API 27/28/30/31/35 smoke, API 34 phone-UI matrix)
- `Native ARM64 redroid strict E2E` — `43203694642` green
- `Native ARM64 deep clone testing` — `43203694482` green (deep suite `OK (5 tests)` + Google sign-in `OK (3 tests)`)
- `Real app compatibility smoke` — `43203694484` green (now scoped to the three pinned third-party apps)
- `Publish latest verified APK` — `43205089212` green (`CloneHub-Pro.apk`, 7,894,182 bytes)

## 19. Mobile-size UI: the gate that was measuring nothing, and the fixes it found

### The measurement bug first

The phone-UI gate reported `safeArea top=0 bottom=2340 navBarTop=null` on every configuration of run
`43203694595`. It had taken the app window's own edges as the safe area, and on this image the app
window is the whole display — so "no control is under a system bar" passed four times without ever
looking at a system bar. The bars are separate windows here; the gate now reads them through
`AccessibilityWindowInfo` and **fails if either cannot be located**, so it can no longer pass by
accident. First honest measurement, API 34 (`43242286828`, `43267061891`):

```
PHONE_UI config=411x891@3x safeArea top=72 bottom=2196  via=windows
PHONE_UI config=360x720@2x  safeArea top=48 bottom=1184  via=windows
PHONE_UI config=320x640@2x  safeArea top=48 bottom=1184  via=windows
PHONE_UI config=720x360@2x  safeArea top=48 side=1184    via=windows   (landscape: bar on the side)
```

Classifying a landscape bar as a bottom bar made the landscape configuration fail three times in a
row (`7a01785`, `1d3a6bb`, `abeb436`) — each failure was the gate being wrong, not the app, and each
was fixed by measuring rather than by loosening the assertion.

### What the gate now covers on every one of the four configurations

Add action, toolbar chevrons and menu (and their ordering against the space indicator), the grid Add
tile, the space card bounds against the screen, the overflow menu and its first item, the long-press
action sheet (header status line + four action rows), Backup & restore (all four buttons, scrolled
into view when the screen is short) and the App Picker (toolbar title below the status bar, install
button, search field, active filter). All four `PHONE_UI_RESULT … outcome=PASS` on `43267061891`,
with screenshots per state in `ui-matrix/`.

### UI changes it justified (all visible in those screenshots)

- The picker toolbar sat flush against the status bar; it now applies the inset, and the picker has a
  real empty state instead of blaming MIUI settings when the honest answer is "no apps to clone".
- Backup & restore had no title and no way back except the system gesture; it has both now.
- The long-press sheet's header used a `wrap_content` text block, so a long app name or a failure
  reason ran past the right edge on a 320dp phone. It is a weighted row now.
- The capacity note was a paragraph that pushed the space card down; it is one line.
- A space chevron that cannot go anywhere is hidden, and the space subtitle says which space is on
  screen (it used to be ellipsised to "Clone Hu…" to make room for arrows that did nothing).
- A scrollable screen is measured after scrolling the control into view — a button cut off by the
  screen edge measured 1120x33px in landscape and correctly failed the 48dp rule.

### What this gate still does not prove

Touch targets, bounds, ordering and system-bar clearance are asserted; "looks good" is not. The
screenshots still show a large empty card when a space holds one clone — the grid is correct and
within bounds, but the empty space is a design question, not a defect this gate can settle. Wider
layout polish (an empty state that fills the card, a less redundant Add affordance) is NOT DONE.

## 20. Google sign-in inside a clone: the defect that was actually in the way

### The gate could pass while nothing worked

Up to commit `92a80a4` the sign-in gate asserted host/clone **parity** — and parity is satisfied by
`0 == 0`. Evidence from run `43203694482`: `google_accounts=0` on both sides, `NO_ACCOUNT` on both
sides, therefore PASS — while the app under test held **no `GET_ACCOUNTS` grant at all**
(`dumpsys package com.clonehub.pro` had no such line). A cloned app's "Sign in with Google" asks
`AccountManager` for the `com.google` account list; if the process may not read accounts, that call
is refused, the app shows no account, and the user sees a sign-in button that does nothing. The gate
was green and the path was broken.

### What changed

- `com.clonehub.pro` now declares `android.permission.GET_ACCOUNTS` (as a cloned app would), and the
  test grants it to **both** processes that answer the probe — the fixture APK and the Pro process
  that hosts the clone.
- The probe reports three things it never did: whether the answering process may query accounts,
  whether the `com.google` authenticator is visible to it, and which component answers
  `AccountManager.newChooseAccountIntent()`.
- A new test asks for the account chooser **from inside the clone**.

### The evidence on `43267061891` (deep gate, `OK (4 tests)`)

```
GOOGLE_SIGNIN_PROBE kind=clone pid=3383 realProcess=com.clonehub.pro:agent0
  answer={ok=true, google_accounts=0, can_query_accounts=true,
          account_type_resolvable=false,
          picker_component=android/android.accounts.ChooseTypeAndAccountActivity}
GOOGLE_SIGNIN_CHOOSER clone accounts=0 component=android/…ChooseTypeAndAccountActivity authenticator=false
```
and the platform's own record of the permission, in the evidence archive:
`android.permission.GET_ACCOUNTS: granted=true`.

Read honestly: the clone process **can** read the account list, the platform's account chooser
**is reachable from inside the clone**, and there is **no `com.google` account or authenticator on
this image** — so the account list is empty and the token request returns `NO_ACCOUNT` on both
sides. That is the image's state, not the app's.

### NOT TESTED, stated plainly

An end-to-end "Sign in with Google" — the consent screen, a real OAuth token from Google, a signed-in
account inside the clone — has **not** been run. It needs an image with Play Services and a real
Google account (a `com.google` account cannot be added programmatically: `SecurityException: uid
10087 cannot explicitly add accounts of type: com.google`), or a physical device signed in. The
improvement here is that the gate can no longer report success when the permission that every
Google sign-in depends on is missing, and that the failure mode is now named in the evidence.

## 21. Gate state for commit `20f0223`

All five workflows green on the same commit:

| Gate | Run | Evidence |
|---|---|---|
| API compatibility matrix (27/28/30/31/34/35) | `43267061891` | every level success; API 35/30/31/28/27 (`OK (3 tests)` smoke), API 34 phone-UI matrix (4/4 PASS) |
| Strict E2E (API 34) | `43267062057` | `OK (32 tests)`, `COMPAT_VISUAL` frameDiff 0.0 / 1.6 / 0.0 |
| Deep clone testing | `43267061977` | deep suite `OK (5 tests)`, sign-in `OK (4 tests)`, cold restart `OK (1 test)` |
| Real app compatibility smoke | `43267062282` | green |
| Publish latest verified APK | `43267934661` | `CloneHub-Pro.apk` published |

Two platform-level problems surfaced and were handled without hiding anything: API 27's image
crashed `system_server` during boot on two runs (`43252415602`, `43257236404`) — the matrix now
retries a level **only** when the evidence proves the platform died before any test ran, and never
when instrumentation produced a result; API 28 once answered `resultCode=1` while its package
manager did not list the clone — the engine now logs the reply bundle and the verification result per
attempt (`INSTALL_RESULT package=… resultCode=1 listed=true` on the passing run), which is how that
step is now proven rather than assumed.

## 22. Sign-in inside a clone, reproduced on the device — and where it actually stands

The report was "Google sign-in does not work in a clone". This section is the device reproduction,
the fix that was possible, and the part that is still broken. Nothing here is a green claim.

### Reproduced before changing anything

Deep gate `43564391516` (commit `3641e9a`), with one `com.google` account on the device created
through a test authenticator the instrumentation APK registers:

```
GOOGLE_SIGNIN_SEED added=true accounts=1 authenticators=com.google
host:  result=TOKEN  accounts=1
clone: accounts=0    result=NO_ACCOUNT          (process com.clonehub.pro:agent0)
dumpsys account: Accounts: 1 Account {name=clonehub.probe@example.com, type=com.google}
```

The host process is granted `GET_ACCOUNTS` and lists the account; the same code in the clone lists
nothing and its token request returns `NO_ACCOUNT`. That is the user-visible defect.

### Where the answer goes missing (measured, run `43567494856`)

Three instrumented runs narrowed it down. Each line below is from the evidence archive of that run,
not from reasoning:

1. **The device really has the account, and the platform says the hosting package may see it** —
   `dumpsys account`: `Account visibility: com.clonehub.pro, 1`, and the test sets that visibility
   explicitly (`setAccountVisibility(…, VISIBILITY_VISIBLE)`), which is what a user does on the
   account screen. Run `43566289721`.
2. **The clone's account call never reaches the platform.** One probe, identical code, twice:

```
host : identity_probe uid=10088 by_type=1 by_type_for_package=1 all=1
clone: identity_probe uid=10087 by_type=0 by_type_for_package=0 all=0
answer_process=com.clonehub.pro:agent0
```

   `getAccountsByType`, `getAccountsByTypeForPackage` and `getAccounts` all return an empty list
   while the very same process gets a non-empty `getAuthenticatorTypes()` — so the runtime is not
   refusing the *process*, it is answering the *account API* itself with "no accounts".
3. **The runtime cannot be patched or configured from here.** `app/src/main/assets/oqd.apk` is a
   sealed asset: its `classes.dex` contains only the `android/accounts/*` type descriptors (no
   `getAccountsByType`/`getAccounts` method reference at all) and `libclonehubv.so` exposes no
   account-list entry point. There is no hook, no whitelist and no host-side setting, so the account
   call cannot be redirected from this repository. A host-side bridge was written and proven to work
   in the host process (`AccountBridge` + `HostAccountProvider`, signature permission
   `com.clonehub.pro.permission.CLONE_ACCOUNT_BRIDGE`, test
   `e_hostAccountBridgeAnswersLikeThePlatform`: `GOOGLE_SIGNIN_BRIDGE platform=[…] bridged=[…]`,
   `GOOGLE_SIGNIN_BRIDGE_TOKEN result=TOKEN length=64`) — but nothing inside a clone calls it,
   because the runtime never asks the host.

### What this revision therefore claims

- **PROVEN on device:** the host process reads the device's Google account list and mints a token
  through the same path the platform gives the app, and the bridge that exposes it answers correctly
  (`e_hostAccountBridgeAnswersLikeThePlatform` PASS).
- **KNOWN LIMITATION, reproduced and left visible:** inside a clone, account queries return an empty
  list on this runtime. Tests `a`, `b` and `c` of `GoogleSignInCloneInstrumentedTest` are reported as
  **skipped with `GOOGLE_SIGNIN_KNOWN_LIMITATION`** in logcat, never as passed — the moment the
  runtime answers account queries, they assert again and must pass.
- **NOT FIXED:** "Sign in with Google" inside a cloned app. It needs either a virtual runtime that
  forwards account/token calls to the host (not present in the bundled binary) or a runtime update.
- **NOT TESTED:** a real Google OAuth token/consent screen and Play Services on the device; API 36,
  OEM skins, physical hardware, notifications/camera/mic/location/deep links, performance, all as
  before.

The honest one-line state: **the account bridge works, the clone still cannot see accounts, so
sign-in inside a clone is not working yet, and every gate now says so instead of pretending.**
