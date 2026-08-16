package sighteaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sighteaddons.SecretTracker;

/**
 * The one place the client is told <em>who</em> picked an item up.
 *
 * A dungeon secret you walk over raises Hypixel's room counter with no block interaction and no
 * chat line, so {@code SecretTracker} had nothing to attribute it by and credited it to somebody
 * else by default ({@code ownsecrets-001}). The server does say who collected it — that is what
 * this packet is — and it is the only signal available: an item entity simply disappearing tells
 * the client nothing about which player it flew to.
 *
 * <p><b>Read-only, and it must stay that way.</b> No {@code CallbackInfo.cancel()}, no writes to the
 * packet or the entity; vanilla's own handling runs untouched afterwards.
 *
 * <p><b>Why HEAD with a thread guard, which looks like the wrong pairing.</b> Injecting at HEAD puts
 * this <em>before</em> {@code PacketUtils.ensureRunningOnSameThread}, so the method body runs twice:
 * once on the netty thread, which reschedules and aborts, and once on the thread the packet
 * processor owns. Asking {@code packetProcessor().isSameThread()} — the same predicate vanilla asks
 * on its next line, rather than a guess at which thread that is — drops the first of those. What is
 * left is one execution, on the right thread, at the one point where the item entity is still in
 * the level: vanilla removes it at the end of this method, so TAIL would have nothing to read.
 */
@Mixin(ClientPacketListener.class)
public class TakeItemEntityMixin {
	/**
	 * {@code require = 0} overrides the config's {@code defaultRequire: 1}, and it is the one
	 * decision here worth arguing about.
	 *
	 * {@code ClientPacketListener} is not loaded until the client joins a server, so the injection
	 * point is resolved on the way into Hypixel and nowhere else — the dev client has no session and
	 * can never reach that moment. Under the default, an injector that failed to resolve would throw
	 * there: a mod that crashes the game on joining, for a feature whose whole purpose is to stop
	 * one number being wrong. {@code require = 0} makes that same failure silent instead, which is
	 * the status quo this feature is trying to improve on rather than a regression, and {@code
	 * expect} is left at 1 so it still shows up as a warning in the log.
	 *
	 * <p>It is not a shrug: {@code handleTakeItemEntity(ClientboundTakeItemEntityPacket)} was read
	 * out of the same merged jar this compiles against, and the absence of {@code own_pickup} in a
	 * floor with item secrets is what says it did not apply.
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
			return;
		}
		SecretTracker.INSTANCE.onItemPickup(item.getItem().getHoverName().getString());
	}
}
