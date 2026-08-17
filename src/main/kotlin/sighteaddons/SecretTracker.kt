package sighteaddons

import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SkullBlockEntity

/**
 * Counts secrets per room, and how many of them were provably yours.
 *
 * Two independent signals are combined:
 *
 * 1. Hypixel puts the **current room's** secret progress in the action bar (`6/10 Secrets`). That is
 *    a per-room number, not a per-player one — a rise only says *somebody* in that room found one.
 * 2. The client sees its **own** actions and no one else's. Right-clicking a chest, lever or one of
 *    the two secret skull types is visible locally; so is picking an item up ([onItemPickup]) and
 *    swinging at a bat (`AttackEntityCallback` in [init]). Those are the four kinds of secret there
 *    are, and the last two arrived with `ownsecrets-001` after live play showed neither counted.
 *
 * A rise that coincides with your own action is yours. A rise without one belongs to somebody
 * else, regardless of who is standing where — which is stronger than guessing from presence alone,
 * and works even with the whole party digging through the same room.
 *
 * Secret coordinates are deliberately not needed for this; only the count from the action bar and
 * the room's expected total from the room database, which guards against attributing the number to
 * the wrong room.
 */
object SecretTracker {
    /** The action bar carries several stats, so this matches inside it rather than the whole string. */
    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")

    /**
     * The action bar still carries legacy `§` colour codes, and one sits directly in front of the
     * secret count: `§70/8 Secrets` is grey plus `0/8`, not seventy out of eight. `\d+` swallows the
     * code's digit, so every count came out prefixed with a 7 and each room's first reading looked
     * like a jump of seventy secrets. Stripped before matching rather than worked around inside
     * [SECRETS], because the same codes sit around every other number the bar carries.
     */
    private val FORMATTING = Regex("§.")

    /** Item names arrive with the odd doubled space; one shape per name keeps [SECRET_ITEMS] exact. */
    private val WHITESPACE = Regex("""\s+""")

    /** Ticks the counter may lag behind your click before the two stop being considered related. */
    private const val OWN_WINDOW = 40

    /** 10 s without a further secret ends a started run — as a discard, never as a slow time. */
    private const val ABANDON_TICKS = 200

    /** A repeat of one click within this many ticks is the same interaction, not a second find. */
    private const val REPEAT_WINDOW = 5

    /** Blocks around the player. Melee reach and a little, which is what makes a dying bat yours. */
    private const val BAT_RADIUS = 6.0

    /** Bats already seen dying this run, so a twenty-tick death animation is one signal. */
    private val dyingBats = HashSet<Int>()

    /** Inventory slots watched for [tickInventory]. The main inventory; armour is not a secret. */
    private const val SLOTS = 36

    private val slotItems = arrayOfNulls<Item>(SLOTS)
    private val slotCounts = IntArray(SLOTS)

    /** Every item name in the inventory and its count, as of the last change. */
    private var lastItems: Map<String, Int> = emptyMap()

    /** Whether a baseline has been taken. Until it has, everything already carried looks new. */
    private var primed = false

    /** Entity classes swung at this run, so [onAttack]'s diagnostic is one line per kind. */
    private val attackedKinds = HashSet<String>()

    /** No own action seen yet this run. Compared, never subtracted from — see [isOwn]. */
    private const val NO_INTERACTION = Int.MIN_VALUE

    /** The three things that can arm [lastOwnInteraction]. Log vocabulary, not behaviour. */
    private const val CLICK = "click"
    private const val PICKUP = "pickup"
    private const val BAT = "bat"

    /**
     * Ticks a chat attribution stays attached to the next counter rise.
     *
     * Half [OWN_WINDOW], and the asymmetry is the point. [OWN_WINDOW] is wide because it spans a
     * *click* and the server's later report of what the click did — two different events with real
     * lag between them. A chat line and the action bar update are two renderings of one server-side
     * event, so one second is already generous, and every tick of it is a tick in which an unrelated
     * chest could rise the counter first and steal the attribution. Narrow is the safe direction: a
     * chat fact that expires costs one secret falling back to the inference that was there before.
     */
    private const val CHAT_WINDOW = 20

    /** Skull profile ids Hypixel uses for secret skulls, as identified by Odin and NoammAddons. */
    private val SECRET_SKULLS = setOf(
        "2865274b-3097-394e-8149-ec629c72d850", // wither essence
        "fed95410-aba1-39df-9b95-1d4f361eb66e", // redstone key
    )

