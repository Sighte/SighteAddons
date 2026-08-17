package sighteaddons.ui.hud

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * The mod's first keybinds.
 *
 * There were none before this — `/sa` was the only way in, and a command is a poor way to reach
 * something wanted mid-fight.
 *
 * Both default to unbound. A keybind that ships already occupying a key is a keybind that breaks
 * somebody's layout on update, and neither of these is needed often enough to justify that.
 *
 * Registered through `fabric-key-mapping-api-v1`, which in 26.1.2 is what the older
 * `fabric-key-binding-api-v1` became: the helper is `KeyMappingHelper.registerKeyMapping` and the
 * category is a `KeyMapping.Category` record around an [Identifier], not the loose translation-key
 * string it used to be.
 */
internal object HudKeys {

    private val CATEGORY: KeyMapping.Category =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sighteaddons", "main"))

    lateinit var expandTotals: KeyMapping
        private set

    fun register() {
        expandTotals = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.sighteaddons.totals",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY,
            ),
        )
    }

    /**
     * Drain the queued presses. Called once per client tick.
     *
     * `consumeClick` in a loop rather than once: it returns one queued press at a time, so a press and
     * a release inside a single tick would otherwise be swallowed. Returns whether the state should
     * flip — an even number of presses in one tick is no change, which is the honest answer.
     */
    fun tick(): Boolean {
        if (!::expandTotals.isInitialized) return false
        var toggled = false
        while (expandTotals.consumeClick()) toggled = !toggled
        return toggled
    }
}
