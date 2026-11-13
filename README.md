<div align="center">

<img src="docs/brand/logo.svg" width="104" alt="AnyVideo logo" />

# AnyVideo

**Save photos and videos from Instagram, YouTube, TikTok, X and Pinterest straight to your gallery.**

<a href="https://github.com/developer-mohanaditya/any-video/releases/download/v1.1.0/AnyVideo-1.1.0.apk">
  <img src="https://img.shields.io/badge/Download_AnyVideo_1.1.0-APK-6C4CF1?style=for-the-badge&logo=android&logoColor=white" alt="Download the AnyVideo APK" />
</a>
<img src="https://img.shields.io/badge/Android-8.0_and_newer-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0 and newer" />

</div>

## Download

The installable file is `AnyVideo-1.1.0.apk`, about 1.6 MB.

- Direct download: [AnyVideo-1.1.0.apk](https://github.com/developer-mohanaditya/any-video/releases/download/v1.1.0/AnyVideo-1.1.0.apk)
- Releases page: [github.com/developer-mohanaditya/any-video/releases](https://github.com/developer-mohanaditya/any-video/releases/latest)
- Copy in the repository: [AnyVideo-1.1.0.apk](AnyVideo-1.1.0.apk)

Android 8.0 or newer. The APK is signed with a development key, so Android will ask you to allow
installs from your browser or your file manager. If a later version is signed with a different key,
uninstall this one first.

## What it is

Paste a link, or share one into AnyVideo, and the app finds the media, downloads it itself with a
live progress bar, and files it in your gallery with the creator's username in the file name. There is no
account to create, no advertising SDK and no analytics. Instagram posts can also be resolved on the
device itself. YouTube, TikTok, X and Pinterest go through a cobalt compatible resolver.

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
| Audio | `Music/anyvideo` | `YouTube-___jNQXAC9IVRw___-.mp3` |

Downloads has separate Photos, Videos and Audio tabs. Swipe left and right to move between them,
tap a tile to preview it full screen, and long press a tile to select several, then share or delete
them together. Active downloads show their progress at the top of the Home and Downloads screens.

## Instagram, and the risk that comes with it

Public posts work without signing in. Private profiles, private posts and stories need a session,
because Instagram no longer returns feeds to signed out clients.

Automating a logged in account is against Instagram's terms and can trigger checkpoints,
temporary limits or a lock. Downloads are paced and stop on the first sign of a rate limit, but keep
the volume low and use an account you can afford to lose.

## Settings worth knowing

- **Appearance**: Auto follows the phone, or pin Light or Dark. The choice applies to every screen.

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
`keyAlias`, `keyPassword`). Without that file the release build falls back to the debug key, which is
how the APK above was built.

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
- The app is distributed as an APK because `MANAGE_EXTERNAL_STORAGE` is restricted on Google Play.

## Project policy

Git identity and publishing rules live in [docs/identity-policy.md](docs/identity-policy.md).
