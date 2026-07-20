# Android project instructions

## Project baseline

- This is a Kotlin + Jetpack Compose + Material 3 Android app.
- Keep the current compatibility baseline unless a change is intentional and documented:
  - `minSdk = 29`
  - `targetSdk = 35`
  - `compileSdk = 36`, minor version `1`, extension level `20`
- The project currently uses Android Gradle Plugin `9.2.1`, Kotlin Compose plugin `2.3.10`, and the Gradle Wrapper.
- AGP 9 provides the Kotlin Android integration. Do not add `org.jetbrains.kotlin.android` unless the project configuration is deliberately migrated.
- Java and Kotlin compilation targets are JVM 17. The host JDK may be newer, but do not create mismatched Java/Kotlin targets.

## Build and validation

- On Windows PowerShell, use the checked-in wrapper:

  ```powershell
  .\gradlew.bat assembleDebug --no-daemon --console=plain
  ```

- In Git Bash, use `./gradlew`.
- The debug APK is generated under `app/build/` and must not be committed.
- Use `adb devices -l` before device operations and always select a device explicitly with `adb -s <serial>` when more than one transport may exist.
- A USB dock or a network interface does not prove that the phone is a USB ADB device. Confirm the Android transport in `adb devices -l`.
- If wireless `adb install` returns `INSTALL_FAILED_USER_RESTRICTED`, treat it as a device-side installation policy issue, not a build failure. Do not silently weaken device security settings or use root installation on an arbitrary device.
- Do not run `sdkmanager --licenses`, `sdkmanager --update`, install commands, or dependency/tool downloads unless explicitly requested.

## Windows and line endings

- `.gitattributes` keeps `gradlew` in LF format and `gradlew.bat` in CRLF format.
- Do not commit IDE metadata, Gradle/Kotlin caches, `local.properties`, native build directories, or APK/AAB artifacts; see `.gitignore`.
- Prefer environment-provided Java and Android SDK paths. Do not encode a developer's absolute user directory in project files.

## Git workflow

- Treat `master` as the only shared integration branch.
- Start feature work from a GitHub Issue, then create a focused `dev/<short-name>` branch from `master`.
- Keep each PR limited to one reviewable goal. Run the relevant build and device checks before opening the PR.
- Merge through the PR workflow; do not commit feature work directly to `master`.

## Local device notes

- Real device addresses, SSH aliases, network topology, root access details, and other machine-specific information belong only in `.agents/local-device.md` when that file exists.
- Read that private file only when the task requires local-device debugging. Never copy its private values into source files, documentation intended for publication, commits, or logs.
