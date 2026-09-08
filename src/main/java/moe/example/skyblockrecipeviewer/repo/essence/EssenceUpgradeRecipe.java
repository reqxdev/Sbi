package moe.example.skyblockrecipeviewer.repo.essence;

import java.util.List;

/**
 * One star-upgrade step for a single item, from {@code constants/essencecosts.json} in the NEU
 * item repo (e.g. "take a 3-star item to 4 stars for 12 Essence of Wither + 2 extra items").
 *
 * Mirrors Firmament's {@code EssenceRecipeProvider.EssenceUpgradeRecipe} (same repo file, same
 * data) - see that class for the reference implementation this was ported from.
 *
 * @param itemSkyblockId  the item being upgraded, e.g. {@code "HYPERION"}.
 * @param starCountAfter  star count reached by paying this cost (so cost to go from N-1 to N
 *                        stars).
 * @param essenceType     which Essence currency this costs, e.g. {@code "WITHER"} (displayed/
 *                        resolved as SkyBlock id {@code "ESSENCE_WITHER"}).
 * @param essenceCost     how much of that Essence type this step costs.
 * @param extraItemIds    additional non-Essence ingredients for this step, as NEU ingredient
 *                        strings ({@code "ITEM_ID:amount"}), e.g. recombobulators on later
 *                        stars.
 */
public record EssenceUpgradeRecipe(
	String itemSkyblockId,
	int starCountAfter,
	String essenceType,
	int essenceCost,
	List<String> extraItemIds
) {
	public String essenceSkyblockId() {
		return "ESSENCE_" + essenceType.toUpperCase(java.util.Locale.ROOT);
	}
}
