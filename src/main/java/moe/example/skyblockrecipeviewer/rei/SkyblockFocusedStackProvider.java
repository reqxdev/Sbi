package moe.example.skyblockrecipeviewer.rei;

import dev.architectury.event.CompoundEventResult;
import me.shedaniel.math.Point;
import me.shedaniel.rei.api.client.registry.screen.FocusedStackProvider;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import moe.example.skyblockrecipeviewer.mixin.accessors.AbstractContainerScreenAccessor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;

/**
 * Supplies REI with the actual ItemStack under the mouse in vanilla container screens.
 *
 * REI normally has no SkyBlock-specific way to identify a server-sent inventory item.
 * In particular, the stack under the mouse is not one of our pre-built REI entry stacks,
 * so relying only on our custom EntryType loses the SkyBlock id when R/U is pressed from
 * the player's inventory. Skyblocker solves this with the same REI FocusedStackProvider
 * mechanism; we do the minimal equivalent here.
 */
public final class SkyblockFocusedStackProvider implements FocusedStackProvider {
	@Override
	public double getPriority() {
		// Skyblocker also registers a focused-stack provider. Run ahead of it so an
		// inventory R/U lookup is represented by the actual vanilla ItemStack here.
		return 1_000_000.0;
	}

	@Override
	public CompoundEventResult<EntryStack<?>> provide(Screen screen, Point point) {
		if (!(screen instanceof AbstractContainerScreen<?> container)) {
			return CompoundEventResult.pass();
		}

		Slot slot = ((AbstractContainerScreenAccessor) container).skyblockRecipeViewer$getHoveredSlot();
		if (slot == null || !slot.hasItem()) return CompoundEventResult.pass();

		ItemStack stack = slot.getItem();
		if (stack.isEmpty()) return CompoundEventResult.pass();

		return CompoundEventResult.interruptTrue(EntryStacks.of(stack));
	}
}
