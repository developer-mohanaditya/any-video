# Project instructions

AnyVideo is an Android app that downloads media from Instagram, YouTube, TikTok, X and
Pinterest. Package `io.anyvideo.downloader`, Kotlin and Jetpack Compose, minSdk 24, targetSdk 35.

## Build and test

JDK 17 or newer and an Android SDK with API 35. The SDK path goes in `ANDROID_HOME` or
`local.properties`.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

No resolver instance is committed. A build that should ship one bakes it in:

```bash
./gradlew :app:assembleRelease -Panyvideo.resolver=https://resolver.example.com
```

Debug builds accept the smoke test mock base and loopback cleartext. Release builds do not.

Release APKs are signed with the project key in `keystore/anyvideo-release.jks`, configured by
`keystore.properties` at the repository root. Both files are gitignored and must stay out of git.
Every published release must use this same key: Android only replaces an installed app when the
signature matches, so a build signed another way cannot be installed as an update. Without
`keystore.properties` the release falls back to the debug key, and that APK must not be published.

## Git identity and publishing

This repository follows [docs/identity-policy.md](docs/identity-policy.md).

- Primary account: developer-mohanaditya, developer.mohanaditya@gmail.com
- Secondary account: work-ma, workofma@gmail.com, used only on explicit request for a named task
- Destination: https://github.com/developer-mohanaditya/any-video, remote `origin`, branch `main`

Commit and push as the primary account. Do not add AI, bot or tool attribution of any kind to
commits, pull requests or release notes. Do not rewrite published history without explicit
approval.
