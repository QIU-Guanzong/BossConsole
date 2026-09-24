package ai.rever.boss.plugin.browser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The preload must name exactly the files JxBrowser will `System.load` later - the same
 * `Versions/<chromium>/Libraries` directory `FluckEngine.chromiumVersionMismatch` checks - or the
 * JVM would load a second copy from another path, and with it a second allocator zone swap.
 */
class ChromiumToolkitPreloadTest {
    private fun engine(
        version: String,
        vararg libs: String,
    ): Path {
        val dir = Files.createTempDirectory("engine")
        val libraries =
            dir.resolve("BOSS.app/Contents/Frameworks/Chromium Framework.framework/Versions/$version/Libraries")
        Files.createDirectories(libraries)
        libs.forEach { Files.createFile(libraries.resolve(it)) }
        return dir
    }

    @Test
    fun `resolves toolkit then ipc under the jar's chromium version`() {
        val dir = engine("152.0.7977.65", "libtoolkit.dylib", "libipc.dylib", "libawt_toolkit.dylib")
        val files = ChromiumToolkitPreload.librariesFor(dir, true, "BOSS", "152.0.7977.65")
        assertEquals(listOf("libtoolkit.dylib", "libipc.dylib"), files.map { it.name })
        assertTrue(files.all { it.path.contains("/Versions/152.0.7977.65/Libraries/") })
    }

    @Test
    fun `awt_toolkit is never preloaded because it links libjawt`() {
        assertFalse("libawt_toolkit.dylib" in ChromiumToolkitPreload.PRELOADED_LIBRARIES)
    }

    @Test
    fun `nothing is preloaded for a mismatched engine, a missing library, or off macOS`() {
        val dir = engine("151.0.7922.138", "libtoolkit.dylib", "libipc.dylib")
        assertTrue(ChromiumToolkitPreload.librariesFor(dir, true, "BOSS", "152.0.7977.65").isEmpty())
        val partial = engine("152.0.7977.65", "libtoolkit.dylib")
        assertTrue(ChromiumToolkitPreload.librariesFor(partial, true, "BOSS", "152.0.7977.65").isEmpty())
        val ok = engine("152.0.7977.65", "libtoolkit.dylib", "libipc.dylib")
        assertTrue(
            ChromiumToolkitPreload.librariesFor(ok, false, "BOSS", "152.0.7977.65").isEmpty(),
        )
        assertTrue(ChromiumToolkitPreload.librariesFor(ok, true, null, "152.0.7977.65").isEmpty())
    }

    @Test
    fun `off switch accepts the usual falsy spellings and ignores a blank env`() {
        assertTrue(ChromiumToolkitPreload.disabledFrom("false", null))
        assertTrue(ChromiumToolkitPreload.disabledFrom(" OFF ", null))
        assertTrue(ChromiumToolkitPreload.disabledFrom("", "0"))
        assertFalse(ChromiumToolkitPreload.disabledFrom(null, null))
        assertFalse(ChromiumToolkitPreload.disabledFrom("true", "false"))
    }

    @Test
    fun `a missing engine directory preloads nothing and does not throw`() {
        assertEquals(0, ChromiumToolkitPreload.preload(null))
    }
}
