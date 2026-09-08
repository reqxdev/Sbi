package moe.example.skyblockrecipeviewer.repo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live SkyBlock market prices - deliberately only ever consumed by our OWN REI list entries
 * (see SkyblockReiPlugin.withPriceTooltip), never real inventory items (Skyblocker, if
 * installed, already covers those with its own, more detailed tooltip).
 *
 * Both markets are purely bulk-refreshed background jobs, queried via simple in-memory map
 * lookups - no per-item network call happens from anywhere, including from inside the
 * tooltip processor.
 *
 * Two refinements added after the very first cold launch caused a noticeable freeze (both
 * bulk fetches landing at the same moment as the very first heavy 8000+ item reload):
 *  1. Staggered, not parallel: Bazaar and Auction each refresh on their own independent timer,
 *     offset from each other by half the refresh interval, so their (bulk, but still real)
 *     network+parse work never lands in the same instant even once refreshes settle into a
 *     steady rhythm.
 *  2. Each is also cached to disk (same pattern as SkyblockItemCache) and loaded synchronously
 *     - fast, no network - at construction time, so a fresh launch has *something* (possibly a
 *     few minutes stale) to show immediately, rather than every tooltip showing nothing until
 *     the first live fetch completes.
 *
 *  - Bazaar: Hypixel's own official public endpoint returns every bazaar-tradeable product's
 *    order book in one request. Parsed directly from the order-book arrays rather than
 *    "quick_status", per real in-game verification: "sell_summary" is actually the BUY ORDER
 *    book, "buy_summary" is actually the SELL OFFER book (swapped from what the names suggest).
 *    The lowest "pricePerUnit" in each is used - sell_summary's lowest is what a player
 *    receives instantly selling; buy_summary's lowest is what a player pays instantly buying.
 *  - Auction House (lowest BIN): Coflnet's public {@code /api/prices/neu} endpoint - confirmed
 *    directly against its real output - is a flat {@code {itemId: lowestBinPrice}} map covering
 *    every priceable item at once, no rate limit, no API key.
 */
public final class SkyblockPriceManager {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Price");

	private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
	private static final String COFLNET_NEU_PRICES_URL = "https://sky.coflnet.com/api/prices/neu";
	private static final long REFRESH_INTERVAL_MINUTES = 5;

	private static final Path BAZAAR_CACHE_FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("skyblockrecipeviewer").resolve("bazaar-prices-cache.json");
	private static final Path AUCTION_CACHE_FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("skyblockrecipeviewer").resolve("auction-prices-cache.json");
	private static final SkyblockPriceManager INSTANCE = new SkyblockPriceManager();

	public static SkyblockPriceManager getInstance() {
		return INSTANCE;
	}

