# AnyVideo 1.3.8

`AnyVideo-1.3.8.apk`, versionCode 17, 1,772,698 bytes, Android 11 or newer. Signed with the same
project key as 1.3.7, so it installs over it and keeps your settings and downloads.

## Player

- Play opens a full screen player with the video or audio file, not a sheet over Downloads.
- The player has play and pause, ten second skip controls, a seek bar, playback speed, a sleep
  timer, subtitles, an external player action, share and delete.
- Picture in picture keeps the video visible over another app. The player enters PiP through the
  system gesture or the Home gesture, and hides its controls while pinned.
- Audio files use a dedicated audio backdrop instead of a stretched video frame.
- Long YouTube titles scroll in the player header instead of ending with an ellipsis.

## Downloads

- Active, Completed and Failed are separate session tabs.
- Active cards show the file, size, progress, Pause or Resume, and More. Cancel moves the transfer
  to Failed with its error and a Retry action.
- The first newly queued download starts automatically. Later items wait as Queued with a Start
  download button, so a carousel or bulk batch does not open every connection at once.
- Pausing suspends the transfer between chunks; Resume continues from the saved progress.
- Completed files offer Play and Share. Failed files show the reason and Retry.
- The download notification uses the app tile as its large icon and a monochrome status icon.

## Updates

- AnyVideo checks the latest GitHub release once a day at launch and from Settings.
- A newer release, or a same-version APK whose checksum differs from the installed one, appears
  in an update prompt. The same-version check lets a 1.3.8 repair reach people already on 1.3.8.
- The APK downloads inside AnyVideo with progress instead of opening GitHub in a browser. The app
  verifies its size, GitHub SHA-256 digest, package name and project signing certificate before
  offering Install update.
- Android asks for permission to install updates from AnyVideo once, then shows its normal install
  confirmation. The app cannot install silently.

## Clip video

- Tools, Clip video picks a local video, previews it, and lets you set a start and end on the
  timeline.
- The selected range is written to a new file in `Movies/anyvideo`; the source stays untouched.
- Trimming uses MediaExtractor and MediaMuxer without re-encoding, so the clip keeps the original
  video and audio streams.

## Ringtone maker

- Tools, Ringtone maker picks a local audio file, previews it, and trims a range on the timeline.
- The tone can be saved for Ringtone, Notification or Alarm. It goes to `Ringtones/anyvideo`.
- After saving, Open sound settings takes you to Android's sound settings to assign the file.
- The original audio file is never modified.

## File names

- Generated media names replace em dashes with ordinary hyphens, so downloaded file names stay
  readable across gallery apps and file managers.

## Verified

- Downloads: Active, Completed and Failed tabs; a second queued item stayed queued until started;
  Pause changed to Resume; Resume continued; Cancel produced a failed card; Completed offered
  Play and Share.
- Player: downloaded and local video playback, local audio playback, speed at 1.25x, sleep timer
  selection at 15 minutes, title scrolling, and clean PiP without the control overlay.
- Clip: 6.03 second output, H.264 360x640 with AAC audio, 3.2 MB, in `Movies/anyvideo`.
- Ringtone: 12.00 second AAC output, 147 KB, in `Ringtones/anyvideo`.
- `:app:testDebugUnitTest` and `:app:assembleRelease` pass.
- The signed 1.3.8 APK installs over 1.3.7 on the API 35 emulator, and the release build opens
  Tools with Clip video and Ringtone maker.
- Updates: launch-time prompt, in-app download progress, checksum and signature checks, and the
  Android install confirmation were exercised on the API 35 emulator.
- No app crash appeared in logcat during the release smoke test.

## Known limits

- Subtitles are not rendered yet. The subtitle control reports when a file has no subtitle track.
- The player, Clip and Ringtone were tested on the Android emulator, not on a physical phone.
- Clip and Ringtone work on files already visible to Android's picker. They do not fetch links.
- Share, delete and the external player action use Android's normal handlers; the release smoke
  test did not exercise those three actions.
- Android still requires the user to allow installs from AnyVideo and to confirm each install.

SHA-256: bd70ad492cb3b187210206a7a4b3cab9a7a196c1221b2b1ca8dd9abea3d2284e
