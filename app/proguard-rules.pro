# R8 keep rules for AnyVideo.
#
# The app is pure Kotlin/Compose with no reflection-driven model classes, so nothing needs an
# explicit keep yet. The release build already relies on the default Android rules plus
# `proguard-android-optimize.txt`; add entries here only when a shrunk build breaks something.

# Keep line numbers in release crash reports without keeping the whole mapping file.
-keepattributes SourceFile,LineNumberTable
