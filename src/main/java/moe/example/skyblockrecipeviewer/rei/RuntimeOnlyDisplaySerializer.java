package moe.example.skyblockrecipeviewer.rei;

import com.mojang.serialization.MapCodec;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import net.minecraft.network.codec.StreamCodec;

/**
 * Every display in this mod is (re)built purely client-side from the downloaded item repo on
 * every REI plugin reload - none of them are ever saved to disk or sent over the network. Each
 * Display subclass still has to return *some* DisplaySerializer to satisfy the abstract
 * getSerializer() method, so this hands back one that satisfies the type but throws if REI ever
 * actually tries to use it (isPersistent = false tells REI not to).
 *
 * Factored out of SkyblockCraftingDisplay's original inline version so the 5 additional
 * recipe-kind Display classes (reforge/forge/essence/pet-upgrade/mob-drop) don't each repeat
 * the same six lines of codec boilerplate.
 */
public final class RuntimeOnlyDisplaySerializer {
	private RuntimeOnlyDisplaySerializer() {
	}

	public static <D extends Display> DisplaySerializer<D> create(String displayClassName) {
		String message = displayClassName + " is generated at runtime and is not persisted or networked.";
		return DisplaySerializer.of(
			MapCodec.unit(() -> {
				throw new UnsupportedOperationException(message);
			}),
			StreamCodec.of(
				(buf, value) -> {
					throw new UnsupportedOperationException(message);
				},
				buf -> {
					throw new UnsupportedOperationException(message);
				}),
			false);
	}
}
