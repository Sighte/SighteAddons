package sighteaddons.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sighteaddons.ui.theme.Contrast
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Palette
import java.io.File

/**
 * Two things here are worth a test because getting either wrong is invisible until somebody cannot
 * read the screen, or until a border silently disappears at one window width and not another.
 *
 * The contrast half is not a style check: it is the acceptance bar stated as arithmetic. It already
 * earned its place by catching the specified `text.tertiary` (`#6C7078`) failing on `surface.overlay`
 * at 3.57:1 — the palette carries a corrected value and a note saying so, and this pins it.
 */
class UiThemeTest {

    // --- Contrast ---------------------------------------------------------------------------

    /** Black on white, the definitional maximum, so the formula itself is anchored. */
    @Test
    fun `contrast ratio spans one to twenty-one`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(21.0, Contrast.ratio(black, white), 0.01)
        assertEquals(1.0, Contrast.ratio(white, white), 0.001)
    }

    /** Order must not matter — a ratio is between two colours, not from one to the other. */
    @Test
    fun `contrast ratio is symmetric`() {
        val a = Palette.DARK.textSecondary
        val b = Palette.DARK.surfaceRaised
        assertEquals(Contrast.ratio(a, b), Contrast.ratio(b, a), 1e-9)
    }

    /** A translucent white over black must measure as the grey it actually looks like. */
    @Test
    fun `compositing folds alpha into an opaque colour`() {
        val halfWhiteOnBlack = Contrast.over(0x80FFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(0xFF, (halfWhiteOnBlack ushr 24) and 0xFF, "result must be opaque")
        assertEquals(0x80, (halfWhiteOnBlack ushr 16) and 0xFF)

        // Fully transparent contributes nothing; fully opaque replaces.
        assertEquals(0xFF123456.toInt(), Contrast.over(0x00FFFFFF, 0xFF123456.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), Contrast.over(0xFFFFFFFF.toInt(), 0xFF123456.toInt()))
    }

    /**
     * Every text token, on every surface it can land on, in both themes.
     *
     * `textDisabled` is deliberately absent: WCAG exempts inactive controls, and a disabled row that
     * met the same bar as an enabled one would not read as disabled.
     */
    @Test
    fun `text meets the contrast floor on every surface in both themes`() {
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            val text = mapOf(
                "textPrimary" to palette.textPrimary,
                "textSecondary" to palette.textSecondary,
                "textTertiary" to palette.textTertiary,
            )
            val surfaces = mapOf(
                "surfaceBase" to palette.surfaceBase,
                "surfaceRaised" to palette.surfaceRaised,
                "surfaceOverlay" to palette.surfaceOverlay,
            )
            for ((textName, fg) in text) {
                for ((surfaceName, bg) in surfaces) {
                    val ratio = Contrast.ratio(fg, bg)
                    assertTrue(
                        ratio >= Contrast.AA,
                        "${palette.name}: $textName on $surfaceName is %.2f:1, below %.1f"
                            .format(ratio, Contrast.AA),
                    )
                }
            }
        }
    }

    /**
     * A hovered row is a surface too. Text sits on `surfaceRaised + surfaceHover`, and the hover wash
     * moves the backdrop toward the text in dark mode — so the worst case is the hovered one and it
     * has to be measured composited, not against the bare card.
     */
    @Test
    fun `text stays legible on a hovered and a pressed row`() {
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            for (surface in palette.surfaces) {
                for (wash in listOf(palette.surfaceHover, palette.surfaceActive)) {
                    val backdrop = Contrast.over(wash, surface)
                    for (fg in listOf(palette.textPrimary, palette.textSecondary, palette.textTertiary)) {
                        val ratio = Contrast.ratio(fg, backdrop)
                        assertTrue(
                            ratio >= Contrast.AA,
                            "${palette.name}: %.2f:1 on a washed surface".format(ratio),
                        )
                    }
                }
            }
        }
    }

    /** A chip's active state is a solid accent fill with the opposite extreme written on it. */
    @Test
    fun `accent text is legible on the accent fill`() {
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            val ratio = Contrast.ratio(palette.accentText, palette.accent)
            assertTrue(ratio >= Contrast.AA, "${palette.name}: accent pair is %.2f:1".format(ratio))
        }
    }

    /**
     * The three borders must be ordered and must all be *visible* against their surface. A border
     * that measures identically to the card it outlines is a token that does nothing.
     */
    @Test
    fun `borders are ordered and each is visible against its surface`() {
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            val subtle = Contrast.ratio(palette.borderSubtle, palette.surfaceRaised)
            val default = Contrast.ratio(palette.borderDefault, palette.surfaceRaised)
            val strong = Contrast.ratio(palette.borderStrong, palette.surfaceRaised)
            assertTrue(subtle > 1.0, "${palette.name}: borderSubtle is invisible")
            assertTrue(default > subtle, "${palette.name}: borderDefault must exceed subtle")
            assertTrue(strong > default, "${palette.name}: borderStrong must exceed default")
        }
    }

    // --- Density ----------------------------------------------------------------------------

    /**
     * The case the nominal GUI scale gets wrong.
     *
     * 1366×768 at GUI scale 4: vanilla computes `guiScaledWidth = ceil(1366/4) = 342`, so the real
     * horizontal ratio is 1366/342 = 3.9942 while the vertical one is exactly 4.0. Taking the
     * nominal 4 for both drifts 0.6 device pixels across a 420 px panel — enough to make one border
     * land on a pixel and the opposite one vanish.
     */
    @Test
    fun `device scale is derived per axis and differs from the nominal scale`() {
        Density.beginFrame(1366, 768, 342, 192)
        assertEquals(3.9942f, Density.scaleX, 0.0001f)
        assertEquals(4.0f, Density.scaleY, 0.0001f)
        assertTrue(Density.scaleX != Density.scaleY, "the two axes must be allowed to differ")
    }

    /** A minimised window reports zero, and a division there would poison every coordinate. */
    @Test
    fun `a zero-sized frame falls back to scale one instead of infinity`() {
        assertEquals(1f, Density.scaleFor(0, 0))
        assertEquals(1f, Density.scaleFor(1920, 0))
        Density.beginFrame(0, 0, 0, 0)
        assertTrue(Density.scaleX.isFinite() && Density.scaleY.isFinite())
    }

    /**
     * Snapping must land the origin exactly on a device pixel — that is the whole point — and must be
     * a no-op when it is already there, so a snap does not itself introduce a half-pixel shift.
     */
    @Test
    fun `snapping moves the origin onto a device pixel and no further`() {
        val scale = 3.9942f
        for (translation in listOf(0f, 4f, 17.5f, 123.4f, 420f)) {
            val snapped = translation + Density.snapOffset(translation, scale)
            val device = snapped * scale
            assertEquals(
                Math.round(device).toFloat(), device, 0.001f,
                "origin $translation did not land on a device pixel",
            )
        }
        assertEquals(0f, Density.snapOffset(0f, 4f), 1e-6f)
        assertEquals(0f, Density.snapOffset(2f, 4f), 1e-6f, "already aligned must not move")
    }

    /** GUI coordinates convert to whole device pixels at every scale the game offers. */
    @Test
    fun `gui coordinates round to whole device pixels at every scale`() {
        for (scale in 1..4) {
            Density.beginFrame(1920, 1080, 1920 / scale, 1080 / scale)
            val hairlineWidth = Density.deviceX(1f) - Density.deviceX(0f)
            assertTrue(hairlineWidth >= 1, "one gui pixel must be at least one device pixel")
        }
        assertEquals(1, Density.hairline, "a hairline is one physical pixel until we decide otherwise")
    }

    // --- The rule the theme package exists for ----------------------------------------------

    /**
     * No colour is written outside `ui/theme/`, and this reads the source rather than trusting it.
     *
     * The rule is the entire point of the package and it is the one rule a compiler cannot hold: an
     * `Int` is an `Int`, so a hue smuggled into a draw call is legal Kotlin and invisible to every
     * other test here. It also has a track record — the pre-redesign mod carried seventeen of them
     * across four files, and after the redesign five survived in three places for two more phases,
     * including a yellow and a red *in the gallery*, which is the screen whose job is to prove there is
     * no hue anywhere.
     *
     * Eight hex digits, because that is how a colour is written in this repository: an ARGB value
     * always carries its alpha, and the six-digit forms that remain outside this package are bit masks
     * (`and 0xFFFFFF`, `and 0xFF`). Matching six as well would flag every one of those, and a check
     * that cries wolf is a check somebody switches off.
     *
     * Comment lines are skipped — a KDoc that *discusses* `0x80000000` is documentation, not a colour —
     * and the scan asserts it can still see the palette it exempts, because a walk that quietly found
     * nothing looks exactly like a tree with nothing in it.
     */
    @Test
    fun `no argb literal is written outside the theme package`() {
        val root = sourceRoot()
        val theme = File(root, "sighteaddons/ui/theme").toPath()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 30, "only ${files.size} sources found under $root — the walk missed the tree")

        val offenders = ArrayList<String>()
        var themeFiles = 0
        var themeLiterals = 0
        for (file in files) {
            val inTheme = file.toPath().startsWith(theme)
            if (inTheme) themeFiles++
            for ((index, line) in file.readLines().withIndex()) {
                val code = code(line) ?: continue
                val hit = ARGB.find(code) ?: continue
                if (inTheme) {
                    themeLiterals++
                } else {
                    offenders.add("${root.toPath().relativize(file.toPath())}:${index + 1}  ${hit.value}")
                }
            }
        }

        assertTrue(themeFiles >= 4, "ui/theme was not reached at all — the exemption path is wrong")
        assertTrue(themeLiterals > 20, "only $themeLiterals literals matched inside ui/theme — the pattern is asleep")
        assertEquals(
            emptyList<String>(), offenders,
            "a colour outside ui/theme/ — move it into Tokens or Palette and read it from there",
        )
    }

    /** The line's code, or null when the whole line is a comment. */
    private fun code(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return null
        val comment = line.indexOf("//")
        return if (comment >= 0) line.substring(0, comment) else line
    }

    /** The project's main source root, found by walking up rather than by trusting a working directory. */
    private fun sourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("no src/main/kotlin at or above ${File("").absolutePath}")
    }

    private companion object {
        /** An eight-digit ARGB literal, and not the leading eight digits of a longer number. */
        val ARGB = Regex("0[xX][0-9a-fA-F]{8}(?![0-9a-fA-F])")
    }
}
