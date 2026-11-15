<div align="center">

<img src="docs/brand/logo.svg" width="104" alt="AnyVideo logo" />

# AnyVideo

**Save photos and videos from Instagram, YouTube, TikTok, X and Pinterest straight to your gallery.**

<a href="https://github.com/developer-mohanaditya/any-video/releases/latest/download/AnyVideo-1.4.0.apk">
  <img src="https://img.shields.io/badge/Download_AnyVideo_1.4.0-APK-6C4CF1?style=for-the-badge&logo=android&logoColor=white" alt="Download the AnyVideo APK" />
</a>
<img src="https://img.shields.io/badge/Android-11_and_newer-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11 and newer" />

</div>

## Download

The installable file is `AnyVideo-1.4.0.apk`, about 1.8 MB.

- Direct download: [AnyVideo-1.4.0.apk](https://github.com/developer-mohanaditya/any-video/releases/latest/download/AnyVideo-1.4.0.apk)
- Releases page: [github.com/developer-mohanaditya/any-video/releases](https://github.com/developer-mohanaditya/any-video/releases/latest)
- Copy in the repository: [AnyVideo-1.4.0.apk](AnyVideo-1.4.0.apk)

Android 11 or newer is the supported baseline. The APK is signed with the project release key, so later versions install
over this one and keep your settings and downloads. Android will ask once for permission to
install from your browser or file manager, and Play Protect may ask you to confirm because the
app does not come from the Play Store.

After the first install, Settings, Updates, Check for updates checks GitHub. AnyVideo also checks
once a day when it starts and offers a new release in a prompt. The signed APK downloads inside
the app, is checked against GitHub's checksum and the project signing key, and then opens Android's
installer. Android asks you to allow installs from AnyVideo once and asks you to confirm each
install; the app cannot install silently.

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
256, 128 or 96 kbps, M4A, or OGG. The video picker can also download a caption track in the
language the video offers. Audio only files go to `Music/anyvideo`.

### Watch, trim and set a ringtone

Tap Play on a completed download to open the full screen player. It keeps the screen awake while
playing and keeps the video visible in a picture in picture window shaped like the video, including
vertical clips, while you use another app. It has speed, sleep timer, orientation, caption and
external player controls. The More menu locks orientation to Auto, Portrait or Landscape.

Captions open from the CC button. They can be turned off, switched between downloaded sidecars, or
styled with Size, Background, Position and Lines. Long press the active caption to copy it or share
it with its timestamp.

Tools, Clip video picks a start and end from a local video and saves a trimmed copy to
`Movies/anyvideo` without re-encoding. Tools, Ringtone maker trims an audio file, previews it, and
saves it to `Ringtones/anyvideo`; the sound settings action lets you make it the ringtone,
notification or alarm sound.

### Take a whole profile

Open Tools, choose Bulk download, type the username, load the feed, select the posts and queue them.
Pages are fetched a few at a time with a short pause between them, so a first run stays modest.

### Save something the app cannot parse

Open the Browser tab and load the page. It is a plain browser with an address bar and a progress
line, and it keeps the session used for private posts. Tap Full screen to hide the address bar,
the app tab bar and the system bars; swipe in full screen or tap the exit button to return. Long
press a video or image in the page to save the file it points at.

### Repost with credit

Open Downloads, pick a file and tap Repost. Choose one of five tag positions and a light or dark
plate, copy the prepared credit caption, then share to Instagram. The file you share keeps its
original quality.

### Edit before posting

Tools holds eleven utilities: bulk download, WhatsApp status saver, clip video, ringtone maker,
repost overlay, caption library, grid splitter, no crop square, resize, circular DP maker and line
break formatter. The image tools write their output to the same folders, so the result is in
Downloads straight away.

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

Downloads has separate Photos, Videos and Audio tabs for the files saved on your phone. Swipe
left and right to move between them, tap a tile to preview it full screen, and swipe left or right
again in the preview to move through the rest of that tab. Long press a tile to select several,
then share or delete them together. Live download progress, completed files and failures appear
in the system notification instead of taking over this screen. Pause, Resume and Cancel are in
the notification while transfers run, and batches start automatically.

## Instagram, and the risk that comes with it

Public posts work without signing in. Private profiles, private posts and stories need a session,
because Instagram no longer returns feeds to signed out clients.

Automating a logged in account is against Instagram's terms and can trigger checkpoints,
temporary limits or a lock. Downloads are paced and stop on the first sign of a rate limit, but keep
the volume low and use an account you can afford to lose.

## Settings worth knowing

- **Appearance**: Auto follows the phone, or pin Light or Dark. The choice applies to every screen.
- **Updates**: checks this project's GitHub releases at launch and from Settings, and shows the
  notes and APK size. The signed APK downloads inside the app and Android's normal installer asks
  for confirmation. Android also asks once for permission to install updates from AnyVideo.
  Dismissing the prompt leaves the update available in Settings.

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
- Android does not allow silent self-installs. The app downloads and verifies the APK, but Android
  still asks you to allow installs from AnyVideo and to confirm the replacement.
- Captions are available only for YouTube downloads whose picker offered a caption track. A
  video without a downloaded sidecar shows `No subtitles` in the CC menu.
- Caption styling, the selected orientation and the long-press actions are available only in the
  built-in player. Opening the file in another app uses that app's caption support.

## Project policy

Git identity and publishing rules live in [docs/identity-policy.md](docs/identity-policy.md).
