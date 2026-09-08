package moe.example.skyblockrecipeviewer.repo.reforge;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One entry from {@code constants/reforges.json} (basic/Blacksmith reforges) or
 * {@code constants/reforgestones.json} (reforge-stone reforges) in the NEU item repo.
 *
 * Mirrors the shape of Firmament's {@code Reforge.kt} (same repo, same JSON) - see that file
 * for the reference implementation this was ported from. Parsed with plain Gson in
 * {@link ReforgeStore} rather than any typed library API, so field names below are exactly the
 * raw JSON keys.
 *
 * @param reforgeId          stable id for this reforge: {@code nbtModifier} if present,
 *                           otherwise {@code reforgeName} lowercased - this is what gets
 *                           written into an item's {@code modifier} NBT tag in real gameplay.
 * @param reforgeStoneId     SkyBlock id of the item that applies this reforge at a Reforge
 *                           Anvil (e.g. {@code "HEAVY_STONE"}), or {@code null} for a basic
 *                           reforge that's simply available for free at the Blacksmith.
 * @param eligibleItems      what this reforge can be applied to - see {@link EligibilityFilter}.
 * @param costsByRarity      coin cost to apply, keyed by rarity name; {@link #RARITY_ANY} for a
 *                           flat cost that doesn't vary by rarity.
 * @param abilityByRarity    reforge ability/perk description text, keyed the same way.
 * @param statsByRarity      stat-id -> bonus value, keyed by rarity, then by stat id
 *                           (e.g. "strength", "critical_damage").
 * @param statUniverse       every stat id that appears anywhere in statsByRarity, in a stable
 *                           order - what the GUI iterates to lay out stat rows.
 */
public record ReforgeData(
	String reforgeName,
	String reforgeId,
	String reforgeStoneId,
	List<EligibilityFilter> eligibleItems,
	Map<String, Double> costsByRarity,
	Map<String, String> abilityByRarity,
	Map<String, Map<String, Double>> statsByRarity,
	List<String> statUniverse
) {
	/** Key used in the *ByRarity maps when the JSON gave one flat value for every rarity. */
	public static final String RARITY_ANY = "*";

	public Double costFor(String rarityName) {
		Double specific = costsByRarity.get(rarityName);
		return specific != null ? specific : costsByRarity.get(RARITY_ANY);
	}

	public String abilityFor(String rarityName) {
		String specific = abilityByRarity.get(rarityName);
		return specific != null ? specific : abilityByRarity.get(RARITY_ANY);
	}

	public Map<String, Double> statsFor(String rarityName) {
		Map<String, Double> specific = statsByRarity.get(rarityName);
		if (specific != null) return specific;
		return statsByRarity.getOrDefault(RARITY_ANY, Map.of());
	}

	/** Every rarity name this reforge actually has data for (excluding the flat/"*" bucket). */
	public Set<String> rarities() {
		Set<String> rarities = new java.util.LinkedHashSet<>();
		rarities.addAll(costsByRarity.keySet());
		rarities.addAll(abilityByRarity.keySet());
		rarities.addAll(statsByRarity.keySet());
		rarities.remove(RARITY_ANY);
		return rarities;
	}

	/**
	 * What an item needs to be for this reforge to apply to it. The repo encodes this as one of
	 * three JSON shapes (a "/"-separated string, an array of single-filter objects, or an
	 * object with internalName/itemId arrays) - {@link ReforgeStore} normalizes all three into
	 * this sealed type.
	 */
	public sealed interface EligibilityFilter
		permits AllowsInternalName, AllowsItemType, AllowsVanillaId {
	}

	/** Applies only to this exact SkyBlock item id (e.g. a unique/legendary-specific reforge). */
	public record AllowsInternalName(String internalName) implements EligibilityFilter {
	}

	/**
	 * Applies to every item of this SkyBlock item-type/category (e.g. {@code "SWORD"},
	 * {@code "BOW"}). We don't attempt to resolve this back to a concrete list of items (that
	 * needs a verified item-category getter we don't have confirmed against the repo parser
	 * library - see NeuRepoManager's class docs) - it's kept purely for display ("Applies to:
	 * Sword, Bow").
	 */
	public record AllowsItemType(String itemType) implements EligibilityFilter {
	}

	/** Applies to a specific vanilla Minecraft item id, independent of SkyBlock item type. */
	public record AllowsVanillaId(String minecraftId) implements EligibilityFilter {
	}
}
