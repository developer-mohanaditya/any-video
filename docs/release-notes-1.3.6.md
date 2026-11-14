# AnyVideo 1.3.6

`AnyVideo-1.3.6.apk`, versionCode 15, about 1.7 MB, Android 11 or newer. Signed with the same
project key as 1.3.5, so it installs over it and keeps your settings and downloads.

## YouTube 4K

- A video that really has a 2160p stream now downloads in 2160p. The app reads YouTube directly
  instead of accepting the resolver's 1080p answer.
- The quality sheet only offers streams that YouTube actually exposes. A 1080p video no longer
  shows 2K or 4K choices.
- 2160p uses VP9 video with Opus audio in a WebM file. On Android 14 and newer, AV1 video with AAC
  audio is used instead and the file is MP4.
- 4K is the highest quality offered. 8K streams are not requested.
- `Best` stays on the broadly compatible 1080p H.264 stream. Choose `2160p` explicitly for 4K.
- 1440p uses the same VP9 path as 4K.

## Launch and downloads

- A cold start shows the AnyVideo splash with the app mark, title and a short spinner.
- Returning from Recents does not replay the splash. A new process after removing the task does.
- Failed and empty-file download errors are cleared when you leave and reopen the app, instead of
  staying on screen.
- Deleting a file, or a selection of files, now asks for confirmation first.
- After a long press, the top bar and the grid show how many items are selected.

## Verified

- 4K VP9: 3840x2160, Opus 48 kHz stereo, 10.021 seconds, 5,508,473 bytes, WebM.
- 4K AV1: 3840x2160, AAC 44.1 kHz stereo, 16.068 seconds, 17,013,530 bytes, MP4.
- A 1080p source showed 1080p and lower qualities only.
- `:app:testDebugUnitTest` and `:app:assembleRelease` pass.

## Known limits

- 4K files are large. A longer 4K video can be around 1 GB, and downloading uses temporary video,
  audio and merged copies, so free storage matters.
- VP9 4K is saved as `.webm`; Android 11-13 use this path. Android 14+ can use AV1 `.mp4` when
  YouTube provides an AV1 stream.
- 8K is not supported in this release.

SHA-256: 1150ef25550c07ead035c3e3f5cb00e90fd1a13d00fee9b64a17abdd7d20b2e7
