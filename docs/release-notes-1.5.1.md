# AnyVideo 1.5.1

`AnyVideo-1.5.1.apk`, versionCode 23, 1,805,970 bytes, Android 11 or newer. Signed with the same
project key as 1.5.0, so it installs over it and keeps your settings and downloads.

## Included resolver

- This APK includes a cobalt-compatible default resolver, so Instagram, TikTok, X and Pinterest
  work after installation without entering a server URL.
- The resolver address is supplied at build time and is not stored in the committed source. A
  rebuild without that build-time value has no default resolver.
- A custom resolver saved by an older install still takes precedence. Open Settings, Resolver,
  and tap Clear server to return to the included default.
- The endpoint was checked directly: `GET /` reported cobalt 11.7.1 with 21 services, including
  Instagram, and real Instagram reel URLs resolved to MP4 tunnel responses.

## Verified

- `:app:testDebugUnitTest` passes with 61 tests.
- `:app:lintRelease` passes with 0 errors and 29 warnings.
- `:app:assembleRelease` passes with the resolver baked into the APK, including R8 shrinking and
  resource shrinking.
- The signed 1.5.1 APK reports versionCode 23 / versionName 1.5.1 and verifies with APK Signature
  Scheme v2.
- Its signing certificate SHA-256 matches the published 1.5.0 APK:
  `13e8cb49791783ef6ec6aa6823447f66fddaab1af2483bb043006d14d72054d4`.
- A clean install on the API 35 emulator resolved a public Instagram reel through the included
  resolver and started saving the MP4.
- Home and the Notifications screen open normally after installation.

## Known limits

- The app checks ran on the API 35 emulator, not a physical phone.
- The baked resolver is reachable only while that tailnet/tunnel endpoint remains online.
- Play Protect may still warn because AnyVideo is sideloaded and keeps the in-app updater.

SHA-256: 1e76dc8aa87f2c4b497b65c8a306aae5ae68a004cfa8984380d898e711c1a71c
