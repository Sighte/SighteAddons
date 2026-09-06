package sighteaddons.ui.screens

import sighteaddons.Cell
import sighteaddons.Door
import sighteaddons.DoorType
import sighteaddons.RoomState
import sighteaddons.RoomType
import sighteaddons.ui.theme.MapColours
import sighteaddons.ui.theme.Tokens

/**
 * Where a floor's cells land on a canvas of a given width — the arithmetic behind the solo tab's map,
 * kept away from [sighteaddons.ui.sk.Sk] so it can be reasoned about with numbers.
 *
 * A cell is a square of [cell] pixels; a room is its cells drawn with an [inset] on every side, plus a
 * bridge over the inset wherever two of its cells touch, which is exactly how the item map draws a
 * room that spans segments — and why an L-shaped room needs no special case here, where BlackAddons'
 * renderer has one. Doors are short bars across the inset between two cells.
 *
 * All positions are relative to the canvas's own top-left; the caller adds where the card is.
 */
internal class SoloMapLayout(val cols: Int, val rows: Int, val cell: Int) {

    val canvasW: Int = cols * cell + PAD * 2
    val canvasH: Int = rows * cell + PAD * 2

    /** The air between a room and its cell's edge — the gap the item map leaves between rooms. */
    val inset: Int = maxOf(1, cell / 8)

    class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
    }

    /** A horizontal run of cells in one row: from [gx0] to [gx1] inclusive. */
    data class Run(val gz: Int, val gx0: Int, val gx1: Int)

    private fun x(gx: Int) = PAD + gx * cell
    private fun y(gz: Int) = PAD + gz * cell

    fun rect(run: Run): Rect = Rect(
        (x(run.gx0) + inset).toFloat(), (y(run.gz) + inset).toFloat(),
        ((run.gx1 - run.gx0 + 1) * cell - 2 * inset).toFloat(), (cell - 2 * inset).toFloat(),
    )

    /** The fill over the inset between a cell and the one under it, both in the same room. */
    fun bridge(upper: Cell): Rect = Rect(
        (x(upper.gx) + inset).toFloat(), (y(upper.gz) + cell - inset).toFloat(),
        (cell - 2 * inset).toFloat(), (2 * inset).toFloat(),
    )

    /** The fill over the corner where four same-room cells meet; [upperLeft] is the one nearest the origin. */
    fun corner(upperLeft: Cell): Rect = Rect(
        (x(upperLeft.gx) + cell - inset).toFloat(), (y(upperLeft.gz) + cell - inset).toFloat(),
        (2 * inset).toFloat(), (2 * inset).toFloat(),
    )

    /** One cell's square, inset like a room — the entrance marker and the hover target. */
    fun square(c: Cell): Rect = rect(Run(c.gz, c.gx, c.gx))

    fun doorRect(door: Door): Rect {
        val across = maxOf(2 * inset, 2)
        val along = maxOf(cell / 3, 3)
        return if (door.a.gz == door.b.gz) {
            val gx = maxOf(door.a.gx, door.b.gx)
            Rect((x(gx) - inset).toFloat(), (y(door.a.gz) + (cell - along) / 2).toFloat(), across.toFloat(), along.toFloat())
        } else {
            val gz = maxOf(door.a.gz, door.b.gz)
            Rect((x(door.a.gx) + (cell - along) / 2).toFloat(), (y(gz) - inset).toFloat(), along.toFloat(), across.toFloat())
        }
    }

    fun centre(c: Cell): Pair<Float, Float> = (x(c.gx) + cell / 2f) to (y(c.gz) + cell / 2f)

    /** The centroid of a room's cells; for an L that lands in the corner's neighbourhood, which reads fine. */
    fun centre(cells: Set<Cell>): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        for (c in cells) {
            val (cx, cy) = centre(c)
            sx += cx
            sy += cy
        }
        return (sx / cells.size) to (sy / cells.size)
    }

    /** The cell under a canvas-relative point, or null in the padding or outside. */
    fun cellAt(px: Float, py: Float): Cell? {
        val gx = Math.floorDiv((px - PAD).toInt(), cell)
        val gz = Math.floorDiv((py - PAD).toInt(), cell)
        if (px < PAD || py < PAD || gx !in 0 until cols || gz !in 0 until rows) return null
        return Cell(gx, gz)
    }

    companion object {
        const val PAD = Tokens.SPACE_8
        const val MIN_CELL = 12
        const val MAX_CELL = 26

        /** The narrowest column a summary card beside the canvas is worth having. */
        const val SIDE_MIN = 120

        /** As big as the width allows within the cell bounds. A floor with no cells gets the largest, so a card still has a size. */
        fun of(cols: Int, rows: Int, width: Int): SoloMapLayout {
            val cell = if (cols <= 0) MAX_CELL else ((width - 2 * PAD) / cols).coerceIn(MIN_CELL, MAX_CELL)
            return SoloMapLayout(cols.coerceAtLeast(1), rows.coerceAtLeast(1), cell)
        }

        /** Whether the summary fits beside a canvas of [canvasW] in a column [width] wide. */
        fun sideBySide(width: Int, canvasW: Int): Boolean = width - canvasW - Tokens.SPACE_16 >= SIDE_MIN

        /** Horizontal run-lengths over [cells], row by row — every room shape, no special cases. */
        fun runs(cells: Set<Cell>): List<Run> {
            val out = ArrayList<Run>()
            for (gz in cells.map { it.gz }.distinct().sorted()) {
                val xs = cells.filter { it.gz == gz }.map { it.gx }.sorted()
                var start = xs.first()
                var prev = start
                for (gx in xs.drop(1)) {
                    if (gx != prev + 1) {
                        out.add(Run(gz, start, prev))
                        start = gx
                    }
                    prev = gx
                }
                out.add(Run(gz, start, prev))
            }
            return out
        }

        /** The cells that have a same-room cell directly below them. */
        fun bridges(cells: Set<Cell>): List<Cell> = cells.filter { Cell(it.gx, it.gz + 1) in cells }

        /**
         * The cells whose right, lower and lower-right neighbours are all in the room: the four meet
         * at a corner the runs and the bridges both leave open, a dark square in the middle of every
         * 2×2. Seen on the first rendered sample, 06.09.2026.
         */
        fun corners(cells: Set<Cell>): List<Cell> = cells.filter {
            Cell(it.gx + 1, it.gz) in cells && Cell(it.gx, it.gz + 1) in cells && Cell(it.gx + 1, it.gz + 1) in cells
        }

        /** The bounding box of a room's cells as `(gx0, gz0, gx1, gz1)`, and whether the cells fill it. */
        fun box(cells: Set<Cell>): IntArray = intArrayOf(
            cells.minOf { it.gx }, cells.minOf { it.gz }, cells.maxOf { it.gx }, cells.maxOf { it.gz },
        )

        fun isRectangle(cells: Set<Cell>): Boolean {
            val b = box(cells)
            return (b[2] - b[0] + 1) * (b[3] - b[1] + 1) == cells.size
        }

        // The colours themselves live in [MapColours], inside `ui/theme/` where the rule that no colour
        // is written anywhere else can see them. These read them by the canvas's own questions.

        fun fill(type: RoomType): Int = MapColours.fill(type)

        /** The fill's opacity for a state: an unopened room is a hint, a discovered one is not yet done. */
        fun opacity(state: RoomState): Float = when (state) {
            RoomState.UNOPENED -> 0.45f
            RoomState.DISCOVERED -> 0.7f
            else -> 1f
        }

        fun check(state: RoomState): Int? = MapColours.check(state)

        fun door(type: DoorType): Int = MapColours.door(type)
    }
}
