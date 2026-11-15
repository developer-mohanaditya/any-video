# AnyVideo 1.4.0

`AnyVideo-1.4.0.apk`, versionCode 19, 1,789,590 bytes, Android 11 or newer. Signed with the same
project key as 1.3.9, so it installs over it and keeps your settings and downloads.

## Subtitles

- YouTube videos can download the caption track selected in the picker as a sidecar. The choice is
  remembered for later videos and for multi-link batches.
- If the direct player reply omits the selected track, AnyVideo asks the Android player response
  before giving up. A manual track is preferred over an auto-generated one for the same language.
- The built-in player opens downloaded captions from the CC menu. Captions can be turned off,
  switched between sidecars, and sized Small, Medium or Large.
- Caption appearance now also covers background strength, vertical position and a one, two or
  three line limit. The settings persist across app restarts.
- Long press the active caption to copy it or share it with its timestamp.

## Player

- The player keeps the screen awake while playback is running.
- Picture in picture uses the video's own aspect ratio, so a vertical clip stays a tall window.
  Captions remain visible in the PiP window with a compact layout.
- The More menu can lock orientation to Auto, Portrait or Landscape. Auto follows the video's
  dimensions and the choice is remembered.
- Horizontal videos open in landscape and vertical videos in portrait under Auto.
- The player keeps its slim progress bar, speed control, sleep timer, audio-only mode and external
  player action.

## Downloads and notifications

- Failed download notifications now have a Retry action. Completed and failed notifications open
  Downloads instead of the home screen.
- Every accepted item in a pasted batch or carousel starts immediately. A three-transfer limit
  keeps the network load bounded while the queue no longer waits behind a hidden item.

## Updates

- The 1.4.0 APK is published as the latest GitHub release with its size and SHA-256 digest.
- AnyVideo 1.3.9 can discover 1.4.0 through the daily check or Settings, Updates, Check for
  updates. The existing in-app download verifies the size, GitHub checksum, package name and
  project signing certificate before offering Android's installer.

## Verified

- `:app:testDebugUnitTest` and `:app:assembleRelease` pass.
- The signed 1.4.0 APK installs over 1.3.9 on the API 35 emulator as versionCode 19 / versionName
  1.4.0.
- The player tests covered downloaded captions, caption styling, caption copy and share,
  keep-screen behavior, orientation switching, and portrait and landscape PiP bounds.
- The direct-caption fallback and track selection are covered by unit tests. The fallback's
  Android network response was not reproduced with a live omitted-track video.
- The PiP window size was verified from the system task bounds. The PiP caption overlay itself was
  not captured in the emulator screenshot-free checks.
- No app crash appeared in logcat during the player smoke test.

## Known limits

- Captions are available only for YouTube videos whose picker offered a caption track. A video
  without a downloaded sidecar shows `No subtitles` in the CC menu.
- The player, PiP and caption controls were tested on the API 35 emulator, not a physical phone.
- Android still requires the user to allow installs from AnyVideo and confirm each update.
- Signing in to Instagram was not exercised in this release test.

SHA-256: d15d6e9f750123da5d80ff7960c8772be41d66bc29aed79f03eb82fc6d77cea1
