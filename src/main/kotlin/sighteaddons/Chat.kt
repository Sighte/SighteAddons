package sighteaddons

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import sighteaddons.ui.theme.Palette

/**
 * Everything this mod says in chat, and the tag that says it was this mod saying it.
 *
 * The outgoing half of chat; [ChatEvents] is the incoming one. Nothing here sends anything — every
 * line goes through `addClientSystemMessage`, which writes into this client's own chat buffer and
 * reaches no server and no other player. That is a constraint the mod this was ported from broke
 * (see [CritMeter]) and it is why there is exactly one function that writes.
 *
 * **One funnel, so a line cannot be written without the tag.** Before this the mod wrote from three
 * places with three different self-identifications: a gold `Sighte Addons` on the upload notice, a
 * gold `Sighte` on the run summary, and nothing at all on the crit readout or on any room line — so
 * on a busy Hypixel floor most of what this mod said was indistinguishable from what the server
 * said. Adding a fourth site is now the same work as adding the tag to it.
 *
 * **One voice, for the same reason.** The tag says which mod is speaking; [value], [label], [meta]
 * and [emphasis] are how it speaks, and [FIELD] is how it punctuates. A call site chooses what a run
 * *is* and never what colour it should be, which is what stops the next line from inventing a fifth
 * way to write a time or a sixth way to separate two facts.
 */
object Chat {

    /** The monogram. Two letters, because a chat line has a few dozen characters of attention. */
    const val MONOGRAM = "SA"

    /**
     * The separator, and the whole of the tag's shape.
     *
     * No brackets, deliberately: Hypixel's chat is already full of them — rank prefixes, `[BOSS]`,
     * `[NPC]` — and a `[SA]` would be read as one more of those before it was read as this mod. A
     * bare monogram and a chevron is the quietest thing that is still unmistakably a tag.
     */
    const val SEPARATOR = "»"

    /** What every tagged line starts with, as it appears in [MutableComponent.getString]. */
    const val PREFIX = "$MONOGRAM $SEPARATOR "

    /**
     * What separates two fields on one of this mod's lines, everywhere, with no second spelling.
     *
     * Before this the mod had four: an em dash on the run headline, a comma after it, `(…)` around
     * the room line's extra players and around every breakdown, and a doubled `·` in the crit
     * readout. Four punctuations for one relationship is four things a reader has to learn before a
     * line can be skimmed, and none of them meant anything the others did not.
     *
     * `·` rather than `-` or `|`: it is in vanilla's own font page (it has shipped in the crit
     * readout and the summary breakdown since those were written, so this is observed rather than
     * assumed), it cannot be confused with the minus sign that now means "faster than the record",
     * and it is narrow enough that a line with four fields still fits the chat width.
     */
    const val FIELD = " · "

    /**
     * The whole of this mod's chat palette: four roles, pinned to the dark ramp rather than read live
     * from [sighteaddons.ui.theme.Tokens].
     *
     * **Why the ramp is pinned.** Chat is the one surface this UI does not own: it is drawn on
     * vanilla's translucent black backdrop whatever theme the mod is set to, so a line that followed
     * the theme would turn near-black and vanish the moment somebody switched to the light palette.
     * The values are still the design system's — taken from [Palette.DARK] rather than written here
     * as literals, because the rule that no colour lives outside the theme package is what keeps this
     * UI one system.
     *
     * **Why four and not one per call site.** Every line the mod writes used to pick its own
     * `ChatFormatting`, which is how the mod ended up saying times in `AQUA` — Hypixel's own colour
     * for its own numbers — and records in `GOLD`, on a floor where both are already everywhere. Four
     * named roles mean a new line chooses what a run *is*, not what colour it should be, and there is
     * one place to change if the answer turns out wrong.
     *
     * **The contrast floor, and the worst case it is measured against.** Vanilla hands `ChatComponent`
     * a backdrop of `0x80000000` — black at 50% — while `backgroundForChatOnly` is on, which is the
     * default. Composited over a *black* world that is `#000000`, and against it these four measure
     * 19.6:1, 8.9:1, 7.0:1 and 21:1 (`Contrast.ratio`; `ChatTest` computes them rather than trusting
     * this sentence).
     *
     * That is deliberately the favourable end of the backdrop, and the reason is arithmetic rather
     * than convenience: the same backdrop over a *white* world composites to `#7F7F7F`, against which
     * the best ratio anything can reach is **4.00:1** — pure white, and still short of the 4.5 floor.
     * No colour clears the floor there, so measuring against it would not choose a better grey; it
     * would only prove the floor unreachable and leave the choice unconstrained. What the bright case
     * does constrain is how far down the ramp a line may go, because each role holds 4.5:1 only up to
     * a world brightness of its own: `#E4E4E4` for [value], `#7E7E7E` for [label] and `#5E5E5E` for
     * [meta]. Hence the rule every builder in this mod follows — **[meta] carries what qualifies a
     * value, never the value the line exists to deliver.** A player who reads chat against a bright
     * world loses the qualifier and keeps the answer.
     *
     * `and 0xFFFFFF` drops the alpha: the palette is packed ARGB, and [TextColor] is RGB only. Chat
     * has no transparency to give.
     */
    private val VALUE: Style = rgb(Palette.DARK.textPrimary)
    private val LABEL: Style = rgb(Palette.DARK.textSecondary)
    private val META: Style = rgb(Palette.DARK.textTertiary)
    private val EMPHASIS: Style = rgb(Palette.DARK.accent)

