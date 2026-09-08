package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import io.github.moulberry.repo.data.NEUKatUpgradeRecipe;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import net.minecraft.resources.Identifier;

/** A single Kat pet-rarity-upgrade recipe as an REI display. */
public class SkyblockPetUpgradeDisplay extends BasicDisplay {

	private static final DisplaySerializer<SkyblockPetUpgradeDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockPetUpgradeDisplay");

	private final NEUKatUpgradeRecipe recipe;

	public SkyblockPetUpgradeDisplay(NEUKatUpgradeRecipe recipe, List<EntryIngredient> inputs,
			List<EntryIngredient> outputs) {
		super(inputs, outputs);
		this.recipe = recipe;
	}

	public NEUKatUpgradeRecipe getRecipe() {
		return recipe;
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockPetUpgradeCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockPetUpgradeDisplay> getSerializer() {
		return SERIALIZER;
	}
}
