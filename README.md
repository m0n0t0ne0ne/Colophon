# Colophon

A free, private audiobook player for Android. It plays your own DRM-free files, keeps long series in order, and can find chapters and their titles by listening to the audio on your phone.

Website: https://m0n0t0ne0ne.github.io/Colophon/
Download: [latest release](https://github.com/m0n0t0ne0ne/colophon/releases/latest)

## What it does

- **Chapter analyzer:** finds likely breaks from longer pauses, transcribes just after each one, and suggests titles such as Prologue, Chapter 12, or Epilogue. Uncertain results are flagged for review.
- **Your edits stay separate:** rename chapters, move start times, or merge and delete markers. The audio file is never changed, and you can reset to the original chapters.
- **Series:** groups books into one series, in publication order or series order.
- **A saved place in every book:** position is saved every few seconds, and the book backs up a little when you return. Jump back undoes a mistaken skip.
- **Custom art and a dynamic background:** choose your own covers, then let the player use blurred art, a color from the art, or black.
- **Fully local:** the app does not request the internet permission.

## Formats

M4B, M4A, MP4, AAC, MP3, FLAC, Ogg, Opus, and WAV, as single files or a folder of files. Existing chapters are read from M4B/MP4, MP3 (ID3), FLAC and Ogg (Vorbis comments), and CUE sheets. M3U playlists set the file order.

Protected Audible AA/AAX files and other DRM-protected audio are not supported.

## Limits

- This release is built for 64-bit ARM phones (`arm64-v8a`) running Android 8.0 or later.
- The analyzer recognizes English. It can mishear unusual names, so use the review screen.
- It is an early release, so please report problems in Issues.

## Building

Requires JDK 17 or later and the Android SDK (API 35).

```sh
scripts/fetch-assets.sh     # downloads the speech model into the app's assets
./gradlew testDebugUnitTest assembleDebug
```

To sign a release build, set `COLOPHON_KEYSTORE`, `COLOPHON_KEYSTORE_PASSWORD`, `COLOPHON_KEY_ALIAS`, and `COLOPHON_KEY_PASSWORD`, then run `./gradlew assembleRelease`. Keep the keystore private and out of git.

## Credits

Speech recognition: [Vosk](https://alphacephei.com/vosk/) and its small English model (Apache 2.0). Playback: AndroidX Media3 (Apache 2.0). Fonts: Newsreader and Hanken Grotesk (SIL Open Font License).

## License

Apache License 2.0. See [LICENSE](LICENSE).
