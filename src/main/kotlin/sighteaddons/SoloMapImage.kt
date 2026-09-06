package sighteaddons

import sighteaddons.ui.screens.SoloMapLayout
import sighteaddons.ui.theme.MapColours
import sighteaddons.ui.theme.Palette
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The floor as a picture for Discord: rooms in the map's colours, doors, and every room's name. Nothing
 * else — no route, no times, no checkmarks. That is the user's brief (06.09.2026): "einfach nur die Map,
 * mit einfach den Namen der Räume", as opposed to what the solo tab draws.
 *
 * Drawn with `java.awt` into a PNG, off-screen. The JVM's image pipeline needs no window for this, and
 * it is the one renderer in the process that can write a file — Skija draws to the screen. The
 * colours are the tab's ([MapColours], the dark [Palette]), so the picture and the panel agree about
 * what a puzzle looks like; the typeface is the bundled JetBrains Mono, falling back to the platform's
 * sans if the resource cannot be read, which costs legibility and nothing else.
 *
 * Names are wrapped inside their cell, word by word, to [MAX_LINES]; what does not fit gets an ellipsis.
 * A room over several cells is named once, in the cell nearest its centre. Cells the map showed grey
 * carry the name the chunk scan found for them, when it found one — that is a fact about the floor, not
 * about the run.
 */
object SoloMapImage {

    /** Wide enough for a nine-letter name — `Overgrown` — on one line at [NAME_SIZE] in the mono face. */
    const val CELL = 88
    const val INSET = 4
    const val PAD = 20
    const val MAX_LINES = 3
    private const val NAME_SIZE = 12f

    private val font: Font by lazy {
        try {
            SoloMapImage::class.java.getResourceAsStream("/assets/sighteaddons/font/jetbrainsmono-medium.ttf")
                ?.use { Font.createFont(Font.TRUETYPE_FONT, it) }?.deriveFont(NAME_SIZE)
                ?: Font(Font.SANS_SERIF, Font.BOLD, NAME_SIZE.toInt())
        } catch (_: Exception) {
            Font(Font.SANS_SERIF, Font.BOLD, NAME_SIZE.toInt())
        }
    }

    /** The PNG bytes. Never throws on a strange record: an empty floor is a small dark square. */
    fun render(record: SoloRuns.Record): ByteArray {
        val floor = record.layout
        val cols = floor.cols.coerceAtLeast(1)
        val rows = floor.rows.coerceAtLeast(1)
        val width = cols * CELL + PAD * 2
        val height = rows * CELL + PAD * 2
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = colour(Palette.DARK.surfaceBase)
            g.fillRect(0, 0, width, height)

            fun x(gx: Int) = PAD + gx * CELL
            fun y(gz: Int) = PAD + gz * CELL

            for (room in floor.rooms) {
                g.color = colour(MapColours.fill(room.type))
                for (run in SoloMapLayout.runs(room.cells)) {
                    g.fillRoundRect(
                        x(run.gx0) + INSET, y(run.gz) + INSET,
                        (run.gx1 - run.gx0 + 1) * CELL - 2 * INSET, CELL - 2 * INSET, 6, 6,
                    )
                }
                for (upper in SoloMapLayout.bridges(room.cells)) {
                    g.fillRect(x(upper.gx) + INSET, y(upper.gz) + CELL - INSET, CELL - 2 * INSET, 2 * INSET)
                }
                for (upperLeft in SoloMapLayout.corners(room.cells)) {
                    g.fillRect(x(upperLeft.gx) + CELL - INSET, y(upperLeft.gz) + CELL - INSET, 2 * INSET, 2 * INSET)
                }
            }
            for (door in floor.doors) {
                g.color = colour(MapColours.door(door.type))
                val along = CELL / 3
                if (door.a.gz == door.b.gz) {
                    val gx = maxOf(door.a.gx, door.b.gx)
                    g.fillRect(x(gx) - INSET, y(door.a.gz) + (CELL - along) / 2, 2 * INSET, along)
                } else {
                    val gz = maxOf(door.a.gz, door.b.gz)
                    g.fillRect(x(door.a.gx) + (CELL - along) / 2, y(gz) - INSET, along, 2 * INSET)
                }
            }

            g.font = font
            g.color = colour(Palette.DARK.textPrimary)
            val metrics = g.fontMetrics
            for (r in floor.rooms) {
                val name = nameOf(r) ?: continue
                // A rectangular room is named across its whole width, centred; an L in its corner cell.
                val (bx, by, bw) = if (SoloMapLayout.isRectangle(r.cells)) {
                    val b = SoloMapLayout.box(r.cells)
                    Triple(x(b[0]), y(b[1]), (b[2] - b[0] + 1) * CELL)
                } else {
                    val anchor = anchorOf(r.cells)
                    Triple(x(anchor.gx), y(anchor.gz), CELL)
                }
                val bh = if (SoloMapLayout.isRectangle(r.cells)) {
                    val b = SoloMapLayout.box(r.cells)
                    (b[3] - b[1] + 1) * CELL
                } else {
                    CELL
                }
                val lines = wrap(name, bw - 2 * INSET - 6) { metrics.stringWidth(it) }
                val block = lines.size * metrics.height
                var ty = by + (bh - block) / 2 + metrics.ascent
                for (line in lines) {
                    val tx = bx + (bw - metrics.stringWidth(line)) / 2
                    g.drawString(line, tx, ty)
                    ty += metrics.height
                }
            }
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** The name to print: the database's, else the type for the rooms whose type is their name. Nothing for a plain room nobody named. */
    internal fun nameOf(room: SoloRuns.Room): String? = room.name ?: when (room.type) {
        RoomType.ENTRANCE -> "Entrance"
        RoomType.BLOOD -> "Blood"
        RoomType.FAIRY -> "Fairy"
        RoomType.TRAP -> "Trap"
        RoomType.PUZZLE -> "Puzzle"
        RoomType.MINIBOSS -> "Miniboss"
        RoomType.ROOM, RoomType.UNKNOWN -> null
    }

    /** The cell nearest the centroid, so an L is named in its corner and a 2×2 in one of its cells. */
    internal fun anchorOf(cells: Set<Cell>): Cell {
        val cx = cells.map { it.gx }.average()
        val cz = cells.map { it.gz }.average()
        return cells.minByOrNull { (it.gx - cx) * (it.gx - cx) + (it.gz - cz) * (it.gz - cz) } ?: Cell(0, 0)
    }

    /** Greedy word wrap into [width] pixels, at most [MAX_LINES] lines, the last one cut with an ellipsis. */
    internal fun wrap(text: String, width: Int, measure: (String) -> Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (measure(candidate) <= width || line.isEmpty()) {
                line = candidate
            } else {
                out.add(line)
                line = word
            }
            if (out.size == MAX_LINES) break
        }
        if (out.size < MAX_LINES && line.isNotEmpty()) out.add(line)
        val cut = out.size > MAX_LINES || (out.size == MAX_LINES && line.isNotEmpty() && out.last() != line)
        val lines = out.take(MAX_LINES).toMutableList()
        for (i in lines.indices) {
            var l = lines[i]
            val needsCut = measure(l) > width || (i == lines.lastIndex && cut)
            if (needsCut) {
                while (l.isNotEmpty() && measure("$l…") > width) l = l.dropLast(1)
                lines[i] = "$l…"
            }
        }
        return lines
    }

    private fun colour(argb: Int) = Color(argb, true)
}
