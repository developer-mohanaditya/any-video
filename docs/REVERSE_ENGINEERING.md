# Reverse-engineering report: `photo.video.instasaveapp` ("Video Downloader")

Target: `app.apk`, 31 MB, sha256 `fdb086e3c2aac6944f74c0cdb265b7a093aa16904821dec7330ff8a0ca7bd6c2`.

| Fact | Value | Source |
| --- | --- | --- |
| Package | `photo.video.instasaveapp` | REA `inspect-android-package` |
| Version | 110.0 (code 110) | same |
| SDK range | min 24, target 35 | same |
| Classes / resources | 14,557 / 1,982 | same |
| DEX | 3 files, 17 MB total, no `lib/` native code | `unzip -l` |
| Display name | "Video Downloader" / launcher "Video Downloader Fastsave" | `resources/res/values/strings.xml` |
| Ads/monetisation | AdMob (`ca-app-pub-1096624161786585/3550364983`), AppLovin MAX, Unity Ads, HMS ML Kit, Firebase (Analytics/FCM/Remote Config), Play Billing SKU `premium_lifetime` | manifest + `purchase/` |
| Analytics/backend | Firebase project `fastsave-for-instagarm` | `strings.xml` (`project_id`, `firebase_database_url`) |

Tooling: REA 6.8.0 (`inspect-android-package`, JADX 0.7.1 bridge, Temurin 21) plus JADX 1.5.1 for a full source tree. No APK code was executed; everything below is static observation.

## 1. Component map

121 declared components: 84 activities, 18 services, 12 receivers, 7 providers.
Launcher: `photo.video.instasaveapp.WelcomeActivity` → `SplashScreen` → `fast.browser.activity.MainActivity` (the browser shell is the real home screen).

Feature packages:

| Area | Classes |
| --- | --- |
| Shell / browser | `fast.browser.activity.{MainActivity,DownloadTaskActivity,SettingActivity,SavedPagesActivity,ScriptListActivity}` |
| Single-link download | `v8.X`, `v7.t`, `v7.S`, `fast.browser.eventBus.ServiceModel` |
| Bulk download | `bulkDownload.{BulkHomeActivity,InstaLoginActivity,InstaDownloadingActivity}`, `bulkDownload/floatingDownload/{FloatingService,a}` |
| Instagram API client | `x8.a` (GraphQL + `i.instagram.com` v1 API) |
| Downloads gallery | `MyDownloadsActivity` (ViewPager: Photos / Videos) |
| Repost | `RepostActivity` + `J1.a` (caption DB) |
| Tools | `tools.{whatsapp,caption,dpMaker,resizePhoto,gridMaker,lineBreak}`, `com.example.newfastsave.{storyArt,bgEraser,collage,editVideo}` |
| Media helpers | `v7.S` (paths + DownloadManager), `y7.h`, `C0888b`/`C0889c` (request models) |
| Premium | `purchase.{PurchaseActivity,r,u}` (Play Billing, SKU `premium_lifetime`) |

## 2. Download pipeline (the core behaviour)

### 2.1 Entry points

1. **Paste link** in the browser search bar (`R.string.search_or_paste_link`, `paste_link_to_download`).
2. **Share sheet**: exported `fast.browser.activity.MainActivity` accepts `SEND`, `SEND_MULTIPLE` and `VIEW` intents, so "Share → Video Downloader" from Instagram lands in the same resolver.
3. **Floating bubble** (`FloatingService`, foreground type `dataSync` + `SYSTEM_ALERT_WINDOW`): reads the clipboard and starts a download without leaving Instagram (`how_to_float_download_des`, `overlay_permission_des`).
4. **In-app browser sniffing**: `MainActivity` injects JS and catches media requests.

### 2.2 Instagram resolver: `v7.t`

