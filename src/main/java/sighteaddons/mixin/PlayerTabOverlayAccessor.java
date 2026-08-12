package sighteaddons.mixin;

import java.util.Comparator;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Hypixel encodes the tab grid layout in the player names, so the party slots only land on
 * rows 1, 5, 9, 13 and 17 once the list is sorted exactly the way vanilla sorts it.
 * That comparator is private, hence this accessor.
 */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
	@Accessor("PLAYER_COMPARATOR")
	static Comparator<PlayerInfo> getOrdering() {
		throw new UnsupportedOperationException();
	}
}
