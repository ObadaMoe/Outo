#!/usr/bin/env bash
# probe-car.sh: a READ-ONLY inventory of the Leopard 5 head unit over ADB.
#
# It only reads: getprop, dumpsys, pm list, settings list, screencap.
# It never installs anything, changes settings, starts apps or writes to the car.
#
# Usage:
#   tools/probe-car.sh                    # the only device adb can see
#   tools/probe-car.sh 192.168.1.50:5555  # a specific device (adb serial / ip:port)
#
# Output: car-probe/<timestamp>/ next to this tools/ folder, plus SUMMARY.txt.

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
  echo "No usable device (${STATE}). Run 'adb devices -l' and pass the serial, e.g. tools/probe-car.sh 192.168.1.50:5555" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/car-probe/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
echo "Probing $("${ADB[@]}" get-serialno) → $OUT"

# run <name> <shell command...>: one device-side command, saved to <name>.txt
run() {
  local name="$1"; shift
  printf '  %-22s' "$name"
  { echo "\$ $*"; "${ADB[@]}" shell "$@" 2>&1; } > "$OUT/$name.txt"
  echo "$(wc -l < "$OUT/$name.txt" | tr -d ' ') lines"
}

echo "Collecting:"
run getprop          getprop
run uptime           'uptime; cat /proc/uptime'
run hardware         "cat /proc/cpuinfo | grep -iE 'hardware|model name|^processor' | sort -u | head -20; head -3 /proc/meminfo"
run screen           'wm size; wm density'
run features         pm list features
run packages         pm list packages -f -U
run packages_user    pm list packages -3 -i
run permissions      pm list permissions -f -g
run broadcasts       dumpsys activity broadcasts
run home_activity    cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
run launcher_apps    cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
run display          dumpsys display
run sf_display_ids   dumpsys SurfaceFlinger --display-id
run window_displays  dumpsys window displays
run window           dumpsys window
run activities       dumpsys activity activities
run containers       dumpsys activity containers
run input            dumpsys input
run power            dumpsys power
run services         service list
run settings_global  settings list global
run settings_secure  settings list secure
run settings_system  settings list system
run overlays         cmd overlay list
run frameworks       'ls -la /system/framework /system_ext/framework /vendor/framework 2>/dev/null'
run app_dirs         'ls /system/app /system/priv-app /product/app /product/priv-app /system_ext/app /system_ext/priv-app /vendor/app 2>/dev/null'
run network          'ip -o addr show 2>/dev/null || ifconfig'
run nav_apps         "for p in com.waze com.google.android.apps.maps; do echo \"== \$p\"; dumpsys package \$p | grep -E 'versionName|targetSdk'; done"
run deviceidle       dumpsys deviceidle whitelist

# Window-management switches compiled into the framework (pulled, read-only, decoded locally).
AAPT2="$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/aapt2 2>/dev/null | tail -1)"
if [ -n "$AAPT2" ] && "${ADB[@]}" pull /system/framework/framework-res.apk "$OUT/framework-res.apk" >/dev/null 2>&1; then
  "$AAPT2" dump resources "$OUT/framework-res.apk" 2>/dev/null \
    | grep -A1 -E 'bool/config_(supportsMultiWindow|supportsSplitScreenMultiWindow|supportsMultiDisplay|freeformWindowManagement|supportsPictureInPicture|supportsSystemDecorsOnSecondaryDisplays|perDisplayFocusEnabled|noHomeScreen)' \
    | grep -vE '^--$' > "$OUT/framework_config.txt"
  rm -f "$OUT/framework-res.apk"
  echo "  framework_config       $(wc -l < "$OUT/framework_config.txt" | tr -d ' ') lines"
else
  echo "(could not read framework config)" > "$OUT/framework_config.txt"
fi

# Screenshots of every physical display: if the cluster appears here, Android can draw on it.
echo "Screenshots:"
"${ADB[@]}" exec-out screencap -p > "$OUT/screen_default.png" 2>/dev/null
echo "  screen_default.png"
for id in $(grep -oE '^Display [0-9]+' "$OUT/sf_display_ids.txt" | awk '{print $2}'); do
  "${ADB[@]}" exec-out screencap -p -d "$id" > "$OUT/screen_$id.png" 2>/dev/null
  if [ -s "$OUT/screen_$id.png" ]; then echo "  screen_$id.png"; else rm -f "$OUT/screen_$id.png"; echo "  display $id: capture failed"; fi
done