* Normalise the URL, strip query, map `/reel/` and `/tv/` to `/p/` (`v8.X.i`).
* Primary: `GET https://www.instagram.com/p/<shortcode>/<?|&>__a=1&__d=1` (`v7.t` line ~592).
* Fallbacks:
  * `GET https://i.instagram.com/api/v1/media/<media_id>/info/`
  * `GET https://i.instagram.com/api/v1/users/<id>/info/`
  * `GET https://i.instagram.com/api/v1/users/web_profile_info/?username=<user>`
  * `GET https://i.instagram.com/api/v1/feed/user/<id>/?count=50`
  * `GET https://i.instagram.com/api/v1/feed/user/<id>/reel_media/`
  * GraphQL `query_hash=44efc15d3c13342d02df0b5a9fa3d33f` (timeline), `90709b530ea0969f002c86a89b4f2b8d` (highlight items), `c9100bf9110dd6361671f113dd02e7d6` (highlight tray), `query_id=17880160963012870`
* User-Agent used for `i.instagram.com`: `Instagram 10.8.0 Android (18/4.3; 320dpi; 720x1280; Xiaomi; HM 1SW; armani; qcom; en_US)`; `x8.a` also carries `Instagram 241.1.0.18.114 Android (31/12; 420dpi; 1080x2312; samsung; SM-G780F; exynos990; en_US;)`.
* Cookies are read from `CookieManager.getInstance().getCookie("https://www.instagram.com")` and replayed as a `Cookie` header, which is how logged-in-only content is reached after `InstaLoginActivity` logs the user in inside a WebView.
* Carousels (`GraphSidecar`) are expanded via `edge_sidecar_to_children`; videos use `video_versions[0].url` (v1 API) or `video_url` (GraphQL), images use `image_versions2.candidates[0].url` / `display_url`.

### 2.3 Generic resolver: `v8.X` ("hidden WebView" trick)

For everything that is not Instagram (Facebook, TikTok, X, Vimeo, Dailymotion, Pinterest):

1. `POST https://snapsave.app/action.php`, form field `url`, headers `user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/115.0.0.0 Safari/537.36`, `cookie:` empty (lines ~196-210).
2. The HTML answer is rewritten: `decodeURIComponent(escape(r))` → `console.log('Hello'+decodeURIComponent(escape(r)))`.
3. That script is run in a **non-attached `WebView`** (`new WebView(context)`, JS enabled, same desktop UA, `evaluateJavascript`).
4. The page's own decoding runs, printing media URLs; the app's `WebChromeClient.onConsoleMessage` parses them with `A8.b.a(...)` and keeps
   * `image/*` URLs, and
   * URLs containing `token=` and `&dl=1` (video CDN links).
5. Each hit becomes `ServiceModel(url, thumbnail, "User-___<timestamp>___-.jpg|.mp4", isVideo, originalPostUrl)`.
6. `X.g()` dispatches the list to EventBus (`f7.c`) or to the floating-download callback.

Direct site helpers also exist: `https://www.dailymotion.com/player/metadata/video/%s`, `https://vimeo.com/%s`, `https://www.pinterest.com/pin/%s`.

### 2.4 Server-side fallback

`MyApplication` registers a `DownloadManager` completion receiver. When a download ends in `DownloadManager.STATUS_FAILED` and the original URL is **not** already a `mobisoftwares.com` URL, it re-enqueues:

```
https://mobisoftwares.com/find/download.php?isvideo=<true|false>&url=<url-encoded>
```

Two more first-party endpoints exist: `get_hashtag.php?cat=` (hashtag catalogue) and the privacy/cookie pages. This is the only server component; everything else is client-side scraping.

### 2.5 Filenames and storage

Filename convention produced by the resolvers:

```
<username>-___<postId|timestamp>___-.<jpg|mp4>
```

`v8.X` uses `User-___<System.currentTimeMillis()>___-.jpg` for generic pages. `RepostActivity` parses that name back:
`username` = text before the first `-`/`_`, caption = `J1.a.c(between "-___" and "___-")` from the `caption_db` SQLite table (`CREATE TABLE post(id INTEGER PRIMARY KEY AUTOINCREMENT,caption TEXT,post_id TEXT UNIQUE)`).

Destination directories (`v7.S.O` / `v7.S.P`):

| API | Images | Videos |
| --- | --- | --- |
| legacy | `Downloads/FastSave/Images` | `Downloads/FastSave/Videos` |
| current | `Pictures/FastSave` | `Movies/FastSave` |
| WhatsApp saver | `Pictures/FastSave` | `Movies/FastSave` (MediaStore `relative_path`) |

