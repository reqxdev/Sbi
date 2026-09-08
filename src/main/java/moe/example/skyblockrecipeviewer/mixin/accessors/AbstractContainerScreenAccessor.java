package moe.example.skyblockrecipeviewer.mixin.accessors;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/** Exposes the slot REI should treat as focused when the player presses R/U. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("hoveredSlot")
	Slot skyblockRecipeViewer$getHoveredSlot();
}
