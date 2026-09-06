package sighteaddons.ui.theme

import sighteaddons.DoorType
import sighteaddons.RoomState
import sighteaddons.RoomType

/**
 * The dungeon map's own vocabulary, for the solo tab's canvas — and the one documented exception to
 * the two-hue rule in [Palette].
 *
 * Hypixel colours a room by its type and a checkmark by what it means, and a reader who knows the item
 * map knows that a purple square is a puzzle before reading anything. Rendering those in `accent` and
 * `positive` would either break the palette's rule (a green check is not the player's own best) or
 * throw the recognition away. So the canvas keeps Hypixel's meanings in muted versions of Hypixel's
 * hues, confined to the canvas: nothing outside it may borrow these, and the route drawn over them is
 * still the theme's `accent`, because the route is the thing that is *selected* — this run, this
 * player.
 *
 * Theme-independent on purpose. A room type is the same fact in the light theme, and the fills are
 * mid-tones that sit on either surface.
 */
internal object MapColours {

    fun fill(type: RoomType): Int = when (type) {
        RoomType.ENTRANCE -> 0xFF4E9A5C.toInt()
        RoomType.ROOM -> 0xFF7A6652.toInt()
        RoomType.PUZZLE -> 0xFF8C5FA8.toInt()
        RoomType.TRAP -> 0xFFC27A3A.toInt()
        RoomType.MINIBOSS -> 0xFFC9B23D.toInt()
        RoomType.FAIRY -> 0xFFD68FB0.toInt()
        RoomType.BLOOD -> 0xFFB0403A.toInt()
        RoomType.UNKNOWN -> 0xFF55585C.toInt()
    }

    /** The checkmark's colour for a state, or null for a room that has none. White, green, red — Hypixel's. */
    fun check(state: RoomState): Int? = when (state) {
        RoomState.CLEARED -> 0xFFF2F2F2.toInt()
        RoomState.GREEN -> 0xFF5CD65C.toInt()
        RoomState.FAILED -> 0xFFE04B4B.toInt()
        else -> null
    }

    fun door(type: DoorType): Int = when (type) {
        DoorType.NORMAL -> 0xFFB8AC9C.toInt()
        DoorType.WITHER -> 0xFF15161A.toInt()
        DoorType.BLOOD -> 0xFFB0403A.toInt()
    }
}
