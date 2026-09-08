package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import io.github.moulberry.repo.data.NEUForgeRecipe;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import net.minecraft.resources.Identifier;

/** A single Forge (the Foundry/Minion forge anvil) recipe as an REI display. */
public class SkyblockForgeDisplay extends BasicDisplay {

	private static final DisplaySerializer<SkyblockForgeDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockForgeDisplay");

	private final NEUForgeRecipe recipe;

	public SkyblockForgeDisplay(NEUForgeRecipe recipe, List<EntryIngredient> inputs, List<EntryIngredient> outputs) {
		super(inputs, outputs);
		this.recipe = recipe;
	}

	public NEUForgeRecipe getRecipe() {
		return recipe;
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockForgeCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockForgeDisplay> getSerializer() {
		return SERIALIZER;
	}
}
