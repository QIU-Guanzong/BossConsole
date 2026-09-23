package ai.rever.boss.components.bars.horizontal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the single "MCP access" bar item that replaced three separate consent controls. The risk
 * in collapsing them is losing a way in: a control that used to be on the bar and is now in no
 * menu, or a live session grant that no longer shows on the bar at all.
 */
class McpAccessSummaryTest {
    private fun labels(summary: McpAccessSummary): List<String> {
        val items = mcpAccessMenuItems(summary, {}, {}, {})
        return items.map { if (it.isDivider) "---" else it.text }
    }

    @Test
    fun `every control the bar used to show is reachable from the menu`() {
        assertEquals(
            listOf("Tool policies (3)...", "Trusted plugins (2)...", "---", "Revoke session trust (1)"),
            labels(McpAccessSummary(savedRules = 3, trustedPlugins = 2, sessionGrants = 1)),
        )
    }

    @Test
    fun `entries that would do nothing are left out, but tool policies always stays`() {
        assertEquals(listOf("Tool policies..."), labels(McpAccessSummary(0, 0, 0)))
    }

    @Test
    fun `each entry runs its own action`() {
        val fired = mutableListOf<String>()
        mcpAccessMenuItems(
            McpAccessSummary(1, 1, 1),
            onPolicies = { fired += "policies" },
            onTrustedPlugins = { fired += "plugins" },
            onRevokeSession = { fired += "revoke" },
        ).filterNot { it.isDivider }.forEach { it.onClick() }
        assertEquals(listOf("policies", "plugins", "revoke"), fired)
    }

    @Test
    fun `visible while anything is granted or any tool could get a rule`() {
        assertFalse(McpAccessSummary(0, 0, 0).isVisible(hasTools = false))
        assertTrue(McpAccessSummary(0, 0, 0).isVisible(hasTools = true))
        assertTrue(McpAccessSummary(1, 0, 0).isVisible(hasTools = false))
        assertTrue(McpAccessSummary(0, 1, 0).isVisible(hasTools = false))
        assertTrue(McpAccessSummary(0, 0, 1).isVisible(hasTools = false))
    }

    @Test
    fun `durations read as ms, seconds, then minutes`() {
        assertEquals("850ms", formatMcpDuration(850))
        assertEquals("1.0s", formatMcpDuration(1_000))
        assertEquals("12.4s", formatMcpDuration(12_480))
        assertEquals("4m 16s", formatMcpDuration(256_100))
        assertEquals("0ms", formatMcpDuration(-5))
    }
}
