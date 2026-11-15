# AnyVideo 1.4.2

`AnyVideo-1.4.2.apk`, versionCode 21, 1,789,590 bytes, Android 11 or newer. Signed with the same
project key as 1.4.1, so it installs over it and keeps your settings and downloads.

## Player gestures

- Tap the video once to show or hide the controls.
- Double tap the left third to seek back 10 seconds, or the right third to seek forward
  10 seconds. Seeks start from the player's live position, so they move by the full 10 seconds
  even when playback has advanced since the last progress update.
- Double tap the center to play or pause.
- Saved-video paging is swipe-only. The previous and next chevrons are gone, and a horizontal
  swipe works whether the controls are visible or hidden without taking taps from the controls.
- The center play/pause button now has an accessible Play/Pause label and button action for
  screen readers and keyboard focus. Touch gestures still go through the same native detector.

## Playback reliability

- Center play and pause now use one gesture path. A single tap and a double tap cannot both fire
  for the same gesture, so playback cannot toggle twice.
- Forward and backward double-tap seeks move by exactly 10 seconds.

## Verified

- `:app:testDebugUnitTest` passes with 59 tests.
- `:app:assembleRelease` passes, including release lint and R8 shrinking.
- The signed 1.4.2 APK reports versionCode 21 / versionName 1.4.2 and verifies with the same
  project certificate as 1.4.1.
- The signed 1.4.2 APK installs over the signed 1.4.1 APK on the API 35 emulator and launches.
- While paused, a right-side double tap moved from `1:14` to `1:24` and left playback paused.
- While paused, a left-side double tap moved back 10 seconds.
- A center single tap paused and resumed playback through the native gesture path.
- A center double tap while playing produced one double-tap callback and one playback toggle.
- The center control appears in the accessibility tree as a clickable, focusable button with the
  Play or Pause description.
- The player checks ran on the API 35 emulator, not a physical phone.

## Known limits

- Double-tap zones are 35 percent left, 30 percent center and 35 percent right of the screen.
- The release build does not bake a resolver instance. TikTok, X and Pinterest need one
  configured in Settings or supplied at build time.
- Play Protect may still warn because AnyVideo is sideloaded and keeps the in-app updater.

SHA-256: 03a8dcb9b21072c1b21f8e8985bb7867903c5fd00561a10df8afcdaad87fadc3
