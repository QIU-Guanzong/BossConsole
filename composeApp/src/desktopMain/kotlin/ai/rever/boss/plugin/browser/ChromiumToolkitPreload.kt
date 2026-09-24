package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.nio.file.Path

/**
 * Loads JxBrowser's `libtoolkit` and `libipc` on the main thread at startup, before the engine
 * pre-warm thread would.
 *
 * **Why.** Each of JxBrowser's macOS JNI libraries carries Chromium's allocator shim, and a static
 * initializer in each one makes PartitionAlloc the process's default malloc zone the way Chromium
 * does it: `malloc_zone_register(pa)`, `malloc_zone_unregister(default)`,
 * `malloc_zone_register(default)`. Between the last two calls the system zone is not listed. A
 * `free()` on any other thread in that window reaches the shim's fallback, which asks every
 * registered zone "is this yours?", finds no owner and executes `brk #0`: an EXC_BREAKPOINT /
 * SIGTRAP that kills BOSS with nothing Java can catch. Chromium does this at process start on
 * one thread; in BOSS it ran whenever JxBrowser first called `System.load`, which was the
 * `fluck-engine-prewarm` thread ~0.5s into launch, while the main thread was loading classes.
 *
 * Measured, not assumed (BOSS dev build on 0fa626d, 2026-09-23, JxBrowser 9.5.0 / Chromium
 * 152.0.7977.65): the crash PC is `libtoolkit+0x4b178`, the `brk #0` after a loop over
 * `malloc_get_all_zones` calling each zone's `size()`; the registers show three zones checked and
 * none claiming the pointer; the faulting thread is the Java main thread in
 * `ClassLoader.defineClass1`; `fluck-engine-prewarm` and `Chromium Process Thread` are alive; the
 * register / unregister / register sequence is in `libtoolkit`'s second `__init_offsets` entry
 * (`0x4b250`). The 27 July 2026 release crash (9.2.60, +514ms, libtoolkit on a `free` path under
 * `libxpc` dealloc) has the same signature.
 *
 * **What this changes.** The swap still happens, but on the main thread, before the startup class
 * loading and before the pre-warm thread exists - so the busiest `free()` callers of that moment
 * cannot be in the window. JxBrowser's own later `System.load` of the same canonical path, from
 * the same class loader, is a no-op in the JVM.
 *
 * **What it does not.** It narrows the race rather than removing it: JVM service threads (GC,
 * JIT) still run. `libawt_toolkit` is deliberately NOT preloaded - it links `@rpath/libjawt.dylib`
 * and so must load after AWT - and it still swaps zones when JxBrowser loads it for the first
 * browser view. Only moving JxBrowser out of the host process removes the family.
 *
 * Off switch: `BOSS_TOOLKIT_PRELOAD=false` (also `0` / `no` / `off`) or
 * `-Dboss.toolkit.preload=false`.
 */
object ChromiumToolkitPreload {
    private val logger by lazy { BossLogger.forComponent("ChromiumToolkitPreload") }

    /** Load order: `libtoolkit` first, as JxBrowser's `ToolkitLibrary` is the first it loads. */
    internal val PRELOADED_LIBRARIES = listOf("libtoolkit.dylib", "libipc.dylib")

    private const val DISABLED_KEY = "BOSS_TOOLKIT_PRELOAD"
    private const val DISABLED_PROPERTY = "boss.toolkit.preload"

    /**
     * The native libraries to preload for the engine at [engineDir], or empty when there is
     * nothing safe to load: not macOS, no `executable.name`, or the framework does not carry
     * [chromiumVersion] (the build this jar was compiled against - loading anything else would be
     * the version-mismatch failure `FluckEngine.chromiumVersionMismatch` exists to report, not
     * cause). Pure, so the path rule is testable off macOS.
     */
    internal fun librariesFor(
        engineDir: Path,
        isMac: Boolean,
        executableName: String?,
        chromiumVersion: String,
    ): List<File> {
        if (!isMac || executableName.isNullOrBlank()) return emptyList()
        val libraries =
            engineDir
                .resolve("$executableName.app/Contents/Frameworks/Chromium Framework.framework/Versions")
                .resolve(chromiumVersion)
                .resolve("Libraries")
                .toFile()
        val files = PRELOADED_LIBRARIES.map { libraries.resolve(it) }
        return if (files.all { it.isFile }) files else emptyList()
    }

    internal fun disabledFrom(
        env: String?,
        property: String?,
    ): Boolean {
        val raw = (env?.takeIf { it.isNotBlank() } ?: property)?.trim()?.lowercase() ?: return false
        return raw == "false" || raw == "0" || raw == "no" || raw == "off"
    }

    /**
     * Preload for [engineDir], the directory the engine will boot from (the caller resolves it
     * with the same `FluckEngine.resolveEngineDir` the engine uses, so the path JxBrowser loads
     * later is this one). Never throws: a failure here must not stop a launch that would
     * otherwise have worked, and JxBrowser still loads the libraries itself.
     *
     * Returns how many libraries were loaded, for the log and tests.
     */
    // TooGenericExceptionCaught: UnsatisfiedLinkError is an Error and nothing may escape.
    // ReturnCount: no engine, switched off, and loaded are three distinct outcomes.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun preload(engineDir: Path?): Int {
        if (engineDir == null) return 0
        if (disabledFrom(System.getenv(DISABLED_KEY), System.getProperty(DISABLED_PROPERTY))) {
            logger.info(LogCategory.BROWSER, "Native toolkit preload disabled")
            return 0
        }
        val files =
            runCatching {
                librariesFor(
                    engineDir = engineDir,
                    isMac =
                        System
                            .getProperty("os.name")
                            .orEmpty()
                            .lowercase()
                            .contains("mac"),
                    executableName =
                        engineDir
                            .resolve("executable.name")
                            .toFile()
                            .readText()
                            .trim(),
                    chromiumVersion =
                        com.teamdev.jxbrowser.VersionInfo
                            .chromiumVersion(),
                )
            }.getOrDefault(emptyList())
        var loaded = 0
        for (file in files) {
            try {
                System.load(file.canonicalPath)
                loaded++
            } catch (t: Throwable) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Native toolkit preload failed; JxBrowser will load it later",
                    mapOf("library" to file.name),
                    error = t,
                )
                break
            }
        }
        if (loaded > 0) {
            logger.info(
                LogCategory.BROWSER,
                "Preloaded native toolkit on the main thread",
                mapOf("libraries" to loaded),
            )
        }
        return loaded
    }
}
