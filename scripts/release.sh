#!/bin/sh
# Builds a signed release APK.
#
#     scripts/release.sh            public APK for the GitHub release (no personal word list)
#     scripts/release.sh personal   the same, plus ~/.config/caw-android/assets (the owner's phone)
#
# The key is ~/.config/caw-android/signing/caw-release.p12 (alias caw), never in the repo. Its password
# comes from CAW_KEYSTORE_PASSWORD, or else the macOS Keychain item "Caw Android signing key".
# Lose the key and installed copies can never be updated again: keep a backup.
#
# Output: build/release/caw-android-<version>.apk (or -personal.apk) and its .sha256.
set -eu
cd "$(dirname "$0")/.."

KEYSTORE="$HOME/.config/caw-android/signing/caw-release.p12"
[ -f "$KEYSTORE" ] || { echo "No signing key at $KEYSTORE" >&2; exit 1; }
if [ -z "${CAW_KEYSTORE_PASSWORD:-}" ]; then
    CAW_KEYSTORE_PASSWORD=$(security find-generic-password -a caw-release -s "Caw Android signing key" -w)
fi
export CAW_KEYSTORE_PASSWORD

case "${1:-public}" in
    public)   ./gradlew -Pcaw.public clean assembleRelease; SUFFIX="" ;;
    personal) ./gradlew clean assembleRelease; SUFFIX="-personal" ;;
    *) echo "usage: scripts/release.sh [public|personal]" >&2; exit 2 ;;
esac

VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "No signed APK (is the key password right?)" >&2; exit 1; }

# A public APK must never carry the owner's word list.
if [ -z "$SUFFIX" ] && unzip -l "$APK" | grep -q word-fixes.personal; then
    echo "Public APK contains word-fixes.personal.json; not releasing it" >&2; exit 1
fi

OUT=build/release/caw-android-$VERSION$SUFFIX.apk
mkdir -p build/release
cp "$APK" "$OUT"
(cd build/release && shasum -a 256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "$OUT"
