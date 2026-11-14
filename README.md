<div align="center">

<img src="docs/brand/logo.svg" width="104" alt="AnyVideo logo" />

# AnyVideo

**Save photos and videos from Instagram, YouTube, TikTok, X and Pinterest straight to your gallery.**

<a href="https://github.com/developer-mohanaditya/any-video/releases/latest/download/AnyVideo-1.3.6.apk">
  <img src="https://img.shields.io/badge/Download_AnyVideo_1.3.6-APK-6C4CF1?style=for-the-badge&logo=android&logoColor=white" alt="Download the AnyVideo APK" />
</a>
<img src="https://img.shields.io/badge/Android-11_and_newer-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11 and newer" />

</div>

## Download

The installable file is `AnyVideo-1.3.6.apk`, about 1.7 MB.

- Direct download: [AnyVideo-1.3.6.apk](https://github.com/developer-mohanaditya/any-video/releases/latest/download/AnyVideo-1.3.6.apk)
- Releases page: [github.com/developer-mohanaditya/any-video/releases](https://github.com/developer-mohanaditya/any-video/releases/latest)
- Copy in the repository: [AnyVideo-1.3.6.apk](AnyVideo-1.3.6.apk)

Android 11 or newer is the supported baseline. The APK is signed with the project release key, so later versions install
over this one and keep your settings and downloads. Android will ask once for permission to
install from your browser or file manager, and Play Protect may ask you to confirm because the
app does not come from the Play Store.

After the first install, Settings, Updates, Check for updates opens the GitHub release page. The
browser downloads the new APK and Android installs it; the app itself does not request permission
to install packages.

## What it is

Paste a link, or share one into AnyVideo, and the app finds the media, downloads it itself with a
live progress bar, and files it in your gallery with the creator's username, or the YouTube video
title, in the file name. There is no account to create, no advertising SDK and no analytics. Instagram posts and YouTube
videos up to 4K can also be resolved on the device itself. TikTok, X and Pinterest go through
a cobalt compatible resolver.

## What you can do with it

### Save a post, reel or carousel

1. Copy the link in Instagram.
2. Open AnyVideo and paste it into the box.
3. Pick the item you want and tap Download.

Photos land in `Pictures/anyvideo`, videos in `Movies/anyvideo`. Carousels arrive as separate files
and each one is queued once, so a carousel pasted a single time is not downloaded twice.

### Save a story, or a post from a private account you follow

1. Open Settings and tap Sign in under Instagram account.
2. Sign in on Instagram's own page in the Browser tab. The app never sees the password.
3. Paste the link as usual. Feed pages and media are fetched with your session.

Settings shows the account Instagram reports. **Check session** prints the raw answer for each
lookup, which is the fastest way to see where a private download stopped.

### Keep a YouTube video, or only the sound

Paste a YouTube link and choose a video quality up to 2160p, or choose Audio and then MP3 at 320,
256, 128 or 96 kbps, M4A, or OGG. Audio only files go to `Music/anyvideo`.

### Take a whole profile

Open Tools, choose Bulk download, type the username, load the feed, select the posts and queue them.
Pages are fetched a few at a time with a short pause between them, so a first run stays modest.

### Save something the app cannot parse

Open the Browser tab and load the page. It is a plain browser with an address bar and a progress
line, and it keeps the session used for private posts. Long press a video or image in the page to
save the file it points at.

### Repost with credit

Open Downloads, pick a file and tap Repost. Choose one of five tag positions and a light or dark
plate, copy the prepared credit caption, then share to Instagram. The file you share keeps its
original quality.

### Edit before posting

Tools holds nine utilities: bulk download, WhatsApp status saver, repost overlay, caption library,
grid splitter, no crop square, resize, circular DP maker and line break formatter. The image tools
write their output to the same folders, so the result is in Downloads straight away.

### Save WhatsApp statuses

Tools, WhatsApp status saver lists the images and videos the app can see, and saves the ones you
pick to the usual folders.

## Where files go

| Media | Folder | Example name |
| --- | --- | --- |
| Photo | `Pictures/anyvideo` | `user-___C1a2B3c4___-.jpg` |
| Video | `Movies/anyvideo` | `user-___C1a2B3c4___-.mp4` |
| YouTube video | `Movies/anyvideo` | `Me at the zoo - jawed [jNQXAC9IVRw].mp4` |
| YouTube audio | `Music/anyvideo` | `Me at the zoo - jawed [jNQXAC9IVRw].mp3` |

Downloads has separate Photos, Videos and Audio tabs. Swipe left and right to move between them,
tap a tile to preview it full screen, and swipe left or right again in the preview to move through
the rest of that tab. Long press a tile to select several, then share or delete them together. Active downloads show their progress at the top of the Home and Downloads screens.

## Instagram, and the risk that comes with it

Public posts work without signing in. Private profiles, private posts and stories need a session,
because Instagram no longer returns feeds to signed out clients.

Automating a logged in account is against Instagram's terms and can trigger checkpoints,
temporary limits or a lock. Downloads are paced and stop on the first sign of a rate limit, but keep
the volume low and use an account you can afford to lose.

## Settings worth knowing

- **Appearance**: Auto follows the phone, or pin Light or Dark. The choice applies to every screen.
- **Updates**: checks this project's GitHub releases and shows the notes and APK size. The button
  opens the release page in your browser, which downloads the APK for Android's normal installer.
  Keeping install permission out of the app avoids Play Protect's self-updater warning.

- **Resolver**: base URL and key for a cobalt compatible instance. **Test** calls `GET /` and
  reports the version and the number of services. No instance ships with this source, so set one
  here or bake one in at build time.
- **Advanced endpoints**: extractor and fallback proxy, off by default and only needed when a site
  stops working.
- **Auto download from clipboard**: opening the app with a copied link saves it without a tap.
- **Download notifications**: show the system notification when a file finishes.

## Build from source

JDK 17 or newer and an Android SDK with API 35. The SDK path goes in `local.properties` or
`ANDROID_HOME`.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease

# bake a default resolver into a build without committing it
./gradlew :app:assembleRelease -Panyvideo.resolver=https://resolver.example.com
```

Release signing reads `keystore.properties` from the repository root (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`). Without that file a release build stops before packaging and points at
`keystore.properties.example`, because a release signed with another key could never update an
installed copy. Debug builds still use the debug key.

## Privacy

No AdMob, AppLovin, Unity Ads, Firebase, Play Billing or third party analytics. Network calls go to
the platform you pasted a link from and to the resolver you configured. The only Android permission
worth explaining is storage: the app writes to public `Pictures`, `Movies` and `Music` folders, which
is why it asks for `MANAGE_EXTERNAL_STORAGE` on Android 11 and newer.

## Known limits

- Tested on Android emulators (API 30 and API 35), not on a physical phone.
- A private Instagram download has not been exercised end to end.
- Instagram endpoints change without notice. When they do, the resolver and the configurable
  extractor are the fallbacks.
- Some newer Instagram reels come back from the resolver as their cover image. The app refuses to
  save that still and asks you to sign in to Instagram, which then fetches the video.
- The app is distributed as an APK because `MANAGE_EXTERNAL_STORAGE` is restricted on Google Play.

## Project policy

Git identity and publishing rules live in [docs/identity-policy.md](docs/identity-policy.md).
