# AnyVideo 1.3.9

`AnyVideo-1.3.9.apk`, versionCode 18, 1,773,202 bytes, Android 11 or newer. Signed with the same
project key as 1.3.8, so it installs over it and keeps your settings and downloads.

## Notifications

- Every download notification now has its own white notification icon, so the status bar and the
  shade no longer show a blank or missing glyph. The expanded notification keeps the app tile as
  its large icon.
- Active transfers show the file name, a progress bar and the percentage in the shade.
- The active notification shows Pause, Resume or Start as appropriate, plus Cancel. Its updated
  state comes from the download engine, so the buttons stay in step with the transfer.
- A finished file leaves a Download complete notification after the service stops. Failures get a
  Download failed notification with the file name and reason. Cancelled transfers do not post a
  failure.
- Completed and failed items use their own notification channels. The old Active, Completed and
  Failed channels are no longer used.

## Downloads

- Downloads is a saved-file gallery again. It shows only Photos, Videos and Audio, with the usual
  grid, preview, share and delete actions.
- There are no Active, Completed or Failed session tabs, no progress cards and no session count.
  Transfers and their results live in the notification shade instead.
- Queued items start automatically in order, so removing the Start button from Downloads does not
  strand a carousel or batch.
- The dead session UI and its unused state have been removed from the screen.

## Browser

- Full screen now hides the address bar, the app tab bar and the system status and navigation
  bars. Instagram gets the whole display instead of fighting the app chrome.
- Exit full screen with the button in the corner, the system back gesture, or by swiping the
  system bars into view. The app chrome returns when full screen ends.
- The WebView uses a mobile viewport with wide content, overview mode and pinch zoom, and accepts
  third party cookies so an Instagram login survives navigation.
- Instagram's clipped zero-height login and consent wrappers are expanded, and the optional cookie
  sheet is dismissed with the essential-only choice.
- WebView history is handled before the app back action, including Instagram's single page
  navigation, so Back stays inside the page you are reading. External schemes go to the matching
  app when one is installed.

## Verified

- `:app:testDebugUnitTest` and `:app:assembleRelease` pass.
- The signed release installs on the API 35 emulator as 1.3.9, versionCode 18.
- Downloads shows only the Photos, Videos and Audio tabs and saved files. The old session tabs and
  cards are gone.
- A 240p YouTube download produced an active notification with the new icon, progress and
  Pause/Cancel actions, then a Download complete notification with the app tile after the service
  stopped.
- Full screen removes the URL bar, app tabs, status bar and navigation bar. Instagram's public
  page and its Search & Explore page both render, and Back returns through the page instead of
  leaving the Browser tab.
- No app crash appeared in logcat during the release smoke test.

## Known limits

- Signing in to Instagram was not exercised in this release test. The login page, public
  Instagram pages and in-page navigation were checked.
- The browser is a WebView. Sites that refuse WebView user agents can still show a reduced page.
- The first time full screen hides the system bars, Android shows its own "Viewing full screen"
  education card. It appears once per device and is dismissed with Got it.
- Android still needs notification permission for the shade, and install permission for in-app
  updates.

SHA-256: 741aba6078de0480195fa438813bf07e2bbbb41184f136c73b52beef6c1f0517
