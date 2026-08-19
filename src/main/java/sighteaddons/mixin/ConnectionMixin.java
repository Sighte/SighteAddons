package sighteaddons.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sighteaddons.ServerTicks;

/**
 * Hypixel's tick beat, counted off the wire — the input half of {@link ServerTicks}, which is where
 * the argument for counting server ticks at all is written down.
 *
 * <p>Hypixel sends a keep-alive {@link ClientboundPingPacket} once per server tick. Odin's own
 * {@code ConnectionMixin} counts exactly those, {@code id != 0} included, and it is what
 * {@code Splits}' tick column has always been measured with — so this is the same signal read the same
 * way, rather than a second definition of "a tick" that would quietly disagree with the mod the numbers
 * are compared against. A ping with id zero is vanilla's own transaction ping and is not part of the
 * beat.
 *
 * <p><b>HEAD, where Odin injects on an {@code INVOKE} of {@code Connection.genericsFtw}.</b> Both see
 * every inbound packet once; HEAD does not depend on a private helper still being called from inside
 * this method, and a counter is exactly the case with nothing to gain from a later injection point —
 * it reads no state that the method body sets up. The descriptor is spelled out in full because
 * {@code Connection} carries two {@code channelRead0} overloads: this one and
 * {@code SimpleChannelInboundHandler}'s {@code (ChannelHandlerContext, Object)} bridge.
 *
 * <p><b>No {@code require = 0} here, unlike {@link SecretPickupMixin}.</b> That one targets a class the
 * dev client never loads, so a failure to resolve could only ever surface on joining a real server, and
 * crashing there was the worse outcome. {@code Connection} is loaded by every launch including this
 * repository's own, so an injector that does not apply is visible immediately and loudly — which is
 * what should happen, because the silent failure is a tick column that reads {@code 0:00.0} for every
 * split and looks like a feature that works.
 *
 * <p><b>Read-only.</b> No {@code CallbackInfo.cancel()}, nothing written to the packet, no listener
 * registered — this is the netty read path for the whole game and the only correct thing to do on it is
 * one increment. {@link ServerTicks#count} is {@code volatile} for the thread this runs on.
 */
@Mixin(Connection.class)
public class ConnectionMixin {

	@Inject(
			method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
			at = @At("HEAD")
	)
	private void sighteaddons$countServerTick(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ClientboundPingPacket ping && ping.getId() != 0) {
			ServerTicks.INSTANCE.bump();
		}
	}
}
