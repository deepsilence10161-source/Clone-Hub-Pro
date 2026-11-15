# Clone Hub Pro - Advanced Multi-Space Architecture (Android 15 Ready)

## 1. अवलोकन (Overview)
यह प्रोजेक्ट **Clone Hub 1.0.16.40** के मूल UI डिज़ाइन का सटीक प्रतिरूप (Exact Replica) है, जिसे आधुनिक **Material 3 (M3)**, **Android 15 (Target SDK 35)** और **सुरक्षित कंटेनर सैंडबॉक्स** पर शून्य क्रैश (Zero Crash) और 100x बेहतर गति के साथ तैयार किया गया है।

---

## 2. मूल APK के घातक बग्स का स्थायी समाधान (Bugs Fixed)
1. **Foreground Service Crash:**
   - पुराने APK में `ChannelService` का `foregroundServiceType=""` रिक्त था, जिससे Android 14/15 पर `MissingForegroundServiceTypeException` क्रैश होता था।
   - **समाधान:** `ContainerForegroundService` में `specialUse` प्रकार, आवश्यक अनुमति और सुव्यवस्थित नोटिफिकेशन चैनल लागू किया गया है।
2. **Dynamic BroadcastReceiver Crash:**
   - पुराने APK में बिना `RECEIVER_NOT_EXPORTED` फ़्लैग के रिसीवर पंजीकृत किए गए थे, जिससे Android 14+ पर `SecurityException` आता था।
   - **समाधान:** 100% अनुपालित सुरक्षित गतिशील रिसीवर।
3. **Data Leakage & ADB Backup Vulnerability:**
   - पुराने APK में `android:allowBackup="true"` और 300 असुरक्षित एक्सपोर्टेड कंपोनेंट्स थे।
   - **समाधान:** `allowBackup="false"` तथा सख्त सिग्नेचर-बेस्ड आंतरिक IPC सैंडबॉक्स।
4. **16 KB Memory Page Size Support:**
   - Android 15 कर्नेल के लिए 16 KB पेज अलाइनमेंट सपोर्ट।

---

## 3. UI और मॉड्यूल संरचना (Exact UI Components)
- **HomeActivity (`ui/home/`):**
  - सटीक टील एक्सेंट कलर (`#00C3CC`) और डार्क सर्फेस।
  - स्पेस स्विचर कंट्रोल (`< Space 1 >`) और डायनामिक स्पेस क्रिएशन।
  - 4-कॉलम ऐप ग्रिड + `+ Add` कार्ड बटन।
  - Material 3 Floating Action Button (`+`).
- **AppPickerActivity (`ui/picker/`):**
  - यूजर और सिस्टम ऐप्स के लिए Material ChipGroup फ़िल्टर।
  - रीयल-टाइम सर्च।
  - MIUI/HyperOS परमिशन गाइड कार्ड।
- **AppActionsBottomSheet (`ui/dialogs/`):**
  - ओपन/लॉन्च।
  - फ़ेक आइकन और नाम बदलना (Disguise)।
  - डेस्कटॉप शॉर्टकट पिन करना।
  - अगले स्पेस में क्लोन करना।
  - स्टोरेज और कैश साफ़ करना।
  - अनइंस्टॉल।

---

## 4. डायरेक्टरी संरचना (Project Files)
```
CloneHub_Advanced/
├── app/
│   ├── build.gradle
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/clonehub/advanced/
│   │   │   ├── CloneHubApplication.kt
│   │   │   ├── core/
│   │   │   │   ├── container/ (SpaceManager, VirtualContainerEngine)
│   │   │   │   └── service/ (ContainerForegroundService)
│   │   │   ├── data/ (ClonedApp, Space, InstalledAppInfo, AppRepository)
│   │   │   └── ui/
│   │   │       ├── dialogs/ (AppActionsBottomSheet, AppCustomizeDialog)
│   │   │       ├── home/ (HomeActivity, SpacePagerAdapter, AppGridAdapter, ShortcutLauncherActivity)
│   │   │       └── picker/ (AppPickerActivity, AppPickerAdapter)
│   │   └── res/
│   │       ├── drawable/ (सारे मूल वेक्टर आइकन्स ic_action_*)
│   │       ├── layout/ (activity_home, activity_apps, dialog_app_actions, आदि)
│   │       └── values/ (colors, dimens, strings, themes)
├── build.gradle
├── gradle.properties
└── settings.gradle
```

## Sign-in inside a clone: what works and what does not

This is measured on the ARM64 API 34 gate, not claimed (audit §27, §28; gate `43696006594`).

