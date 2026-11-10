#!/usr/bin/env bash
# start-helper.sh: start Otility's in-car helper from this Mac over ADB.
#
# Otility normally starts its helper itself, through the car's own network ADB
# (Settings > Car Connection). Use this when that can't reach ADB, for example
# when ADB is only open to a computer on a cable or over wireless debugging.
#
# What it does on the car, and nothing else:
#   - stops a helper that's already running;
#   - starts the helper from Otility's installed APK, as the shell user, in its
#     own session so it keeps running after ADB disconnects.
# It doesn't install anything or change settings. Otility (app.leopard.cockpit)
# must already be installed. The helper restarts by itself when Otility updates.
#
# Usage:
#   tools/start-helper.sh                    # the only device ADB sees
#   tools/start-helper.sh 192.168.1.50:5555  # a car on the network
#   tools/start-helper.sh emulator-5554      # the emulator

set -euo pipefail
PKG=app.leopard.cockpit
NAME=otility_helper
MAIN=app.leopard.cockpit.helper.HelperMain

if ! command -v adb >/dev/null 2>&1; then
  ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  [ -x "$ADB" ] || { echo "adb isn't installed (Android Studio's platform-tools)."; exit 1; }
else
  ADB=adb
fi

TARGET=()
if [ $# -ge 1 ]; then
  if [[ "$1" == *:* ]]; then "$ADB" connect "$1" >/dev/null || true; fi
  TARGET=(-s "$1")
fi

if ! "$ADB" "${TARGET[@]}" shell true 2>/dev/null; then
  echo "Can't reach the device. If the car shows \"Allow USB debugging?\", tick \"Always allow\" and try again."
  exit 1
fi

APK=$("$ADB" "${TARGET[@]}" shell pm path "$PKG" | head -1 | tr -d '\r' | sed 's/^package://')
if [ -z "$APK" ]; then
  echo "Otility ($PKG) isn't installed on this device."
  exit 1
fi

echo "Starting the helper from $APK"
"$ADB" "${TARGET[@]}" shell "kill \$(pidof $NAME) 2>/dev/null; CLASSPATH='$APK' setsid nohup app_process /system/bin --nice-name=$NAME $MAIN > /data/local/tmp/otility_helper.log 2>&1 < /dev/null & sleep 1; echo started" | tr -d '\r'

sleep 2
if "$ADB" "${TARGET[@]}" shell pidof "$NAME" >/dev/null 2>&1; then
  echo "The helper is running. Otility connects to it within a few seconds."
else
  echo "The helper didn't stay up. Its log:"
  "$ADB" "${TARGET[@]}" shell cat /data/local/tmp/otility_helper.log | tail -20
  exit 1
fi
