# Moving the car to UI7

Plan agreed 21 Nov 2026: update the Leopard 5 from the shop's older DiLink 5.1
build (`persist.sys.version` 34.1.3.2512224.1, Android 13, built 19 Jan 2026)
to BYD's latest UI7 software. That brings the fragrance controls back and loses
BYD's own split screen; Outo's split screen doesn't depend on BYD's, so it
replaces it.

## 1. Before the update (car connected over ADB)

1. Update Outo to 0.5.34 or newer (Settings › Updates) and open it once. From
   0.5.34 Outo keeps a copy of its own settings in a file the backup can pull.
2. Run the backup. It's read-only on the car:

   ```bash
   tools/backup-car.sh <car-ip>:5555
   ```

   It saves every installed app (all split parts), Outo's settings (sidebar,
   layouts, widgets, every switch, trip and pedal numbers), each app's
   permissions and app-ops, the system switches (accessibility, ADB, home app, battery
   exemptions) and a full probe for a before/after comparison, in
   `car-backup/<time>/` and `car-probe/<time>/`. Neither folder goes to GitHub.
   Check it prints "Outo settings saved".
3. Note which apps need a sign-in: the backup can't copy other apps' data. **Waze needs a Google sign-in again, and the car
   has no real Google services: that was the hard part last time.**
4. Ask the shop:
   - Will they do it as a normal update (apps stay) or a full flash (wipe)?
   - Will they open network ADB again afterwards? (DiLink 5.x ADB is usually
     unlocked remotely by the seller.)
   - Which UI7 version it is, and is it the latest for the Middle East Leopard 5?
5. Updates seem switched off on the car now (`persist.service.ota.enable=0`,
   `persist.sys.byd.otaupdate=false`). The update may not show in Settings until
   the shop turns that back on.

## 2. Right after the update

1. Network ADB back on (Settings › Car Connection). Some UI 7.0 cars show the
   switch on but nothing answers after a restart: turn it off and on again.
2. Check the apps are still there: `adb shell pm list packages -3`. If they're
   gone, reinstall them:

   ```bash
   tools/restore-car-apps.sh car-backup/<time> <car-ip>:5555
   ```

   When it reinstalls Outo it also puts Outo's settings back; Outo loads them
   when it opens and says "Outo's settings were restored from the backup". If
   Outo survived the update, its settings did too and are left alone.

3. Turn the switches back on using `system.txt` and `perms/` in the backup:
   Outo's accessibility service (map reader), overlays, battery exemption.
4. Open Outo once so it starts its helper.
5. Run a new probe and compare:

   ```bash
   tools/probe-car.sh <car-ip>:5555
   ```

   ```bash
   diff car-probe/<before>/SUMMARY.txt car-probe/<after>/SUMMARY.txt
   ```

## 3. What to test in Outo on UI7

| Area | What could change | Test |
|---|---|---|
| ADB after a reboot | Reported to close at every restart on some 2026 builds | Turn the car fully off and on. If ADB closed, switch it back on and open Outo: it starts its helper again by itself. Not a blocker. |
| Android version | If UI7 is Android 14, some hidden calls Outo makes change | Split screen, moving apps between screens, two-finger tabs |
| Split screen | Outo's own; BYD's is gone | Drag an app onto a half; both halves take touches |
| Cluster map | BYD's projection screens (`fission_bg_*`) may be renamed | Waze / Google Maps on the cluster; HUD turn arrows |
| Car data | BYD may tighten permissions | Speed, temperatures, energy bar, engine sound |
| Car start | BYD's home may take over in a new way | Start the car: Outo opens and stays in front |
| Fragrance | The reason for the update | Shows in BYD's climate settings |

Anything that breaks: grab `adb logcat` and Outo's logs
(`/sdcard/Android/data/app.leopard.cockpit/files`) straight away, then fix it
in Outo.
