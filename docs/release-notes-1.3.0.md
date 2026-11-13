# AnyVideo 1.3.0

`AnyVideo-1.3.0.apk`, about 1.6 MB, Android 8.0 or newer. Signed with the same project key as
1.2.2, so it installs over it and keeps your settings and downloads.

## YouTube

- Gallery names are the video title plus the video id, as in
  `Me at the zoo - jawed [jNQXAC9IVRw].mp4`. Two videos with the same title no longer collide
  and no longer look like they were already saved.
- Choosing a quality in the format sheet starts the download immediately. The progress row sits
  under the paste field, and the separate Download button is gone.
- A resolver link that expired before the transfer started is refreshed and retried instead of
  failing with HTTP 404.
- Audio downloads use the same title with `.mp3`, `.m4a`, `.opus`, `.ogg` or `.wav`.

## Reels

- A reel that the resolver returns as its cover image is no longer saved as a JPG. The app asks
  for an Instagram sign-in and then fetches the video from Instagram.

## Interface

- Settings is a tab in the bottom bar.
- The platform marks moved to the top right, are smaller, and include Facebook.
- The keyboard closes after pasting a link, tapping download or auto-resolving one.
- The bottom bar is back to its standard height, so its icons are no longer clipped.

## Build

- A release can no longer be built without `keystore.properties`. This keeps every published APK
  on the same signing key, which is what makes in-place updates possible.

SHA-256: 005fb8665b07b633f697a692b056c2703bc42d5f55bc0492ce647fb99938c836
