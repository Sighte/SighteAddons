package sighteaddons

/**
 * How many ticks Hypixel has run since this client connected — the clock a split time is compared
 * against between two players.
 *
 * ### Why not the client's own tick
 *
 * [Splits] shows two numbers per row: elapsed wall-clock time, and the same span counted in *server*
 * ticks. They are not the same measurement and the difference is the point. Wall clock includes every
 * moment Hypixel spent behind — a lag spike lengthens it and nobody in the party did anything wrong;
 * the server-tick span does not, so it is the number two people comparing a run can actually hold each
 * other to. `ClientTickEvents` would count the local client's frames-worth of ticks, which drift for
 * reasons on *this* machine, and would produce a third quantity that looks like the second and is not.
 *
 * ### Where the count comes from
 *
 * Hypixel sends a keep-alive `ClientboundPingPacket` every server tick, and Odin's `ConnectionMixin`
 * counts exactly those — this is that mechanism, and [sighteaddons.mixin.ConnectionMixin] is the half
 * that observes them. Odin's `id != 0` filter is kept: a ping with id zero is vanilla's own transaction
 * ping, not the tick beat.
 *
 * ### Monotonic on purpose, and never reset
 *
 * Every consumer stores an absolute reading and subtracts two of them, so the zero point is irrelevant
 * and there is nothing a reset would fix. What it *would* add is an ordering hazard: [DungeonSession.reset]
 * runs on a world change and this counter is written from a Netty thread, so a reset there could land
 * between a split's two readings and turn a span into a negative number. A counter that only ever goes
 * up cannot do that.
 *
 * ### Threading
 *
 * One writer — the Netty event loop that decodes packets — and several readers: the client thread
 * ([Splits]) and the render thread ([SplitsHud]). `@Volatile` is the whole requirement for that shape,
 * and it is the same argument [DungeonSession.floor] carries. `count++` from two threads would need an
 * `AtomicLong`; from one it does not.
 */
object ServerTicks {

    /** Ticks seen since launch. Only [bump] writes it. */
    @Volatile
    var count: Long = 0L
        private set

    /**
     * One server tick observed. Called from the packet thread, so it does exactly this and nothing
     * else — anything thrown here happens inside Minecraft's network pipeline.
     */
    fun bump() {
        count++
    }
}