    /**
     * The four, as RGB. For `ChatTest`, which renders every builder in the mod and asserts nothing
     * else got through — the one property no individual call site can hold on its own.
     */
    internal val RAMP: Set<Int> = listOf(VALUE, LABEL, META, EMPHASIS).mapNotNull { it.color?.value }.toSet()

    /** A name, a number, a time — the thing the line is about. */
    fun value(text: String): MutableComponent = Component.literal(text).setStyle(VALUE)

    /** The words between the values. Nothing here is a fact; deleting them would lose no data. */
    fun label(text: String): MutableComponent = Component.literal(text).setStyle(LABEL)

    /** A field that qualifies a value the reader has already found. Never the value itself. */
    fun meta(text: String): MutableComponent = Component.literal(text).setStyle(META)

    /**
     * The one run on a line that has to be found without reading the line.
     *
     * **It is never the only thing marking that run, and it cannot be.** This is a monochrome design
     * with a measured cost: `Palette.DARK` records that its tertiary sits 1.27:1 from its secondary,
     * so a step on the ramp is not something a reader can be asked to notice on its own — and the top
     * of the ramp is worse, since [EMPHASIS] is `#FFFFFF` against a [VALUE] of `#F6F7F8`. So every
     * emphasised run here is also carried by a word (`PB`, `too many`) and by its position — the end
     * of the line, where a reader who only wants that one answer looks. The colour is the third
     * signal, not the first, and a greyscale screenshot loses nothing.
     */
    fun emphasis(text: String): MutableComponent = Component.literal(text).setStyle(EMPHASIS)

    /**
     * A line that already arrived as one string, split on [FIELD] and given the ramp back.
     *
     * For [CritMeter], which owns its own wording as plain text so that `CritMeterTest` can drive the
     * whole readout — window, parse, arithmetic and phrasing — without a `Minecraft`. Handing that
     * string to [value] whole was the obvious thing and it was visibly wrong: the separators came out
     * a shade brighter than the identical separators on every other line, which is the one difference
     * a reader notices without being able to name it. This is the cheapest way to keep the wording
     * where it is testable and the colours where they belong.
     */
    fun fields(text: String): MutableComponent {
        val out = Component.empty()
        text.split(FIELD).forEachIndexed { index, part ->
            if (index > 0) out.append(meta(FIELD))
            out.append(value(part))
        }
        return out
    }

    private fun rgb(argb: Int): Style = Style.EMPTY.withColor(TextColor.fromRgb(argb and 0xFFFFFF))

    /**
     * [body], tagged. Pure, so the tag is checkable without a client.
     *
     * The root is [Component.empty] rather than the monogram with the rest appended to it: a sibling
     * inherits its parent's style for anything it does not set itself, so hanging the message off a
     * white root would silently repaint every uncoloured run in it. An empty root sets nothing and
     * therefore hands nothing down.
     */
    fun line(body: Component): MutableComponent = Component.empty()
        // The tag is the first user of the ramp rather than a pair of colours of its own: the
        // monogram is the value — it is what the tag says — and the chevron is metadata about it.
        .append(value(MONOGRAM))
        .append(meta(" $SEPARATOR "))
        .append(body)

    /**
     * Writes [body] into the local chat, tagged.
     *
     * Scheduled onto the client thread, which is not a formality: this is called from the run-end
     * chat handler, from [SecretApi]'s network callback and from the upload notice, and only one of
     * those is already on the right thread.
     */
    fun say(body: Component) {
        val client = Minecraft.getInstance()
        val text = line(body)
        client.schedule { client.gui.chat.addClientSystemMessage(text) }
    }
}
