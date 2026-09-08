package moe.example.skyblockrecipeviewer.repo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUItem;

/**
 * Reads NPC shop purchase data directly from each item's own raw repo JSON file, rather than
 * through neurepoparser's typed recipe model - confirmed (by decompiling CosmicPings, a
 * different, working NEU-repo-backed mod, since this project has no local copy of
 * neurepoparser's own source/jar to check directly) that neurepoparser has no typed class for
 * "npc_shop"-type recipe entries the way it does for "crafting"/"forge"/"drops" (which this
 * project already consumes via NEUCraftingRecipe/NEUForgeRecipe/NEUMobDropRecipe - see
 * NeuRepoManager). The raw JSON shape (confirmed against that same decompile): an item's own
 * "recipes" array can contain an entry with {@code "type": "npc_shop"}, a {@code "cost"} array
 * of ingredient-slot strings ({@code "ITEM_ID:COUNT"}, the same format NEUIngredient uses
 * elsewhere in this repo, with {@code "SKYBLOCK_COIN"} as the item id for a pure coin cost),
 * and a {@code "result"} slot string for what buying it actually gives you.
 *
 * NOTE: NEU repo data does not attach a specific merchant identity to an npc_shop entry at
 * all (confirmed the same way - CosmicPings' own parsing falls back to the *selling item's
 * own* internalname/displayname where a real NPC name might be expected, since there isn't
 * one in the raw data). So this feature can only show "buyable from an NPC shop for X", not
 * which specific NPC/location sells it.
 *
 * Reading and parsing one JSON file per known item (thousands of files) is real, non-trivial
 * disk I/O - unlike getCraftingRecipes()/getMobDropRecipes()/etc, which are just filtering
 * already-in-memory objects NEURepository parsed once at load time. Given the earlier,
 * previously-fixed freeze bug in this project (see SkyblockReiPlugin's own history/docs on
 * tryLivePush()) was caused by exactly this class of mistake - real work run synchronously
 * where a lookup was expected to be instant - this index is built once per repo load, on its
 * own background thread, and cached; getEntries() below only ever returns whatever's already
 * cached (empty on a cold, not-yet-built cache) and never blocks waiting for a build.
 */
