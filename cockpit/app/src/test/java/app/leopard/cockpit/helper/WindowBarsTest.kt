package app.leopard.cockpit.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowBarsTest {
    private val yt = "com.google.android.youtube"

    /** One window, as `dumpsys window <package>` prints it on Android 16 (from the emulator). */
    private fun window(display: Int = 84, vsysui: String = "LAYOUT_STABLE LAYOUT_HIDE_NAVIGATION LAYOUT_FULLSCREEN IMMERSIVE_STICKY", extra: String = "", visible: Boolean = true) = """
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{5a2b58d u0 $yt/com.google.android.youtube.app.honeycomb.Shell${'$'}HomeActivity}:
    mDisplayId=$display taskId=217 mSession=Session{996a78 27938:u0a10181} mClient=android.os.BinderProxy@b36b624
    mAttrs={(0,0)(fillxfill) sim={adjust=resize forwardNavigation} layoutInDisplayCutoutMode=always ty=BASE_APPLICATION fmt=TRANSLUCENT wanim=0x103030d sysuil=true
      fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
      pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED
      vsysui=$vsysui
      bhv=SHOW_TRANSIENT_BARS_BY_SWIPE}
    Requested w=1920 h=996 mLayoutSeq=15
$extra
    mFullConfiguration={1.0 310mcc260mnc [en_US] winConfig={ mWindowingMode=fullscreen }}
    isOnScreen=$visible
    isVisible=$visible
""".trimIndent()

    @Test fun browsingKeepsTheBars() = assertFalse(WindowBars.hides(window(), yt, 84))

    @Test fun oldFlagsHideThem() =
        assertTrue(WindowBars.hides(window(vsysui = "LAYOUT_STABLE LAYOUT_HIDE_NAVIGATION LAYOUT_FULLSCREEN FULLSCREEN HIDE_NAVIGATION IMMERSIVE_STICKY"), yt, 84))

    @Test fun windowFlagFullscreenHidesThem() =
        assertTrue(WindowBars.hides(window().replace("fl=LAYOUT_IN_SCREEN", "fl=LAYOUT_IN_SCREEN FULLSCREEN"), yt, 84))

    @Test fun android13RequestedVisibilities() =
        assertTrue(WindowBars.hides(window(extra = "    Requested visibilities: ITYPE_STATUS_BAR: invisible, ITYPE_NAVIGATION_BAR: invisible"), yt, 84))

    @Test fun android14RequestedTypes() =
        assertTrue(WindowBars.hides(window(extra = "    Requested non-default-visibility types: statusBars navigationBars"), yt, 84))

    @Test fun onlyTheKeyboardRequestedDoesNotCount() =
        assertFalse(WindowBars.hides(window(extra = "    Requested non-default-visibility types: ime"), yt, 84))

    @Test fun anotherDisplayDoesNotCount() =
        assertFalse(WindowBars.hides(window(display = 0, vsysui = "FULLSCREEN HIDE_NAVIGATION"), yt, 84))

    @Test fun aHiddenWindowDoesNotCount() =
        assertFalse(WindowBars.hides(window(vsysui = "FULLSCREEN", visible = false), yt, 84))

    @Test fun anotherAppDoesNotCount() =
        assertFalse(WindowBars.hides(window(vsysui = "FULLSCREEN"), "com.waze", 84))
}
