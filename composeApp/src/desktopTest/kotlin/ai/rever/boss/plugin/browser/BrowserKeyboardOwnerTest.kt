package ai.rever.boss.plugin.browser

import ai.rever.boss.window.MainPanelFocusTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#1566: [resolveBrowserKeyboardOwner] is the one rule for "does a browser hold the
 * keyboard", which decides whether the AWT keymap's BROWSER-context bindings (Cmd+L above all)
 * can match. Each test is one row of the table in its KDoc.
 */
class BrowserKeyboardOwnerTest {
    @Test
    fun `no active browser in the window means no browser owns the keyboard`() {
        // A browser in a sidebar slot, or the background half of a split, is not the window's
        // active browser, so even its own focused page must not bring BROWSER context with it.
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner(null, listOf("sidebar"), true))
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner(null, emptyList(), true))
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner(null, emptyList(), false))
    }

    @Test
    fun `the active browser's focused page owns the keyboard whatever Compose focus says`() {
        // HARDWARE_ACCELERATED: clicking the native page moves no Compose focus, so a sidebar
        // editor can still hold it. The page's own focus event is the truthful one.
        assertEquals(BrowserKeyboardOwner.PAGE, resolveBrowserKeyboardOwner("a", listOf("a"), false))
        assertEquals(BrowserKeyboardOwner.PAGE, resolveBrowserKeyboardOwner("a", listOf("a"), true))
    }

    @Test
    fun `another browser's focused page takes the keyboard away from the active one`() {
        // Keys are going to a browser this window's shortcuts would not act on, and Compose focus
        // left in the main panel is stale, so answering CHROME would act on the wrong browser.
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner("a", listOf("b"), true))
    }

    @Test
    fun `Compose focus in the main panel is the chrome`() {
        assertEquals(BrowserKeyboardOwner.CHROME, resolveBrowserKeyboardOwner("a", emptyList(), true))
    }

    @Test
    fun `focus outside the main panel is not the browser`() {
        // The sidebar editor case: Cmd+L must stay Go To Line there.
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner("a", emptyList(), false))
    }

    @Test
    fun `main panel focus survives a handoff between split halves in either order`() {
        val window = "owner-test-window"
        val left = Any()
        val right = Any()

        // Gain on the right reported before the loss on the left.
        MainPanelFocusTracker.update(window, left, true)
        MainPanelFocusTracker.update(window, right, true)
        MainPanelFocusTracker.update(window, left, false)
        assertTrue(MainPanelFocusTracker.hasFocus(window))

        // And the other way round.
        MainPanelFocusTracker.update(window, left, false)
        MainPanelFocusTracker.update(window, right, false)
        MainPanelFocusTracker.update(window, left, true)
        assertTrue(MainPanelFocusTracker.hasFocus(window))

        MainPanelFocusTracker.update(window, left, false)
        assertFalse(MainPanelFocusTracker.hasFocus(window))
    }

    @Test
    fun `main panel focus is per window`() {
        val panel = Any()
        MainPanelFocusTracker.update("owner-test-w1", panel, true)
        assertTrue(MainPanelFocusTracker.hasFocus("owner-test-w1"))
        assertFalse(MainPanelFocusTracker.hasFocus("owner-test-w2"))
        MainPanelFocusTracker.update("owner-test-w1", panel, false)
        assertFalse(MainPanelFocusTracker.hasFocus("owner-test-w1"))
    }
}
