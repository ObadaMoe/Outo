#!/usr/bin/env bash
# cluster-test.sh: step-by-step test of the driver cluster's map channel.
#
# Each step is its own command so it can be run one at a time with the driver
# watching the cluster. Only "status" is read-only; the others change what the
# cluster shows (nothing is written to the car's settings or files).
#
#   tools/cluster-test.sh status       # read-only: size and content of each cluster channel
#   tools/cluster-test.sh on [code]    # ask the cluster to show the map channel (default 30 = 12.3" cluster), then 16
#   tools/cluster-test.sh show         # start our test card on the full-map channel
#   tools/cluster-test.sh hide         # close our test card
#   tools/cluster-test.sh waze         # move Waze onto the full-map channel
#   tools/cluster-test.sh off          # ask the cluster to go back to normal (18, then 0)
#
# The on/off codes are the ones 7orr sends to BYD's auto_container service
# (transaction 2 = sendInfo(1000, code, "")).
#
# Set CAR=ip:port to choose the car (default 172.20.10.3:5555).

set -u
CAR="${CAR:-172.20.10.3:5555}"
a() { adb -s "$CAR" shell "$@"; }

full_map_id() {
  a dumpsys display | grep -oE 'mBaseDisplayInfo=DisplayInfo\{"shared_fission_bg_[A-Za-z]+_0", displayId [0-9]+' \
    | grep -oE '[0-9]+$' | head -1
}

send_info() {
  echo "  auto_container sendInfo($1): $(a service call auto_container 2 i32 1000 i32 "$1" s16 '""' | tr -d '\r' | tr '\n' ' ')"
}

case "${1:-status}" in
  status)
    a dumpsys SurfaceFlinger | awk '
      /^Display [0-9]+ \(virtual, "(shared_)?fission_bg/ {n=$0; sub(/.*"(shared_)?fission_bg_XDJAScreenProjection/,"",n); sub(/".*/,"",n); if(n=="") n="background"; if(n=="_0") n="full map"; if(n=="_1") n="map card"; cur=n}
      /^Display [0-9]+ \(virtual, "wolfos/ {cur=""}
      cur && /displaySpace=ProjectionSpace/ {match($0,/bounds=Rect\([0-9, ]+\)/); s[cur]=substr($0,RSTART+12,RLENGTH-13)}
      cur && /Output Layer/ {l=$0; sub(/.*\(/,"",l); sub(/\)$/,"",l); o[cur]=o[cur] "\n      " l}
      END {for (k in s) printf "  %-10s drawn at [%s]%s\n", k, s[k], (o[k]=="" ? "\n      (nothing on it)" : o[k])}'
    echo "  (a channel drawn at [0, 0, 1, 1] is switched off by the cluster)"
    ;;
  on)
    send_info "${2:-30}"
    sleep 1
    send_info 16
    ;;
  show)
    id="$(full_map_id)"
    if [ -z "$id" ]; then echo "No full-map cluster channel found." >&2; exit 1; fi
    echo "  starting the test card on display $id"
    a am start-activity --display "$id" -n app.leopard.cockpit/.ClusterTestActivity
    ;;
  hide)
    a am force-stop app.leopard.cockpit
    echo "  test card closed"
    ;;
  waze)
    # Starting Waze with --display 4 lands on the centre screen (its splash screen
    # hands off to MainActivity), so start it normally, then move its task.
    id="$(full_map_id)"
    if [ -z "$id" ]; then echo "No full-map cluster channel found." >&2; exit 1; fi
    task() { a dumpsys activity activities | grep -oE 'Task\{[0-9a-f]+ #[0-9]+ type=standard A=[0-9]+:com\.waze ' | head -1 | grep -oE '#[0-9]+' | tr -d '#'; }
    t="$(task)"
    if [ -z "$t" ]; then
      a am start-activity -n com.waze/.FreeMapAppActivity >/dev/null
      sleep 4
      t="$(task)"
    fi
    if [ -z "$t" ]; then echo "Waze didn't start." >&2; exit 1; fi
    echo "  moving Waze (task $t) to display $id"
    a am display move-stack "$t" "$id"
    ;;
  off)
    send_info 18
    sleep 1
    send_info 0
    ;;
  *)
    sed -n '2,18p' "$0"; exit 1 ;;
esac
