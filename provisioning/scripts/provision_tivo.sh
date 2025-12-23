#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  TiVo Stream 4K → Pure Google TV Provisioning Script
#  - Removes TiVo + OEM + streaming apps
#  - Forces install of modern Google TV launcher
#  - Installs TiVoChannelsHome app
#  - Enables TiVoButtonService AccessibilityService
#  - Promotes Channels DVR into Apps row
#  - Reboots cleanly
# ============================================================

ANDROID_SERIAL=${1:-${ANDROID_SERIAL:-}}
if [ -z "$ANDROID_SERIAL" ]; then
  echo "Usage: ANDROID_SERIAL=ip:5555 $0  OR  $0 <adb-serial>"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Paths relative to provisioning/
APK_DIR="$SCRIPT_DIR/../launcher"
APP_APK="$SCRIPT_DIR/../../app/build/outputs/apk/debug/app-debug.apk"

LAUNCHER_PKG="com.google.android.tvlauncher"
CHANNELS_PKG="com.getchannels.dvr.app"
TIVO_HOME_PKG="com.fiospace.tivochannelshome"
TIVO_ACCESSIBILITY_SERVICE="$TIVO_HOME_PKG/.TiVoButtonService"

echo "============================================================"
echo " Provisioning TiVo Stream 4K → Pure Google TV"
echo " Target device: $ANDROID_SERIAL"
echo " Launcher APK directory: $APK_DIR"
echo " TiVoChannelsHome APK: $APP_APK"
echo "============================================================"
echo

# ------------------------------------------------------------
# 0. Basic sanity checks
# ------------------------------------------------------------
if [ ! -f "$APK_DIR/base.apk" ] || [ ! -f "$APK_DIR/split_config.en.apk" ] || [ ! -f "$APK_DIR/split_config.xhdpi.apk" ]; then
  echo "ERROR: One or more launcher APK splits are missing in $APK_DIR"
  exit 1
fi

if [ ! -f "$APP_APK" ]; then
  echo "ERROR: TiVoChannelsHome APK not found at:"
  echo "  $APP_APK"
  echo "Build the debug APK first (e.g., via Android Studio or ./gradlew assembleDebug)."
  exit 1
fi

# ------------------------------------------------------------
# 1. Full list of all packages to remove
# ------------------------------------------------------------
PACKAGES=(
  # --- TiVo packages ---
  com.tivo.stream
  com.tivo.atom
  com.tivo.sageservice
  com.tivo.tivoplusplayer
  com.tivo.tvlaunchercustomization
  com.uei.uas.tivo
  com.nes.tivo.quickset
  com.nes.tivo.remote.pair
  com.nes.tivo.remote.ota

  # --- Amazon packages ---
  com.amazon.freevee
  com.amazon.amazonvideo.livingroom
  com.amazon.avod

  # --- Other streaming apps ---
  com.sling
  com.bydeluxe.d3.android.program.starz
  com.netflix.ninja

  # --- Google bloat to remove ---
  com.google.android.play.games
  com.google.android.videos
)

echo "Removing TiVo + OEM + streaming packages..."
echo

for PKG in "${PACKAGES[@]}"; do
  echo "--- Processing $PKG ---"

  if ! adb -s "$ANDROID_SERIAL" shell pm list packages | grep -q "^package:$PKG$"; then
    echo "Package $PKG not installed. Skipping."
    echo
    continue
  fi

  OUT=$(adb -s "$ANDROID_SERIAL" shell pm uninstall --user 0 "$PKG" 2>&1 || true)
  if echo "$OUT" | grep -qi "Success"; then
    echo "Uninstalled $PKG"
    echo
    continue
  fi

  echo "Uninstall failed, attempting disable-user..."
  OUT2=$(adb -s "$ANDROID_SERIAL" shell pm disable-user --user 0 "$PKG" 2>&1 || true)
  echo "Disable result: $OUT2"
  echo
done

echo "Package removal complete."
echo

# ------------------------------------------------------------
# 2. Force install modern Google TV launcher from APK splits
# ------------------------------------------------------------
echo "Installing modern Google TV launcher (forced replace)..."

