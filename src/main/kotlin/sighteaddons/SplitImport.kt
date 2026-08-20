package sighteaddons

/**
 * Folding a local Odin install's split records into this mod's, from wherever a hand asks for it.
 *
 * ### Why this is a file and not a method on either neighbour
 *
 * There are two ways in now — `/sa import` and the row on the `/sa` debug page — and the import is
 * three steps: read the neighbour's config, save ours if anything moved, say what happened. Only the
 * first of those belongs to [SplitPbs], and the other two cannot go there:
 *
 *  - **[SplitPbs] does not save, on purpose.** Its own KDoc says so about [SplitPbs.record], because a
 *    run's final chat line produces three records and the caller is what knows to write the file once
 *    afterwards. A sibling that saved would make that rule a thing you have to remember rather than a
 *    thing the object does.
 *  - **[SplitPbs] holds no Minecraft types**, which is what lets `SplitPbsTest` drive [SplitPbs.merge]
 *    with the real bytes of a real Odin config. [Chat] returns a `Component`, so announcing from in
 *    there would put `net.minecraft` into the one record store that has managed without it.
 *
 * And it cannot live on [SettingsScreen] either, which is where it used to: a command routing through
 * a screen to import a file is a command that needs a screen. So the seam between the store, the
 * config file and the chat line gets the one place where the two entry points meet — and there is one
 * wording rather than two that drift.
 *
 * ### Reading another mod's config, and only when a hand asks
 *
 * Never on startup. [SplitPbs.importFromOdin] argues that in full: a mod that went through a
 * neighbour's files on every launch would be doing something nobody asked for, and the answer cannot
 * change while playing.
 */
internal object SplitImport {

    /** Where Odin keeps it, as the message prints it. The path itself is [SplitPbs]' to know. */
    private const val ODIN_CONFIG = "config/odin/odin-config.json"

    /**
     * Imports, saves if anything moved, and says what happened.
     *
     * **The file is named when there was nothing to read, and only then.** The debug row carries the
     * path in its own value column, so it never needed saying; a command has no such context, and
     * "nothing to import" without a path is a message that cannot be acted on. The other two cases
     * already say everything: a count, or that the records were here already.
     *
     * **Saved only when something changed.** The merge is a minimum and therefore idempotent, so a
     * second run writes nothing — which is exactly what stops somebody wondering whether they have
     * already done it.
     *
     * The success line points at the table, because the question straight after "83 records imported"
     * is "where are they" — and until this week there was nowhere to send anybody.
     */
    fun run() {
        val result = SplitPbs.importFromOdin()
        if (result.changed > 0) Config.save()
        Chat.say(
            when {
                !result.found -> Chat.label("no odin config at ").append(Chat.meta(ODIN_CONFIG))
                result.changed == 0 -> Chat.label("odin's split records were already in here")
                else -> Chat.value(result.changed.toString())
                    .append(Chat.label(" split records imported from odin"))
                    .append(Chat.meta(Chat.FIELD))
                    .append(Chat.meta("/sa splits"))
            },
        )
    }
}
