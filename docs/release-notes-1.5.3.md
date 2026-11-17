# AnyVideo 1.5.3

`AnyVideo-1.5.3.apk`, versionCode 25, 1,805,970 bytes, Android 11 or newer. Signed with the same
project key as 1.5.2, so it installs over it and keeps your settings and downloads.

## Instagram sign-in

- The in-app browser now uses the phone's real WebView user agent instead of a fabricated Pixel 6
  identity. Instagram's login receipt should name the actual device.
- Account checks, private-media requests and the web fallback use that same user-agent identity.
  Instagram binds a web session to the client that created it, so mixing a browser UA with a
  desktop or default WebView UA could make a valid session look unusable.
- Browser fetch headers and the session CSRF token are sent with web requests. Cookie values are
  stripped of control bytes that would make the HTTP client reject an entire request.
- Account checks no longer follow Instagram's login redirect. A rejected session now shows a
  status such as `web_form_data 302` instead of misleading `unreachable`.
- Private Instagram posts now also try the `www` media endpoint using the web session.
- An existing session was created with the old Pixel 6 identity. After updating, sign out of
  Instagram in Settings and sign in again.

## Retained

- Settings still shows only whether the built-in or a custom resolver is active; the resolver URL
  and key are not displayed or prefilled.
- The baked default resolver is unchanged.

## Verified

- `:app:testDebugUnitTest` passes with 62 tests.
- `:app:lintRelease` passes with 0 errors and 29 warnings.
- `:app:assembleRelease` passes with R8 and resource shrinking.
- The signed APK reports versionCode 25 / versionName 1.5.3 and verifies with APK Signature Scheme
  v2.
- Its signing certificate SHA-256 matches the published 1.5.2 APK:
  `13e8cb49791783ef6ec6aa6823447f66fddaab1af2483bb043006d14d72054d4`.
- The fake Pixel 6 identity is absent from both the source and the release APK.

## Known limits

- The private Instagram API path was not exercised against a live account during this release.
  If Instagram still refuses the session, Settings will now report the specific HTTP status for
  each account lookup.
- The app checks ran on the API 35 emulator, not a physical phone.
- The built-in resolver is reachable only while its endpoint remains online.
- Hiding the resolver URL from the UI does not remove it from the APK.
- Play Protect may still warn because AnyVideo is sideloaded and keeps the in-app updater.

SHA-256: 84f8a6d195d7bb56e6a1716cc3098df3a9f92127837866e72384bc35157c758e
