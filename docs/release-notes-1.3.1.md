# AnyVideo 1.3.1

`AnyVideo-1.3.1.apk`, about 1.6 MB, Android 8.0 or newer. Signed with the same project key as
1.3.0, so it installs over it and keeps your settings and downloads.

## Instagram stories and highlights

- Story links now download on the device. A link to one story saves that story; a profile story
  link saves the profile's active stories.
- Highlight links save every item in the highlight.
- Story media uses the signed-in Instagram session, so private accounts work when you follow
  them. Signed out, the app asks you to sign in instead of reporting an unsupported link.

## Instagram sign-in

- The sign-in page now renders in the Browser tab. Instagram lays the form inside wrappers with
  zero height and clipped overflow, which left the page black and its fields untappable. The
  browser now expands those wrappers to the viewport height.

## Saving

- The progress line describes the actual files, such as `Saving 2 pictures`, `Saving 1 movie` or
  `Saving 1 picture and 1 movie`, instead of one generic message for every type.
- The download button in the input field becomes a tick for about 1.6 seconds, then returns to
  the download icon and shows a short `Saved to gallery` alert. The permanent check row under the
  preview is gone.

SHA-256: 283a5b97756558c0b29f71e1f99825a59460441b4d967001f9f56fd1d8e6d645
