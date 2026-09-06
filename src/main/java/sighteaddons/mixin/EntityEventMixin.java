package sighteaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sighteaddons.LiveScore;

/**
 * The mimic's death, read the way Odin reads it: entity event {@code 3} — the death animation — on a
 * baby {@code Zombie}. Hypixel prints no chat line for the mimic and the tab list carries no row for
 * it, so the wire is the only place the two bonus points can be seen from; see
 * {@link LiveScore#onMimicDead()} for what the run of 2026-09-06 cost without them.
 *
 * <p><b>Client thread only.</b> {@code handleEntityEvent} runs twice per packet: once on the netty
 * thread, where its first statement re-schedules it and throws, and once on the client thread. HEAD
 * sees both, and the entity lookup belongs to the second, so the first is skipped by asking the
 * client whether this is its thread.
 *
 * <p><b>Read-only, and it decides nothing.</b> Whether a baby zombie's death is a mimic — the floor,
 * the phase, whether one was already counted — is {@link LiveScore}'s question, in Kotlin, where a
 * test can reach it. Default {@code require}, for {@code ConnectionMixin}'s reason: the class loads on
 * every launch, so an injector that stops applying is loud on the first one rather than a bonus that
 * quietly stops arriving.
 */
@Mixin(ClientPacketListener.class)
public class EntityEventMixin {

	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void sighteaddons$mimicDeath(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		if (packet.getEventId() != 3) return;
		Minecraft client = Minecraft.getInstance();
		if (!client.isSameThread()) return;
		Level level = client.level;
		if (level == null) return;
		Entity entity = packet.getEntity(level);
		if (entity instanceof Zombie zombie && zombie.isBaby()) {
			LiveScore.INSTANCE.onMimicDead();
		}
	}
}
