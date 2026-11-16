# AnyVideo 1.5.2

`AnyVideo-1.5.2.apk`, versionCode 24, 1,805,970 bytes, Android 11 or newer. Signed with the same
project key as 1.5.1, so it installs over it and keeps your settings and downloads.

## Resolver privacy

- Settings no longer displays the resolver URL. The Resolver card reports only **Built-in resolver
  active** or **Custom resolver active**.
- The server URL and API key fields start blank. They are never prefilled with saved values.
  **Change server** accepts a replacement, and **Reset to built-in** removes a custom server.
- Connection failures no longer repeat the resolver address in a toast.
- The included cobalt-compatible resolver is unchanged, so Instagram, TikTok, X and Pinterest work
  without setting up a server.
- A custom resolver saved by an older install still takes precedence. Open Settings, Resolver, and
  use **Reset to built-in** to return to the included resolver.

## Verified

- `:app:testDebugUnitTest` passes with 61 tests.
- `:app:lintRelease` passes with 0 errors and 29 warnings.
- `:app:assembleRelease` passes with the resolver baked into the APK, including R8 and resource
  shrinking.
- The signed APK reports versionCode 24 / versionName 1.5.2 and verifies with APK Signature Scheme
  v2.
- Its signing certificate SHA-256 matches the published 1.5.1 APK:
  `13e8cb49791783ef6ec6aa6823447f66fddaab1af2483bb043006d14d72054d4`.
- A clean install on the API 35 emulator showed `Built-in resolver active` in Settings with no
  resolver URL anywhere in the visible UI.
- Saving a custom resolver produced `Custom resolver active.`; the saved URL was not shown in the
  visible UI or in the expanded server fields.

## Known limits

- The app checks ran on the API 35 emulator, not a physical phone.
- The built-in resolver is reachable only while its endpoint remains online.
- Hiding the URL from the UI does not remove it from the APK; it is still present in the signed
  binary as the built-in endpoint.
- Play Protect may still warn because AnyVideo is sideloaded and keeps the in-app updater.

SHA-256: 38c08c5786ba18d5797e82c1908126a30522fb3a52cf2da6b260435b02e4cb98
