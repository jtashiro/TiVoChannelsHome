#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  TiVo Stream 4K → Pure Google TV Provisioning Script
#  - Removes TiVo + OEM + streaming apps
#  - Installs modern Google TV launcher (if needed)
#  - Clears HOME defaults and triggers HOME resolution
#  - Promotes Channels DVR into the Apps row
#  - Reboots cleanly
# ============================================================

ANDROID_SERIAL=${1:-${ANDROID_SERIAL:-}}
if [ -z "$ANDROID_SERIAL" ]; then
  echo "Usage: ANDROID_SERIAL=ip:5555 $0  OR  $0 <adb-serial>"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$SCRIPT_DIR/../launcher"

LAUNCHER_PKG="com.google.android.tvlauncher"
CHANNELS_PKG="com.getchannels.dvr.app"

echo "============================================================"
echo " Provisioning TiVo Stream 4K → Pure Google TV"
echo " Target device: $ANDROID_SERIAL"
echo " Launcher APK directory: $APK_DIR"
echo "============================================================"
echo

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
# Install modern Google TV launcher (force replace)
# ------------------------------------------------------------
echo "Installing modern Google TV launcher (forced replace)..."

adb -s "$ANDROID_SERIAL" install-multiple -r -d \
  "$APK_DIR/base.apk" \
  "$APK_DIR/split_config.en.apk" \
  "$APK_DIR/split_config.xhdpi.apk"

echo "Launcher installation complete."
echo
echo

# ------------------------------------------------------------
# 4. Clear HOME defaults so Android re-evaluates launcher
# ------------------------------------------------------------
echo "Clearing HOME defaults..."
adb -s "$ANDROID_SERIAL" shell cmd package clear com.android.tv.settings || true
echo

# ------------------------------------------------------------
# 5. Trigger HOME resolution
# ------------------------------------------------------------
echo "Triggering HOME resolution..."
adb -s "$ANDROID_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME || true
echo

# ------------------------------------------------------------
# 6. Promote Channels DVR toward top of Apps row
# ------------------------------------------------------------
echo "Promoting Channels DVR into Apps row (Favorites-equivalent)..."

# Only attempt if Channels DVR is present
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
# 7. Reboot device
# ------------------------------------------------------------
echo "Rebooting device..."
adb -s "$ANDROID_SERIAL" reboot

echo
echo "============================================================"
echo " Provisioning complete."
echo " Device will reboot into modern Google TV UI."
echo " Post-boot checks:"
echo "   - adb -s $ANDROID_SERIAL shell pm list packages | grep -i tivo"
echo "   - Confirm Channels DVR appears in Apps row on HOME"
echo "============================================================"