# ---- Summary of the facts the feasibility study depends on ----
prop() { grep -F "[$1]" "$OUT/getprop.txt" | head -1 | sed -E 's/^\[[^]]*\]: \[(.*)\]$/\1/'; }
setting() { local v; v="$(grep -E "^$1=" "$OUT/$2.txt" | head -1 | cut -d= -f2-)"; echo "${v:-(unset)}"; }
# One line per display: name, id, size, density, type, state, owner and FLAG_* (FLAG_PRIVATE/FLAG_TRUSTED decide who may launch there).
displays() {
  grep -oE 'mBaseDisplayInfo=DisplayInfo\{.*' "$OUT/display.txt" | while IFS= read -r l; do
    name="$(echo "$l" | grep -oE '\{"[^"]*"' | head -1 | tr -d '{"')"
    id="$(echo "$l" | grep -oE 'displayId [0-9]+' | head -1)"
    size="$(echo "$l" | grep -oE 'real [0-9]+ x [0-9]+' | head -1)"
    dens="$(echo "$l" | grep -oE 'density [0-9]+' | head -1)"
    type="$(echo "$l" | grep -oE 'type [A-Z_]+' | head -1)"
    state="$(echo "$l" | grep -oE 'state [A-Z_]+' | head -1)"
    owner="$(echo "$l" | grep -oE 'owner [^,}]+' | head -1)"
    flags="$(echo "$l" | grep -oE 'FLAG_[A-Z_]+' | sort -u | tr '\n' ' ')"
    echo "- \"$name\" | $id | $size | $dens | $type | $state | ${owner:-no owner} | $flags"
  done
}

{
  echo "=== Leopard 5 head unit: probe summary ($(date)) ==="
  echo
  echo "## Build"
  echo "Brand/model:      $(prop ro.product.brand) / $(prop ro.product.model) ($(prop ro.product.manufacturer))"
  echo "Android:          $(prop ro.build.version.release) (API $(prop ro.build.version.sdk)), patch $(prop ro.build.version.security_patch)"
  echo "Build:            $(prop ro.build.display.id)"
  echo "Fingerprint:      $(prop ro.build.fingerprint)"
  echo "Platform/SoC:     $(prop ro.board.platform) / $(prop ro.hardware) / $(prop ro.soc.model)"
  echo "Uptime:           $(sed -n 2p "$OUT/uptime.txt")"
  echo
  echo "## Main screen"
  sed -n '2,$p' "$OUT/screen.txt"
  echo
  echo "## Displays Android can see (name | id | size | density | type | state | owner | flags)"
  displays
  echo "Cluster projection displays (on the 2025 Leopard 5: shared_fission_bg_XDJAScreenProjection_0 = full map, _1 = side card):"
  displays | grep -iE 'fission|projection|xdja|cluster|instrument' || echo "  none found by name"
  echo
  echo "## SurfaceFlinger (physical) displays"
  grep -E '^Display [0-9]+' "$OUT/sf_display_ids.txt"
  echo
  echo "## Window-management features and framework switches"
  grep -iE 'freeform|secondary_displays|picture_in_picture|automotive' "$OUT/features.txt" || echo "(no multi-window / secondary-display features listed)"
  sed 's/^ *//' "$OUT/framework_config.txt" | paste - - 2>/dev/null | sed -E 's/resource [^ ]+ //; s/\(\) *//'
  echo "enable_freeform_support=$(setting enable_freeform_support settings_global)"
  echo "force_resizable_activities=$(setting force_resizable_activities settings_global)"
  echo "development_settings_enabled=$(setting development_settings_enabled settings_global)"
  echo "adb_enabled=$(setting adb_enabled settings_global)  adb_wifi_enabled=$(setting adb_wifi_enabled settings_global)"
  echo
  echo "## Home (launcher) activity"
  sed -n '2,$p' "$OUT/home_activity.txt"
  echo
  echo "## Navigation apps and Google services"
  if grep -q 'package:.*com.waze' "$OUT/packages.txt"; then echo "Waze: installed"; else echo "Waze: not installed"; fi
  if grep -q 'package:.*com.google.android.apps.maps' "$OUT/packages.txt"; then echo "Google Maps: installed"; else echo "Google Maps: not installed"; fi
  if grep -q 'package:.*com.google.android.gms' "$OUT/packages.txt"; then echo "Google Play services: present"; else echo "Google Play services: absent"; fi
  if grep -q 'package:.*moe.shizuku.privileged.api' "$OUT/packages.txt"; then echo "Shizuku: installed"; else echo "Shizuku: not installed"; fi
  sed -n '2,$p' "$OUT/nav_apps.txt"
  echo
  echo "## Vehicle-data permissions (BYD / vehicle / android.car), by protection level"
  echo "   normal or dangerous = a sideloaded app can use them; signature = BYD-signed apps only"
  VEH='byd|vehicle|android\.car\.|cluster|instrument|dilink'
  awk -v re="$VEH" '/permission:/{p=$0; sub(/.*permission:/,"",p)} /protectionLevel:/{ if (tolower(p) ~ re) { pl=$0; sub(/.*protectionLevel:/,"",pl); print pl } }' "$OUT/permissions.txt" | sort | uniq -c | sort -rn
  awk -v re="$VEH" '/permission:/{p=$0; sub(/.*permission:/,"",p)} /protectionLevel:/{ if (tolower(p) ~ re) { pl=$0; sub(/.*protectionLevel:/,"",pl); print "  " p " -> " pl } }' "$OUT/permissions.txt" | sort -u | head -60
  echo
  echo "## Vendor system services"
  grep -iE 'byd|vehicle|cluster|instrument|dilink|autoservice|xdja|fission|car_service|carservice|android\.car' "$OUT/services.txt" | head -40
  echo
  echo "## Broadcast actions that look like power / ignition / vehicle events"
  grep -oE '^ +[A-Za-z0-9_.]+:$' "$OUT/broadcasts.txt" | tr -d ' :' | grep -vE '^com\.google\.' \
    | grep -iE 'byd|[._]acc([._]|$)|ignition|vehicle|android\.car|power_?(on|off)|boot|shutdown|screen_?(on|off)|suspend|resume|str_' | sort -u | head -60
  echo
  echo "## User-installed apps (with installer)"
  sed -n '2,$p' "$OUT/packages_user.txt"
  echo
  echo "## Vendor packages (byd/fcb/dilink/cluster/instrument/launcher)"
  grep -iE 'byd|fcb|dilink|xdja|cluster|instrument|launcher|diplus|van\.' "$OUT/packages.txt" | sed -E 's/^package://' | head -60
} > "$OUT/SUMMARY.txt"

echo
cat "$OUT/SUMMARY.txt"
echo
echo "Saved to: $OUT"