    /**
     * Item names Hypixel only ever hands out for a dungeon secret, lower-cased.
     *
     * **This is the whole false-positive defence and it is a whitelist on purpose.** A dungeon floor
     * is full of item pickups; a rule that armed the window on *any* of them would credit the local
     * player for a teammate's secret every time the two happened within [OWN_WINDOW] of each other,
     * and a wrong record is permanent where a missing one is not. So an item earns the signal only
     * by being one whose sole source is a secret — none of these is a mob drop, a boss reward or a
     * chest reward.
     *
     * What it costs in each direction, stated rather than implied:
     * - **Missed credits:** any secret item not in this list, and any Hypixel renames or additions.
     *   That is the same under-count as today, so the list can only improve the number, never worsen
     *   it. [UNMATCHED] is how the list gets corrected — see there.
     * - **Wrong credits:** one of these picked up off the floor after a *teammate* dropped it to
     *   share it, with somebody else's secret landing in the same room within the next
     *   [OWN_WINDOW] ticks. The window only reads backwards, so the common sequence — they open a
     *   chest, the counter rises, then they drop you the key — cannot mis-credit that rise. It takes
     *   a second secret inside two seconds of the pickup, which is the exposure this rule buys and
     *   it is the reason the list is not widened by prefix or substring matching.
     *
     * **Every string here is a hypothesis.** Nothing on disk confirms one: no build has ever looked
     * at an item name, so the twenty session logs cannot contain the answer.
     */
    private val SECRET_ITEMS = setOf(
        "architect's first draft",
        "candycomb",
        "decoy",
        "defuse kit",
        "dungeon chest key",
        // Five spellings of one potion, all of them Odin's. Hypixel has written the strength as a
        // numeral and as a roman, before and after the noun, and a name this list gets wrong is a
        // secret that silently stops counting rather than a visible failure.
        "healing 8 splash potion",
        "healing potion 8 splash potion",
        "healing potion viii splash potion",
        "healing viii splash potion",
        "health potion viii splash potion",
        "inflatable jerry",
        "revive stone",
        "secret dye",
        "spirit leap",
        "superboom tnt",
        "training weights",
        "trap",
        "treasure talisman",
    )

    /** Distinct item names seen this run that [SECRET_ITEMS] did not match, so each is logged once. */
    private val UNMATCHED = mutableSetOf<String>()

    /**
     * Ceiling on [UNMATCHED], because it is fed by every pickup in a dungeon and the debug log is a
     * file on the user's disk. The vocabulary it exists to reveal is far smaller than this.
     */
    private const val UNMATCHED_CAP = 32

    private var lastOwnInteraction = NO_INTERACTION

    /**
     * Which signal set [lastOwnInteraction] — `click` or `pickup`. Carried for the debug log only:
     * it decides nothing, and a real floor needs it to say whether the pickup signal ever fires.
     */
    private var lastOwnSource = CLICK

    /**
     * The most recent secret Hypixel named a finder for, and whether that finder was us.
     *
     * Only wither-essence secrets ever set this — see [ChatEvents.Event.SecretFound] for why that is
     * the only one there is. Held rather than applied on arrival, because the room's counter is what
     * decides a secret *happened*; this only decides whose it was.
     */
    private var chatSecretMine = false
    private var chatSecretAt = NO_INTERACTION

    /** Only suppresses duplicate log lines; unlike [lastOwnInteraction] it survives a credit. */
    private var lastLoggedPos: BlockPos? = null
    private var lastLoggedTick = NO_INTERACTION

    /** The same suppression for bats, which take more than one hit and are hit more than once. */
    private var lastBatId: Int? = null
    private var lastBatTick = NO_INTERACTION

    fun init() {
        UseBlockCallback.EVENT.register { _, level, _, hit ->
            // Read-only observer: always PASS so the interaction itself is untouched.
            if (DungeonSession.calibrated) {
                val type = secretTypeAt(level, hit.blockPos)
                if (type != null) {
                    val pos = hit.blockPos.immutable()
                    lastOwnInteraction = DungeonSession.runTicks
                    lastOwnSource = CLICK
                    // One right click arrives more than once — main hand and off hand both fire —
                    // so the raw stream showed every chest two to four times over.
                    if (!isRepeat(pos)) {
                        lastLoggedPos = pos
                        lastLoggedTick = DungeonSession.runTicks
                        DebugLog.event("own_interaction", "type" to type, "pos" to pos)
                    }
                }
            }
            InteractionResult.PASS
        }
        // The other secret no block interaction can see: a bat you kill. Same read-only PASS, same
        // window, and reported by the user in the same session as the pickup.
        //
        // The client cannot see who dealt a killing blow — only its own swing — so this is the exact
        // analogue of the right-click above, one layer over: it says the local player acted on the
        // thing, and Hypixel's counter still decides that a secret happened. Narrowed to [Bat] and
        // nothing else because a dungeon floor is killed through end to end; zombies and skeletons
        // are never secrets and must never arm this.
        AttackEntityCallback.EVENT.register { _, _, _, entity, _ ->
            if (DungeonSession.calibrated) onAttack(entity)
            InteractionResult.PASS
        }
    }

