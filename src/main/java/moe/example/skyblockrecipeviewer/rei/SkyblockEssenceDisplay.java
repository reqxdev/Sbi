package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import moe.example.skyblockrecipeviewer.repo.essence.EssenceUpgradeRecipe;
import net.minecraft.resources.Identifier;

/**
 * A single Essence-upgrade star step as an REI display. Because this mod doesn't model
 * star-count NBT variants of an item (that needs the same kind of full item-stack-reconstruction
 * machinery Firmament's SBItemStack provides, which isn't present here), the "input" and
 * "output" item slots both resolve to the plain base item - {@link
 * SkyblockEssenceCategory#setupDisplay} adds a "★(N-1) -> ★N" label between them to make clear
 * this is a star upgrade, not a different item.
 */
public class SkyblockEssenceDisplay extends BasicDisplay {

	private static final DisplaySerializer<SkyblockEssenceDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockEssenceDisplay");

	private final EssenceUpgradeRecipe recipe;

	public SkyblockEssenceDisplay(EssenceUpgradeRecipe recipe, List<EntryIngredient> inputs,
			List<EntryIngredient> outputs) {
		super(inputs, outputs);
		this.recipe = recipe;
	}

	public EssenceUpgradeRecipe getRecipe() {
		return recipe;
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockEssenceCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockEssenceDisplay> getSerializer() {
		return SERIALIZER;
	}
}
