package moe.example.skyblockrecipeviewer.repo;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUCraftingRecipe;
import io.github.moulberry.repo.data.NEUForgeRecipe;
import io.github.moulberry.repo.data.NEUKatUpgradeRecipe;
import io.github.moulberry.repo.data.NEUMobDropRecipe;
import moe.example.skyblockrecipeviewer.repo.essence.EssenceStore;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeStore;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class NeuRepoManager {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Repo");

	// Mods known to independently manage config/notenoughupdates/repo themselves. If any of
	// these are present, we treat that folder as read-only: no downloading, no deleting, no
	// GitHub checks on our part. Discovered the hard way - SkyHanni rewrites that folder on
	// its own schedule, and our mod trying to also write to it at the same time caused a
	// race where we'd read half-deleted files mid-rewrite and crash.
	// (Must stay declared above INSTANCE - static fields init in textual order, and
	// INSTANCE's constructor reads this immediately.)
	private static final List<String> KNOWN_REPO_MANAGERS = List.of("skyhanni", "notenoughupdates", "firmament");

	private static final NeuRepoManager INSTANCE = new NeuRepoManager();

	private final RepoDownloader downloader;
	private final boolean weManageRepo;
	private final AtomicReference<NEURepository> loadedRepo = new AtomicReference<>();
	private CompletableFuture<NEURepository> loadInFlight;
	private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "skyblock-repo-loader");
		t.setDaemon(true);
		return t;
	});

	private NeuRepoManager() {
		// The original NotEnoughUpdates mod (and anything sharing its conventions, e.g.
		// Firmament, SkyHanni) stores its downloaded item repo at config/notenoughupdates/repo,
		// versioned via config/notenoughupdates/currentCommit.json. We always read from that
		// shared location; whether we're also allowed to write to it depends on whether
		// something else already claims that job.
		Path neuConfigDir = FabricLoader.getInstance().getConfigDir().resolve("notenoughupdates");
		this.downloader = new RepoDownloader(neuConfigDir, LOGGER);

		this.weManageRepo = KNOWN_REPO_MANAGERS.stream().noneMatch(id -> FabricLoader.getInstance().isModLoaded(id));
		if (!weManageRepo) {
			LOGGER.info("Detected another installed mod that manages the shared NotEnoughUpdates "
				+ "item repo - reading it read-only and letting that mod keep it updated instead of "
				+ "also downloading/writing to it ourselves.");
		}
	}

	public static NeuRepoManager getInstance() {
		return INSTANCE;
	}

	/**
	 * The shared repo folder's root (config/notenoughupdates/repo, or wherever another mod's
	 * copy of it lives) - e.g. {@code getRepoDir().resolve("constants/petnums.json")}. The
	 * whole NotEnoughUpdates-REPO archive lands here (see RepoDownloader), not just the
	 * items/ folder NEURepository itself parses, so anything under constants/ is already on
	 * disk here the moment the repo has been downloaded once, with nothing extra to fetch.
	 */
	public Path getRepoDir() {
		return downloader.getRepoDir();
	}

	/**
	 * Loads whatever's already on disk right now, without touching the network. Safe/cheap to
	 * call repeatedly (e.g. on every REI plugin reload, not just once at mod init) - unlike an
	 * earlier version of this method, a failed read is NOT cached forever; each call retries
	 * fresh from disk, which matters because another mod (e.g. SkyHanni) can be mid-rewrite of
	 * the same shared folder at any given moment.
	 */
	/**
	 * Same as {@link #loadAsync()}, but skips hitting disk again if we already have a
	 * successfully loaded repo in memory. REI calls registerEntries/registerDisplays/etc as
	 * separate callbacks within the same reload pass; without this, each one independently
	 * re-parses the whole repo from disk, which on a slow first launch can be enough to blow
	 * past a caller's timeout even though the data was already available a moment earlier.
	 */
	public synchronized CompletableFuture<NEURepository> ensureLoaded() {
		NEURepository cached = loadedRepo.get();
		if (cached != null) return CompletableFuture.completedFuture(cached);
		return loadAsync();
	}

	public synchronized CompletableFuture<NEURepository> loadAsync() {
		if (loadInFlight != null) return loadInFlight;
		CompletableFuture<NEURepository> created =
			CompletableFuture.supplyAsync(this::loadFromDiskWithRetries, executor);
		loadInFlight = created;
		created.whenComplete((repository, error) -> {
			synchronized (NeuRepoManager.this) {
				if (loadInFlight == created) loadInFlight = null;
			}
		});
		return created;
	}

	/**
	 * Compares the local repo against GitHub's latest commit and downloads an update if
	 * there's a mismatch; otherwise leaves the on-disk copy untouched. Meant to be called
	 * once per connection to a Hypixel server, not on every launch. No-ops entirely if another
	 * mod already manages the shared repo folder (see weManageRepo).
	 *
	 * REI does not cleanly support re-registering displays mid-session (it logs a "runtime
	 * DisplayRegistry modification" warning if you try), so an update here only takes full
	 * effect after the next REI plugin reload or game restart - the returned future
	 * indicates whether that's now the case, so the caller can notify the player.
	 *
	 * @return a future completing with true if an update was downloaded, false if already
	 *         current, the check failed (e.g. offline), or another mod owns this folder
	 */
	public CompletableFuture<Boolean> checkForUpdatesOnJoin() {
		if (!weManageRepo) {
			return CompletableFuture.completedFuture(false);
		}
		return CompletableFuture.supplyAsync(() -> {
			var newSha = downloader.checkForUpdate();
			if (newSha.isEmpty()) return false;
			boolean applied = downloader.downloadAndApply(newSha.get());
			if (applied) {
				loadFromDiskWithRetries();
			}
			return applied;
		}, executor);
	}

	/**
	 * Retries a few times with a short delay before giving up, since a transient read failure
	 * here usually just means another mod is mid-rewrite of the same shared folder right now,
	 * not that anything is actually broken.
	 */
	private NEURepository loadFromDiskWithRetries() {
		Path repoDir = downloader.getRepoDir();
		Exception lastError = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			NEURepository repository = NEURepository.of(repoDir);
			try {
				repository.reload();
				// Pre-build the derived recipe index on the background repo-loader thread so the
				// first REI lookup does not have to walk every item. NPC-shop indexing is also
				// kicked off here; it has its own worker because it reads raw item JSON from disk.
				ensureRecipeCache(repository);
				// Reforge/essence data lives in constants/*.json, which the repo download
				// already puts on disk under repoDir regardless of whether NEURepository's own
				// item parsing succeeded - reload these straight off disk here too, right after
				// a confirmed-good repo load, so they stay in lockstep with it (same retry-on-
				// failure behavior, same "another mod may be mid-rewrite" tolerance).
				ReforgeStore.getInstance().reload(repoDir);
				EssenceStore.getInstance().reload(repoDir);
				loadedRepo.set(repository);
				NpcShopIndex.ensureBuilt(this, repository);
				return repository;
			} catch (Exception e) {
				lastError = e;
				if (attempt < 3) {
					try {
						Thread.sleep(1500);
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		}
		LOGGER.error("Failed to parse SkyBlock item repo at {} after 3 attempts - if another "
			+ "SkyBlock mod also manages this folder, it may just have been mid-update each time; "
			+ "this should resolve itself on the next reload.", repoDir, lastError);
		return loadedRepo.get();
	}

	public NEURepository getLoadedRepoOrNull() {
		return loadedRepo.get();
	}

	/**
	 * Recipe data is embedded in every NEU item. The old implementation walked all ~9k
	 * items every time REI asked for a focused recipe/usage view, then repeated that work for
	 * crafting, forge, mob-drop and Kat recipes independently. REI can call those generators
	 * frequently while hovering/searching, so cache the extracted lists for the current repo.
	 * The cache is keyed by the NEURepository instance: a real repo reload produces a new
	 * instance and therefore automatically invalidates it.
	 */
	private final Object recipeCacheLock = new Object();
	private volatile NEURepository recipeCacheRepo;
	private volatile List<NEUCraftingRecipe> cachedCraftingRecipes = List.of();
	private volatile List<NEUForgeRecipe> cachedForgeRecipes = List.of();
	private volatile List<NEUMobDropRecipe> cachedMobDropRecipes = List.of();
	private volatile List<NEUKatUpgradeRecipe> cachedPetUpgradeRecipes = List.of();

	private void ensureRecipeCache(NEURepository repo) {
		if (recipeCacheRepo == repo) return;
		synchronized (recipeCacheLock) {
			if (recipeCacheRepo == repo) return;

			List<NEUCraftingRecipe> crafting = new java.util.ArrayList<>();
			List<NEUForgeRecipe> forge = new java.util.ArrayList<>();
			List<NEUMobDropRecipe> mobDrops = new java.util.ArrayList<>();
			List<NEUKatUpgradeRecipe> petUpgrades = new java.util.ArrayList<>();

			for (io.github.moulberry.repo.data.NEUItem item : repo.getItems().getItems().values()) {
				var recipes = item.getRecipes();
				if (recipes == null || recipes.isEmpty()) continue;

				List<NEUCraftingRecipe> itemCrafting = new java.util.ArrayList<>();
				List<NEUForgeRecipe> itemForge = new java.util.ArrayList<>();
				List<NEUMobDropRecipe> itemMobDrops = new java.util.ArrayList<>();
				List<NEUKatUpgradeRecipe> itemPetUpgrades = new java.util.ArrayList<>();

				for (var recipe : recipes) {
					if (recipe instanceof NEUCraftingRecipe r) itemCrafting.add(r);
					if (recipe instanceof NEUForgeRecipe r) itemForge.add(r);
					if (recipe instanceof NEUMobDropRecipe r) itemMobDrops.add(r);
					if (recipe instanceof NEUKatUpgradeRecipe r) itemPetUpgrades.add(r);
				}

				crafting.addAll(RecipeDedup.dedupe(itemCrafting, RecipeDedup::craftingSignature));
				forge.addAll(RecipeDedup.dedupe(itemForge, RecipeDedup::forgeSignature));
				mobDrops.addAll(RecipeDedup.dedupe(itemMobDrops, RecipeDedup::mobDropSignature));
				petUpgrades.addAll(RecipeDedup.dedupe(itemPetUpgrades, RecipeDedup::katUpgradeSignature));
			}

			cachedCraftingRecipes = List.copyOf(crafting);
			cachedForgeRecipes = List.copyOf(forge);
			cachedMobDropRecipes = List.copyOf(mobDrops);
			cachedPetUpgradeRecipes = List.copyOf(petUpgrades);
			recipeCacheRepo = repo;
		}
	}

	public List<NEUCraftingRecipe> getCraftingRecipes() {
		NEURepository repo = loadedRepo.get();
		if (repo == null) return List.of();
		ensureRecipeCache(repo);
		return cachedCraftingRecipes;
	}

	public List<io.github.moulberry.repo.data.NEUItem> getAllItems() {
		NEURepository repo = loadedRepo.get();
		if (repo == null) return List.of();
		return List.copyOf(repo.getItems().getItems().values());
	}

	public List<NEUForgeRecipe> getForgeRecipes() {
		NEURepository repo = loadedRepo.get();
		if (repo == null) return List.of();
		ensureRecipeCache(repo);
		return cachedForgeRecipes;
	}

	/** Mob-drop tables (what a given mob drops, its XP/coins) embedded in the item repo. */
	public List<NEUMobDropRecipe> getMobDropRecipes() {
		NEURepository repo = loadedRepo.get();
		if (repo == null) return List.of();
		ensureRecipeCache(repo);
		return cachedMobDropRecipes;
	}

	/** Kat pet-rarity-upgrade recipes (e.g. Epic -> Legendary pet upgrades). */
	public List<NEUKatUpgradeRecipe> getPetUpgradeRecipes() {
		NEURepository repo = loadedRepo.get();
		if (repo == null) return List.of();
		ensureRecipeCache(repo);
		return cachedPetUpgradeRecipes;
	}

}