    /**
     * Any entity the local player swung at. [Bat] arms the window; everything else is a diagnostic.
     *
     * The diagnostic exists because `own_bat` never firing has two very different explanations and
     * this repository cannot tell them apart: either `AttackEntityCallback` is not being delivered at
     * all in 26.1.2, or it is and Hypixel's secret bats are not the class this checks for. One line
     * per entity class per run separates them — `own_attack` with nothing but `zombie` in it says the
     * callback works and the bats are something else; no `own_attack` at all says the callback is
     * dead and the bat check was never the problem.
     *
     * The class's simple name rather than the registry id, because that is the thing being doubted:
     * `entity is Bat` is a class check, so the answer has to be about the class.
     */
    private fun onAttack(entity: Entity) {
        if (entity is Bat) onBatHit(entity.id)
        // Once per class per run. A floor is thousands of swings and this is a question about kinds.
        val kind = entity.javaClass.simpleName
        if (attackedKinds.add(kind)) DebugLog.event("own_attack", "kind" to kind, "bat" to (entity is Bat))
    }

    /**
     * Nearby bats that have started dying. Called once per tick, from the clear phase only.
     *
     * **The death, not the removal, and the difference is the whole of why bats never counted.**
     * 0.16.0-dev11 watched `ClientEntityEvents.ENTITY_UNLOAD` and it failed twice over, both visible
     * in one floor's log:
     *
     * - **It is not a kill.** 63 `own_bat` events on that floor landed on three ticks — 36 at once on
     *   tick 13, 26 at once on tick 827, one at 1334. That is the client dropping entities as chunks
     *   come and go, not a party killing 36 bats in a twentieth of a second. The signal was noise
     *   that armed the window in bursts, and that it never mis-credited anybody was luck.
     * - **It arrives too late.** A killed bat plays out its death animation before the client removes
     *   it, while Hypixel's counter moves at the kill. The one real case in that log is a rise at tick
     *   1316 attributed to nobody and the bat leaving the world at 1334 — eighteen ticks *after* the
     *   secret it was responsible for. [isOwn] only ever looks backwards, correctly, so an arming
     *   event that lands after its own rise can never claim it.
     *
     * Health reaching zero happens on the tick the bat dies, ahead of the counter, which is the
     * ordering every other signal here already has. An unloading entity is not scanned at all — it is
     * gone from the level before this looks — so the burst problem cannot come back either.
     *
     * Bounded by [BAT_RADIUS] around the player rather than scanning the level: a box query is what
     * makes this cheap enough to run every tick, and the radius is the same "plausibly yours" guard
     * the removal version had. It is still the weakest signal in this file — a teammate killing a bat
     * beside you arms your window — and it is still only an arming event, with Hypixel's counter
     * deciding whether anything is credited. [SecretAudit] is what will say if that goes wrong: an
     * over-credit shows up as `too many` in every run's log.
     */
    fun tickBats(client: Minecraft) {
        val player = client.player ?: return
        val level = client.level ?: return
        for (bat in level.getEntitiesOfClass(Bat::class.java, player.boundingBox.inflate(BAT_RADIUS))) {
            if (!bat.isDeadOrDying) continue
            // A death animation is twenty ticks long and this runs on every one of them; the set is
            // what makes it one signal per bat rather than twenty.
            if (!dyingBats.add(bat.id)) continue
            lastOwnInteraction = DungeonSession.runTicks
            lastOwnSource = BAT
            lastBatId = bat.id
            lastBatTick = DungeonSession.runTicks
            DebugLog.event("own_bat", "id" to bat.id, "at" to lastOwnInteraction, "by" to "death")
        }
    }

    /**
     * The local player swung at a secret bat. See the registration in [init] for why an attack is
     * the only observation available.
     *
     * **Melee only, and that is a real hole rather than an oversight.** A bat killed with a bow, a
     * wand or any area-of-effect weapon never reaches [AttackEntityCallback], so it stays
     * unattributed exactly as it is today. The hole is in the direction this feature is required to
     * err in: it costs a credit rather than inventing one, and `attributedBy: bat` never appearing
     * on a floor where bats were shot is what it looks like.
     */
    private fun onBatHit(entityId: Int) {
        lastOwnInteraction = DungeonSession.runTicks
        lastOwnSource = BAT
        // A bat takes more than one swing; the log wants the bat, not the flurry. Re-arming the
        // window on every hit is deliberate and is not deduplicated — it is the last hit that the
        // counter rise follows.
        if (!isRepeatBat(entityId)) {
            lastBatId = entityId
            lastBatTick = DungeonSession.runTicks
            DebugLog.event("own_bat", "id" to entityId, "at" to lastOwnInteraction)
        }
    }

