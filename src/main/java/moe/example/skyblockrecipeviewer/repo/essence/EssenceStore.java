package moe.example.skyblockrecipeviewer.repo.essence;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads and indexes every essence upgrade step from {@code constants/essencecosts.json} in the
 * shared NEU item repo (the per-star-level Essence + item cost to upgrade a piece of gear).
 *
 * Parsed with plain Gson straight off disk - see
 * {@link moe.example.skyblockrecipeviewer.repo.reforge.ReforgeStore}'s class docs for why raw
 * JSON is used here rather than the typed neurepoparser constants API.
 *
 * File shape confirmed against a real {@code essencecosts.json}: each item is one flat object
 * keyed by star number directly (see {@link #readCostsFile} for the exact shape) - an earlier
 * version of this class guessed at a nested {@code essenceCosts}/{@code itemCosts} wrapper
 * (ported from Firmament's typed {@code EssenceRecipeProvider.kt} API without a way to verify
 * the shape), which was wrong and silently produced zero recipes for every single item.
 */
public final class EssenceStore {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Essence");
	private static final EssenceStore INSTANCE = new EssenceStore();

	private final AtomicReference<List<EssenceUpgradeRecipe>> all = new AtomicReference<>(List.of());
	private final AtomicReference<Map<String, List<EssenceUpgradeRecipe>>> byItem =
		new AtomicReference<>(Map.of());

	private EssenceStore() {
	}

	public static EssenceStore getInstance() {
		return INSTANCE;
	}

	public List<EssenceUpgradeRecipe> getAll() {
		return all.get();
	}

	public List<EssenceUpgradeRecipe> getForItem(String skyblockId) {
		if (skyblockId == null) return List.of();
		return byItem.get().getOrDefault(skyblockId, List.of());
	}

	public void reload(Path repoDir) {
		Path file = repoDir.resolve("constants/essencecosts.json");
		if (!Files.isRegularFile(file)) return;
		try {
			List<EssenceUpgradeRecipe> recipes = readCostsFile(file);
			Map<String, List<EssenceUpgradeRecipe>> index = new LinkedHashMap<>();
			for (EssenceUpgradeRecipe recipe : recipes) {
				index.computeIfAbsent(recipe.itemSkyblockId(), k -> new ArrayList<>()).add(recipe);
			}
			all.set(List.copyOf(recipes));
			byItem.set(Map.copyOf(index));
			LOGGER.info("Loaded {} essence upgrade steps from the item repo.", recipes.size());
		} catch (Exception e) {
			LOGGER.warn("Failed to load essence upgrade data from the item repo - essence "
				+ "upgrade displays will be unavailable until this succeeds.", e);
		}
	}

	/**
	 * Confirmed against a real {@code essencecosts.json} (previously this guessed at a nested
	 * {@code essenceCosts}/{@code itemCosts} wrapper per item, ported from Firmament's typed
	 * API without a way to verify the shape - that guess was wrong, which is why every item
	 * was being silently skipped and no essence recipes ever loaded).
	 *
	 * The real shape has star tiers as flat keys directly on the item object itself, sitting
	 * right alongside "type":
	 * <pre>
	 * "ADAPTIVE_BELT": {
	 *   "type": "Wither",
	 *   "1": 10, "2": 20, "3": 35, "4": 50, "5": 75,
	 *   "items": { "4": ["SKYBLOCK_COIN:10000"], "5": ["SKYBLOCK_COIN:25000"] }
	 * }
	 * </pre>
	 * i.e. each numbered key IS an essence-cost-for-that-star entry (not a container to look
	 * inside), and "items" (not "itemCosts"/"item_costs") holds the extra non-Essence
	 * ingredients for the star tiers that need them - already in "ID:amount" form, matching
	 * {@link EssenceUpgradeRecipe#extraItemIds}'s existing format exactly.
	 */
	private static List<EssenceUpgradeRecipe> readCostsFile(Path file) throws IOException {
		JsonObject root;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = JsonParser.parseReader(reader).getAsJsonObject();
		}
		// Defensive fallback, not the common case: if the repo ever wraps these under a
		// "costs" key the way some other constants files do, unwrap it; otherwise (the
		// confirmed real shape) the item names are already the root object's own keys.
		JsonObject byItemId = firstObject(root, "costs").orElse(root);

		List<EssenceUpgradeRecipe> out = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : byItemId.entrySet()) {
			if (!entry.getValue().isJsonObject()) continue;
			JsonObject itemCosts = entry.getValue().getAsJsonObject();
			String type = firstString(itemCosts, "type").orElse(null);
			if (type == null) continue;

			JsonObject extraItemsByStar = firstObject(itemCosts, "items").orElse(null);

			for (Map.Entry<String, JsonElement> field : itemCosts.entrySet()) {
				String key = field.getKey();
				// "type" and "items" are the only non-star-tier keys on this object - every
				// other key is a star number with its essence cost as the value.
				if (key.equals("type") || key.equals("items")) continue;
				int starCountAfter = parseIntOrSkip(key);
				if (starCountAfter < 0 || !field.getValue().isJsonPrimitive()) continue;
				int cost = field.getValue().getAsJsonPrimitive().getAsInt();

				List<String> extraItems = List.of();
				if (extraItemsByStar != null) {
					JsonElement extra = extraItemsByStar.get(key);
					if (extra != null && extra.isJsonArray()) {
						extraItems = new ArrayList<>();
						for (JsonElement item : extra.getAsJsonArray()) {
							if (item.isJsonPrimitive()) extraItems.add(item.getAsString());
						}
					}
				}

				out.add(new EssenceUpgradeRecipe(entry.getKey(), starCountAfter, type, cost, extraItems));
			}
		}
		return out;
	}

	private static int parseIntOrSkip(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static java.util.Optional<JsonObject> firstObject(JsonObject parent, String... keys) {
		for (String key : keys) {
			JsonElement el = parent.get(key);
			if (el != null && el.isJsonObject()) return java.util.Optional.of(el.getAsJsonObject());
		}
		return java.util.Optional.empty();
	}

	private static java.util.Optional<String> firstString(JsonObject parent, String... keys) {
		for (String key : keys) {
			JsonElement el = parent.get(key);
			if (el != null && el.isJsonPrimitive()) return java.util.Optional.of(el.getAsString());
		}
		return java.util.Optional.empty();
	}
}
