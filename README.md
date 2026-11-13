# HFP Client Tester

A read-only Android diagnostic app that investigates whether a device exposes
the Bluetooth **Hands-Free Profile Client** role (`BluetoothProfile.HEADSET_CLIENT`)
through public, non-root Android APIs. It does **not** place calls or forward
audio — it is purely a diagnostic tool to help you decide whether building a
full HFP-client call app on your Samsung Galaxy Note20 is even possible.

No local Android Studio or SDK installation is required to build it — a
GitHub Actions workflow in this repo builds the APK for you on GitHub's
servers.

## What it checks

- Phone model, Android version, API level
- Bluetooth adapter presence and enabled state
- Paired Bluetooth devices
- Public `BluetoothProfile` constants (HEADSET, A2DP, HEADSET_CLIENT, PBAP_CLIENT)
- Whether `android.bluetooth.BluetoothHeadsetClient` exists in the framework
  (informational only — this does **not** prove the profile actually works)
- Whether `BluetoothAdapter.getProfileProxy(..., HEADSET_CLIENT)` actually
  connects — this is the real evidence of whether the service is implemented
  *and* reachable by a normal third-party app
- Permission grant state and any errors encountered

The app clearly separates "the class exists" from "the service works," and
will tell you plainly if a result is inconclusive rather than claiming success
it can't back up.

## 1. Upload this project to GitHub

1. Create a new **empty** repository on GitHub (do not initialize it with a
   README, license, or `.gitignore` — this project already has its own).
2. From the folder containing this project, run:

   ```bash
   git init
   git add .
   git commit -m "Initial commit: HFP Client Tester"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```

   If you'd rather not use the command line, you can also use GitHub
   Desktop, or drag-and-drop the project folder into GitHub's web upload UI
   (github.com → your new repo → "uploading an existing file").

## 2. Run the build

The workflow at `.github/workflows/build-apk.yml` runs automatically on every
push to `main`/`master`. You can also trigger it manually:

1. Go to your repository on GitHub.
2. Click the **Actions** tab.
3. Select **Build Debug APK** in the left sidebar.
4. Click **Run workflow** → **Run workflow**.

The workflow:
- Installs Java 17 and an official Gradle 8.7 distribution (via GitHub's
  `gradle/actions/setup-gradle`, which verifies Gradle's download against
  Gradle's own checksums — no unofficial mirrors).
- Generates the Gradle wrapper (this repo does not commit the
  `gradle-wrapper.jar` binary, by design — the workflow fetches and verifies
  Gradle itself instead).
- Runs `./gradlew assembleDebug`.
- Uploads the resulting debug APK as a workflow artifact.

A run typically takes 2–4 minutes. If a step fails, the Actions log will show
exactly which step and why — the workflow does not report success unless
`assembleDebug` actually completes and an `.apk` file is found.

## 3. Download the APK

1. Open the finished workflow run (Actions tab → the run → it should show a
   green checkmark).
2. Scroll down to the **Artifacts** section at the bottom of the run summary
   page.
3. Download **hfp-client-tester-debug-apk** — this is a `.zip` containing the
   `.apk` file. Unzip it to get `app-debug.apk`.

## 4. Install on your Samsung Galaxy Note20

1. Copy `app-debug.apk` to the Note20 (USB cable, Samsung's "Quick Share",
   cloud storage, email to yourself, etc.).
2. On the Note20, open the file from **My Files** or your downloads/notifications.
3. If prompted, allow **"Install unknown apps"** for the app you used to open
   the file (Settings → Apps → Special access → Install unknown apps, or
   Android will prompt you directly the first time).
4. Tap **Install**. Since this is a debug build, Android may show an
   "unverified app" style warning — this is expected for apps installed
   outside the Play Store and is not Play Protect flagging genuine malware
   behavior; you can review the warning details and choose to proceed only if
   you're comfortable doing so.
5. Open **HFP Client Tester** from the app drawer.

## 5. Run the diagnostics and share the report

1. Tap **Run Diagnostics**. The first time, Android will ask for Bluetooth
   permissions (`BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` on Android 12+) —
   grant them so the app can read paired devices and query the profile
   service.
2. Review the four sections on screen: **HFP Client Status**, **Bluetooth
   Devices**, **Bluetooth Profiles**, and **Permissions and Errors**.
3. Tap **Copy Report** to copy the full plain-text report to the clipboard.
4. Paste it anywhere you want — including into a chat with an AI assistant
   for further analysis, as you mentioned.

## Reading the result honestly

- If the **HFP Client Status** section says the `HEADSET_CLIENT` profile
  proxy *connected*, that means Samsung's Bluetooth stack does register and
  expose an HFP-client-role service to third-party apps on this device. That
  is a real, meaningful positive signal.
- If it says the proxy *never connected*, that means this device's stack
  either doesn't implement that role at all, or doesn't expose it to normal
  apps — the app cannot tell which, and says so rather than guessing.
- The "class present" line (Layer 1) is informational only. Android ships the
  `BluetoothHeadsetClient` class on essentially all modern builds whether or
  not the underlying service is actually enabled, so by itself it proves
  nothing — the report says this explicitly.
- Neither result proves or disproves that a real call could be routed
  end-to-end between the two phones; that would require an actual attempted
  call against a real Audio Gateway peer, which this diagnostic intentionally
  does not attempt.

## Project structure

```
hfp-client-tester/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/hfptest/clienttester/MainActivity.kt
│       └── res/...
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── .github/workflows/build-apk.yml
```

## Permissions requested

- `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (Android 12+, runtime-requested)
- `BLUETOOTH`, `BLUETOOTH_ADMIN` (legacy, install-time only, maxSdkVersion 30)

No `INTERNET` permission, no location permission, no analytics, no ads, no
background services, no root required.