    fun reset() {
        lastOwnInteraction = NO_INTERACTION
        lastOwnSource = CLICK
        lastLoggedPos = null
        lastLoggedTick = NO_INTERACTION
        lastBatId = null
        lastBatTick = NO_INTERACTION
        chatSecretMine = false
        chatSecretAt = NO_INTERACTION
        UNMATCHED.clear()
        // Per run, so the diagnostic answers "what did this floor swing at" rather than fading out
        // after the first dungeon of a session.
        attackedKinds.clear()
        // Entity ids are per world and a run is a server transfer, so carrying these across would
        // silence a bat in the next dungeon that happens to reuse an id.
        dyingBats.clear()
        // The inventory baseline goes with the run. Kept across one, the first floor's leftover
        // Superboom TNT would read as the next floor's first secret.
        slotItems.fill(null)
        slotCounts.fill(0)
        lastItems = emptyMap()
        primed = false
    }

    /**
     * The local player just collected an item stack called [rawName].
     *
     * **This is the third attribution signal, and it exists because the first two cannot see the
     * commonest secret there is.** A secret you walk over raises Hypixel's room counter with no
     * block interaction and no chat line, so [onActionBar] had nothing to attribute it by and
     * credited it to somebody else by default. Reported from live play on 2026-08-16: the readout
     * does not move when the secret is an item you pick up. Measured over the six single-member
     * sessions on disk — where every secret is the local player's by construction — 37 of 117 were
     * missed, and 26 of those 37 have no `own_interaction` within 80 ticks of them at all.
     *
     * It feeds [lastOwnInteraction], the same window a click feeds, rather than adding a second
     * mechanism. That is deliberate on both halves:
     * - The counter still decides that a secret *happened*; this only decides whose it was. A
     *   pickup on its own credits nothing, and a pickup that no rise follows expires unspent.
     * - The credit consumes the signal exactly as a click's does, so one pickup can never pay for
     *   two secrets.
     *
     * [OWN_WINDOW] is not widened for it, though a pickup and the rise it causes are two renderings
     * of one server-side event and would tolerate a much narrower one. Of the 37 missed secrets
     * above only 6 sit in the 41–80 tick band, so widening buys almost nothing and costs
     * false-positive exposure in every party room.
     *
     * Reads the clock rather than taking it, as the [UseBlockCallback] handler in [init] does: the
     * decision this is testable at is [secretItem], which is pure.
     */
    fun onItemPickup(rawName: String) {
        if (!DungeonSession.calibrated) return
        val item = secretItem(rawName)
        if (item == null) {
            if (noteUnmatched(rawName)) DebugLog.event("pickup_unmatched", "item" to strip(rawName))
            return
        }
        lastOwnInteraction = DungeonSession.runTicks
        lastOwnSource = PICKUP
        // Logged on arrival rather than only where it is spent, for the reason `own_interaction` is:
        // a real floor then says whether the signal fires at all, separately from whether a rise
        // followed it. Zero of these in a floor with item secrets means the packet never reached
        // the mixin; plenty of these and no `attributedBy: pickup` means the window is wrong.
        DebugLog.event("own_pickup", "item" to item, "at" to lastOwnInteraction)
    }

    /**
     * A secret item that stopped existing next to the local player, with nobody named as taking it.
     *
     * **The route the take-item packet does not cover, and the one the solo M1 of 2026-08-17 20:51
     * went down.** Six secrets in one room, party of one, and the first arrived with no signal at
     * all — no `own_pickup`, no `pickup_unmatched`, not even the `pickup_unresolved` added to report
     * a packet whose entity could not be named. There was no packet. Hypixel removes the entity
     * instead, and Odin's dispatcher listens for exactly that as its second item route.
     *
     * **Weaker than [onItemPickup] and guarded differently.** That one carries a collector id, so it
     * is proof; this carries a list of ids and no reason, so the *name* stands in for the proof — only
     * an item whose sole source is a secret gets through, and only from within six blocks. It still
     * only arms the window. A teammate taking a Spirit Leap beside you can mis-arm it, and
     * [SecretAudit] is what would say so, as an over-credit, in the run's own log.
     *
     * Silent for anything off the whitelist, unlike [onItemPickup]: every item that despawns or that
     * anybody picks up near you comes through here, so logging the misses would bury the ones worth
     * reading inside one floor.
     */
    fun onItemVanished(rawName: String) {
        if (!DungeonSession.calibrated) return
        val item = secretItem(rawName) ?: return
        lastOwnInteraction = DungeonSession.runTicks
        lastOwnSource = PICKUP
        DebugLog.event("own_vanished", "item" to item, "at" to lastOwnInteraction)
    }

