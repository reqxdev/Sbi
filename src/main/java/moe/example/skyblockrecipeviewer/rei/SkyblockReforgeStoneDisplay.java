package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData;
import net.minecraft.resources.Identifier;

/**
 * A single reforge-STONE item as a compact REI display - just the stone's icon, sized to pack
 * many onto one page (see {@link SkyblockReforgeStoneCategory}), with the full per-rarity stat
 * table living in the stone ItemStack's own tooltip/lore rather than as on-page text widgets
 * (unlike {@link SkyblockReforgeDisplay}, which is one full page per free Blacksmith reforge -
 * there are few enough of those for a detailed page each to make sense, but not for ~80 stones).
 */
public class SkyblockReforgeStoneDisplay extends BasicDisplay {

	private static final DisplaySerializer<SkyblockReforgeStoneDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockReforgeStoneDisplay");

	private final ReforgeData reforge;
	/** See {@link SkyblockReforgeDisplay#getHighlightRarities} - same meaning, same source. */
	private final List<String> highlightRarities;

	public SkyblockReforgeStoneDisplay(ReforgeData reforge, List<EntryIngredient> inputs,
			List<String> highlightRarities) {
		super(inputs, List.of());
		this.reforge = reforge;
		this.highlightRarities = highlightRarities;
	}

	public ReforgeData getReforge() {
		return reforge;
	}

	public List<String> getHighlightRarities() {
		return highlightRarities;
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockReforgeStoneCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockReforgeStoneDisplay> getSerializer() {
		return SERIALIZER;
	}
}
