# AnyVideo 1.5.4

`AnyVideo-1.5.4.apk`, versionCode 26, 1,805,970 bytes, Android 11 or newer. Signed with the same
project key as 1.5.3, so it installs over it and keeps your settings and downloads.

## Instagram photos and carousels

- The embed page is now requested with a crawler identity. Instagram only fills that page's
  `contextJSON` for a crawler; a desktop browser gets an empty app shell. That shell is why an
  image carousel could look like it had a single picture, and why some image posts returned
  nothing at all.
- A post that the reply calls a video, but that arrives without a stream URL, is no longer saved
  from its `display_url`. Before this, its cover was written to `Pictures/anyvideo` as a photo and
  looked like a successful video download.
- The embed's own shape markers are read as well: `isSidecar` marks a carousel, a `GraphVideo`
  context a video, and a null context a plain photo. If the embed cannot be read, the post page's
  `og:url` route is checked, so a video served under `/p/` is still recognised.
- The public oEmbed fallback now runs only for a post that is known to be a single photo. It
  answers with exactly one image, so it must not stand in for a video or for a carousel.
- Carousels are counted, not guessed. Every slide the reply described is compared with the
  candidates that were actually built; when a multi-slide post is missing one, nothing is saved and
  the app says the carousel was incomplete. A partial carousel is never presented as the whole
  post, and a single unusable item is reported as what it is instead of as a missing slide.
- The same rule applies to the v1 API, the `?__a=1` web payload and the embed page, so every tier
  answers the same way.

## Retained

- Settings still shows only whether the built-in or a custom resolver is active; the resolver URL
  and key are not displayed or prefilled.
- The baked default resolver is unchanged.

## Verified

- `:app:testDebugUnitTest` passes with 73 tests, 0 failures and 0 errors, including six new cases:
  a video node without a stream is dropped instead of saved as its cover, an unreadable carousel
  slide is flagged as incomplete while a single unusable item is not, an embed video keeps its
  stream, the embed shape separates a photo shell from a video reply, and the post page marks a
  reel route as video.
- `:app:assembleDebug` passes.
- `:app:assembleRelease` passes with R8, resource shrinking and `lintVitalRelease`.
- The smoke harness gained an `instagram-image-oembed` scenario: an image-only post whose API and
  web tiers are empty, whose embed names it a plain photo, and which is saved through oEmbed. Its
  new harness routes were served and checked over HTTP.
- The signed APK reports versionCode 26 / versionName 1.5.4 and verifies with APK Signature Scheme
  v2.
- Its signing certificate SHA-256 matches the published 1.5.3 APK:
  `13e8cb49791783ef6ec6aa6823447f66fddaab1af2483bb043006d14d72054d4`.

## Known limits

- The adb-driven smoke scenario was added but not executed for this build: the macOS toolchain used
  here has no emulator package, and no device was attached. The parser and shape logic it exercises
  is covered by the unit tests; the on-device flow still needs a phone or emulator run.
- The private Instagram API path was not exercised against a live account. The new fallbacks use
  only public endpoints, so a private carousel still needs a session.
- The embed page's crawler view is Instagram's behaviour today and can change without notice. When
  it does, the app refuses an unreadable carousel instead of saving one slide.
- The crawler identity is only used when there is no session. A signed-in request keeps the browser
  identity that owns its cookie, and that reply can be the empty app shell; if the private API and
  `?__a=1` tiers also come back empty, the single-image oEmbed answer can still stand in for a
  carousel. That path needs a session whose API tiers both fail and was not exercised here.

SHA-256: 48ea47d8554193a26ae7bd7700d6e6474bfb8d3911991a205f511bf54e0f0f00
