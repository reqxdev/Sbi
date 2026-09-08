package moe.example.skyblockrecipeviewer.repo;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Holds the SkyBlock-id -> skull-texture map parsed from Hypixel's public item resource.
 * Mirrors NeuRepoManager's load-from-disk-only, refresh-on-Hypixel-join design, but this is
 * a much smaller/simpler dataset (just id -> optional skin), so it's kept as a plain map
 * rather than a repository object.
 */
public final class HypixelSkinManager {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Skins");
	private static final HypixelSkinManager INSTANCE = new HypixelSkinManager();

	private final HypixelSkinDownloader downloader;
	private final Map<String, SkullSkin> skins = new ConcurrentHashMap<>();
	// See ItemCategoryResolver: the NEU repo's own items/<id>.json files (confirmed against a
	// real one, e.g. ADAPTIVE_BOOTS.json) have NO "category" field at all - that assumption
	// was mixed up with Hypixel's own /resources/skyblock/items response, which DOES carry a
	// "category" per entry (e.g. "SWORD", "BOW"). Since this class already downloads and
	// caches exactly that resource for skull skins, it's the natural place to also capture
	// category from, rather than adding a whole second downloader for one extra field.
	private final Map<String, String> categories = new ConcurrentHashMap<>();
	private final AtomicBoolean loaded = new AtomicBoolean(false);
	private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "skyblock-skin-loader");
		t.setDaemon(true);
		return t;
	});

	private HypixelSkinManager() {
		var configDir = FabricLoader.getInstance().getConfigDir().resolve("skyblockrecipeviewer");
		this.downloader = new HypixelSkinDownloader(configDir, LOGGER);
	}

	public static HypixelSkinManager getInstance() {
		return INSTANCE;
	}

	/**
	 * A skull's base64 texture value, as Hypixel's API and vanilla skulls both use.
	 *
	 * NOTE: Hypixel's /resources/skyblock/items response does NOT wrap this in an object -
	 * each item entry that has a skin carries it as a plain top-level string, e.g.
	 * {@code "skin": "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Li4ufX19"} (confirmed against
	 * Hypixel's own forum documentation of this endpoint). There is also no accompanying
	 * "signature" field on this endpoint - that only exists on session-server responses for
	 * real player profiles, not on Hypixel's static item resource - so a resolved profile
	 * built from this is unsigned. That's fine: createResolved() renders it purely
	 * client-side with no session-server signature check, exactly like any other
	 * NBT-defined custom skull.
	 */
	public record SkullSkin(String value) {
	}

	public CompletableFuture<Void> ensureLoaded() {
		if (loaded.get()) return CompletableFuture.completedFuture(null);
		return CompletableFuture.runAsync(this::loadFromDisk, executor);
	}

	/**
	 * Non-blocking "is skin data already in memory right now" check - unlike ensureLoaded(),
	 * never triggers or waits on any loading. Used by SkyblockReiPlugin.registerEntries() so it
	 * can decide whether to populate synchronously without ever blocking REI's (cancellable,
	 * shared-with-every-other-plugin) reload thread.
	 */
	public boolean isLoaded() {
		return loaded.get();
	}

	private void loadFromDisk() {
		JsonObject root = downloader.loadFromDisk();
		populateFrom(root);
		loaded.set(true);
	}

	/**
	 * @return the number of skins parsed from {@code root} (0 if {@code root} is null/empty).
	 * Categories are parsed for every entry regardless of skin presence (most items have a
	 * category but no skin - skins only exist for skull-based items), so this count is
	 * intentionally skins-only, not a combined total; see the log line at the end for both.
	 */
	private int populateFrom(JsonObject root) {
		if (root == null || !root.has("items")) return 0;
		JsonArray items = root.getAsJsonArray("items");
		int skinCount = 0;
		for (var element : items) {
			try {
				JsonObject entry = element.getAsJsonObject();
				if (!entry.has("id")) continue;
				String id = entry.get("id").getAsString();

				// Independent of the skin handling below (and NOT gated on "skin" being
				// present, unlike the loop's old single combined condition) - the vast
				// majority of items have a category but no skin at all.
				if (entry.has("category")) {
					com.google.gson.JsonElement categoryElement = entry.get("category");
					if (categoryElement.isJsonPrimitive()) {
						String category = categoryElement.getAsString();
						if (!category.isBlank()) {
							categories.put(id, category.toUpperCase(java.util.Locale.ROOT));
						}
					}
				}

				if (!entry.has("skin")) continue;
				// "skin" is a bare base64 string directly on the item entry (e.g.
				// {"material":"SKULL_ITEM","skin":"eyJ0ZXh0dXJlcyI6...","id":"..."}),
				// NOT a {"skin":{"value":...}} sub-object - the old getAsJsonObject("skin")
				// call here threw IllegalStateException on literally every entry (Gson
				// refuses to coerce a JsonPrimitive into a JsonObject), which silently
				// aborted this whole loop on the very first skull and left the entire
				// skins map permanently empty, so every player head fell through to its
				// plain vanilla texture no matter what. Read it as a string instead.
				com.google.gson.JsonElement skinElement = entry.get("skin");
				if (skinElement == null || !skinElement.isJsonPrimitive()) continue;
				String value = skinElement.getAsString();
				if (value.isBlank()) continue;
				skins.put(id, new SkullSkin(value));
				skinCount++;
			} catch (Exception e) {
				LOGGER.warn("Skipping malformed entry in Hypixel item resource: {}", e.toString());
			}
		}
		LOGGER.info("Parsed {} SkyBlock item categories and {} skull skins from Hypixel's item resource.",
			categories.size(), skinCount);
		return skinCount;
	}

	/**
	 * Re-fetches from Hypixel and replaces both the on-disk cache AND the in-memory map used
	 * by {@link #getSkin}, so a refresh actually takes effect this session rather than only
	 * on the next full game restart.
	 */
	public CompletableFuture<Boolean> refreshFromNetwork() {
		return CompletableFuture.supplyAsync(() -> {
			JsonObject fresh = downloader.fetchAndCacheReturningJson();
			if (fresh == null) return false;
			populateFrom(fresh);
			return true;
		}, executor);
	}

	public Optional<SkullSkin> getSkin(String skyblockId) {
		if (skyblockId == null) return Optional.empty();
		return Optional.ofNullable(skins.get(skyblockId));
	}

	/**
	 * @return the item's category (e.g. {@code "SWORD"}, {@code "BOW"}, {@code "ARMOR"}) as
	 * reported by Hypixel's item resource, or empty if unknown/not yet loaded. See
	 * {@link moe.example.skyblockrecipeviewer.repo.ItemCategoryResolver}, which delegates here.
	 */
	public Optional<String> getCategory(String skyblockId) {
		if (skyblockId == null) return Optional.empty();
		return Optional.ofNullable(categories.get(skyblockId));
	}
}