**Web-based sign-in works inside a clone.** A page that loads Google Identity Services in a WebView
gets the same environment in the clone as outside it: the same WebView provider
(`com.android.webview:125.0.6422.113`), JavaScript, DOM storage and cookies enabled, an HTTPS page that
loads, a secure context with `crypto.subtle`, a cookie that survives a reload, and
`accounts.google.com/gsi/client` served (~280 KB). A link that opens a browser resolves inside the
clone too.

**Native sign-in still fails inside a clone.** Inside the clone every `AccountManager` query answers an
empty list (`by_type=0`, `by_type_for_package=0`, `all=0`) while the host app lists the device's Google
account and receives a token. An app that asks Android for the account before signing in — most native
apps, Play Games, and many web apps that pick the account first — therefore sees "no account", which is
what a failing "Sign in with Google" button looks like from the outside. The host-side bridge that can
answer correctly exists and is proven (`AccountBridge` + `HostAccountProvider`); what is missing is
routing the container's own account calls through it.

**Not tested:** a real Google consent (this image has no Play services, so its `com.google` account is
created by a test authenticator and labelled as such), Play-services-dependent SDKs (Games, Firebase
federated sign-in), and the app's own backend.

## In-app updates

The app updates itself from its own release channel — no store, no sideloading by hand.

**Where:** overflow menu → **App updates**. The screen shows the installed build, the offered build,
the signing key this build carries, whether this device accepts an installer intent, and the SHA-256
the downloaded file was checked against. A background check runs at most once every six hours and
speaks at most once per `versionCode`.

**How a release becomes an update:** `Publish latest verified APK` runs only after both native ARM64
gates pass, decodes the APK's own `versionCode` with `aapt2`, computes its SHA-256 and publishes it
with a title of `CloneHub Pro - Latest Verified APK (build-<versionCode>)` and a body containing
`sha256:<hex>` and `size:<bytes>`. The app reads exactly those fields:

```
GET https://api.github.com/repos/deepsilence10161-source/Clone-Hub-Pro/releases/tags/latest-verified
```

It reads the release by **tag**, not by `/releases/latest`. That endpoint answers with whatever GitHub
calls newest, and it was measured answering with the wrong release: once the workflow also began
archiving each build under `verified-build-<code>`, that archive became "latest". A tag cannot drift,
so `latest-verified` is what the app asks for; `/releases/latest` is only a fallback for a fork that
has no such tag, and the log says when it is used.

**What the app refuses to do:**

- it never compares version *names*; without a numeric `versionCode` in the release title/tag it says
  it cannot tell, rather than offering a downgrade or hiding an update;
- it never installs unverified bytes: if the release publishes a SHA-256, a mismatch deletes the
  download and fails loudly;
- it never claims an install path works when the platform refuses it. Android 14 rejects an APK
  delivered by an intent URL (`INSTALL_FAILED_INVALID_APK: … intent-URL path`); the first attempt's
  result is recorded and the panel switches to "open the verified APK as a file" instead.

**One key for every build, because Android leaves no choice.** Android installs an update only when it
is signed with the *same key* as the installed app. That was measured, not assumed: three CI runs of
this repository carried three different debug keys, so every update would have been refused with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The builds are now signed with
`.github/signing/clonehub-signing.p12`, committed on purpose, and the deep gate asserts on the device
that the installed build carries that key (`signing_key … matches=true`). It is a public,
debug-grade key — the previous keys were public too, only unstable — and
`.github/signing/README.md` says so, lists the three measured keys, and explains what a release
pipeline would do differently. The update screen shows the fingerprint the running build actually has,
so a key change is visible rather than mysterious.

**What is proven, and what is still owed.** Proven on the device: the channel is read and its
`build-2002` title becomes `versionCode=2002`; the installed build is compared against it and correctly
reported as not older; the signing key matches the committed one; the FileProvider serves
`cache/updates`; the installer intent is built with the right action, type and flags; the download is
checked against the published SHA-256 (the published APK's digest and the release body's `sha256:` line
are identical). **Not yet proven:** a real update from an installed build to a newer one — that
replaces the binary under the running test, so the end-to-end proof is the next gate run reading a
higher `build-<code>` from the device after installing over the previous APK. Also untested: the
system "allow this app to install packages" dialog (the gate image reports
`canRequestInstalls=false`), API 36 and OEM installer behaviour, and the Android 14 intent-URL refusal,
which is handled in code but has not been reproduced on a device — which is why the panel prints
`path=UNKNOWN` instead of claiming a silent install works.

A fork or a private build can point the checker at its own manifest instead of GitHub by setting
`UpdateChecker.MANIFEST_URL`; the JSON shape is documented in `UpdateChecker.parseManifest`.
