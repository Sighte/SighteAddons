package sighteaddons

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sighteaddons.ui.theme.Contrast
import java.util.Optional

/**
 * The chat tag, which is how a player tells this mod's lines from Hypixel's, and the four-colour ramp
 * everything behind it is written in.
 */
class ChatTest {

    /** The tag is on the front of the line, not somewhere in it. */
    @Test
    fun `every line this mod writes carries the tag`() {
        val line = Chat.line(Component.literal("Water Board cleared in 0:41.2"))
        assertEquals("SA » Water Board cleared in 0:41.2", line.string)
        assertTrue(line.string.startsWith(Chat.PREFIX), "the tag has to be the first thing read")
    }

    /**
     * The tag's colour stops at the tag.
     *
     * A sibling inherits its parent's style for anything it does not set itself, so building the line
     * as `literal("SA").append(body)` — which is the obvious way to write it and the way this was
     * written first — hands the monogram's white down to every uncoloured run in the message. It is
     * invisible in the code and repaints whole lines on screen. Hence the empty root, and hence this.
     */
    @Test
    fun `the tag does not repaint the message`() {
        val runs = runsOf(Chat.line(Chat.meta("no new records")))

        assertEquals(3, runs.size, "monogram, separator, body")
        assertEquals("SA", runs[0].first)
        assertEquals(TEXT_PRIMARY, runs[0].second, "the monogram is the ramp's primary")
        assertEquals("no new records", runs[2].first)
        assertEquals(TEXT_TERTIARY, runs[2].second, "the body kept the colour it asked for")
    }

    /**
     * The acceptance bar for this UI, stated as arithmetic rather than judged by eye, on the one
     * surface the UI does not own.
     *
     * **The backdrop assumed is vanilla's own: `0x80000000` — black at 50% — which is what
     * `Options.getBackgroundColor` hands `ChatComponent` while `backgroundForChatOnly` is on, and
     * that is the default. Composited over a black world it is `#000000`, and that is the reference
     * here.** Deliberately the favourable end, and the reason is arithmetic: the same backdrop over a
     * *white* world composites to `#7F7F7F`, where the best ratio anything can reach is 4.00:1 — pure
     * white, and still under the floor. Measuring there would not pick a better grey, it would only
     * show the floor is unreachable. The bright case is instead spent on the rule the builders follow
     * (`Chat.meta` never carries the value a line exists to deliver), which this cannot check.
     */
    @Test
    fun `every colour this mod writes chat in clears the contrast floor`() {
        val backdrop = Contrast.over(VANILLA_CHAT_BACKDROP, BLACK_WORLD)
        assertEquals(0xFF000000.toInt(), backdrop, "the assumed worst case, spelled out")
        for (colour in Chat.RAMP) {
            val ratio = Contrast.ratio(OPAQUE or colour, backdrop)
            assertTrue(
                ratio >= Contrast.AA,
                "#%06X on vanilla's chat backdrop is %.2f:1, below %.1f".format(colour, ratio, Contrast.AA),
            )
        }
    }

    /**
     * No line this mod can say uses a colour that is not on the ramp.
     *
     * This is the one thing about the chat redesign that no single call site can hold on its own: the
     * mod writes from four files, every one of them used to reach for a `ChatFormatting` of its
     * choosing, and the failure is silent — an `AQUA` time looks fine in isolation and is
     * indistinguishable from Hypixel's own on a floor. So every builder is rendered here and every
     * run in the result is checked against the four.
     *
     * Not a full wording pin. What each line *says* is checked where the line is built; this asks
     * only that it be said in the design system's colours.
     */
    @Test
    fun `no line reaches for a colour outside the ramp`() {
        val lines = listOf(
            RoomHistory.clearLine("Nyx", "Water Board", 824, 0, null),
            RoomHistory.clearLine("Nyx", "Water Board", 824, 2, RoomHistory.pbSuffix(880, 824)),
            RoomHistory.secretRunLine("Atlas", 640, 6, 4, RoomHistory.pbSuffix(null, 640)),
            RoomHistory.bloodLine("Blood Room", 2680, RoomHistory.pbSuffix(2802, 2680)),
            RoomHistory.headline("M7", 6442, 19),
            RoomHistory.playerRow("Nyx", 12.34, 7, 10, 29),
            RoomHistory.unattributedLine(2.0),
            RoomHistory.recordsLine(0),
            RoomHistory.recordsLine(3),
            RoomHistory.secretLine(mapOf("Nyx" to 12, "Mate" to 8)),
            RoomHistory.auditLine(audit(12, 10, answered = 3, asked = 4)),
            Chat.fields(CritMeter.line(12e9, 8.0)),
        )

        for (body in lines) {
            for ((text, colour) in runsOf(Chat.line(body))) {
                assertTrue(
                    colour != null && colour in Chat.RAMP,
                    "'$text' is #%06X, which is not on the ramp".format(colour ?: -1),
                )
            }
        }
    }

    private fun audit(tracked: Int, actual: Int, answered: Int, asked: Int) =
        SecretAudit.Result(tracked, actual, floorTracked = 29, floorActual = 30, answered = answered, asked = asked)

    /** Every run of the rendered line, with the colour it actually resolves to. */
    private fun runsOf(component: Component): List<Pair<String, Int?>> {
        val out = ArrayList<Pair<String, Int?>>()
        component.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                if (text.isNotEmpty()) out.add(text to style.color?.value)
                Optional.empty()
            },
            Style.EMPTY,
        )
        return out
    }

    private companion object {
        /** `Palette.DARK.textPrimary` and `.textTertiary` without alpha — the theme is the UI's. */
        const val TEXT_PRIMARY = 0xF6F7F8
        const val TEXT_TERTIARY = 0x91959D

        /** What vanilla draws behind a chat message at the default settings. */
        const val VANILLA_CHAT_BACKDROP = 0x80000000.toInt()

        /** The world behind it, at its darkest — see the test that uses these. */
        const val BLACK_WORLD = 0xFF000000.toInt()

        const val OPAQUE = 0xFF shl 24
    }
}
