# AnyVideo 1.3.7

`AnyVideo-1.3.7.apk`, versionCode 16, about 1.7 MB, Android 11 or newer. Signed with the same
project key as 1.3.6, so it installs over it and keeps your settings and downloads.

## Update fix

- The update button now opens the release APK's direct download URL instead of the GitHub release
  page. The release page could be handled by the GitHub app or fall back to the repository root.
- The browser downloads `AnyVideo-1.3.7.apk` and hands it to Android's normal installer. AnyVideo
  still does not request permission to install packages itself.
- Version 1.3.6 and older cannot use the fixed button, so this APK has to be installed once from
  the release page. After that, Check for updates opens the APK download directly.

## Also included from 1.3.6

- Real 4K YouTube downloads at 2160p, with VP9/WebM on Android 11-13 and AV1/MP4 on Android 14+.
- The quality sheet only offers streams the video actually has; 8K is not offered.
- Cold-start splash screen, delete confirmation, selected-item count, and stale download-error
  cleanup.

## Verified

- The latest-release parser returns the APK `browser_download_url`, not the release page.
- The GitHub asset URL answers with `application/vnd.android.package-archive`.
- `:app:testDebugUnitTest` and `:app:assembleRelease` pass.
- The signed 1.3.7 APK installs over 1.3.6 on the emulator.

SHA-256: 140ef0c78bac83a7b9b302dfa888a5abc52a7fd681dd4538921795a8c5ec48c1
