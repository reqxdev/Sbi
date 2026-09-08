package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import net.minecraft.resources.Identifier;

/**
 * A single NPC shop purchase as an REI display: item-based costs (if any) as inputs, the
 * purchased item as the single output. Coin cost is kept separately (not as an ingredient
 * slot) since it's virtually always present and isn't itself a real item - see
 * NpcShopIndex's own class docs for why NEU repo data can't tell us which specific NPC/
 * location actually sells it, only that it's buyable from "an NPC shop" for this cost.
 */
public class SkyblockNpcShopDisplay extends BasicDisplay {

	private static final DisplaySerializer<SkyblockNpcShopDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockNpcShopDisplay");

	private final long coinCost;

	public SkyblockNpcShopDisplay(List<EntryIngredient> inputs, List<EntryIngredient> outputs, long coinCost) {
		super(inputs, outputs);
		this.coinCost = coinCost;
	}

	public long getCoinCost() {
		return coinCost;
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockNpcShopCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockNpcShopDisplay> getSerializer() {
		return SERIALIZER;
	}
}
