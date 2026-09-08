package moe.example.skyblockrecipeviewer.repo;

import java.util.List;
import java.util.Locale;

import io.github.moulberry.repo.data.NEUItem;

/**
 * Extracts a SkyBlock item's rarity tier the same way a player actually reads it in-game:
 * the rarity keyword shows up, bolded and rarity-colored, in the LAST non-blank lore line -
 * e.g. {@code "§6§lLEGENDARY DUNGEON SWORD"} (Hyperion) or {@code "§5§lEPIC DUNGEON BOOTS"}
 * (Adaptive Boots), confirmed against real repo item lore. There's no separate typed
 * "rarity"/"tier" getter on NEUItem verified against neurepoparser's actual API, so this
 * reads the same lore text every other display path (EssenceStore, SkyblockItemResolver's
 * LORE component overlay) already relies on, rather than guessing at another field name.
 */
public final class ItemRarityResolver {
	/**
	 * Every rarity keyword that can appear in that line. Checked longest-first so
	 * "VERY SPECIAL" matches before the "SPECIAL" substring inside it does.
	 */
	private static final List<String> KEYWORDS = List.of(
		"VERY SPECIAL", "SPECIAL", "DIVINE", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON");

	/**
	 * The standard 7-tier scale a Recombobulator moves an item up by one step on. SPECIAL/
	 * VERY_SPECIAL sit outside this normal progression (recombobulating doesn't turn a
	 * SPECIAL item into "VERY_SPECIAL" the way it turns EPIC into LEGENDARY), so they're
	 * deliberately not included here - {@link #oneTierUp} returns null for them.
	 */
	private static final List<String> RECOMBOBULATOR_TIERS = List.of(
		"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE");

	private ItemRarityResolver() {
	}

	/**
	 * @return the item's rarity (e.g. {@code "LEGENDARY"}, {@code "VERY_SPECIAL"} with the
	 * space normalized to an underscore to match {@link
	 * moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData}'s rarity-key convention), or
	 * null if it can't be determined (no lore, or no recognized keyword found).
	 */
	public static String getRarity(NEUItem item) {
		if (item == null) return null;
		List<String> lore = item.getLore();
		if (lore == null || lore.isEmpty()) return null;
		// Scan from the end: the rarity line is always the very last (or very near last,
		// e.g. followed by a blank line) line of real gear lore, and scanning backwards also
		// means an earlier lore line that happens to mention a rarity word in passing (an
		// ability description, say) can't be mistaken for the item's actual tier.
		for (int i = lore.size() - 1; i >= 0; i--) {
			String plain = stripFormatting(lore.get(i)).toUpperCase(Locale.ROOT);
			if (plain.isBlank()) continue;
			for (String keyword : KEYWORDS) {
				if (plain.contains(keyword)) {
					return keyword.replace(' ', '_');
				}
			}
		}
		return null;
	}

	/**
	 * @return the rarity one tier above {@code rarity} (what a Recombobulator produces), or
	 * null if {@code rarity} is null, unrecognized, already DIVINE, or outside the normal
	 * 7-tier scale (SPECIAL/VERY_SPECIAL).
	 */
	public static String oneTierUp(String rarity) {
		if (rarity == null) return null;
		int index = RECOMBOBULATOR_TIERS.indexOf(rarity);
		if (index < 0 || index + 1 >= RECOMBOBULATOR_TIERS.size()) return null;
		return RECOMBOBULATOR_TIERS.get(index + 1);
	}

	private static String stripFormatting(String s) {
		return s.replaceAll("\u00A7.", "");
	}
}
