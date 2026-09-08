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
 * A single reforge (basic Blacksmith reforge or reforge-stone reforge) as an REI display. The
 * per-rarity stat/cost table lives on {@link #reforge} itself, not as entry ingredients -
 * {@link SkyblockReforgeCategory#setupDisplay} reads it directly to lay out the stat rows.
 */
public class SkyblockReforgeDisplay extends BasicDisplay {

	private static final DisplaySerializer<SkyblockReforgeDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockReforgeDisplay");

	private final ReforgeData reforge;
	/**
	 * The specific rarity tiers to show, in order - e.g. {@code ["EPIC", "LEGENDARY"]} for an
	 * Adaptive Boots lookup (its natural EPIC tier, plus the LEGENDARY a Recombobulator would
	 * bump it to), rather than the reforge's full 7-rarity table. Empty when there's no
	 * specific item this display was generated for (browsing the reforge category's own tab
	 * via {@link SkyblockReforgeDisplayGenerator#generate}), in which case {@link
	 * SkyblockReforgeCategory} falls back to showing every rarity the reforge has data for.
	 */
	private final List<String> highlightRarities;

	public SkyblockReforgeDisplay(ReforgeData reforge, List<EntryIngredient> inputs) {
		this(reforge, inputs, List.of());
	}

	public SkyblockReforgeDisplay(ReforgeData reforge, List<EntryIngredient> inputs,
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
		return SkyblockReforgeCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockReforgeDisplay> getSerializer() {
		return SERIALIZER;
	}
}
