package ai.rever.boss.git

import ai.rever.boss.components.workspaces.CommandProcessor
import ai.rever.boss.components.workspaces.ShellPathQuoting
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the quoting on [GitService.runInTerminal]: it builds a shell command string, so an
 * argument containing `;`, `|` or `$()` must arrive single-quoted and inert rather than
 * live shell. There is no in-repo caller today - the test exists so the first one does not
 * open an injection hole.
 */
class GitRunInTerminalQuotingTest {
    @Test
    fun `runInTerminal command builder quotes every argument independently`() {
        val arguments =
            listOf(
                "status",
                "'",
                "\"",
                "\\",
                "",
                "\$(touch sentinel)",
                "`touch sentinel`",
                "line one\nline two",
                ";",
                "|",
                "&&",
            )

        val expected = arguments.joinToString(" ", prefix = "git ") { CommandProcessor.quotePath(it) }
        assertEquals(expected, buildGitTerminalCommand(arguments))

        for (argument in arguments) {
            assertEquals(argument, unquotePosix(ShellPathQuoting.posix(argument)), "POSIX: $argument")
            assertEquals(
                argument,
                unquotePowerShell(ShellPathQuoting.powershell(argument)),
                "PowerShell: $argument",
            )
            val hostQuoted = CommandProcessor.quotePath(argument)
            val hostUnquoted = if (isWindows()) unquotePowerShell(hostQuoted) else unquotePosix(hostQuoted)
            assertEquals(argument, hostUnquoted, "Host shell: $argument")
        }
    }

    @Test
    fun `POSIX shell receives metacharacters literally`() {
        val shell = java.io.File("/bin/sh")
        if (!shell.exists()) return

        val directory = Files.createTempDirectory("git-run-in-terminal").toFile()
        val sentinel = directory.resolve("shell-command-ran").absolutePath
        val arguments =
            listOf(
                "'",
                "\"",
                "\\",
                "",
                "\$(touch \"$sentinel\")",
                "`touch \"$sentinel\"`",
                "line one\n touch \"$sentinel\"",
                "; touch \"$sentinel\"",
                "| touch \"$sentinel\"",
                "&& touch \"$sentinel\"",
                "'; touch \"$sentinel\"; ' ",
            )

        try {
            for (argument in arguments) {
                val command = "printf '%s' ${ShellPathQuoting.posix(argument)}"
                val process = ProcessBuilder(shell.absolutePath, "-c", command).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "shell rejected quoting for: $argument")
                assertEquals(argument, output, "shell round trip for: $argument")
                assertFalse(directory.resolve("shell-command-ran").exists(), "shell evaluated: $argument")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun unquotePosix(token: String): String {
        assertTrue(token.length >= 2 && token.startsWith("'") && token.endsWith("'"))
        return token.substring(1, token.length - 1).replace("'\\''", "'")
    }

    private fun unquotePowerShell(token: String): String {
        assertTrue(token.length >= 2 && token.startsWith("'") && token.endsWith("'"))
        return token.substring(1, token.length - 1).replace("''", "'")
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