    /**
     * A secret item that appeared in the inventory without any item entity being collected.
     *
     * **The signal the pickup packet cannot carry, measured on the solo M1 of 2026-08-17 20:51.**
     * Six secrets in the Pirate room, party of one, so every one of them was the local player's. Five
     * were credited — one bat, four clicks — and the first, at tick 303, had no signal of any kind in
     * front of it: no click, no bat, no `own_pickup`, no `pickup_unmatched`, and not even the
     * `pickup_unresolved` dev13 added. The mixin applied cleanly, so the conclusion is the one that
     * silence leaves: `ClientboundTakeItemEntityPacket` was never sent, because nothing was ever
     * collected. Hypixel put the item straight into the inventory, and an item that never exists in
     * the world cannot be seen being picked up.
     *
     * Whitelisted and silent otherwise, unlike [onItemPickup]. That path is told "you collected an
     * item entity", which is rare and specific enough that an unrecognised name is worth logging.
     * This one sees every single thing the server ever puts in the inventory, so a `pickup_unmatched`
     * here would fill its own cap with loot inside one floor and hide the names worth reading.
     */
    fun onItemGiven(rawName: String) {
        if (!DungeonSession.calibrated) return
        val item = secretItem(rawName) ?: return
        lastOwnInteraction = DungeonSession.runTicks
        lastOwnSource = PICKUP
        DebugLog.event("own_given", "item" to item, "at" to lastOwnInteraction)
    }

    /**
     * Watches the inventory for [onItemGiven]. Called once per tick from the clear phase.
     *
     * Two stages, because the cheap question answers itself almost every tick. Stage one compares
     * each slot's item identity and count against the last tick — no allocation, no name resolution,
     * and false on the overwhelming majority of ticks. Only when something actually moved does stage
     * two resolve names, and only for the whitelist.
     *
     * **Totals per item, not per slot.** A slot whose count went up is not a new item — dragging a
     * Spirit Leap from one slot to another makes the destination grow, and crediting that would arm
     * the window every time a player tidies their hotbar. The sum across the inventory is what only
     * rises when something arrived.
     *
     * [primed] is why the first pass after a reset credits nothing: at that moment every item in the
     * inventory looks new, including the Superboom TNT that has been sitting there since the hub.
     */
    fun tickInventory(client: Minecraft) {
        val player = client.player ?: return
        val items = player.inventory.nonEquipmentItems
        if (!slotsChanged(items)) return

        // Every item, not only the whitelisted ones, because the whitelist is the thing in doubt.
        // The solo M1 of 2026-08-17 21:02 lost three secrets in rooms where nothing was clicked and
        // nothing was collected, and this path stayed silent through all three — by design, since it
        // only ever spoke for names it already knew. A list that is missing a name and a path that
        // says nothing about names it does not know cannot be told apart from the outside.
        val totals = HashMap<String, Int>()
        for (stack in items) {
            if (stack.isEmpty) continue
            val name = strip(stack.hoverName.string).lowercase()
            totals[name] = (totals[name] ?: 0) + stack.count
        }
        if (primed) {
            for ((name, count) in totals) {
                if (count <= (lastItems[name] ?: 0)) continue
                if (name in SECRET_ITEMS) {
                    onItemGiven(name)
                } else if (noteUnmatched(name)) {
                    // Capped and deduped by name, the same way an unrecognised pickup is. A floor
                    // hands over maybe a dozen distinct things, so this is a short list of exactly
                    // the vocabulary [SECRET_ITEMS] is being guessed against.
                    DebugLog.event("item_gained", "item" to name, "at" to DungeonSession.runTicks)
                }
            }
        }
        lastItems = totals
        primed = true
    }

    /** Whether any slot's item or count differs from the last look. Updates the snapshot as it goes. */
    private fun slotsChanged(items: List<ItemStack>): Boolean {
        var changed = false
        for (i in items.indices) {
            if (i >= SLOTS) break
            val stack = items[i]
            val item = if (stack.isEmpty) null else stack.item
            val count = if (stack.isEmpty) 0 else stack.count
            if (item !== slotItems[i] || count != slotCounts[i]) {
                changed = true
                slotItems[i] = item
                slotCounts[i] = count
            }
        }
        return changed
    }

