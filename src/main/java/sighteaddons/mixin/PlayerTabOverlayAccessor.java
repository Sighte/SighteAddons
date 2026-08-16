package sighteaddons.mixin;

import java.util.Comparator;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
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

	/**
	 * The block of text under the tab list, where Hypixel puts the floor's blessings.
	 *
	 * There is a public {@code setFooter} and no getter — the field is written by the packet
	 * handler and read only by the renderer — so this is the one way to see what the server put
	 * there. Read-only: nothing in this mod calls the setter.
	 *
	 * Null before the server has ever sent a footer, which is most of the time outside a floor.
	 * {@code CritMeter} treats that as "no power reading" rather than as zero power.
	 */
	@Accessor("footer")
	Component getFooter();
}
