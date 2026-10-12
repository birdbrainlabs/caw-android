# Caw for Android

Caw is hold-to-talk dictation for Android. Hold Crumb (the little 3D meatball on
screen) or the volume-down key, speak, let go, and your words appear.

Speech recognition runs entirely on the phone with
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and the Moonshine base
English model. It needs no Google services and no network to dictate.

It was built by someone with a stutter, for people whose speech the standard
recognizers follow poorly. Pauses, restarts and repeated sounds are expected,
not treated as mistakes, and a hold can last up to 5 minutes.

## What it does

- **Dictation app.** Opening Caw shows the dictation screen: a transcript of
  everything you said, with Copy and Clear (Clear can be undone for a few
  seconds).
- **Voice input method.** Caw is also an Android input method with a voice
  subtype, so a keyboard's mic key can hand off to it. With
  [HeliBoard](https://github.com/Helium314/HeliBoard), tap the mic key, hold
  Crumb, let go: the text goes into the text box and Caw switches back to
  HeliBoard.
- **Send to computer.** Paired with a computer running Caw Desktop (Linux, a
  separate program that is not part of this repo), Caw can type your
  dictation on the computer instead, with Backspace and Enter buttons.
  Pairing is by QR code or a typed code; the connection is TLS 1.3 on your
  local network, with both sides pinned by certificate fingerprint.
- **Touchpad.** The phone can move the paired computer's mouse: one finger
  moves, tap clicks, two fingers scroll, a two-finger tap right-clicks, press
  and hold to drag.
- **Word fixes.** Every recognizer mishears some of your words the same way
  every time. Caw keeps a list of "when it hears *this*, write *that*" in
  `word-fixes.json` in the app's files (the same format as Caw for Mac). The
  app has no editor for it yet; a debug build lets you read and replace it
  with `adb shell run-as com.caw.android ...`. The seed list is
  `app/src/main/assets/word-fixes.json`.
- A built-in cleaner removes fillers and stutters and tidies spacing and
  capitals, and Caw tells you when the mic seems covered ("I can't hear you").

## Private by design

- Dictation never leaves the phone. The only network use is Send to computer
  and the touchpad, and only to the computer you paired.
- No accounts, no analytics, no Google Play services.
- Crumb's page is served from the app's own files; the WebView blocks every
  other request.
- Debug builds keep the audio of the last 5 holds in the app's private files,
  to help with tuning. Release builds do not.

## Requirements

- An arm64 Android phone running Android 10 (API 29) or later. The APK only
  carries arm64 libraries.
- To build: JDK 17 and the Android SDK (platform 35). Android Studio is not
  needed.
- About 300 MB free on the phone: the speech model is packed into the APK.

## Build and run

1. Point Gradle at your Android SDK: set `ANDROID_HOME`, or write
   `sdk.dir=/path/to/android-sdk` into `local.properties` (not tracked).
2. Get the sherpa-onnx Android library. It is a GitHub release asset, not on
   Maven Central:

       curl -fL -o app/libs/sherpa-onnx.aar \
         https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar

3. Get the speech model (about 290 MB, not tracked):

       scripts/fetch-model.sh

   It downloads `sherpa-onnx-moonshine-base-en-int8` from the sherpa-onnx
   releases into `app/src/main/assets/models/moonshine-base-en/`. Without it
   the app builds but cannot transcribe. (`scripts/fetch-model.sh streaming`
   fetches an older streaming model that Caw falls back to when Moonshine is
   absent.)
4. Build, test and install (phone connected, `adb devices` shows it):

       ./gradlew testDebugUnitTest
       ./gradlew installDebug

5. Open Caw and hold Crumb. Caw asks for the microphone the first time (and,
   on Android 13+, for notifications: a silent "Caw is ready" notification
   keeps the speech model loaded in the background).

To use Caw from a keyboard, turn it on in Settings › System › Keyboard ›
On-screen keyboard › Manage keyboards (the wording varies by phone), then tap
HeliBoard's mic key. If the mic key does nothing, make sure only one voice
input method is enabled; HeliBoard uses the first one it finds.

`scripts/caw_desktop_stub.py` is a small test desktop for Send to computer and
the touchpad: it prints what the phone sends and answers like Caw Desktop
would (Python 3.8+ with TLS 1.3, standard library only).

## Status

Personal project. It works for the author; no support is promised.

## Licence

- Code: MIT, see `LICENSE`.
- Artwork, the Crumb character, and the names Caw and Crumb:
  all rights reserved, see `ART-LICENSE.md`.
- Third-party code and models (sherpa-onnx, ONNX Runtime, Moonshine, three.js,
  ZXing, AndroidX, Kotlin): see `THIRD_PARTY_LICENSES.md`.