    /**
     * The server said the local player collected something the client cannot name. [what] is the
     * entity's class, or `absent` when the client never had it.
     *
     * **The gap that made an item secret disappear without leaving a mark.** A floor that collects
     * one and logs neither [onItemPickup]'s hit nor its miss has told nobody anything: the mixin
     * could have failed to apply, the packet could never have arrived, or it arrived about an entity
     * this client had already dropped. The first two are still read from the *absence* of events; this
     * separates out the third, which is the one Hypixel is most likely to produce — an item spawned
     * and collected inside a tick is never loaded here to be named.
     *
     * Arms nothing, and that is not caution for its own sake: without a name there is nothing to hold
     * against [SECRET_ITEMS], and "the player picked up something" is precisely the rule that
     * whitelist exists to refuse. Counted rather than logged per event, because if this is the answer
     * it will happen for every item on the floor.
     */
    fun onPickupUnresolved(what: String) {
        if (!DungeonSession.calibrated) return
        if (noteUnmatched("unresolved:$what")) {
            DebugLog.event("pickup_unresolved", "entity" to what, "at" to DungeonSession.runTicks)
        }
    }

    /**
     * Hypixel named the finder of a wither-essence secret, at run tick [at]. [mine] is that name
     * resolved against the local player — the resolution happens at the call site, which is the only
     * place that knows what the local player is called.
     *
     * Takes [at] rather than reading the clock, for the reason [ContributionTracker.onDeath] does.
     */
    fun onChatSecret(mine: Boolean, at: Int) {
        chatSecretMine = mine
        chatSecretAt = at
        // Logged on arrival and not only where it is used, so a real floor shows both ticks and
        // settles the one thing this repository cannot: whether the chat line reaches the client
        // before the action bar update it is meant to attribute. If it does not, every one of these
        // will be followed by a `secret` carrying `attributedBy: click`.
        //
        // And the window is NOT the fix for that, however much it looks like one. [chatAttribution]
        // reads forward from a line that has already landed, so a line that arrives late leaves
        // nothing to widen toward — the credit would have to be deferred until the chat for that tick
        // has been seen, which is a different change to a different place. Worse, a late line is not
        // inert while it sits there: it stays valid for [CHAT_WINDOW] ticks and can be spent on the
        // *next* secret taken inside that window, so the failure mis-attributes rather than merely
        // missing. Read `attributedBy` on a real floor before believing either half of this.
        DebugLog.event("chat_secret", "mine" to mine, "at" to at)
    }

    private fun isRepeat(pos: BlockPos) = pos == lastLoggedPos &&
        lastLoggedTick != NO_INTERACTION && DungeonSession.runTicks - lastLoggedTick <= REPEAT_WINDOW

    /** [isRepeat] for a bat: same sentinel guard, because subtracting [NO_INTERACTION] overflows. */
    private fun isRepeatBat(id: Int) = id == lastBatId &&
        lastBatTick != NO_INTERACTION && DungeonSession.runTicks - lastBatTick <= REPEAT_WINDOW

