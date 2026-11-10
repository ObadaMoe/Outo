package app.leopard.cockpit.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which GitHub release counts as newer than the Outo in the car. */
class UpdaterTest {
    @Test fun laterVersions() {
        assertTrue(Updater.isNewer("0.5.1", "0.5.0"))
        assertTrue(Updater.isNewer("v0.5.0", "0.4.9"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updater.isNewer("1.0", "0.99.99"))
        assertTrue(Updater.isNewer("0.5.0.1", "0.5.0"))
    }

    @Test fun sameOrOlder() {
        assertFalse(Updater.isNewer("0.5.0", "0.5.0"))
        assertFalse(Updater.isNewer("v0.5.0", "0.5"))
        assertFalse(Updater.isNewer("0.4.9", "0.5.0"))
        assertFalse(Updater.isNewer("0.5.0-beta", "0.5.0"))
        assertFalse(Updater.isNewer("", "0.5.0"))
    }
}
