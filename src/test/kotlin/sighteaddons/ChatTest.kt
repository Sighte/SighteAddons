package sighteaddons

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * The chat tag, which is how a player tells this mod's lines from Hypixel's.
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
        val grey = Component.literal("no new records").withStyle(ChatFormatting.DARK_GRAY)
        val runs = runsOf(Chat.line(grey))

        assertEquals(3, runs.size, "monogram, separator, body")
        assertEquals("SA", runs[0].first)
        assertEquals(Palette_DARK_TEXT_PRIMARY, runs[0].second, "the monogram is the ramp's primary")
        assertEquals("no new records", runs[2].first)
        assertEquals(
            ChatFormatting.DARK_GRAY.color, runs[2].second,
            "the body kept the colour it asked for",
        )
    }

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
        /** `Palette.DARK.textPrimary` without its alpha — the theme package is internal to the UI. */
        const val Palette_DARK_TEXT_PRIMARY = 0xF6F7F8
    }
}
