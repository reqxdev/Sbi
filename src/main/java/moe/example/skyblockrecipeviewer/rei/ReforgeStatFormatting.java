package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData;
import net.minecraft.ChatFormatting;

/**
 * Rarity ordering/coloring/stat-line formatting shared between {@link SkyblockReforgeCategory}
 * (free Blacksmith reforges, shown as detailed on-page text) and {@link
 * SkyblockReforgeStoneCategory} (reforge-stone reforges, shown as compact icons with the same
 * stat text moved into the stone's own tooltip instead) - split out so both stay in sync rather
 * than maintaining two copies of "how do we print a rarity's stats" that could drift apart.
 */
final class ReforgeStatFormatting {
	/** Canonical display order; any rarity name not in this list is appended at the end. */
	static final List<String> RARITY_ORDER = List.of(
		"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC",
		"DIVINE", "SPECIAL", "VERY_SPECIAL", "ADMIN", "ULTIMATE");

	private ReforgeStatFormatting() {
	}

	static List<String> orderedRarities(ReforgeData reforge) {
		List<String> present = new ArrayList<>(reforge.rarities());
		present.sort((a, b) -> {
			int ia = RARITY_ORDER.indexOf(a);
			int ib = RARITY_ORDER.indexOf(b);
			if (ia < 0) ia = Integer.MAX_VALUE;
			if (ib < 0) ib = Integer.MAX_VALUE;
			return Integer.compare(ia, ib);
		});
		return present;
	}

	/** The stat/cost content for one rarity, with no leading "RARITY: " label. */
	static String statsLine(ReforgeData reforge, String rarity) {
		StringBuilder sb = new StringBuilder();
		Map<String, Double> stats = reforge.statsFor(rarity);
		boolean first = true;
		for (Map.Entry<String, Double> stat : stats.entrySet()) {
			if (!first) sb.append(", ");
			first = false;
			sb.append(RecipeFormatting.prettifyStatId(stat.getKey())).append(' ')
				.append(RecipeFormatting.signedNumber(stat.getValue()));
		}
		if (stats.isEmpty()) sb.append("(no stat data)");

		Double cost = reforge.costFor(rarity);
		if (cost != null) {
			sb.append("  [").append(RecipeFormatting.coins(cost)).append(" coins]");
		}
		return sb.toString();
	}

	/** {@link #statsLine} with a leading "RARITY: " (or "All rarities: ") label. */
	static String labelledLine(ReforgeData reforge, String rarity) {
		String label = !ReforgeData.RARITY_ANY.equals(rarity) ? rarity : "All rarities";
		return label + ": " + statsLine(reforge, rarity);
	}

	/**
	 * Same content as {@link #statsLine}, but split into multiple lines - 3 stats per line -
	 * rather than one continuous line, since a raw tooltip Component list (unlike REI's
	 * on-page text widgets, which word-wrap on their own) renders each Component as one fixed
	 * line with no wrapping at all, so a reforge with many stats just ran off the tooltip's
	 * edge. The "[X coins]" cost suffix (if any) is appended to the last line only, since it's
	 * a cost, not a stat, and doesn't count toward the 3-per-line grouping.
	 */
	static List<String> statsLines(ReforgeData reforge, String rarity) {
		Map<String, Double> stats = reforge.statsFor(rarity);
		List<String> pieces = new ArrayList<>();
		for (Map.Entry<String, Double> stat : stats.entrySet()) {
			pieces.add(RecipeFormatting.prettifyStatId(stat.getKey()) + " "
				+ RecipeFormatting.signedNumber(stat.getValue()));
		}
		if (pieces.isEmpty()) pieces.add("(no stat data)");

		List<String> lines = new ArrayList<>();
		for (int i = 0; i < pieces.size(); i += 3) {
			lines.add(String.join(", ", pieces.subList(i, Math.min(i + 3, pieces.size()))));
		}

		Double cost = reforge.costFor(rarity);
		if (cost != null) {
			int last = lines.size() - 1;
			lines.set(last, lines.get(last) + "  [" + RecipeFormatting.coins(cost) + " coins]");
		}
		return lines;
	}

	/**
	 * Hypixel's standard rarity color scheme (the same colors the game itself uses for these
	 * words), so a reforge stone's tooltip reads the same way a real SkyBlock item's rarity
	 * line does.
	 */
	static ChatFormatting colorFor(String rarity) {
		if (rarity == null) return ChatFormatting.WHITE;
		return switch (rarity) {
			case "UNCOMMON" -> ChatFormatting.GREEN;
			case "RARE" -> ChatFormatting.BLUE;
			case "EPIC" -> ChatFormatting.DARK_PURPLE;
			case "LEGENDARY" -> ChatFormatting.GOLD;
			case "MYTHIC" -> ChatFormatting.LIGHT_PURPLE;
			case "DIVINE" -> ChatFormatting.AQUA;
			case "SPECIAL", "VERY_SPECIAL" -> ChatFormatting.RED;
			default -> ChatFormatting.WHITE; // COMMON and anything unrecognized
		};
	}
}