public final class NpcShopIndex {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "skyblock-npc-shop-index");
		t.setDaemon(true);
		return t;
	});

	private static volatile NEURepository builtForRepo;
	private static volatile List<Entry> cached = List.of();
	private static volatile NEURepository buildingForRepo;
	private static volatile CompletableFuture<List<Entry>> buildInFlight;

	private NpcShopIndex() {
	}

	public record CostSlot(String itemId, int count) {
	}

	public record Entry(String resultItemId, int resultCount, List<CostSlot> cost) {
	}

	/**
	 * Whatever's cached right now - empty if never built yet for this repo instance, or a
	 * rebuild triggered by a repo change hasn't finished. Triggers a background (re)build as
	 * a side effect when needed, but never blocks waiting for it; matches this project's
	 * established "a cold cache returns empty once and silently warms up for next time"
	 * pattern (see SkyblockItemResolver's own resolved-stack cache).
	 */
	public static List<Entry> getEntries(NeuRepoManager manager) {
		NEURepository repo = manager.getLoadedRepoOrNull();
		if (repo == null) return List.of();
		ensureBuilt(manager, repo);
		return cached;
	}

	public static synchronized CompletableFuture<List<Entry>> ensureBuilt(
			NeuRepoManager manager, NEURepository repo) {
		if (repo == builtForRepo) return CompletableFuture.completedFuture(cached);
		if (repo == buildingForRepo && buildInFlight != null) return buildInFlight;

		CompletableFuture<List<Entry>> created =
			CompletableFuture.supplyAsync(() -> buildFromDisk(manager, repo), EXECUTOR);
		buildingForRepo = repo;
		buildInFlight = created;
		created.whenComplete((entries, error) -> {
			synchronized (NpcShopIndex.class) {
				if (buildInFlight != created) return;
				if (error == null) {
					cached = entries;
					builtForRepo = repo;
				}
				buildingForRepo = null;
				buildInFlight = null;
			}
		});
		return created;
	}

	private static List<Entry> buildFromDisk(NeuRepoManager manager, NEURepository repo) {
		Path itemsDir = manager.getRepoDir().resolve("items");
		List<Entry> result = new ArrayList<>();
		for (NEUItem item : repo.getItems().getItems().values()) {
			String id = item.getSkyblockItemId();
			if (id == null || id.isBlank()) continue;
			Path itemFile = itemsDir.resolve(id + ".json");
			if (!Files.exists(itemFile)) continue;
			try {
				String raw = Files.readString(itemFile, StandardCharsets.UTF_8);
				JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
				result.addAll(parseNpcShopRecipes(json));
			} catch (IOException | RuntimeException e) {
				// One unreadable/malformed item file shouldn't take down the whole index.
			}
		}
		// A few new event-shop pets are not represented as npc_shop recipes in the NEU item
		// JSON at all. Keep those small, stable exceptions local and append them to the same
		// cached index; this avoids any per-pet network requests.
		result.addAll(loadLocalPetShopOverrides());
		return List.copyOf(result);
	}

	private static List<Entry> parseNpcShopRecipes(JsonObject itemJson) {
		List<Entry> entries = new ArrayList<>();
		if (!itemJson.has("recipes") || !itemJson.get("recipes").isJsonArray()) return entries;
		JsonArray recipesArray = itemJson.getAsJsonArray("recipes");
		for (JsonElement element : recipesArray) {
			if (!element.isJsonObject()) continue;
			JsonObject recipe = element.getAsJsonObject();
			String type = recipe.has("type") ? recipe.get("type").getAsString() : null;
			if (!"npc_shop".equalsIgnoreCase(type)) continue;

			List<CostSlot> cost = new ArrayList<>();
			if (recipe.has("cost") && recipe.get("cost").isJsonArray()) {
				for (JsonElement costElement : recipe.getAsJsonArray("cost")) {
					if (costElement.isJsonNull()) continue;
					CostSlot slot = parseSlot(costElement.getAsString());
					if (slot != null) cost.add(slot);
				}
			}

			String resultRaw = recipe.has("result") && !recipe.get("result").isJsonNull()
				? recipe.get("result").getAsString() : null;
			CostSlot resultSlot = resultRaw != null ? parseSlot(resultRaw) : null;
			if (resultSlot == null || resultSlot.itemId().isBlank()) continue;

			entries.add(new Entry(resultSlot.itemId(), Math.max(1, resultSlot.count()), cost));
		}
		return entries;
	}

	private static List<Entry> loadLocalPetShopOverrides() {
		List<Entry> entries = new ArrayList<>();
		try (InputStream stream = NpcShopIndex.class.getResourceAsStream(
			"/data/skyblockrecipeviewer/pet_npc_shop.json")) {
			if (stream == null) return entries;
			JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
				.getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				if (!entry.getValue().isJsonObject()) continue;
				JsonObject value = entry.getValue().getAsJsonObject();
				int count = value.has("count") ? Math.max(1, value.get("count").getAsInt()) : 1;
				List<CostSlot> cost = new ArrayList<>();
				if (value.has("cost") && value.get("cost").isJsonArray()) {
					for (JsonElement costElement : value.getAsJsonArray("cost")) {
						CostSlot slot = parseSlot(costElement.getAsString());
						if (slot != null) cost.add(slot);
					}
				}
				entries.add(new Entry(entry.getKey(), count, List.copyOf(cost)));
			}
		} catch (Exception ignored) {
			// Optional local overrides must never break the main NPC-shop index.
		}
		return entries;
	}

	/** "ITEM_ID:COUNT" (split at the last colon), matching NEUIngredient's own slot format. */
	private static CostSlot parseSlot(String raw) {
		if (raw == null || raw.isBlank()) return null;
		String trimmed = raw.trim();
		int lastColon = trimmed.lastIndexOf(':');
		if (lastColon > 0 && lastColon < trimmed.length() - 1) {
			String idPart = trimmed.substring(0, lastColon).trim();
			String countPart = trimmed.substring(lastColon + 1).trim();
			try {
				int count = (int) Math.round(Double.parseDouble(countPart));
				return new CostSlot(idPart, count);
			} catch (NumberFormatException ignored) {
				// Not "id:number" after all - fall through and treat the whole raw string as
				// a bare item id with an implicit count of 1.
			}
		}
		return new CostSlot(trimmed, 1);
	}
}
