# Scripts

Run everything from the repository root.

| Script | Purpose |
| --- | --- |
| `setup-emulator.sh` | Installs the emulator packages and creates the AVD used by the smoke test (once per machine). |
| `emulator-run.sh <avd>` | Boots the AVD headless with a software GPU. |
| `smoke-test/server.py` | Local stand-in for the resolver API, the Instagram endpoints and the generic extractor. |
| `smoke-test/e2e.py` | Drives the app over adb and asserts the downloads, file bytes and the request log. Needs a booted emulator. |
| `windows/setup-cobalt.ps1` | Optional: installs a cobalt resolver on Windows (WSL2 + Docker) so a fresh install has somewhere to send links. |
| `dev/setup-android-toolchain.sh` | Optional: installs the JDK/Android SDK/Gradle toolchain under `$HOME` when the machine has no root. |
| `dev/install-rea.sh` | Optional: installs the reverse-engineering CLI used while writing `docs/REVERSE_ENGINEERING.md`. Not needed to build or run the app. |

The app itself does not need any of these to build: `./gradlew :app:assembleDebug` is enough once an
Android SDK is on `ANDROID_HOME` (or in `local.properties`).