    /**
     * Called for every action bar update while in a dungeon.
     *
     * [self] is the local player's Minecraft name, and it is here for one reason: a secret credited
     * to this client is worth ClearPoints now (`secretpoints-001`), and [ContributionTracker]
     * credits by name. It is passed in rather than read from `Minecraft.getInstance()` here for the
     * same reason [playerX] and [playerZ] are — this object is driven by tests that have no client.
     */
    fun onActionBar(text: String, self: String, playerX: Double, playerZ: Double) {
        if (!DungeonSession.calibrated) return
        val bar = parseSecrets(text) ?: return

        val room = ContributionTracker.roomAt(DungeonGrid.physicalRoomPos(playerX, playerZ)) ?: return
        val expected = room.info?.secrets

        // Only trust the number if the total it reports matches what the database says this room has.
        // Without that check a stale action bar gets attributed to whichever room you walked into next.
        if (expected == null || expected != bar.max) {
            DebugLog.event(
                "secret_room_mismatch",
                "room" to room.label(), "barMax" to bar.max, "expected" to (expected ?: -1),
                "barFound" to bar.found,
            )
            return
        }

        // One call rather than "note the first reading, then test for a rise, then advance the
        // counter": the order between those is load-bearing and is now the room's own business.
        // A plain `0/10` is not a rise, and it is the only reading that can ever say the room was
        // untouched when we walked in — see TrackedRoom.readBar.
        val reading = room.readBar(bar.found)
        if (reading.first) {
            DebugLog.event(
                "secret_room_first_bar",
                "room" to room.label(), "found" to bar.found, "max" to bar.max,
                // Whether a secret-run record is possible in this room at all. Logged as its own
                // fact so a real floor says how often a room is entered clean, rather than leaving
                // the rate of discards to be inferred from their absence.
                "untouched" to (bar.found == 0),
            )
        }
        if (!reading.rose) return

        val previous = reading.previous
        val delta = bar.found - previous

        // Two answers to "was that one yours", and the fact beats the coincidence. `chat` is Hypixel
        // naming the finder; `clicked` is the 40-tick window this mod has always used, which a click
        // arms and — since `ownsecrets-001` — so do a secret-item pickup and a swing at a bat. The
        // name is kept: the window does not care which of the three armed it. Where chat has
        // spoken the inference is not consulted at all — that is `chat-001`'s one real replacement,
        // and it is narrow on purpose: it covers wither-essence secrets and nothing else, because
        // they are the only secrets Hypixel names anybody for.
        //
        // What it buys is the false positive, not the false negative. A teammate taking the essence
        // while your own click on a chest is still inside its window used to credit you; now the line
        // that names them overrules it.
        val chat = chatAttribution(DungeonSession.runTicks, chatSecretAt, chatSecretMine)
        val clicked = isOwn(DungeonSession.runTicks, lastOwnInteraction)
        val mine = chat ?: clicked
        // Read before the credit below spends either of them. `none` is not a fourth signal: it is
        // the case that used to be logged as `click` whether or not a click existed, which said
        // nothing. A secret that reaches here with `none` is one nobody's action explained.
        val signal = when {
            chat != null -> "chat"
            clicked -> lastOwnSource
            else -> "none"
        }
        // When the secret is yours *and it was your own action that said so*, that action is the
        // moment it was taken — the bar can lag up to [OWN_WINDOW] ticks behind it, and timing a run
        // off the lag would measure the server, not the player. A chat-attributed secret with no
        // action behind it has no earlier timestamp to offer, and [NO_INTERACTION] must never be
        // read as one. Read before the credit below resets it.
        val at = if (mine && clicked) lastOwnInteraction else DungeonSession.runTicks
        // One line, two consumers, and they must not be able to disagree: `ownSecrets` is what
        // the HUD's secrets row shows as "you", and the quarter point is what the standings show. The
        // user's complaint was that the second did not exist — the score only moved on a checkmark,
        // because the room's weight paid for the secrets the *database* said it held. See
        // ContributionTracker.onOwnSecret.
        if (mine) {
            room.ownSecrets++
            ContributionTracker.onOwnSecret(self)
        }
        // One click, or one pickup, credits one secret and not a whole burst — and only when it is
        // what credited it. A signal the chat line has just overruled belongs to a secret to come.
        if (mine && clicked) lastOwnInteraction = NO_INTERACTION
        // Likewise one line credits one secret.
        if (chat != null) chatSecretAt = NO_INTERACTION
        DebugLog.event(
            "secret",
            "room" to room.label(), "found" to bar.found, "max" to bar.max, "delta" to delta,
            "mine" to mine, "ownTotal" to room.ownSecrets,
            // Which of the three answered, so the data says how often the fact was available at all
            // rather than leaving it to be assumed. `chat` here is also the proof the ordering
            // works, and `pickup` is the only proof `ownsecrets-001` ever reaches a real secret.
            "attributedBy" to signal,
        )
        trackRun(room, previous, bar, at)
    }

    /**
     * Feeds one secret into the room's run timer. Only a completed run reaches the history; a run
     * that started late or died quietly is logged and dropped.
     */
    private fun trackRun(room: TrackedRoom, previous: Int, bar: BarSecrets, at: Int) {
        when (room.onSecret(previous, bar.found, bar.max, at)) {
            TrackedRoom.SecretRun.STARTED ->
                DebugLog.event("secret_run_start", "room" to room.label(), "at" to at, "max" to bar.max)
            TrackedRoom.SecretRun.DONE -> {
                DebugLog.event(
                    "secret_run_done",
                    "room" to room.label(), "ticks" to room.secretRunTicks,
                    "secrets" to bar.max, "own" to room.ownSecrets,
                )
                RoomHistory.onSecretRun(room)
            }
            TrackedRoom.SecretRun.DISCARDED ->
                DebugLog.event(
                    "secret_run_discarded",
                    "room" to room.label(), "previous" to previous, "found" to bar.found, "max" to bar.max,
                    // Which of the three discard reasons fired. Without this a late entry and a
                    // one-secret room look identical in the log, and the late entry is the one that
                    // used to produce a record.
                    "firstBar" to (room.firstBarFound ?: -1),
                )
            TrackedRoom.SecretRun.RUNNING, TrackedRoom.SecretRun.IGNORED -> Unit
        }
    }

