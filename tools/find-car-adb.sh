#!/usr/bin/env bash
# find-car-adb.sh: is ADB on the car reachable from this Mac? Read-only.
#
# It looks for ADB the ways a car can expose it:
#   1. ADB services announced on the network (mDNS): classic network ADB,
#      or Android 11+ wireless debugging (pairing / connect)
#   2. Classic network ADB on TCP port 5555 (scan)
# then tries to connect and tells you what the result means.
# It never installs anything or changes settings on the car.
#
# Usage:
#   tools/find-car-adb.sh                # search the Wi-Fi network this Mac is on
#   tools/find-car-adb.sh 192.168.1.50   # check one address (the car's IP)
#
# The Mac and the car must be on the same local network. No internet is needed.
#   - iPhone hotspot (both joined): easiest, but iPhones often block devices on
#     the hotspot from reaching each other, so "nothing found" there isn't final.
#   - Mac Internet Sharing: iPhone on a USB cable as the internet source, shared
#     over the Mac's Wi-Fi, with the car joining that Wi-Fi. Most reliable.
#   - The car's own Wi-Fi hotspot, or home Wi-Fi if it reaches the car.

set -u
PORT=5555

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install it with: brew install android-platform-tools" >&2
  exit 1
fi
adb start-server >/dev/null 2>&1

# ---- 1. mDNS announcements ----------------------------------------------------
echo "== 1. ADB services announced on this network (mDNS)"
MDNS="$(adb mdns services 2>/dev/null | grep -E '_adb' || true)"
if [ -z "$MDNS" ]; then  # adb's mDNS browser needs a moment after the server starts
  sleep 4
  MDNS="$(adb mdns services 2>/dev/null | grep -E '_adb' || true)"
fi
TARGETS=""
OTHERS=""
if [ -z "$MDNS" ]; then
  echo "   none announced"
else
  while read -r name type addr; do
    case "$type" in
      _adb._tcp*)               echo "   $addr  classic network ADB ($name)"; TARGETS="$TARGETS $addr" ;;
      _adb-tls-connect._tcp*)   echo "   $addr  wireless debugging, ready to connect ($name)"; TARGETS="$TARGETS $addr" ;;
      _adb-tls-pairing._tcp*)   echo "   $addr  wireless debugging, waiting to be PAIRED ($name)"
                                echo "      -> On the car: Wireless debugging > Pair device with pairing code, then run:"
                                echo "         adb pair $addr <6-digit-code>"
                                echo "         (the pairing port shown on the car screen may differ from this one; use the car's)" ;;
      *)                        echo "   $addr  $type ($name)" ;;
    esac
  done <<< "$MDNS"
fi
echo

# ---- 2. Port 5555 ---------------------------------------------------------------
if [ $# -ge 1 ]; then
  echo "== 2. Checking $1:$PORT"
  if nc -z -G 2 "$1" "$PORT" >/dev/null 2>&1; then
    echo "   port $PORT is open"
    TARGETS="$TARGETS $1:$PORT"
  else
    echo "   port $PORT is closed on $1"
  fi
else
  # Every local network this Mac is on: Wi-Fi (en*), cable or iPhone USB (en*),
  # and the network it shares with Internet Sharing (bridge*). VPN tunnels are skipped.
  NETS=""
  for ifc in $(ifconfig -l); do
    case "$ifc" in en[0-9]*|bridge[0-9]*) ;; *) continue ;; esac
    for ip in $(ifconfig "$ifc" 2>/dev/null | awk '/inet /{print $2}'); do
      case "$ip" in 127.*|169.254.*) continue ;; esac
      NETS="$NETS $ifc=$ip"
    done
  done
  if [ -n "${SCAN_PREFIX:-}" ]; then NETS="test=$SCAN_PREFIX.0"; fi
  if [ -z "$NETS" ]; then
    echo "This Mac has no network address. Join the same network as the car first." >&2
    exit 1
  fi
  MYIPS=" $(for n in $NETS; do printf '%s ' "${n#*=}"; done)"
  for n in $NETS; do
    ifc="${n%%=*}"; ip="${n#*=}"; PREFIX="${ip%.*}"
    echo "== 2. Scanning $PREFIX.1-254 for port $PORT (this Mac is $ip on $ifc; ~10 s)"
    # Each worker gets one host number as $1; the prefix is passed in as $0.
    HITS="$(seq 1 254 | xargs -P 64 -n 1 sh -c 'nc -z -G 1 "$0.$1" '"$PORT"' >/dev/null 2>&1 && echo "$0.$1"' "$PREFIX" | sort -t. -k4 -n)"
    found=""
    for h in $HITS; do
      case "$MYIPS" in *" $h "*) continue ;; esac  # skip this Mac itself
      found="$found $h"; TARGETS="$TARGETS $h:$PORT"
    done
    if [ -n "$found" ]; then echo "   port $PORT open on:$found"; else echo "   nothing on this network has port $PORT open"; fi
  done
fi
echo

# ---- 3. Connect to everything found ---------------------------------------------------
FOUND=""
for target in $(echo "$TARGETS" | tr ' ' '\n' | grep -v '^$' | sort -u); do
  echo "== 3. Connecting to $target"
  echo "   $(adb connect "$target" 2>&1 | tail -1)"
  state="$(adb -s "$target" get-state 2>&1 | tail -1)"
  case "$state" in
    device)
      fp="$(adb -s "$target" shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
      model="$(adb -s "$target" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
      rel="$(adb -s "$target" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
      echo "   ADB WORKS: $model, Android $rel"
      echo "   $fp"
      if echo "$fp $model" | grep -qiE 'byd|ivi|dilink|denza|fcb'; then
        FOUND="$FOUND $target"
        echo "   -> This is the car. Next: tools/probe-car.sh $target"
      else
        echo "   -> Some other Android device, not the car. Disconnecting."
        OTHERS="$OTHERS $target"
        adb disconnect "$target" >/dev/null 2>&1
      fi
      ;;
    *unauthorized*|*authenticate*|*confirmation\ dialog*)
      FOUND="$FOUND $target"
      echo "   ADB is ON, but this Mac isn't approved yet."
      echo "   -> Look at the car's screen for 'Allow USB debugging?', tick 'Always allow', tap Allow,"
      echo "      then run this again. (Some BYD builds hide that prompt; then the shop has to approve it.)"
      ;;
    *)
      echo "   Not answering as ADB from here ($state)."
      adb disconnect "$target" >/dev/null 2>&1
      ;;
  esac
  echo
done

# ---- Verdict ----------------------------------------------------------------------------
if [ -n "$FOUND" ]; then
  echo "== Result: ADB is active on the car:$FOUND"
elif echo "$MDNS" | grep -q '_adb-tls-pairing'; then
  echo "== Result: wireless debugging is on but needs pairing once (see step 1)."
else
  if [ -n "$OTHERS" ]; then
    echo "== Result: ADB answered only on other devices ($OTHERS ), not on the car."
  else
    echo "== Result: no ADB reachable from this Mac."
  fi
  echo "   That doesn't prove the car's ADB is off. Possible reasons:"
  echo "   - The Mac and the car aren't on the same network, or it's the iPhone hotspot, which often"
  echo "     blocks devices from reaching each other. Try Mac Internet Sharing instead (see top of this script)."
  echo "   - ADB is on but only reachable from inside the car itself (127.0.0.1); many BYD tools use it that way."
  echo "   - The shop switches it on only while they work, or it closes at every reboot."
  echo "   Next: ask the shop whether ADB / wireless debugging is enabled, and on which port."
fi
