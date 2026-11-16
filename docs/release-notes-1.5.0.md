# AnyVideo 1.5.0

`AnyVideo-1.5.0.apk`, versionCode 22, 1,805,970 bytes, Android 11 or newer. Signed with the same
project key as 1.4.2, so it installs over it and keeps your settings and downloads.

## Download notifications

- The Home notification bell now opens a session Notifications screen. Its badge still counts
  active transfers and shows `!` for failures.
- Active downloads show the source, media type and a compact progress card. The progress bar uses
  80% of the row, with Pause or Resume and the options menu centered on the bar itself.
- Pause, Resume and Cancel work from the card. Queued items can be started, and failures show
  their error with a Retry action.
- Completed and failed transfers from the current session appear below the active section.
  Clear removes the finished session entries, while Downloads remains the saved-media gallery.
- Resolver-only downloads are labelled with their service name, including Vimeo, Dailymotion,
  Reddit, Snapchat and Likee, instead of a generic Download label.

## Reliability

- Fixed the M4A fallback message so it is posted on the main looper after a resolver audio
  download falls back.
- Instagram carousel parsing now skips unusable media URLs instead of adding entries that cannot
  be downloaded.

## Verified

- `:app:testDebugUnitTest` passes with 61 tests.
- `:app:lintRelease` passes with 0 errors and 29 warnings.
- `:app:assembleRelease` passes, including R8 shrinking and resource shrinking.
- The signed 1.5.0 APK reports versionCode 22 / versionName 1.5.0 and verifies with APK Signature
  Scheme v2.
- Its signing certificate SHA-256 matches the published 1.4.2 APK:
  `13e8cb49791783ef6ec6aa6823447f66fddaab1af2483bb043006d14d72054d4`.
- The signed 1.5.0 APK installs over the signed 1.4.2 APK on the API 35 emulator and launches.
- Home and the Notifications screen open from the bell without a crash.
- The download-card checks covered an active 2160p transfer, Pause, the Cancel menu and the
  centered action controls on the API 35 debug build.

## Known limits

- The app checks ran on the API 35 emulator, not a physical phone.
- The release build does not bake a resolver instance. TikTok, X and Pinterest need one configured
  in Settings or supplied at build time.
- Play Protect may still warn because AnyVideo is sideloaded and keeps the in-app updater.

SHA-256: d5931e568e704300fa32c786938a9431ab31f65988da421ca21d93b0ae69809f
