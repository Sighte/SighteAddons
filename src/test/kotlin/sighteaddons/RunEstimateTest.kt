package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The projection arithmetic, which is the part of the estimate that would otherwise be guessed at.
 *
 * The cases pinned are the ones a reader of the row cannot check for themselves: that a running split
 * past its plan contributes its elapsed time rather than the plan (the estimate never claims the past
 * back), and that one missing expectation means no estimate at all rather than a partial sum that
 * looks like a whole one. The drawing is [SplitsHud] and untestable here by its own statement.
 */
class RunEstimateTest {

    private fun row(name: String, ms: Long, running: Boolean = false) = Splits.Row(
        name = name,
        label = name.uppercase(),
        ms = ms,
        ticks = if (ms < 0) -1L else ms / 50,
        running = running,
        timeText = "",
        tickText = "",
    )

    private val plan = mapOf("a" to 10f, "b" to 20f, "c" to 30f)

    @Test
    fun `closed splits are measured, the rest is the plan`() {
        // a closed at 12 s (slower than its 10 s plan — a fact, kept), b running at 5 s against a 20 s
        // plan (the plan still stands), c not started (the plan is all there is).
        val rows = listOf(row("a", 12_000L), row("b", 5_000L, running = true), row("c", -1L))
        assertEquals(12_000L + 20_000L + 30_000L, RunEstimate.projectMs(rows, false, plan::get))
    }

    @Test
    fun `a running split past its plan contributes its elapsed time`() {
        val rows = listOf(row("a", 12_000L), row("b", 25_000L, running = true), row("c", -1L))
        assertEquals(12_000L + 25_000L + 30_000L, RunEstimate.projectMs(rows, false, plan::get))
    }

    @Test
    fun `one split without a plan and there is no estimate`() {
        val rows = listOf(row("a", 12_000L), row("b", 5_000L, running = true), row("d", -1L))
        assertEquals(RunEstimate.NONE, RunEstimate.projectMs(rows, false, plan::get))
    }

    @Test
    fun `a finished run has a total, not an estimate`() {
        val rows = listOf(row("a", 12_000L), row("b", 18_000L), row("c", 28_000L))
        assertEquals(RunEstimate.NONE, RunEstimate.projectMs(rows, true, plan::get))
    }

    @Test
    fun `a hole mid-chain falls back to that split's plan`() {
        // A missed chat line leaves b unknown between two closed neighbours; its plan fills the gap
        // rather than the row after it being mis-read.
        val rows = listOf(row("a", 12_000L), row("b", -1L), row("c", 28_000L, running = true))
        assertEquals(12_000L + 20_000L + 30_000L, RunEstimate.projectMs(rows, false, plan::get))
    }
}
