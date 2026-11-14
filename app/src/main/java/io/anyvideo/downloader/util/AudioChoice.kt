package io.anyvideo.downloader.util

/**
 * The bitrate to save after the YouTube picker is confirmed. An automatic target lowered for the
 * current video stays local; only a tap on a bitrate chip changes the saved preference.
 */
internal fun audioBitrateToSave(
    saved: String,
    effective: String,
    explicitlyChosen: Boolean,
): String = if (explicitlyChosen) effective else saved
