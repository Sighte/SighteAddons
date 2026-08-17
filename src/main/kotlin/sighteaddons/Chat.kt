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
     * The tag's two colours, pinned to the dark ramp rather than read live from [sighteaddons.ui.theme.Tokens].
     *
     * Chat is the one surface this UI does not own: it is drawn on vanilla's translucent black
     * backdrop whatever theme the mod is set to, so a tag that followed the theme would turn
     * near-black and vanish the moment somebody switched to the light palette. The values are still
     * the design system's — taken from [Palette.DARK] rather than written here as literals, because
     * the rule that no colour lives outside the theme package is what keeps this UI one system.
     *
     * `and 0xFFFFFF` drops the alpha: the palette is packed ARGB, and [TextColor] is RGB only. Chat
     * has no transparency to give, which is also why the greys used here are the two that were
     * measured to clear the contrast floor on their own.
     */
    private val MONOGRAM_STYLE: Style = rgb(Palette.DARK.textPrimary)
    private val SEPARATOR_STYLE: Style = rgb(Palette.DARK.textTertiary)

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
        .append(Component.literal(MONOGRAM).setStyle(MONOGRAM_STYLE))
        .append(Component.literal(" $SEPARATOR ").setStyle(SEPARATOR_STYLE))
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