	/**
	 * Either field may be NaN if that side of the order book had no active listings at all
	 * (not the same as a price of 0, which could in principle be a real - if bizarre - price)
	 * - callers should check {@link Double#isNaN} before displaying a given side.
	 */
	public record BazaarPrice(double instantBuyPrice, double instantSellPrice) {
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "SkyblockRecipeViewer-PriceRefresh");
		t.setDaemon(true);
		return t;
	});

	private volatile Map<String, BazaarPrice> bazaarPrices = Map.of();
	private volatile Map<String, Double> auctionLowestBin = Map.of();

	private SkyblockPriceManager() {
		loadBazaarCacheFromDisk();
		loadAuctionCacheFromDisk();

		// Staggered, not parallel - see class docs. Auction starts at half the refresh
		// interval so its steady-state ticks never coincide with Bazaar's.
		scheduler.scheduleWithFixedDelay(this::refreshBazaarSafely, 0, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
		scheduler.scheduleWithFixedDelay(this::refreshAuctionSafely,
			Math.max(1, REFRESH_INTERVAL_MINUTES / 2), REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
	}

	public Optional<BazaarPrice> getBazaarPrice(String skyblockId) {
		if (skyblockId == null) return Optional.empty();
		return Optional.ofNullable(bazaarPrices.get(skyblockId));
	}

	/**
	 * Forces an immediate refresh outside the normal staggered 5-minute schedule (see class
	 * docs) - for /sbi pricereload. Submitted to the same background executor the scheduled
	 * refreshes already use, so this never blocks the calling (command/render) thread on the
	 * network request itself, and can't run concurrently with a scheduled refresh either
	 * (single-thread executor).
	 */
	public java.util.concurrent.CompletableFuture<Void> reloadBazaarAsync() {
		return java.util.concurrent.CompletableFuture.runAsync(this::refreshBazaarSafely, scheduler);
	}

	/** Same as {@link #reloadBazaarAsync()}, for the Auction House lowest-BIN data instead. */
	public java.util.concurrent.CompletableFuture<Void> reloadAuctionAsync() {
		return java.util.concurrent.CompletableFuture.runAsync(this::refreshAuctionSafely, scheduler);
	}

	/**
	 * Pure map lookup against the most recent bulk /api/prices/neu snapshot (or, before the
	 * first refresh completes this session, the on-disk cache from last time) - never
	 * triggers a network call.
	 */
	public Optional<Double> getAuctionLowestBin(String skyblockId) {
		if (skyblockId == null) return Optional.empty();
		return Optional.ofNullable(auctionLowestBin.get(skyblockId));
	}

	// ---- Disk cache: same plain-Gson-JsonObject pattern as SkyblockItemCache/HypixelSkinManager ----

	private void loadBazaarCacheFromDisk() {
		try {
			if (!Files.exists(BAZAAR_CACHE_FILE)) return;
			JsonObject root = JsonParser.parseString(Files.readString(BAZAAR_CACHE_FILE, StandardCharsets.UTF_8))
				.getAsJsonObject();
			if (!root.has("prices")) return;
			JsonObject prices = root.getAsJsonObject("prices");
			Map<String, BazaarPrice> loaded = new HashMap<>();
			int count = 0;
			for (String itemId : prices.keySet()) {
				try {
					JsonObject entry = prices.getAsJsonObject(itemId);
					double buy = entry.has("buy") ? entry.get("buy").getAsDouble() : Double.NaN;
					double sell = entry.has("sell") ? entry.get("sell").getAsDouble() : Double.NaN;
					loaded.put(itemId, new BazaarPrice(buy, sell));
					count++;
				} catch (Exception perItem) {
					LOGGER.warn("Skipping corrupt cached Bazaar entry for {} ({}).", itemId, perItem.toString());
				}
			}
			bazaarPrices = Map.copyOf(loaded);
			LOGGER.info("Loaded {} Bazaar prices from local cache (pending live refresh).", count);
		} catch (Exception e) {
			LOGGER.warn("Could not read local Bazaar price cache ({}).", e.toString());
		}
	}

	private void writeBazaarCacheToDisk() {
		try {
			JsonObject root = new JsonObject();
			JsonObject prices = new JsonObject();
			for (Map.Entry<String, BazaarPrice> entry : bazaarPrices.entrySet()) {
				JsonObject value = new JsonObject();
				if (!Double.isNaN(entry.getValue().instantBuyPrice())) value.addProperty("buy", entry.getValue().instantBuyPrice());
				if (!Double.isNaN(entry.getValue().instantSellPrice())) value.addProperty("sell", entry.getValue().instantSellPrice());
				prices.add(entry.getKey(), value);
			}
			root.add("prices", prices);
			Files.createDirectories(BAZAAR_CACHE_FILE.getParent());
			Files.writeString(BAZAAR_CACHE_FILE, root.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.error("Failed to write local Bazaar price cache.", e);
		}
	}

	private void loadAuctionCacheFromDisk() {
		try {
			if (!Files.exists(AUCTION_CACHE_FILE)) return;
			JsonObject root = JsonParser.parseString(Files.readString(AUCTION_CACHE_FILE, StandardCharsets.UTF_8))
				.getAsJsonObject();
			if (!root.has("prices")) return;
			JsonObject prices = root.getAsJsonObject("prices");
			Map<String, Double> loaded = new HashMap<>();
			int count = 0;
			for (String itemId : prices.keySet()) {
				try {
					JsonElement value = prices.get(itemId);
					if (!value.isJsonPrimitive()) continue;
					loaded.put(itemId, value.getAsDouble());
					count++;
				} catch (Exception perItem) {
					LOGGER.warn("Skipping corrupt cached auction entry for {} ({}).", itemId, perItem.toString());
				}
			}
			auctionLowestBin = Map.copyOf(loaded);
			LOGGER.info("Loaded {} Auction House lowest-BIN prices from local cache (pending live refresh).", count);
		} catch (Exception e) {
			LOGGER.warn("Could not read local Auction House price cache ({}).", e.toString());
		}
	}

	private void writeAuctionCacheToDisk() {
		try {
			JsonObject root = new JsonObject();
			JsonObject prices = new JsonObject();
			for (Map.Entry<String, Double> entry : auctionLowestBin.entrySet()) {
				prices.addProperty(entry.getKey(), entry.getValue());
			}
			root.add("prices", prices);
			Files.createDirectories(AUCTION_CACHE_FILE.getParent());
			Files.writeString(AUCTION_CACHE_FILE, root.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.error("Failed to write local Auction House price cache.", e);
		}
	}

	// ---- Live refresh ----

	private void refreshBazaarSafely() {
		try {
			refreshBazaar();
		} catch (Exception e) {
			LOGGER.warn("Failed to refresh Bazaar prices: {}", e.toString());
		}
	}

	private void refreshAuctionSafely() {
		try {
			refreshAuctionLowestBins();
		} catch (Exception e) {
			LOGGER.warn("Failed to refresh Auction House lowest-BIN prices: {}", e.toString());
		}
	}

	private void refreshAuctionLowestBins() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(COFLNET_NEU_PRICES_URL))
			.timeout(Duration.ofSeconds(20)).GET().build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			LOGGER.warn("Coflnet /api/prices/neu returned HTTP {}", response.statusCode());
			return;
		}
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		Map<String, Double> refreshed = new HashMap<>();
		int count = 0;
		for (String itemId : root.keySet()) {
			try {
				JsonElement value = root.get(itemId);
				if (!value.isJsonPrimitive()) continue;
				refreshed.put(itemId, value.getAsDouble());
				count++;
			} catch (Exception perItem) {
				LOGGER.warn("Skipping malformed price entry for {}: {}", itemId, perItem.toString());
			}
		}
		auctionLowestBin = Map.copyOf(refreshed);
		LOGGER.info("Refreshed {} Auction House lowest-BIN prices from Coflnet.", count);
		writeAuctionCacheToDisk();
	}

	private void refreshBazaar() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(BAZAAR_URL)).timeout(Duration.ofSeconds(15)).GET().build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			LOGGER.warn("Bazaar API returned HTTP {}", response.statusCode());
			return;
		}
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("products")) {
			LOGGER.warn("Bazaar API response missing success/products.");
			return;
		}
		JsonObject products = root.getAsJsonObject("products");
		Map<String, BazaarPrice> refreshed = new HashMap<>();
		int count = 0;
		for (String itemId : products.keySet()) {
			try {
				JsonObject product = products.getAsJsonObject(itemId);
				// sell_summary = the BUY ORDER book (what a player receives instantly selling);
				// buy_summary = the SELL OFFER book (what a player pays instantly buying) - see
				// class docs, confirmed against real in-game labels, not assumed from names.
				double instantSellPrice = lowestPricePerUnit(product, "sell_summary");
				double instantBuyPrice = lowestPricePerUnit(product, "buy_summary");
				if (Double.isNaN(instantBuyPrice) && Double.isNaN(instantSellPrice)) continue;
				refreshed.put(itemId, new BazaarPrice(instantBuyPrice, instantSellPrice));
				count++;
			} catch (Exception perItem) {
				LOGGER.warn("Skipping malformed Bazaar product {}: {}", itemId, perItem.toString());
			}
		}
		bazaarPrices = Map.copyOf(refreshed);
		LOGGER.info("Refreshed {} Bazaar product prices.", count);
		writeBazaarCacheToDisk();
	}

	/** @return the lowest "pricePerUnit" in product's {@code arrayKey} order-book array, or NaN if absent/empty. */
	private static double lowestPricePerUnit(JsonObject product, String arrayKey) {
		if (!product.has(arrayKey)) return Double.NaN;
		JsonArray array = product.getAsJsonArray(arrayKey);
		double lowest = Double.NaN;
		for (JsonElement element : array) {
			JsonObject entry = element.getAsJsonObject();
			if (!entry.has("pricePerUnit")) continue;
			double price = entry.get("pricePerUnit").getAsDouble();
			if (Double.isNaN(lowest) || price < lowest) lowest = price;
		}
		return lowest;
	}
}
