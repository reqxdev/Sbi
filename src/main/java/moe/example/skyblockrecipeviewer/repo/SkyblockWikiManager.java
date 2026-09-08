package moe.example.skyblockrecipeviewer.repo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the wiki URL attached to each item in the local NEU repo.
 *
 * The repo stores this as:
 *   "infoType": "WIKI_URL",
 *   "info": ["https://..."]
 *
 * Only the requested item file is read, then the result is cached. This avoids
 * doing thousands of JSON reads just to populate the REI item list.
 */
public final class SkyblockWikiManager {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Wiki");
	private static final SkyblockWikiManager INSTANCE = new SkyblockWikiManager();

	private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

	private SkyblockWikiManager() {
	}

	public static SkyblockWikiManager getInstance() {
		return INSTANCE;
	}

	public Optional<String> getWikiUrl(String skyblockId) {
		if (skyblockId == null || skyblockId.isBlank()) return Optional.empty();
		return cache.computeIfAbsent(skyblockId, this::loadWikiUrl);
	}

	private Optional<String> loadWikiUrl(String skyblockId) {
		try {
			Path file = NeuRepoManager.getInstance().getRepoDir()
				.resolve("items")
				.resolve(skyblockId + ".json");

			if (!Files.isRegularFile(file)) return Optional.empty();

			JsonObject item = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
				.getAsJsonObject();

			if (!item.has("infoType") || !"WIKI_URL".equals(item.get("infoType").getAsString())) {
				return Optional.empty();
			}
			if (!item.has("info") || !item.get("info").isJsonArray()) return Optional.empty();

			JsonArray info = item.getAsJsonArray("info");
			for (JsonElement element : info) {
				if (!element.isJsonPrimitive()) continue;
				String url = element.getAsString();
				if (url != null && !url.isBlank()) return Optional.of(url);
			}
		} catch (Exception e) {
			LOGGER.debug("Could not read wiki URL for {}: {}", skyblockId, e.toString());
		}
		return Optional.empty();
	}
}
