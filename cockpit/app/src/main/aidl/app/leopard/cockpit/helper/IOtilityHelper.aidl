package app.leopard.cockpit.helper;

import android.os.ParcelFileDescriptor;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * The in-car helper: a small process that runs as the shell user (started once
 * through the car's own debugging connection) and does what a normal app can't:
 * see every screen's apps, move apps between screens, host apps inside Otility,
 * mirror a screen, and pass touches through.
 */
interface IOtilityHelper {
    int version();

    /** JSON: {"displays":[...], "tasks":[...]} — what runs where right now. */
    String snapshot();

    /** Starts an app on a display. Returns the task id once it's there, or -1. */
    int launch(String pkg, int displayId);

    /** Moves an app's task to another display. */
    boolean moveTask(int taskId, int displayId);

    /** Closes an app (like swiping it away in Recents). */
    boolean closeTask(int taskId);

    /**
     * A display that lives inside Otility: Android draws into [surface], and
     * touches arrive through [inject]. [surface] may be null (the app runs
     * unseen until Otility zooms in on it). Returns the new display id, or -1.
     */
    int createHostDisplay(String name, in @nullable Surface surface, int width, int height, int dpi);
    void resizeHostDisplay(int displayId, int width, int height, int dpi);
    void setHostSurface(int displayId, in @nullable Surface surface);
    void releaseHostDisplay(int displayId);

    /** Shows another display's picture in [surface]. Returns a mirror id, or -1. */
    int startMirror(int sourceDisplayId, in Surface surface, int width, int height);
    void stopMirror(int mirrorId);

    /** A touch, already in the target display's pixels. */
    oneway void injectMotion(in MotionEvent event, int displayId);
    oneway void injectKey(int keyCode, int displayId);

    /** Asks the driver cluster to show (or stop showing) the map channel. */
    String cluster(int code);

    /** Reads car values (speed, battery...) as JSON. */
    String vehicle();

    /** Plays the car's sound through the speaker outside (or back inside). */
    boolean exteriorSpeaker(boolean on);

    /**
     * Opens the first of [uris] that [pkg] understands, on a display (a place in a
     * navigation app). Returns true if one opened.
     */
    boolean openUri(String pkg, in String[] uris, int displayId);

    /** Lets Otility read notifications (it keeps only navigation apps' directions), or stops it. */
    boolean notificationAccess(boolean allow);

    /** Lets Otility's map reader read Waze's and Google Maps' screens (Android's accessibility), or stops it. */
    boolean accessibilityAccess(boolean allow);

    /**
     * Sets one of the driver cluster's navigation values (the turn arrow, the
     * distances, the navigation mode). Anything else is refused. Returns the
     * car's reply.
     */
    String instrumentSet(int device, int feature, int value);

    /** Reads one of those values, or Integer.MIN_VALUE when it can't be read. */
    int instrumentGet(int device, int feature);

    /**
     * Sets the cluster's next street (its only text value; "" clears it). Returns
     * the car's reply, or what went wrong.
     */
    String instrumentSetRoad(String road);

    /**
     * A small picture of part of a display (a map app's turn arrow): the rectangle
     * scaled to width × height (each at most 256), as ARGB pixels row by row. Empty
     * when it couldn't be taken.
     */
    int[] capture(int displayId, int left, int top, int right, int bottom, int width, int height);

    /**
     * The road's speed limit for the cluster and the HUD, in km/h (0 clears it, at
     * most 200), as BYD's own map sets it. Returns the car's reply, or what went wrong.
     */
    String setSpeedLimit(int kmh);

    /**
     * Opens Otility on the main screen whenever the car is switched on ("Start with
     * the car"). Remembered by the helper, which outlasts Otility: BYD force-stops
     * Otility at switch-off, and then nothing reaches it when the car wakes.
     */
    void setOpenWithCar(boolean on);

    /**
     * Installs a newer Outo from [apk] (read here, as the app's own files can't be by
     * the shell), then opens it again. Only Outo itself. Returns "ok", or what went
     * wrong; on success the caller has usually been closed by the update meanwhile.
     */
    String installUpdate(in ParcelFileDescriptor apk);
}
