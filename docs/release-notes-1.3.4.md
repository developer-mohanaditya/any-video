# AnyVideo 1.3.4

`AnyVideo-1.3.4.apk`, about 1.6 MB, Android 8.0 or newer. Signed with the same project key as
1.3.3, so it installs over it and keeps your settings and downloads.

## YouTube fixes

- Audio-only downloads no longer stop at an empty resolver tunnel. The app saves YouTube's
  original AAC track as an M4A file instead.
- MP3, OGG and WAV still go through the resolver for conversion. If the resolver cannot produce
  the selected format, the fallback is M4A, not a failed download.
- A 720p choice no longer quietly becomes 360p. The direct stream is only used when it has the
  requested height; otherwise the resolver gets the request.

## Gallery preview

- Swipe left or right in the full screen preview to move through the photos or videos in the
  current tab.
- A page counter shows the position, for example `2 / 6`.
- Pinch to zoom still works. While zoomed, a drag pans the image instead of changing pages.

## Verified on the emulator

- `https://youtube.com/watch?v=ByCbte_e5PE` at 720p: 1280x720 H.264 with 128 kbps stereo AAC,
  211.9 seconds.
- The same link audio-only: 3.3 MB M4A, 128 kbps stereo AAC, 211.9 seconds.

SHA-256: 1fdfed377497f1daf1f9cec8c8ea09b0abd4b3b37540850ab5f9f519daeba097
