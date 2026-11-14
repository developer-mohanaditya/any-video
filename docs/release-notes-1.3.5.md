# AnyVideo 1.3.5

`AnyVideo-1.3.5.apk`, versionCode 14, about 1.7 MB, Android 8.0 or newer. Signed with the same
project key as 1.3.4, so it installs over it and keeps your settings and downloads.

## YouTube downloads

- The app now asks YouTube's visionOS player for an H.264 video and an AAC audio stream, downloads
  both, and merges them on the phone. This fixes the exact link that previously failed or saved
  only a metadata-sized 360p file.
- Direct downloads work up to 1080p. 1440p and 4K still go through the resolver.
- Each stream is fetched in short range requests, so a CDN that closes a long connection part way
  through does not leave a truncated file.
- A 720p choice no longer quietly becomes 360p. The direct stream is only used when it has the
  requested height; otherwise the resolver gets the request.
- Audio-only downloads no longer stop at an empty resolver tunnel. The app saves YouTube's
  original AAC track as an M4A file instead.
- MP3, OGG and WAV still go through the resolver for conversion. If the resolver cannot produce
  the selected format, the fallback is M4A, not a failed download.

## Gallery preview

- Swipe left or right in the full screen preview to move through the photos or videos in the
  current tab.
- A page counter shows the position, for example `2 / 6`.
- Pinch to zoom still works. While zoomed, a drag pans the image instead of changing pages.
- A video or audio preview now shows its full length under the file name, formatted as `3:31` or
  `1:01:01`, not as a raw number of seconds.

## Updates and Play Protect

- The app no longer requests `REQUEST_INSTALL_PACKAGES` and no longer downloads or launches an APK
  installer itself. Settings, Updates opens the GitHub release page, and the browser plus Android's
  normal installer handle the download.
- This removes the self-updater behaviour that Play Protect treats as harmful. The first release
  did not have this permission; the in-app updater added it later. Google still controls the final
  verdict for an APK distributed outside Play.
- `MANAGE_EXTERNAL_STORAGE` remains because the WhatsApp status saver and public gallery saving need
  it.

## Verified on the emulator

- Exact link at 720p: 1280x720 H.264, 128 kbps stereo AAC, 211.9 seconds, 26,667,318 bytes.
- Exact link at 1080p: 1920x1080 H.264, 128 kbps stereo AAC, 211.9 seconds, 45,812,160 bytes.
- Another video through `youtu.be` at 720p: 1280x720 H.264, 128 kbps stereo AAC, 213.1 seconds,
  29,939,520 bytes.
- The same video through a `/shorts/` URL at 360p: 640x360 H.264, 128 kbps stereo AAC, 213.1
  seconds, 11,873,473 bytes.
- The gallery preview displays `3:31` for the 211.9 second file.

SHA-256: 91b14f515b5ec33a460df3da21541046a437df3171de0e93b2f11fa82814e774
