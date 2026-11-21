#!/usr/bin/env bash
# restore-car-apps.sh: reinstall the apps saved by backup-car.sh.
#
# It installs each saved app (all its split parts together) and skips any app
# that's already installed. When it installs Outo, it also puts Outo's saved
# settings back (sidebar, layouts, widgets, switches): Outo loads them the next
# time it opens. SETTINGS=1 does that even into an Outo that was kept.
# It doesn't change system settings: use the backup's system.txt and perms/ to turn the same switches
# (accessibility, overlays, network ADB) back on by hand, and open Outo once so
# it starts its helper.
#
# Usage:
#   tools/restore-car-apps.sh car-backup/<timestamp>                    # only device
#   tools/restore-car-apps.sh car-backup/<timestamp> 192.168.1.50:5555  # car on network
#   ONLY="com.waze app.leopard.cockpit" tools/restore-car-apps.sh car-backup/<ts>

set -u

BACKUP="${1:-}"
SERIAL="${2:-}"
if [ -z "$BACKUP" ] || [ ! -d "$BACKUP/apks" ]; then
  echo "Usage: tools/restore-car-apps.sh car-backup/<timestamp> [serial]" >&2
  exit 1
fi
ADB=(adb)
if [ -n "$SERIAL" ]; then ADB=(adb -s "$SERIAL"); fi

STATE="$("${ADB[@]}" get-state 2>&1)"
if [ "$STATE" != "device" ]; then
  echo "No usable device (${STATE})." >&2
  exit 1
fi

OK=0; SKIP=0; FAIL=0
for dir in "$BACKUP"/apks/*/; do
  pkg="$(basename "$dir")"
  if [ -n "${ONLY:-}" ] && ! printf ' %s ' "$ONLY" | grep -q " $pkg "; then continue; fi
  apks=("$dir"*.apk)
  [ -e "${apks[0]}" ] || { echo "  $pkg: no APKs saved, skipped"; continue; }

  saved="$(awk -F'\t' -v p="$pkg" '$1==p {print $2}' "$BACKUP/apps.tsv")"
  have="$("${ADB[@]}" shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r' | grep -m1 'versionName=' | sed 's/.*versionName=//')"
  if [ -n "$have" ]; then
    echo "  $pkg: already installed ($have; saved $saved), skipped"
    SKIP=$((SKIP + 1)); continue
  fi

  printf '  %-45s %s … ' "$pkg" "$saved"
  if [ "${#apks[@]}" -gt 1 ]; then
    res="$("${ADB[@]}" install-multiple -r "${apks[@]}" 2>&1)"
  else
    res="$("${ADB[@]}" install -r "${apks[0]}" 2>&1)"
  fi
  if printf '%s' "$res" | grep -q Success; then
    echo "installed"; OK=$((OK + 1))
    [ "$pkg" = app.leopard.cockpit ] && OUTO_INSTALLED=1
  else
    echo "FAILED: $(printf '%s' "$res" | tail -1)"; FAIL=$((FAIL + 1))
  fi
done

# Outo's settings: only into an Outo this run installed (a kept Outo has newer
# settings than the backup), unless SETTINGS=1 asks for it anyway.
OUTO=app.leopard.cockpit
SAVED="$BACKUP/outo-files/files/backup/outo-settings.json"
if [ -f "$SAVED" ] && { [ "${OUTO_INSTALLED:-0}" = 1 ] || [ "${SETTINGS:-0}" = 1 ]; }; then
  # Straight into Outo's folder: a sub-folder made here would belong to the shell.
  DEST=/sdcard/Android/data/$OUTO/files
  "${ADB[@]}" shell am force-stop "$OUTO" >/dev/null 2>&1
  if "${ADB[@]}" shell mkdir -p "$DEST" && "${ADB[@]}" push "$SAVED" "$DEST/outo-restore.json" >/dev/null 2>&1; then
    echo "  Outo's settings put back: they load the next time Outo opens."
  else
    echo "  FAILED to put Outo's settings back."; FAIL=$((FAIL + 1))
  fi
elif [ -f "$SAVED" ]; then
  echo "  Outo's settings not restored (Outo was already there). Use SETTINGS=1 to restore them anyway."
else
  echo "  (no Outo settings in this backup)"
fi

echo
echo "Installed $OK, skipped $SKIP, failed $FAIL."
[ "$FAIL" = 0 ] || exit 2