    /** Called once per tick per room, from the tracker that owns the clock. */
    fun expireRun(room: TrackedRoom, now: Int) {
        if (!room.expireSecretRun(now, ABANDON_TICKS)) return
        DebugLog.event(
            "secret_run_abandoned",
            "room" to room.label(), "found" to room.secretsFound, "max" to (room.info?.secrets ?: -1),
            "quiet" to ABANDON_TICKS,
        )
    }

    /** The room's secret counter as the action bar reports it. */
    internal data class BarSecrets(val found: Int, val max: Int)

    /** Null when the bar carries no secret counter at all, which is most of the time. */
    internal fun parseSecrets(text: String): BarSecrets? {
        val match = SECRETS.find(FORMATTING.replace(text, "")) ?: return null
        val found = match.groupValues[1].toIntOrNull() ?: return null
        val max = match.groupValues[2].toIntOrNull() ?: return null
        return BarSecrets(found, max)
    }

    /**
     * Whether a secret that just appeared can be credited to the local player.
     *
     * [NO_INTERACTION] is compared, never subtracted: `runTicks - Int.MIN_VALUE` overflows into a
     * large negative number, which passes the window check. That silently credited every secret
     * found *without* a preceding click — the first reading of every room, and every reading after
     * a credit had reset the timestamp back to the sentinel.
     */
    internal fun isOwn(runTicks: Int, lastInteraction: Int) =
        lastInteraction != NO_INTERACTION && runTicks - lastInteraction <= OWN_WINDOW

    /**
     * Whether a chat line has already answered who this secret belonged to. **Null means chat said
     * nothing**, which is the ordinary case and the one that must fall back to [isOwn] rather than to
     * `false` — a missing fact is not a denial, and reading it as one would un-credit every chest.
     *
     * `in 0..CHAT_WINDOW` rather than `<=`: [chatAt] is [NO_INTERACTION] until a line has ever landed,
     * and `runTicks - Int.MIN_VALUE` overflows into a large negative that passes a `<=` check. That
     * is not hypothetical — it is the exact defect [isOwn]'s KDoc records, found in a real M7 log,
     * and the range check is what makes it unrepresentable here instead of merely absent.
     */
    internal fun chatAttribution(runTicks: Int, chatAt: Int, chatMine: Boolean): Boolean? =
        if (chatAt != NO_INTERACTION && runTicks - chatAt in 0..CHAT_WINDOW) chatMine else null

    /** Colour codes off, whitespace normalised. Hypixel names arrive as `§9Spirit Leap`. */
    private fun strip(rawName: String) = FORMATTING.replace(rawName, "").trim().replace(WHITESPACE, " ")

    /**
     * The canonical secret-item name [rawName] is, or null if it is ordinary loot.
     *
     * **Exact match after [strip], never a prefix or a substring**, and that is the safety property:
     * a substring rule would take `Enchanted Decoy` or a renamed pet for the real thing, and every
     * false match here is a permanent wrong record. An item this does not recognise costs a credit
     * that is already being lost today.
     */
    internal fun secretItem(rawName: String): String? {
        val name = strip(rawName)
        return if (name.lowercase() in SECRET_ITEMS) name else null
    }

    /**
     * Whether [rawName] is worth one `pickup_unmatched` line: the first time this run it is seen,
     * and only until [UNMATCHED_CAP] distinct names have been.
     *
     * **This is the only thing that can ever correct [SECRET_ITEMS]**, which is a guess in every
     * entry. Deduplicated because it is fed by every pickup in a dungeon and what it is worth
     * knowing is the *vocabulary*, not the volume — the second Rotten Flesh teaches nothing and the
     * log is a file on the user's disk.
     */
    internal fun noteUnmatched(rawName: String): Boolean {
        if (UNMATCHED.size >= UNMATCHED_CAP) return false
        return UNMATCHED.add(strip(rawName).lowercase())
    }

    /** Null when the block is not a secret. Mirrors the block set Odin and NoammAddons use. */
    private fun secretTypeAt(level: Level, pos: BlockPos): String? = when (level.getBlockState(pos).block) {
        Blocks.CHEST, Blocks.TRAPPED_CHEST -> "chest"
        Blocks.LEVER -> "lever"
        Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD -> {
            val id = (level.getBlockEntity(pos) as? SkullBlockEntity)?.ownerProfile?.partialProfile()?.id?.toString()
            if (id in SECRET_SKULLS) "skull" else null
        }
        else -> null
    }
}
