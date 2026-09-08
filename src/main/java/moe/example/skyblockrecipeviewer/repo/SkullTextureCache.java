package moe.example.skyblockrecipeviewer.repo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Persists the {uuid, base64 texture value, signature} triples {@link SkullProfileResolver}
 * extracts out of each skull item's raw nbttag, keyed by SkyBlock item id, to this mod's
 * config folder (config/skyblockrecipeviewer/skull-textures-cache.json) - a genuine
 * on-disk cache of the skin data used to skin skulls, as distinct from
 * {@link SkyblockItemCache}'s cache of complete resolved ItemStacks.
 *
 * <p><b>Where this fits next to SkyblockItemCache:</b> once {@link SkullProfileResolver} is
 * wired into {@link SkyblockItemResolver}, a skull's PROFILE component (built from an entry
 * in this cache, or from a fresh regex extraction) becomes part of the resolved ItemStack
 * like any other component, and round-trips through {@code ItemStack.CODEC} the same way -
 * so SkyblockItemCache's existing cross-launch cache of full resolved stacks already carries
 * correct skin data on a warm second launch with zero changes of its own. What this smaller
 * cache buys on top of that is avoiding re-running {@link SkullProfileResolver}'s regexes
 * against every one of the repo's several-thousand items' raw nbttag text on every resolve
 * pass *within* a session (e.g. after a repo update triggers
 * {@code SkyblockItemResolver}'s in-memory RESOLVED_CACHE being cleared) - this cache is
 * keyed purely by SkyBlock item id + repo sha, so it stays valid across those clears and only
 * actually needs recomputing when the repo commit itself changes, the same staleness rule
 * {@link SkyblockItemCache} uses.
 *
 * <p><b>What this is not a cache of:</b> no image/skin PNG data is ever downloaded or stored
 * here - see {@link SkullProfileResolver}'s class javadoc for why that job is deliberately
 * left entirely to vanilla Minecraft's own client-side skin texture cache.
 */
public final class SkullTextureCache {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Skulls");
	private static final Path CACHE_FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("skyblockrecipeviewer").resolve("skull-textures-cache.json");

	// Guarded by the class monitor (all access goes through the synchronized methods below) -
	// this cache is tiny (one entry per skull-type item in the repo, a few hundred at most)
	// and resolving is already funneled through NeuRepoManager's single-threaded executor, so
	// a coarse class-level lock is simpler than trying to make this a ConcurrentHashMap and
	// costs nothing measurable in practice.
	private static Map<String, Entry> memoryCache;
	private static String memoryCacheRepoSha;

	private SkullTextureCache() {
	}

	/**
	 * @param uuid      always non-null - either the id actually captured in the repo's
	 *                  SkullOwner NBT, or a stable UUID derived from {@code value} when the
	 *                  repo snapshot didn't have one (see SkullProfileResolver.parseUuid's
	 *                  caller).
	 * @param value     the base64 "textures" property value. Never null/blank - callers only
	 *                  ever construct an Entry once a real value has been found.
	 * @param signature the accompanying base64 signature, or null if the repo snapshot didn't
	 *                  capture one (harmless either way - see SkullProfileResolver.buildProfile).
	 */
	public record Entry(String uuid, String value, String signature) {
	}

	/**
	 * @return the cached entry for {@code skyblockId} under the given repo sha, loading the
	 * on-disk cache into memory on first call this session (or discarding it and starting
	 * fresh if the sha on disk doesn't match, i.e. the repo has since been updated). Empty if
	 * nothing is cached yet - callers should fall back to
	 * {@link SkullProfileResolver#resolveFromRawNbt} and, if that finds something, call
	 * {@link #put} so it's cached from then on.
	 */
	public static synchronized Optional<Entry> get(String repoSha, String skyblockId) {
		ensureLoaded(repoSha);
		if (skyblockId == null) return Optional.empty();
		return Optional.ofNullable(memoryCache.get(skyblockId));
	}

	public static synchronized void put(String repoSha, String skyblockId, Entry entry) {
		if (skyblockId == null || entry == null) return;
		ensureLoaded(repoSha);
		memoryCache.put(skyblockId, entry);
	}

	/**
	 * Writes whatever's currently in memory to disk, tagged with the repo sha it was built
	 * from. Meant to be called once after a full item-resolution pass (see
	 * SkyblockReiPlugin's REGISTER_ENTRIES / RESOLVED_CACHE-populating step), not per item -
	 * same convention as {@code SkyblockItemCache.write}.
	 */
	public static synchronized void flush() {
		if (memoryCache == null || memoryCacheRepoSha == null || memoryCache.isEmpty()) return;
		try {
			JsonObject root = new JsonObject();
			root.addProperty("repoSha", memoryCacheRepoSha);
			JsonObject entries = new JsonObject();
			for (Map.Entry<String, Entry> e : memoryCache.entrySet()) {
				Entry entry = e.getValue();
				JsonObject obj = new JsonObject();
				obj.addProperty("uuid", entry.uuid());
				obj.addProperty("value", entry.value());
				if (entry.signature() != null) {
					obj.addProperty("signature", entry.signature());
				}
				entries.add(e.getKey(), obj);
			}
			root.add("entries", entries);
			Files.createDirectories(CACHE_FILE.getParent());
			Files.writeString(CACHE_FILE, root.toString(), StandardCharsets.UTF_8);
			LOGGER.info("Wrote {} SkyBlock skull texture(s) to the local cache (repo {}).",
				entries.size(), memoryCacheRepoSha);
		} catch (Exception e) {
			LOGGER.warn("Failed to write the local skull texture cache ({}).", e.toString());
		}
	}

	private static void ensureLoaded(String repoSha) {
		if (memoryCache != null && Objects.equals(memoryCacheRepoSha, repoSha)) return;
		Map<String, Entry> loaded = new LinkedHashMap<>();
		try {
			if (Files.exists(CACHE_FILE)) {
				JsonObject root = JsonParser.parseString(Files.readString(CACHE_FILE, StandardCharsets.UTF_8))
					.getAsJsonObject();
				String cachedSha = root.has("repoSha") ? root.get("repoSha").getAsString() : null;
				if (Objects.equals(cachedSha, repoSha) && root.has("entries")) {
					JsonObject entries = root.getAsJsonObject("entries");
					for (String key : entries.keySet()) {
						JsonObject obj = entries.getAsJsonObject(key);
						if (!obj.has("value")) continue;
						String uuid = obj.has("uuid") ? obj.get("uuid").getAsString() : null;
						String value = obj.get("value").getAsString();
						String signature = obj.has("signature") ? obj.get("signature").getAsString() : null;
						loaded.put(key, new Entry(uuid, value, signature));
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Could not read local skull texture cache ({}) - skulls will just be "
				+ "re-extracted from the repo this session instead.", e.toString());
		}
		memoryCache = loaded;
		memoryCacheRepoSha = repoSha;
	}
}
