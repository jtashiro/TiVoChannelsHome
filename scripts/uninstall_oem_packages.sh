#!/usr/bin/env bash
set -euo pipefail

# Uninstall or disable a list of OEM apps from an Android device via adb.
# Usage:
#   ANDROID_SERIAL=192.168.1.73:5555 ./scripts/uninstall_oem_packages.sh
# or
#   ./scripts/uninstall_oem_packages.sh 192.168.1.73:5555

ANDROID_SERIAL=${1:-${ANDROID_SERIAL:-}}
if [ -z "$ANDROID_SERIAL" ]; then
  echo "Usage: ANDROID_SERIAL=ip:5555 $0 or: $0 <adb-serial>"
  exit 1
fi

# List of package IDs to remove (edit as needed)
PACKAGES=(
  com.tivo.atom
  com.amazon.freevee
  com.sling
  com.amazon.amazonvideo.livingroom
  com.amazon.avod
  com.google.android.play.games
  com.google.android.videos
  com.bydeluxe.d3.android.program.starz
  com.netflix.ninja
)

# Per-target timeout (seconds) to wait for removal before falling back
PER_TARGET_TIMEOUT_SEC=180
POLL_INTERVAL_SEC=3

echo "Target device: $ANDROID_SERIAL"
echo "Packages to process: ${#PACKAGES[@]} -> ${PACKAGES[*]}"

echo "\n== Start uninstall sequence =="

for PKG in "${PACKAGES[@]}"; do
  echo "\n--- Processing: $PKG ---"
  echo "Checking presence on device..."
  adb -s "$ANDROID_SERIAL" shell pm list packages | grep -q "^package:$PKG$" && present=true || present=false

  if [ "$present" = false ]; then
    echo "Package $PKG not currently listed for this user. Showing pm path and dumpsys for diagnostics..."
    adb -s "$ANDROID_SERIAL" shell pm path "$PKG" || true
    adb -s "$ANDROID_SERIAL" shell dumpsys package "$PKG" | sed -n '1,60p' || true
    echo "Skipping uninstall for $PKG (not present)."
    continue
  fi

  echo "Package present — attempting silent uninstall (user 0):"
  CMD=(adb -s "$ANDROID_SERIAL" shell pm uninstall --user 0 "$PKG")
  echo "-> ${CMD[*]}"
  OUT=$(${CMD[*]} 2>&1) || true
  RC=$?
  echo "Result rc=$RC output='${OUT//'"'/"\""}'"

  if echo "$OUT" | grep -qi "success"; then
    echo "pm uninstall reported Success for $PKG — polling to confirm removal"
  else
    echo "pm uninstall did not report Success (output above). Will poll for removal and fall back if needed."
  fi

  # Poll for removal up to timeout
  START_TS=$(date +%s)
  REMOVED=false
  LAST_LOG_TS=$START_TS
  while true; do
    if ! adb -s "$ANDROID_SERIAL" shell pm list packages | grep -q "^package:$PKG$"; then
      echo "Detected $PKG removed for user (pm list no longer shows it)."
      REMOVED=true
      break
    fi

    NOW_TS=$(date +%s)
    ELAPSED=$((NOW_TS - START_TS))
    if [ $ELAPSED -ge $PER_TARGET_TIMEOUT_SEC ]; then
      echo "Timeout waiting for removal of $PKG after ${PER_TARGET_TIMEOUT_SEC}s"
      break
    fi

    # periodic status log
    if [ $((NOW_TS - LAST_LOG_TS)) -ge 5 ]; then
      echo "Waiting for $PKG removal: elapsed=${ELAPSED}s..."
      LAST_LOG_TS=$NOW_TS
    fi
    sleep $POLL_INTERVAL_SEC
  done

  if [ "$REMOVED" = true ]; then
    echo "Completed removal for $PKG"
    continue
  fi

  # Fallback: attempt to disable-user (works for system apps)
  echo "Attempting fallback: disable-user --user 0 $PKG"
  CMD2=(adb -s "$ANDROID_SERIAL" shell pm disable-user --user 0 "$PKG")
  echo "-> ${CMD2[*]}"
  OUT2=$(${CMD2[*]} 2>&1) || true
  RC2=$?
  echo "disable-user rc=$RC2 output='${OUT2//'"'/"\""}'"

  # Show package dumpsys for inspection
  echo "dumpsys package $PKG (short):"
  adb -s "$ANDROID_SERIAL" shell dumpsys package "$PKG" | sed -n '1,120p' || true

  # Final verification
  if adb -s "$ANDROID_SERIAL" shell pm list packages | grep -q "^package:$PKG$"; then
    echo "FINAL: $PKG still present (may be a privileged/system app). Consider using adb root or OEM tooling."
  else
    echo "FINAL: $PKG not listed (either removed or disabled for user)."
  fi

done

echo "\n== Uninstall sequence complete =="

echo "Tip: Reboot device to ensure launchers and services refresh:"
echo "adb -s $ANDROID_SERIAL reboot"