Downloads use `android.app.DownloadManager` with `setDestinationUri`, `setMimeType(image/*|video/*)`, `setAllowedOverMetered(true)`, notification visibility toggled by a user setting.

## 3. Bulk download

`InstaLoginActivity` logs in through a WebView (`https://www.instagram.com/accounts/login/`) so the session cookie exists; `login_in_required_des` says login is needed for private accounts. `x8.a` then pages the profile:

* `graphql/query/?query_hash=44efc15d…&variables={"id":…,"first":24,…,"after":"<cursor>"}` for timeline posts,
* `highlights/<user>/highlights_tray/` and `feed/reels_media/?reel_ids=highlight:<id>` for stories/highlights,
* `users/web_profile_info/?username=` for the user id.

`BulkHomeActivity` shows the grid, `InstaDownloadingActivity` queues selected posts through the same `DownloadManager`, and `FloatingService` keeps the queue alive in the background (foreground service, `dataSync`).

## 4. Media tools (each is a separate activity)

| Tool | String resource | Behaviour observed |
| --- | --- | --- |
| Repost | `R.string.repost` | Composes the saved image + a username tag overlay (5 positions: left/right top/bottom, remove; dark/light styles), copies `credit:@username + caption` to the clipboard, then `ACTION_SEND` `image/*` to `com.instagram.android` through `FileProvider`. |
| Caption | `captions` | Caption browser/editor with tag selection (`select_hashtags_des`), copy-to-clipboard. |
| Hashtags | `hashtags` | Catalogue loaded from `mobisoftwares.com/get_hashtag.php?cat=` with local fallback. |
| DP maker | `create_dp`, `dp_frames` | Frames + text overlay, saves to `Downloads/FastSave`, share. |
| Grid maker | `grid_maker` | 2-9 photo grid, splits one image into tiles (`select_2_to_9_photos`, `GridResultActivity`). |
| Square photo | `photo_square` | No-crop square with blurred background/filters. |
| Resize photo | `resizePhoto` | Crop (uCrop) + resize presets. |
| Line break | `line_break` | Caption formatter that inserts line breaks, then copies. |
| WhatsApp saver | `whatsapp` strings | Reads `WhatsApp/Media/.Statuses` (and `Android/media/...`), lists images/videos, saves via MediaStore to `FastSave`, deletes status. |
| Story art / Bg eraser / Collage / Edit video | `moduleStoryArt`, `moduleBgEraser`, `moduleCollage`, `moduleEditVideo` | On-demand feature modules (downloaded, `Downloading %1$s files`, `module*Activity` strings). HMS ML Kit is present for the bg-eraser class of features. |

## 5. Gating, ads and remote config

Firebase Remote Config keys observed: `isGoToMain`, `isDownloadFlow`, `isShowAppOpen`, `showDemoAd`, `interGap`, `isButtonUp`, `isCollapsible`, `isCustomOff`, `addNativeAfterDSuccess`, `download_data`. They gate: the welcome/consent path, an app-open ad, interstitial pacing (`interGap`), native ads after a download, and a collapsible/up-shifted download button.

Flow that matters for a rebuild: UMP consent → AppLovin consent flags → `SplashScreen` → browser home. Interstitials fire on `MyDownloadsActivity.onResume` every third visit and around download completion. `premium_lifetime` unlocks via Play Billing (`PurchaseActivity`, `purchase/r`).

## 6. What a rebuild must reproduce

1. Paste/share/bubble → resolve → download → gallery, with the same filename and folder conventions.
2. Instagram resolver with cookie replay and carousel/Reels/highlight support.
3. A generic resolver that works when Instagram's private API changes (the hidden-WebView + console-channel trick is the reusable idea).
4. Bulk profile/story download behind a WebView login.
5. The tool suite (repost composer, caption/hashtag, DP/grid/square/resize/line-break, WhatsApp status saver).
6. Download notifications and a background floating entry point.

Deliberately not reproduced: AdMob/AppLovin/Unity ad units, Firebase, HMS, Play Billing, and the `mobisoftwares.com` fallback host (the rebuild exposes a configurable endpoint instead, since that server belongs to the original publisher).
