# Share Intent Handling, Onboarding State Machine & Two-Line Mini Bar

## Summary
Refactor Flip's share intent intake (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`), resolve onboarding race conditions using the Android `SplashScreen` API with a three-state machine (`Loading`, `Done`, `NotDone`), and upgrade the docked session mini bar to a two-line layout featuring cumulative queue byte percentage, tabular numerals (`tnum`), and smoothed ETA.

---

## Diagnostic Reports (Required)

### Share Intent App Transfer Failure (5-Line Report)
1. **Intent Contents**: `action = ACTION_SEND`, `type = "application/vnd.android.package-archive"` (or `*/*`), `EXTRA_STREAM` is a `content://` URI (scheme `content`, authority of source file provider, e.g. `<source_pkg>.fileprovider`), `EXTRA_TEXT` is null or app link.
2. **Root Cause**: `TransferService` and file handlers improperly tested `uri.path?.startsWith("/")` on content URIs, attempting `FileInputStream(File(uri.path))` on a content scheme instead of strictly resolving via `contentResolver.openInputStream(uri)`.
3. **Exact Exception Thrown**: `java.io.FileNotFoundException: /root/data/...: open failed: ENOENT (No such file or directory)` or `java.lang.SecurityException: Permission Denial: reading ... requires ...` thrown in `TransferService.kt:190` / `StorageService.kt:562`.
4. **Error Masking Bug**: Raw exception strings containing `com.example` package paths and class names leaked into UI toasts instead of sanitized user-facing descriptions.
5. **Debug Telemetry**: Adding debug log with tag `"FlipShare"` logging exclusively: `scheme`, `authority`, `mime`, and `textLength` without leaking personal file data or internal paths.

### Onboarding Flag Race Condition (4-Line Report)
1. **Storage & Reading**: Stored in SharedPreferences `"flip_prefs"`, key `"onboarding_complete"`, read asynchronously during Jetpack Compose first frame composition inside `FlipAppContent`.
2. **First Frame Timing**: Not read before the first window frame draw; Compose initialized `showSplash = !prefs.getBoolean("onboarding_complete", false)` during initial composition layout.
3. **Transient Start Destination**: While reading preferences, Compose briefly evaluated default `false`, causing `SplashScreen` (Getting Started) to flash or trap incoming share intents.
4. **Solution**: Read the flag synchronously in `MainActivity.onCreate` using a three-state enum (`Loading`, `Done`, `NotDone`), hold system splash via `splashScreen.setKeepOnScreenCondition { state is Loading }`, and write synchronously with `commit()` before navigating.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions were clarified and confirmed during interactive Phase 1 review:

- **Confirmed Decision 1 (Shared App Selection UI)**: When a Play Store link (`play.google.com/store/apps/details?id=...`) or bare package ID is received in `EXTRA_TEXT` and the package is installed on the device, Flip will display a dedicated ModalBottomSheet with two options:
  1. *Send App*: Displays the app icon, title, formatted size, and `"Prepared as .apks"` badge if split APKs are detected, extracting the APK(s) via `StorageService.extractApk`.
  2. *Send Text*: Sends the original Play Store link or package ID as plain text.
  If the package is not installed on the phone, the share is treated as plain text immediately without prompting.
- **Confirmed Decision 2 (Post-Onboarding Pending Share Delivery)**: When a share arrives while onboarding is not yet done (`NotDone`), items are retained in `FlipViewModel`. Upon tapping "Get started", Flip saves the flag via `commit()` and surfaces a bottom confirmation banner:
  - Text: `"<N> files ready to send"` (or file name if single).
  - Actions: `Send` (opens the readiness check/radios and queues files) and `Dismiss` (clears pending items). No premature radio prompts or automated sender start occur.
  - If already connected when a share arrives, the banner is skipped and Flip shows the existing `"Send N files to <device>?"` confirmation sheet.
- **Confirmed Decision 3 (Two-Line Mini Bar Typography & Layout)**:
  - Line 1: `"Connected to <name>"` (single line with ellipsis truncation).
  - Line 2: `"2 of 5 · 43% · 12s left"` in smaller body styling with tabular numerals (`fontFeatureSettings = "tnum"`) to eliminate numeral jitter.
  - Initial 3 seconds shows `"Calculating..."` in place of the time, and near-zero speeds render `"—"`.
  - Tested against 320dp compact width and 1.3 fontScale.

---

## 1. Overview & Core Concept

