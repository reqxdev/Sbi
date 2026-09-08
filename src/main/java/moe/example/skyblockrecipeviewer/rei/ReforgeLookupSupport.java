package moe.example.skyblockrecipeviewer.rei;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUItem;
import moe.example.skyblockrecipeviewer.repo.ItemCategoryResolver;
import moe.example.skyblockrecipeviewer.repo.ItemRarityResolver;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeStore;

/**
 * Shared "what reforges apply to this item" matching logic, used by both {@link
 * SkyblockReforgeDisplayGenerator} (free Blacksmith reforges) and {@link
 * SkyblockReforgeStoneDisplayGenerator} (reforge-stone reforges) - the two are really the same
 * lookup filtered to opposite halves of {@link ReforgeStore#getAllReforges()} (reforgeStoneId()
 * null vs. non-null), so this keeps that shared part from drifting into two copies.
 */
final class ReforgeLookupSupport {
	private ReforgeLookupSupport() {
	}

	/**
	 * The five real per-item categories Hypixel's own item resource reports for these slots
	 * (confirmed directly against it: Arachne's Belt -> "BELT", Arachne's Gloves -> "GLOVES",
	 * Arachne's Cloak -> "CLOAK", Arachne's Necklace -> "NECKLACE", and Bracelets use
	 * "BRACELET" the same way) - {@code reforgestones.json} uses "EQUIPMENT" as an umbrella
	 * term meaning "any of these five", but since no real item's own category is ever literally
	 * "EQUIPMENT", a plain exact-match lookup against {@link ReforgeStore#getByItemType} could
	 * never find a single match for any of them. This is the confirmed bug behind "we don't
	 * have reforges for items that are Equipment."
	 */
	private static final Set<String> EQUIPMENT_CATEGORIES =
		Set.of("BELT", "GLOVES", "CLOAK", "NECKLACE", "BRACELET");

	/**
	 * Same umbrella-term problem as {@link #EQUIPMENT_CATEGORIES}, for armor: Hypixel reports
	 * each armor piece's own specific category (confirmed: a helmet's category is "HELMET", a
	 * chestplate's "CHESTPLATE", and so on for "LEGGINGS"/"BOOTS"), but most reforge-stone
	 * reforges - e.g. Precursor Gear/Ancient, {@code "itemTypes": "ARMOR"}, confirmed directly
	 * against the real repo file - use "ARMOR" as an umbrella meaning "any of these four", the
	 * same way "EQUIPMENT" means "any of the five accessory slots".
	 *
	 * Without this expansion, a plain exact-match lookup only ever found reforges that happen
	 * to name a specific slot directly (e.g. Red Scarf/Loving, which really is chestplate-only
	 * by design - its own tooltip says "combined with a chestplate") - the generic ARMOR-wide
	 * reforges that make up the bulk of reforgestones.json never matched any piece at all. This
	 * exactly explains the reported symptom: helmet/chestplate showed the small number of
	 * genuinely slot-specific reforges that exist for those two slots, while leggings/boots -
	 * which have no slot-specific reforges of their own by game design - showed nothing,
	 * because the many generic ARMOR reforges never got a chance to match either of them.
	 */
	private static final Set<String> ARMOR_CATEGORIES =
		Set.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

	/**
	 * Every reforge (both free and stone-based - callers filter by {@code reforgeStoneId()} for
	 * their own category) that's eligible for {@code skyblockId}: the item itself IS a reforge
	 * stone, the item is explicitly allow-listed by id (a unique-item-specific reforge), or the
	 * item's own category matches one of the reforge's eligible item types - see
	 * {@link ItemCategoryResolver}'s class docs for why that last one required its own fix to
	 * work at all, and {@link #EQUIPMENT_CATEGORIES}'s / {@link #ARMOR_CATEGORIES}'s docs for
	 * the umbrella-term ("EQUIPMENT"/"ARMOR") expansion fixes.
	 */
	static Set<ReforgeData> matchesFor(NeuRepoManager manager, String skyblockId) {
		ReforgeStore store = ReforgeStore.getInstance();
		Set<ReforgeData> matches = new LinkedHashSet<>();
		ReforgeData stoneReforge = store.getByReforgeStone(skyblockId);
		if (stoneReforge != null) matches.add(stoneReforge);
		matches.addAll(store.getByInternalName(skyblockId));
		String category = ItemCategoryResolver.getCategory(manager.getRepoDir(), skyblockId);
		if (category != null) {
			matches.addAll(store.getByItemType(category));
			if (EQUIPMENT_CATEGORIES.contains(category)) {
				matches.addAll(store.getByItemType("EQUIPMENT"));
			}
			if (ARMOR_CATEGORIES.contains(category)) {
				matches.addAll(store.getByItemType("ARMOR"));
			}
		}
		return matches;
	}

	/**
	 * The specific rarity tiers to highlight/narrow a lookup down to: the item's own natural
	 * rarity, plus one tier up (what a Recombobulator - extremely common - would bump it to).
	 * Empty (meaning "show every rarity, don't narrow") if:
	 *  - the item's rarity can't be determined at all, in which case callers should fall back
	 *    to showing every rarity rather than guessing, or
	 *  - {@code skyblockId} is itself one of the reforge stones being looked up (confirmed bug:
	 *    viewing "usages of Blazen Sphere" itself was narrowing to Blazen Sphere's OWN rarity -
	 *    "RARE REFORGE STONE" per its own lore - which is a coincidence, not a meaningful
	 *    narrowing. A reforge stone's own rarity has nothing to do with which tier of its
	 *    *applied* reforge someone would want highlighted; that narrowing only makes sense when
	 *    looking up reforges for an actual piece of equipment the reforge would go on.
	 */
	static List<String> highlightRaritiesFor(NEURepository repository, String skyblockId) {
		if (ReforgeStore.getInstance().getByReforgeStone(skyblockId) != null) return List.of();
		NEUItem targetItem = repository.getItems().getItemBySkyblockId(skyblockId);
		String rarity = ItemRarityResolver.getRarity(targetItem);
		if (rarity == null) return List.of();
		String recombobulated = ItemRarityResolver.oneTierUp(rarity);
		return recombobulated != null ? List.of(rarity, recombobulated) : List.of(rarity);
	}
}
