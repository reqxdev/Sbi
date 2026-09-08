package moe.example.skyblockrecipeviewer.repo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;

/**
 * Fetches Hypixel's own public SkyBlock item resource (api.hypixel.net/v2/resources/...),
 * which - unlike the community NEU repo - is the authoritative source for skull ("SKULL_ITEM")
 * textures: each entry optionally carries a "skin" object with the same base64
 * value+signature pair vanilla uses for custom player heads. This is a public /resources/
 * endpoint - no API key required, same as the NEU repo download, this is just a second
 * public data source with no personal/account data involved.
 *
 * Cached to this mod's own config folder (not the shared notenoughupdates/ one - this is
 * Hypixel-API-derived data, unrelated to the community repo other SkyBlock mods share).
 */
public final class HypixelSkinDownloader {

	private static final String ENDPOINT = "https://api.hypixel.net/v2/resources/skyblock/items";

	private final Path cacheFile;
	private final Logger logger;
	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.build();

	public HypixelSkinDownloader(Path configDir, Logger logger) {
		this.cacheFile = configDir.resolve("hypixel-items.json");
		this.logger = logger;
	}

	public Path getCacheFile() {
		return cacheFile;
	}

	/**
	 * Fetches the current resource from Hypixel and overwrites the local cache if it parses
	 * successfully. Meant to be called on Hypixel join, same cadence as the NEU repo update
	 * check - not on every launch.
	 *
	 * @return the parsed resource on success, or null if the fetch/parse/write failed
	 */
	public JsonObject fetchAndCacheReturningJson() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT)).GET().build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IOException("Hypixel API returned HTTP " + response.statusCode());
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			if (!json.has("success") || !json.get("success").getAsBoolean()) {
				throw new IOException("Hypixel API response did not report success");
			}
			Files.createDirectories(cacheFile.getParent());
			Files.writeString(cacheFile, response.body(), StandardCharsets.UTF_8);
			logger.info("Fetched Hypixel SkyBlock item skins ({} bytes).", response.body().length());
			return json;
		} catch (Exception e) {
			logger.warn("Could not fetch Hypixel SkyBlock item skins ({}).", e.toString());
			return null;
		}
	}

	/**
	 * @return true if the cache was written
	 * @deprecated use {@link #fetchAndCacheReturningJson()} - this discards the parsed data,
	 * forcing a caller who needs it to re-read from disk (which is the bug that meant
	 * refreshed skins never actually took effect until a full game restart).
	 */
	@Deprecated
	public boolean fetchAndCache() {
		return fetchAndCacheReturningJson() != null;
	}

	/**
	 * Reads whatever's cached on disk right now, without touching the network. Returns null
	 * if nothing has been cached yet (e.g. genuinely first launch, before ever joining
	 * Hypixel).
	 */
	public JsonObject loadFromDisk() {
		try {
			if (!Files.exists(cacheFile)) return null;
			return JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			logger.warn("Could not read cached Hypixel SkyBlock item skins ({}).", e.toString());
			return null;
		}
	}
}