Flip will provide robust, zero-crash handling for external system shares (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`). It resolves stream resolution errors, safely handles permissions, parses installed app package links, ensures seamless startup without flashing Getting Started, and renders a compact two-line docked session mini bar with cumulative queue metrics.

```
┌───────────────────────────────────────────────────────────────┐
│ External App (Files, Play Store, Browser)                    │
│ intent: ACTION_SEND / ACTION_SEND_MULTIPLE                    │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│ MainActivity (Single Intake Handler)                          │
│ 1. Read onboarding state (Loading -> Done / NotDone)          │
│ 2. splashScreen.setKeepOnScreenCondition { Loading }          │
│ 3. Log FlipShare (scheme, auth, mime, textLength)             │
│ 4. Clear intent extras & set ACTION_MAIN                      │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│ FlipViewModel (Share Processing & Queue Pipeline)             │
│ ├── Stream Uri Resolver (openInputStream, zip header check)   │
│ ├── App Package Detection (Play Store regex -> split check)   │
│ └── Cumulative Queue Metrics (5s smoothed speed, overall %)   │
└───────────────────────────────┬───────────────────────────────┘
                                │
        ┌───────────────────────┴───────────────────────┐
        ▼                                               ▼
┌───────────────────────────────┐       ┌───────────────────────────────┐
│ Bottom Confirmation Banner    │       │ Two-Line Docked Mini Bar      │
│ (Post-Onboarding Pending)     │       │ Line 1: Connected to Pixel 8  │
│ "<N> files ready to send"     │       │ Line 2: 2 of 5 · 43% · 12s    │
└───────────────────────────────┘       └───────────────────────────────┘
```

---

## 2. User Experience & Visual Design

### 2.1 Two-Line Docked Session Mini Bar
- **Top Row (Line 1)**: `"Connected to <Device Name>"` in `titleSmall` / `bodyMedium` (FontWeight.Bold, single line, `TextOverflow.Ellipsis`).
- **Bottom Row (Line 2)**: `"<Current> of <Total> · <OverallPercentage>% · <ETA>"`:
  - Rendered with `fontFeatureSettings = "tnum"` for fixed-width tabular figures.
  - For the first 3 seconds after connection or queue change: displays `"Calculating..."` in place of the duration.
  - If speed is near zero (< 1 KB/s after calculation window): displays `"—"`.
- **Right Action Column**: Clean, accessible touch targets (48dp) for Expand and Disconnect.
- **Responsiveness**: Formatted to ensure zero clipping at 320dp screen width with 1.3 system fontScale.

### 2.2 App Share ModalBottomSheet
- Triggered when `EXTRA_TEXT` contains an installed package reference.
- **Card 1 (Send App)**: Displays the real app icon, app label, APK/APKS file size, and split package pill badge (`"Prepared as .apks"`).
- **Card 2 (Send Text)**: Displays the raw link or package string.
- Tapping "Send App" invokes background APK extraction and queues the file.

### 2.3 Post-Onboarding Pending Share Banner
- Positioned above navigation bar on `HomeScreen` when onboarding is completed with pending shares.
- Styled in `surfaceContainerHigh` with clear `Send` (Primary Tonal) and `Dismiss` (Subtle Text) buttons.

---

## 3. Key Product Decisions & Technical Specifications

### 3.1 Content Stream Resolution & MIME Fallback
- `EXTRA_STREAM` is opened exclusively using `contentResolver.openInputStream(uri)`.
- Filename resolved from `OpenableColumns.DISPLAY_NAME`.
- If MIME is `application/vnd.android.package-archive` or if the stream is a ZIP archive containing `AndroidManifest.xml` (detected via header magic `PK\003\004` and entry inspection), `.apk` is appended if missing.
- Catch `SecurityException`, `FileNotFoundException`, and `IllegalArgumentException`, displaying sanitized message:
  `"Couldn't read this file from <source app name>. Try sharing it again."`

### 3.2 Onboarding Three-State Machine
```kotlin
sealed interface OnboardingState {
    data object Loading : OnboardingState
    data object Done : OnboardingState
    data object NotDone : OnboardingState
}
```
- In `MainActivity.onCreate`:
  1. Call `installSplashScreen().setKeepOnScreenCondition { viewModel.onboardingState.value is OnboardingState.Loading }`.
  2. Read `"onboarding_complete"` from `flip_prefs`.
  3. Transition `OnboardingState` to `Done` or `NotDone`.
- On clicking "Get started":
  `prefs.edit().putBoolean("onboarding_complete", true).commit()` ensures write completes before UI state transitions.

### 3.3 Cumulative Queue Metrics Math
- `totalQueueBytes = sumOf { it.fileSize }`
- `transferredQueueBytes = sumOf { it.bytesTransferred }`
- `overallPercentage = (transferredQueueBytes.toFloat() / totalQueueBytes * 100).toInt().coerceIn(0, 100)`
- `remainingQueueBytes = totalQueueBytes - transferredQueueBytes`
- `smoothedSpeed = 5-second sliding window average throughput (excluding paused durations)`
- `cumulativeEtaSeconds = if (smoothedSpeed > 1024L) remainingQueueBytes / smoothedSpeed else 0L`

---

## 4. Technical Architecture & File Modifications

### 4.1 Affected Components
- `MainActivity.kt`:
  - Integrate `installSplashScreen()` and `setKeepOnScreenCondition`.
  - Implement unified `handleIncomingShareIntent(intent: Intent?)` for both `onCreate` and `onNewIntent`.
  - Add debug logging with tag `"FlipShare"` printing only `scheme`, `authority`, `mime`, `textLength`.
  - Clear extras and set `ACTION_MAIN` without resetting navigation backstack.
- `FlipViewModel.kt`:
  - Introduce `OnboardingState` flow.
  - Implement `StreamUriParser` with ZIP manifest verification and `.apk` extension enforcement.
  - Implement Play Store link regex parser and package manager installation check.
  - Store pending shares in `_pendingPostOnboardingShare` flow if onboarding is `NotDone`.
  - Track 5-second sliding window throughput and cumulative queue ETA.
- `FlipAppUi.kt`:
  - Update `SessionMiniBar` to two-line layout with tabular numbers (`tnum`) and 3-second `"Calculating..."` window.
  - Add `SharedAppOptionSheet` (ModalBottomSheet) for installed package shares.
  - Add `PostOnboardingShareBanner` on `HomeScreen`.
  - Guard `SplashScreen` against displaying during `OnboardingState.Loading`.

---

## 5. Verification Plan

1. **Compilation**: Execute `compile_applet` to verify Gradle build.
2. **Unit & Logic Tests**: Execute `gradle :app:testDebugUnitTest` to verify view model queue logic, cumulative ETA math, and URI stream parsing.
3. **Layout & Accessibility**: Validate 320dp width and 1.3 font scale rendering.
4. **Runtime Verification**: Note limitations where real cross-app share intents require physical Android execution.
