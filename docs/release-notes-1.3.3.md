# AnyVideo 1.3.3

`AnyVideo-1.3.3.apk`, about 1.6 MB, Android 8.0 or newer. Signed with the same project key as
1.3.2, so it installs over it and keeps your settings and downloads.

## YouTube downloads

- For YouTube videos up to 1080p, the app now asks YouTube's own player for an H.264 video and
  an AAC audio stream, downloads both, and merges them on the phone. The resolver remains the
  fallback when that path is unavailable.
- Direct YouTube downloads work up to 1080p. 4K and higher still go through the resolver.
- Each stream is fetched in short range requests, so a CDN that closes a long connection part
  way through does not leave a truncated file.
- `https://youtube.com/watch?v=ByCbte_e5PE` saves as a 1280x720 MP4 with stereo AAC audio and
  runs 211.9 seconds.

## Gallery preview

- Swipe left or right in the full screen preview to move to the previous or next photo or video
  in the current tab.
- A page counter shows the position, for example `2 / 6`.
- Pinch to zoom still works. While zoomed, a drag pans the image instead of changing pages.

SHA-256: 866876a2d3814f21487be1d6e9e0fb081c1f47af7f201a164790f2537a0e8a55
