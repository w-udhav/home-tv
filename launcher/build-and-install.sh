#!/bin/bash
# Builds the launcher without Gradle (aapt2 + javac + d8), installs it on the TV and makes it the
# default home screen.
#
#   TV_HOST=192.168.1.50 launcher/build-and-install.sh      # build, install, set as Home
#   launcher/build-and-install.sh --no-install              # build only (CI)
#
# Needs JDK 17, Android build-tools 34 and the API 34 platform. Point at them with JAVA_HOME,
# BUILD_TOOLS (folder with aapt2, d8, apksigner) and ANDROID_JAR, or keep them all under
# ANDROID_BUILD as jdk/, android-14/ (the build-tools zip unpacks to that name) and android-34/.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_BUILD:-$HOME/.local/share/android-build}"
BT="${BUILD_TOOLS:-$SDK/android-14}"
JAR="${ANDROID_JAR:-$SDK/android-34/android.jar}"
export JAVA_HOME="${JAVA_HOME:-$SDK/jdk}"; export PATH="$JAVA_HOME/bin:$PATH"
TV="${TV:-${TV_HOST:-192.168.1.2}:5555}"
ADB="${ADB:-$(command -v adb || echo "$HOME/.local/share/platform-tools/adb")}"
B=build; VER=$(( $(date +%s) / 60 ))

rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml -A assets \
  --java "$B/gen" --min-sdk-version 28 --target-sdk-version 34 \
  --version-code "$VER" --version-name "1.0.$VER" "$B/res.zip"
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java') 2>&1 | grep -v 'warning: \[options\]' || true
[ -f "$B/classes/com/clean/home/HomeActivity.class" ] || { echo "javac failed"; exit 1; }
"$BT/d8" --min-api 28 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
(cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
# A per-machine debug key, never committed. Keep it: Android refuses updates signed with another key.
[ -f debug.jks ] || keytool -genkeypair -keystore debug.jks -storepass android -keypass android \
  -alias home -keyalg RSA -keysize 2048 -validity 10000 -dname CN=CleanHome >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.jks --ks-pass pass:android --out "$B/CleanHome.apk" "$B/aligned.apk"
echo "Built launcher/$B/CleanHome.apk"
[ "${1:-}" = "--no-install" ] && exit 0

a(){ "$ADB" -s "$TV" "$@" </dev/null; }
"$ADB" connect "$TV" >/dev/null
a push "$B/CleanHome.apk" /data/local/tmp/CleanHome.apk >/dev/null
a shell pm install -r /data/local/tmp/CleanHome.apk      # pm, not adb install: the latter can hang on Android 14
a shell rm /data/local/tmp/CleanHome.apk
a shell pm grant com.clean.home android.permission.ACCESS_FINE_LOCATION || true   # lets the Wi-Fi pill show the network name
a shell cmd package set-home-activity --user 0 com.clean.home/.HomeActivity
a shell am force-stop com.clean.home
a shell input keyevent KEYCODE_HOME
echo "Installed and set as home."
# Some firmware (the Fastway/SEI box) hard-codes its stock launcher for the Home key; the
# launcher's accessibility service redirects it. Harmless where Home already works.
a shell settings put secure enabled_accessibility_services com.clean.home/com.clean.home.HomeKeyService
a shell settings put secure accessibility_enabled 1
# Screensaver: the launcher's dim moving clock.
a shell settings put secure screensaver_components com.clean.home/.ClockDream
