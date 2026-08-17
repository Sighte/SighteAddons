package sighteaddons.mixin;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sighteaddons.SecretTracker;

/**
 * The two packets that can mean "the local player just took a secret item off the floor".
 *
 * <p>A dungeon secret you walk over raises Hypixel's room counter with no block interaction and no
 * chat line, so {@code SecretTracker} had nothing to attribute it by and credited it to somebody else
 * by default ({@code ownsecrets-001}).
 *
 * <p><b>Two injections, because Hypixel uses both routes and only one of them names a collector.</b>
 * The solo M1 of 2026-08-17 20:51 is what proved one was not enough: six secrets in one room, party
 * of one, and the first of them arrived with no signal of any kind — no take-item packet, and so not
 * even the diagnostic that was added to report a take-item packet it could not resolve. Odin listens
 * to the same two packets ({@code EventDispatcher.kt}), which is the confirmation that the second
 * route is real rather than a guess about why the first was quiet.
 *
 * <p><b>Read-only, and it must stay that way.</b> No {@code CallbackInfo.cancel()}, no writes to a
 * packet or an entity; vanilla's own handling runs untouched afterwards.
 *
 * <p><b>Why HEAD with a thread guard, which looks like the wrong pairing.</b> Injecting at HEAD puts
 * these <em>before</em> {@code PacketUtils.ensureRunningOnSameThread}, so the method body runs twice:
 * once on the netty thread, which reschedules and aborts, and once on the thread the packet processor
 * owns. Asking {@code packetProcessor().isSameThread()} — the same predicate vanilla asks on its next
 * line, rather than a guess at which thread that is — drops the first of those. What is left is one
 * execution, on the right thread, at the one point where the entity is still in the level: both
 * methods remove it before they return, so TAIL would have nothing to read.
 */
@Mixin(ClientPacketListener.class)
public class SecretPickupMixin {
	/** Blocks, squared. Odin's own radius for the same decision, and melee-ish either way. */
	private static final double NEAR_SQ = 6.0 * 6.0;

	/**
	 * {@code require = 0} overrides the config's {@code defaultRequire: 1}, and it is the one
	 * decision here worth arguing about.
	 *
	 * {@code ClientPacketListener} is not loaded until the client joins a server, so the injection
	 * point is resolved on the way into Hypixel and nowhere else — the dev client has no session and
	 * can never reach that moment. Under the default, an injector that failed to resolve would throw
	 * there: a mod that crashes the game on joining, for a feature whose whole purpose is to stop one
	 * number being wrong. {@code require = 0} makes that same failure silent instead, which is the
	 * status quo this feature is trying to improve on rather than a regression, and {@code expect} is
	 * left at 1 so it still shows up as a warning in the log.
	 *
	 * <p>It is not a shrug: both method signatures were read out of the same merged jar this compiles
	 * against, and the absence of {@code own_pickup} in a floor with item secrets is what says one did
	 * not apply.
	 */
	@Inject(method = "handleTakeItemEntity", at = @At("HEAD"), require = 0)
	private void sighteaddons$onTakeItemEntity(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.packetProcessor().isSameThread()) {
			return;
		}
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			return;
		}
		// The packet is broadcast for every nearby collector, so this is what makes the signal mean
		// "mine" at all. Without it a teammate's pickup would arm the local player's window.
		if (packet.getPlayerId() != player.getId()) {
			return;
		}
		Entity entity = level.getEntity(packet.getItemId());
		// Experience orbs come through here too, and they are not items.
		if (!(entity instanceof ItemEntity item)) {
			SecretTracker.INSTANCE.onPickupUnresolved(entity == null ? "absent" : entity.getClass().getSimpleName());
			return;
		}
		SecretTracker.INSTANCE.onItemPickup(item.getItem().getHoverName().getString());
	}

	/**
	 * The other route: the item entity is simply removed, with nobody named as having taken it.
	 *
	 * <p><b>This one cannot say whose pickup it was, and the name is what stands in for that.</b> The
	 * packet above carries a collector id and is therefore proof; this carries a list of ids and no
	 * reason. So it is only offered to {@code SecretTracker} when the item is one of the handful whose
	 * sole source is a dungeon secret <em>and</em> it vanished within {@link #NEAR_SQ} of the player —
	 * and even then it only arms the attribution window, with Hypixel's counter still deciding whether
	 * anything is credited. A teammate taking a Spirit Leap four blocks from you can still mis-arm it;
	 * {@code SecretAudit} is what would report that, as an over-credit, in every run's log.
	 */
	@Inject(method = "handleRemoveEntities", at = @At("HEAD"), require = 0)
	private void sighteaddons$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.packetProcessor().isSameThread()) {
			return;
		}
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			return;
		}
		IntList ids = packet.getEntityIds();
		for (int i = 0; i < ids.size(); i++) {
			if (!(level.getEntity(ids.getInt(i)) instanceof ItemEntity item)) {
				continue;
			}
			if (item.distanceToSqr(player) > NEAR_SQ) {
				continue;
			}
			SecretTracker.INSTANCE.onItemVanished(item.getItem().getHoverName().getString());
		}
	}
}
