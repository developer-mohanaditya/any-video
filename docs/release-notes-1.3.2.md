# AnyVideo 1.3.2

`AnyVideo-1.3.2.apk`, about 1.6 MB, Android 8.0 or newer. Signed with the same project key as
1.3.1, so it installs over it and keeps your settings and downloads.

## YouTube downloads

- A finished YouTube download no longer reports zero bytes. Tunnel replies can omit
  `Content-Length`, so the app uses the resolver's estimated length while downloading and the
  actual bytes on disk when it completes.
- YouTube preview cards now show the video's poster thumbnail instead of only the YouTube mark.
- A tunnel response with no body is rejected before it reaches the gallery. The app asks the
  resolver for one fresh link; if the reply is still empty it reports `Empty file` and publishes
  nothing.
- Zero-byte gallery entries left by an earlier attempt are removed before a new download starts.
- A stale gallery row at the same path no longer causes a `UNIQUE constraint failed` error. The
  app saves the new file with a numbered name instead of failing after the transfer.

SHA-256: 5cd2ab487fef68512ddea8d522452ade39dac41af4cb5547c0d0357f02cf14c5