adb -s "$ANDROID_SERIAL" install-multiple -r -d \
  "$APK_DIR/base.apk" \
  "$APK_DIR/split_config.en.apk" \
  "$APK_DIR/split_config.xhdpi.apk"

echo "Launcher installation complete."
echo

# ------------------------------------------------------------
# 3. Install TiVoChannelsHome app
# ------------------------------------------------------------
echo "Installing TiVoChannelsHome app from:"
echo "  $APP_APK"
adb -s "$ANDROID_SERIAL" install -r "$APP_APK"
echo "TiVoChannelsHome installation complete."
echo

# ------------------------------------------------------------
# 4. Enable TiVoButtonService AccessibilityService
# ------------------------------------------------------------
echo "Enabling AccessibilityService: $TIVO_ACCESSIBILITY_SERVICE"

# Append our service to any existing enabled_accessibility_services
CURRENT_SERVICES=$(adb -s "$ANDROID_SERIAL" shell settings get secure enabled_accessibility_services | tr -d '\r')

if [ "$CURRENT_SERVICES" = "null" ] || [ -z "$CURRENT_SERVICES" ]; then
  NEW_SERVICES="$TIVO_ACCESSIBILITY_SERVICE"
else
  # Avoid duplicate entries
  if echo "$CURRENT_SERVICES" | tr ':' '\n' | grep -qx "$TIVO_ACCESSIBILITY_SERVICE"; then
    NEW_SERVICES="$CURRENT_SERVICES"
  else
    NEW_SERVICES="$CURRENT_SERVICES:$TIVO_ACCESSIBILITY_SERVICE"
  fi
fi

adb -s "$ANDROID_SERIAL" shell settings put secure enabled_accessibility_services "$NEW_SERVICES"
adb -s "$ANDROID_SERIAL" shell settings put secure accessibility_enabled 1

echo "Accessibility services set to:"
echo "  $NEW_SERVICES"
echo

# Optional quick verification:
echo "Verifying AccessibilityService is enabled..."
adb -s "$ANDROID_SERIAL" shell settings get secure enabled_accessibility_services
echo

# ------------------------------------------------------------
# 5. Clear HOME defaults so Android re-evaluates launcher
# ------------------------------------------------------------
echo "Clearing HOME defaults..."
adb -s "$ANDROID_SERIAL" shell cmd package clear com.android.tv.settings || true
echo

# ------------------------------------------------------------
# 6. Trigger HOME resolution
# ------------------------------------------------------------
echo "Triggering HOME resolution..."
adb -s "$ANDROID_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME || true
echo

# ------------------------------------------------------------
# 7. Promote Channels DVR toward top of Apps row
# ------------------------------------------------------------
echo "Promoting Channels DVR into Apps row (Favorites-equivalent)..."

if adb -s "$ANDROID_SERIAL" shell pm list packages | grep -q "$CHANNELS_PKG"; then
  adb -s "$ANDROID_SERIAL" shell monkey -p "$CHANNELS_PKG" -c android.intent.category.LAUNCHER 1
  sleep 2
  adb -s "$ANDROID_SERIAL" shell input keyevent HOME
  sleep 1

  adb -s "$ANDROID_SERIAL" shell monkey -p "$CHANNELS_PKG" -c android.intent.category.LAUNCHER 1
  sleep 2
  adb -s "$ANDROID_SERIAL" shell input keyevent HOME
  sleep 1

  echo "Channels DVR launched twice to influence launcher ranking."
else
  echo "Channels DVR ($CHANNELS_PKG) not installed; skipping promotion."
fi

echo

# ------------------------------------------------------------
# 8. Reboot device
# ------------------------------------------------------------
echo "Rebooting device..."
adb -s "$ANDROID_SERIAL" reboot

echo
echo "============================================================"
echo " Provisioning complete."
echo " Device will reboot into modern Google TV UI."
echo " Post-boot checks:"
echo "   - adb -s $ANDROID_SERIAL shell pm list packages | grep -i tivo"
echo "   - Verify TiVoChannelsHome is installed and AccessibilityService is active"
echo "   - Confirm Channels DVR appears in Apps row on HOME"
echo "============================================================"