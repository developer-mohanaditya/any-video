# AnyVideo 1.4.1

`AnyVideo-1.4.1.apk`, versionCode 20, 1,789,586 bytes, Android 11 or newer. Signed with the same
project key as 1.4.0, so it installs over it and keeps your settings and downloads.

## Player and paging

- Swipe left or right in the full-screen player to move to the previous or next saved video or
  audio file of the same kind.
- Previous and next chevron buttons are available while the player controls are visible.
- Auto orientation now follows the phone instead of forcing landscape for every horizontal clip.
- The new Full screen button rotates to landscape and hides the system bars. Exit full screen
  restores portrait and the bars.
- Pause and the other controls no longer lose taps to the swipe overlay.

## Captions

- Captions are hidden while the player controls are visible and return when the controls hide.
- Tapping a visible caption brings the controls back instead of blocking Pause.
- The caption background is lighter, rounded, and wraps the text instead of drawing a full-width
  black bar.
- The YouTube picker hides the captions checkbox when no tracks are available and shows
  `Captions not available.` instead.
- Videos with caption tracks keep the existing checkbox and language choices.

## YouTube downloads

- `Best` now selects the highest available stream, including 1440p and 2160p, instead of capping
  the direct path at 1080p H.264.
- Explicit quality choices still request that exact height.
- Direct streams that return HTTP 403 or 410 retry once at the next lower quality.
- HTTP 403 is treated as a retryable direct-download failure.

## Home and system UI

- Added a download-status bell after the platform icons on the homepage.
- The bell shows a purple count badge for queued, running or paused downloads and a red `!` for
  failed downloads. Tapping it opens Downloads.
- Status bar and navigation bar icon colors follow the light or dark theme.

## Verified

- `:app:testDebugUnitTest` passes with 59 tests.
- `:app:lintDebug` passes with 0 errors and 28 warnings.
- `:app:assembleRelease` passes.
- The signed 1.4.1 APK reports versionCode 20 / versionName 1.4.1 and verifies with APK Signature
  Scheme v2.
- The 1.4.1 signing certificate matches the published 1.4.0 certificate.
- The signed 1.4.1 APK installs over the signed 1.4.0 APK on the API 35 emulator and launches.
- The live in-app update was tested from 1.4.0: Settings, Updates found 1.4.1, downloaded the
  signed APK, and Android installed versionCode 20 over 1.4.0. A later check reported that the app
  is on the newest version.
- The player checks covered swipe paging, previous and next controls, Pause, full-screen rotation,
  system-bar restore, caption visibility and the lighter caption treatment.
- The YouTube `Best` test selected 2160p and started a download without HTTP 403.
- The homepage bell showed an active badge and opened Downloads.

## Known limits

- The specific video that returned HTTP 403 was not available for a live retry test.
- The 2160p test was stopped after it started to avoid downloading the entire file.
- Player and caption checks ran on the API 35 emulator, not a physical phone.
- The release build does not bake a resolver instance. TikTok, X and Pinterest need one configured
  in Settings or supplied at build time.
- Play Protect may still warn because AnyVideo is sideloaded and keeps the in-app updater.

SHA-256: 40953439e4121a414ab7693bafc247e6a2258a39aa71c6fd823a4f8b46a045ce
