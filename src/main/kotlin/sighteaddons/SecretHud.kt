package sighteaddons

/**
 * One HUD line: the secrets **this client can attribute to the local player**, for the room you are
 * standing in and for the run so far.
 *
 * Hypixel's own action bar already counts the room's secrets, and that number is the party's — a
 * rise in it says *somebody* found one. This says how many of them were yours, which is the number
 * nothing on screen has ever shown.
 *
 * **Attributed, not found, and the wording on screen has to keep saying so.** [TrackedRoom.ownSecrets]
 * is credited by [SecretTracker] on exactly two signals — a counter rise inside `OWN_WINDOW` of your
 * own block interaction, or a wither-essence chat line naming you — so a secret walked over is
 * credited to nobody and this readout under-counts. That is measured, it is `ownsecrets-001`'s whole
 * subject, and it is deliberately **not** compensated for here: this displays what attribution
 * answers and nothing else. When `ownsecrets-001` makes the number underneath more nearly complete,
 * "your secrets" is still exactly what this line was saying, which is why it is labelled that way
 * rather than "found" or "collected".
 *
 * **Since `secretpoints-001` that same attribution also pays ClearPoints** — a quarter per secret,
 * credited by [ContributionTracker.onOwnSecret] on the line that increments `ownSecrets`. So this
 * readout and the standings below it are two views of one signal by construction, and widening
 * attribution (`ownsecrets-001`) now moves both. It could not move only one without them
 * contradicting each other on screen, which is why they share the counter rather than each
 * deciding what a secret of yours is.
 *
 * Pure on purpose. The dev client cannot reach Hypixel, so the wiring — that [SighteAddons.renderHud]
 * calls this with the room the player is in, every frame, while the game is running — is not
 * observable in this repository. What *is* observable is what the line says for a given tracker
 * state, and that is this function, driven directly by `SecretHudTest`.
 */
internal object SecretHud {
    /** The room's total is not known: no room under the player, or no database entry for it. */
    private const val UNKNOWN = "--/--"

    /**
     * [current] is the room the player is standing in, or null; [rooms] is every room this run has
     * visited — [ContributionTracker.visitedRooms], which is where the run total comes from and is
     * already deduplicated by room rather than by grid cell.
     *
     * [current] is one of [rooms] whenever it is not null (both come out of the same map), so the
     * room half is a subset of the run half and never an addition to it.
     */
    fun line(current: TrackedRoom?, rooms: List<TrackedRoom>): String =
        "Your secrets  ${room(current)} room  ·  ${rooms.sumOf { it.ownSecrets }} run"

    /**
     * `own/total` against the room's total **from the room database**, not against
     * [TrackedRoom.secretsFound] — the question is how much of the room is yours, and the party's
     * progress through it is already the action bar's job.
     *
     * A null total is [UNKNOWN]; a total of zero is `0/0` and not [UNKNOWN]. The two are different
     * facts — "this room has no secrets" against "we do not know this room" — and a readout that
     * spelled them the same way would make an unidentified room look like a solved one.
     */
    private fun room(current: TrackedRoom?): String {
        val total = current?.info?.secrets ?: return UNKNOWN
        return "${current.ownSecrets}/$total"
    }
}
