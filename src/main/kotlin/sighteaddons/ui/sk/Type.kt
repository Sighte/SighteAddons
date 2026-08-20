package sighteaddons.ui.sk

import org.blackaddons.blackskija.api.SkijaFonts

/**
 * The two typefaces this UI has, and the rule for which one a piece of text gets.
 *
 * ### Weight is doing the job a colour would have done
 *
 * The palette is achromatic on purpose — `accent` is white, and the theme has been that way since it
 * was written. That decision was reaffirmed when this screen was redesigned, which leaves exactly
 * three ways to make one thing read as more important than its neighbour: size, brightness, and
 * weight. The old screen had only the first two, because the vanilla bitmap font has one weight, and
 * you can see the consequence in it — every distinction is carried by grey level, so the greys have
 * to be spent carefully enough that `textTertiary` is a *measured* value rather than a chosen one.
 *
 * [MEDIUM] is the third axis arriving. It is worth a bundled 270 KB precisely because the design
 * refuses to spend a hue: a number in Medium beside a label in Regular separates without either of
 * them moving on the grey ramp, which means the ramp stays free to mean "how important is this" and
 * stops having to also mean "which of these two is the value".
 *
 * ### Which is which
 *
 * [MEDIUM] is for **the thing being reported**: a time, a count, a record, the value in a row, the
 * selected tab. [REGULAR] is for everything that describes it — labels, the one-line reasons under a
 * setting, footers, the table's own headers. If both halves of a pair are Medium then neither is, so
 * the rule is enforced by using it sparingly rather than by a check.
 *
 * ### Registration is late on purpose
 *
 * [SkijaFonts.register] decodes through `FontMgr`, which is native Skija — so calling it at mod init
 * would touch the native before it has been provisioned and poison the classes for the life of the
 * process. See rule 2 in [Sk]. [ensure] is therefore called from inside the draw calls themselves,
 * which is the only place the timing is guaranteed, and costs one already-true boolean per call.
 */
internal object Type {

    /**
     * Regular: BlackSkija's own bundled JetBrains Mono, already on the classpath.
     *
     * Not re-bundled here. A second copy of the same typeface would be 270 KB spent to own a file we
     * already have, and the only thing it would buy is certainty that Regular and [MEDIUM] came from
     * the same release of the family. JetBrains Mono's metrics have been stable across releases, so
     * that certainty is worth less than the size — but if the two ever look mismatched at a small
     * size, bundling our own Regular beside the Medium is a one-line fix and the first thing to try.
     */
    const val REGULAR: String = SkijaFonts.DEFAULT

    /** Medium: bundled here, because BlackSkija ships Regular only. */
    const val MEDIUM = "sighteaddons-mono-medium"

    private const val MEDIUM_TTF = "/assets/sighteaddons/font/jetbrainsmono-medium.ttf"

    @Volatile
    private var registered = false

    /**
     * Registers [MEDIUM] on first use. Safe to call from anywhere inside a draw pass, and only there.
     *
     * Not synchronised, because everything that reaches here is already on the render thread — the
     * drawing API throws otherwise, at the call site. A `@Volatile` flag rather than `by lazy` so the
     * common path is a field read and not a lazy-initialiser check with its own monitor.
     *
     * A failure is swallowed rather than propagated: the font is a refinement, and a screen rendered
     * entirely in Regular is a far better outcome than a screen that throws out of its first draw.
     * The failure would have to be a corrupt or missing resource in our own jar, which no player can
     * cause and no launch can recover from, so retrying it each frame would only mean failing at the
     * same place many times a second.
     */
    fun ensure() {
        if (registered) return
        registered = true
        runCatching { SkijaFonts.register(MEDIUM, MEDIUM_TTF) }
    }
}
