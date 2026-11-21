#!/usr/bin/env bash
# backup-car.sh: save everything needed to set the car up again after a software
# update or a factory reset. READ-ONLY on the car: it only pulls and reads.
#
# What it saves, in car-backup/<timestamp>/:
#   apks/<package>/*.apk   every app you installed (all split parts), to reinstall
#   apps.tsv               package, version, installer, where it came from
#   perms/<package>.txt    each app's granted permissions and app-ops, so the same
#                          switches can be turned on again afterwards
#   system.txt             accessibility services, default home app, ADB switches,
#                          overlay / battery exemptions, the car's software version
#   outo-files/            Outo's logs, and its settings backup (Outo 0.5.34+ keeps
#                          files/backup/outo-settings.json current by itself)
#   then runs probe-car.sh for a full "before" inventory to compare with later.
#
# It cannot copy other apps' private data (sign-ins): Android doesn't allow that
# without root.
#
# Usage:
#   tools/backup-car.sh                    # the only device adb can see
#   tools/backup-car.sh 192.168.1.50:5555  # the car on the network

set -u

SERIAL="${1:-}"
ADB=(adb)
if [ -n "$SERIAL" ]; then ADB=(adb -s "$SERIAL"); fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install it with: brew install android-platform-tools" >&2
  exit 1
fi

STATE="$("${ADB[@]}" get-state 2>&1)"
if [ "$STATE" != "device" ]; then
  echo "No usable device (${STATE}). Run 'adb devices -l' and pass the serial, e.g. tools/backup-car.sh 192.168.1.50:5555" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/car-backup/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/apks" "$OUT/perms" "$OUT/outo-files"
echo "Backing up $("${ADB[@]}" get-serialno) → $OUT"

sh_() { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }

# ---- Apps you installed ------------------------------------------------------
printf 'package\tversion\tinstaller\tfirst_installed\n' > "$OUT/apps.tsv"
PKGS="$(sh_ pm list packages -3 | sed 's/^package://' | sort)"
COUNT=0; FAILED=0
for pkg in $PKGS; do
  COUNT=$((COUNT + 1))
  dump="$(sh_ dumpsys package "$pkg")"
  ver="$(printf '%s\n' "$dump" | grep -m1 'versionName=' | sed 's/.*versionName=//')"
  inst="$(printf '%s\n' "$dump" | grep -m1 'installerPackageName=' | sed 's/.*installerPackageName=//')"
  first="$(printf '%s\n' "$dump" | grep -m1 'firstInstallTime=' | sed 's/.*firstInstallTime=//')"
  printf '%s\t%s\t%s\t%s\n' "$pkg" "$ver" "$inst" "$first" >> "$OUT/apps.tsv"

  printf '  %-45s %s' "$pkg" "$ver"
  mkdir -p "$OUT/apks/$pkg"
  ok=1
  for path in $(sh_ pm path "$pkg" | sed 's/^package://'); do
    "${ADB[@]}" pull "$path" "$OUT/apks/$pkg/$(basename "$path")" >/dev/null 2>&1 || ok=0
  done
  if [ "$ok" = 1 ] && ls "$OUT/apks/$pkg"/*.apk >/dev/null 2>&1; then
    echo "  saved ($(ls "$OUT/apks/$pkg" | wc -l | tr -d ' ') part(s))"
  else
    echo "  FAILED to pull"; FAILED=$((FAILED + 1))
  fi

  {
    echo "== granted runtime/install permissions"
    printf '%s\n' "$dump" | grep -E 'android\.permission\.[A-Z_]+: granted=true|permission\.[A-Za-z_.]+: granted=true' | sed 's/^ *//' | sort -u
    echo; echo "== app-ops (overlay, usage stats, etc.)"
    sh_ appops get "$pkg"
  } > "$OUT/perms/$pkg.txt"
done

# ---- System switches the apps rely on ---------------------------------------
{
  echo "== car software"
  for p in persist.sys.version ro.osd.sw_ver mcu_version ro.build.version.release ro.build.version.sdk \
           ro.build.fingerprint ro.build.date persist.sys.gbClientVersion ro.byd.theme.version \
           persist.service.ota.enable persist.sys.byd.otaupdate; do
    printf '%-32s %s\n' "$p" "$(sh_ getprop "$p")"
  done
  echo; echo "== accessibility"
  echo "enabled_accessibility_services = $(sh_ settings get secure enabled_accessibility_services)"
  echo "accessibility_enabled          = $(sh_ settings get secure accessibility_enabled)"
  echo; echo "== ADB"
  echo "adb_enabled      = $(sh_ settings get global adb_enabled)"
  echo "adb_wifi_enabled = $(sh_ settings get global adb_wifi_enabled)"
  echo "service.adb.tcp.port = $(sh_ getprop service.adb.tcp.port)  persist.adb.tcp.port = $(sh_ getprop persist.adb.tcp.port)"
  echo; echo "== default home app"
  sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
  echo; echo "== battery-saver exemptions (deviceidle whitelist)"
  sh_ dumpsys deviceidle whitelist
  echo; echo "== notification listeners"
  echo "$(sh_ settings get secure enabled_notification_listeners)"
} > "$OUT/system.txt"

# ---- Outo's files on shared storage (logs, crash notes) ----------------------
"${ADB[@]}" pull /sdcard/Android/data/app.leopard.cockpit/files "$OUT/outo-files/" >/dev/null 2>&1 \
  && echo "  Outo files saved" || echo "  (no Outo files on shared storage)"
if [ -f "$OUT/outo-files/files/backup/outo-settings.json" ]; then
  echo "  Outo settings saved"
else
  echo "  WARNING: no Outo settings backup. Update Outo to 0.5.34 or newer and open it once, then run this again."
fi
sh_ ls -la /sdcard/Download > "$OUT/outo-files/sdcard-download-list.txt"

# ---- Full inventory, for a before/after comparison ---------------------------
echo "Running the full probe…"
"$ROOT/tools/probe-car.sh" ${SERIAL:+"$SERIAL"} | tail -3

echo
echo "Done: $COUNT apps, $FAILED failed to pull."
echo "Backup: $OUT"
echo "Restore later with: tools/restore-car-apps.sh \"$OUT\" [serial]"
[ "$FAILED" = 0 ] || exit 2
