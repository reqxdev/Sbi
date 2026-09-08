package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUCraftingRecipe;
import io.github.moulberry.repo.data.NEUIngredient;
import io.github.moulberry.repo.data.NEUItem;
import me.shedaniel.rei.api.client.entry.filtering.base.BasicFilteringRule;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.category.visibility.CategoryVisibilityPredicate;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.visibility.DisplayVisibilityPredicate;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import dev.architectury.event.EventResult;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import moe.example.skyblockrecipeviewer.repo.HypixelSkinManager;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemCache;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;
import moe.example.skyblockrecipeviewer.repo.SkullTextureCache;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public class SkyblockReiPlugin implements REIClientPlugin {

	/**
	 * Tracks which NEURepository instance we've already live-pushed entries for, so the
	 * async completion callback (attached on every reload, see registerEntries) doesn't
	 * double-add the same items if the synchronous fast path already handled them. Static
	 * because REI plugin instances are recreated per reload, but the underlying data (and
	 * therefore "have we shown this yet") persists across that.
	 */
	private static volatile NEURepository lastLivePushedRepo = null;
	private static final Object pushLock = new Object();

	/**
	 * Set at the top of registerCategories(). NOT the first of the three callbacks REI calls on
	 * a plugin each reload - registerEntries() runs first (confirmed via REI's own reload log:
	 * "EntryRegistryImpl" phase completes before "CategoryRegistryImpl" starts) - but it is
	 * always called strictly *after* REI's own DefaultPlugin has finished registering its entry
	 * types, including "minecraft:item". That's the guarantee tryLivePush() actually needs (see
	 * its javadoc), so it still waits on this flag; registerEntries() just can't be the thing
	 * that sets it, and can't block long enough to risk registerCategories() never being reached
	 * before a reload gets cancelled.
	 */
	private static volatile boolean reiPluginsRegistered = false;

	/**
	 * Guards forceReiReloadIfNeeded() below. Not a one-shot: if a forced attempt gets
	 * cancelled by a Hypixel server hop before registerCategories() ever runs (same
	 * cancellation this class already deals with elsewhere), reiPluginsRegistered stays
	 * false and we do want to try again eventually. But retrying too eagerly is worse than
	 * not retrying at all: registerEntries() over ~8000 items is real, possibly
	 * multi-second work, and re-issuing ReloadStage.START every 2s (tryLivePush's existing
	 * retry cadence) before a previous attempt even finishes would keep restarting that
	 * work forever instead of ever letting it complete - a self-inflicted livelock. A
	 * cooldown gives a legitimate in-flight reload room to finish before we'd ever
	 * consider forcing a second one.
	 */
	private static volatile long lastForcedReloadAttemptMs = 0L;
	private static final long FORCE_RELOAD_COOLDOWN_MS = 15_000L;

	/**
	 * One-shot per process: once the disk cache has been read into
	 * SkyblockItemResolver's in-memory cache, there's no reason to re-read/re-parse the same
	 * JSON file on every subsequent reload pass - RESOLVED_CACHE already holds it (until a
	 * real repo load invalidates it, at which point live data has taken over anyway).
	 */
	private static volatile List<ItemStack> diskCachedItemStacks = null;
	/** True when an REI reload ran before the NEU repo was available, so displays need one
	 * automatic reload after the repo becomes usable. */
	private static volatile boolean repoBackedReiReloadNeeded = false;
	private static volatile long lastRepoBackedReloadMs = 0L;
	private static final long REPO_BACKED_RELOAD_COOLDOWN_MS = 5_000L;

	@Override
	public void registerCategories(CategoryRegistry registry) {
		reiPluginsRegistered = true;
		// Redundant safety net, not the primary call site anymore - registerEntries()
		// (which runs before this every reload) now calls this first, since it's the one
		// that actually needs SkyblockItemEntryDefinition.TYPE to exist already. Harmless to
		// call again here too: registerType() is a plain idempotent overwrite, not a
		// once-only static initializer anymore.
		SkyblockItemEntryDefinition.registerType();
		registry.add(new SkyblockCraftingCategory());
		registry.registerVisibilityPredicate(SkyblockCraftingCategory.hideVanillaCrafting());
		registry.add(new SkyblockReforgeCategory());
		registry.add(new SkyblockReforgeStoneCategory());
		registry.add(new SkyblockForgeCategory());
		registry.add(new SkyblockEssenceCategory());
		registry.add(new SkyblockPetUpgradeCategory());
		registry.add(new SkyblockMobDropCategory());
		registry.add(new SkyblockNpcShopCategory());
		registry.add(new SkyblockInfoCategory());

		Identifier skyblockerInfoId = Identifier.fromNamespaceAndPath("skyblocker", "skyblock_info");
		registry.registerVisibilityPredicate(new CategoryVisibilityPredicate() {
			@Override
			public double getPriority() { return 1000.0; }

			@Override
			public EventResult handleCategory(me.shedaniel.rei.api.client.registry.display.DisplayCategory<?> category) {
				return category.getCategoryIdentifier().getIdentifier().equals(skyblockerInfoId)
					? EventResult.interruptFalse()
					: EventResult.pass();
			}
		});
	}

	/**
	 * Hides every entry in REI's ingredient panel (the scrollable item list on the right) that
	 * isn't one of our own SkyBlock items. Without this, DefaultPlugin's vanilla registration
	 * (every plain Minecraft item/block) sits in that same panel right alongside the ~8000
	 * SkyBlock items this mod adds, which both roughly doubles the panel's size and makes
	 * searching for a SkyBlock item by name return a pile of unrelated vanilla results too.
	 *
	 * registerBasicEntryFiltering (not a one-shot removal in registerEntries) is REI's own
	 * intended hook for this - the Supplier below is re-evaluated by REI itself whenever it
	 * (re)filters the panel (a search, a config change, a real reload, ...), so this stays
	 * correct regardless of what order different plugins' registerEntries() callbacks happen
	 * to run in, or whether some other installed mod adds panel entries later than we do.
	 */
	@Override
	public void registerBasicEntryFiltering(BasicFilteringRule<?> rule) {
		rule.hide(() -> EntryRegistry.getInstance().getEntryStacks()
			.filter(stack -> !(stack.getValue() instanceof ItemStack item)
				|| !SkyblockItemEntryDefinition.hasSkyblockMarker(item))
			.collect(java.util.stream.Collectors.toList()));
	}

	/**
	 * Without this, REI only ever sees these items as ingredients buried inside a specific
	 * recipe's slot - it never gets a standalone list of "these SkyBlock items exist", which
	 * is what its search panel, "find usages"/"find recipes for" lookups, and general entry
	 * bookkeeping are built around.
	 *
	 * MUST NOT BLOCK. Confirmed from REI's own reload log that its phases run in this order:
	 * EntryRegistryImpl (this method) -> ... -> CategoryRegistryImpl (registerCategories(),
	 * which is what flips reiPluginsRegistered) - the opposite of what an earlier version of
	 * this file assumed. REI runs every plugin's registerEntries() back-to-back on one shared
	 * reload thread, and that whole reload is cancelled and restarted from scratch on a server
	 * disconnect ("Player quit, clearing reload tasks!" in REI's log). On Hypixel, joining
	 * silently hops the client between backend servers (limbo -> lobby -> ...), and each hop is
	 * a full disconnect+reconnect from REI's perspective - so this method previously blocking
	 * here for up to 30s (awaitRepoAndSkins) meant the reload got cancelled by the next hop
	 * before registerCategories() ever ran even once, and reiPluginsRegistered stayed false
	 * forever - which is why tryLivePush() below just looped "REI hasn't finished registering
	 * its own plugins yet" indefinitely and no items ever appeared on a straight Hypixel join,
	 * while singleplayer (no hopping, no cancellation) worked fine. Only ever add what's
	 * already sitting in memory *right now* (a cheap, instant check); everything else is left
	 * entirely to the async tryLivePush() path at the bottom, which isn't part of this
	 * cancellable reload pass at all and so isn't affected by any of this.
	 */
	@Override
	public void registerEntries(EntryRegistry registry) {
		// Must happen here, first, every single call - not just once via static init (see
		// registerType()'s own javadoc for why that was the actual bug), and not only in
		// registerCategories() (too late: registerEntries() runs before it every reload, and
		// this method needs SkyblockItemEntryDefinition.TYPE to already exist the moment it
		// tries to build any EntryStack with it, a few lines below).
		SkyblockItemEntryDefinition.registerType();

		NeuRepoManager manager = NeuRepoManager.getInstance();
		// Never resolve thousands of item stacks inside REI's initialization callback, even if
		// the repo happens to have finished loading already. The expensive SNBT/DFU/item-skin
		// conversion is prepared as one background pass by tryLivePush() below; this callback
		// should only register already-cached stacks. Doing the resolve here was the remaining
		// source of cold-start REI stutter: timing could make the repo ready before REI called
		// registerEntries(), which then moved the entire ~8k-item conversion onto REI's reload
		// thread.
		pushCachedItemEntries(registry);
		SkyblockRecipeViewer.LOGGER.info(
			"Using the persistent SkyBlock item cache during REI initialization; item data is "
				+ "repo-SHA cached and is only rebuilt when the repo changes.");

		// .thenRun() runs on whatever thread completes the future - our own background
		// executors (NeuRepoManager/HypixelSkinManager) here, not the main client thread.
		// REI's registries (and VanillaEntryTypes.ITEM's lazy resolution, which
		// SkyblockItemEntryDefinition's static init touches) aren't safe to touch off-thread -
		// doing so throws inside that static initializer, which then poisons the class
		// permanently (NoClassDefFoundError on every subsequent call) until the next restart.
		// Hop back onto the render thread first, same as REI itself always calls
		// registerEntries/registerDisplays on the main thread to begin with. Kicking these off
		// (ensureLoaded, not awaiting them) is still fine to do here since it's just scheduling
		// background work, not blocking on it.
		CompletableFuture.allOf(manager.ensureLoaded(), HypixelSkinManager.getInstance().ensureLoaded())
			.thenRun(SkyblockReiPlugin::tryLivePush);
	}

	/**
	 * Instant, network-free fallback for when live repo/skin data isn't loaded yet this
	 * session: reads SkyblockItemCache's on-disk cache (if any exists from a previous
	 * successful run) straight into REI, so the very first thing the player sees isn't an
	 * empty search panel. See registerEntries()'s call site and SkyblockItemCache's class
	 * javadoc for why a possibly-one-version-stale list here is an acceptable, deliberate
	 * tradeoff - tryLivePush() corrects it shortly after if the repo actually changed.
	 */
	private static void pushCachedItemEntries(EntryRegistry registry) {
		// REI clears and rebuilds its EntryRegistry on every real plugin reload. The expensive
		// part is resolving the NEU item data into fully-NBT'd ItemStacks, not constructing the
		// lightweight EntryStack wrappers. Keep the resolved ItemStacks in memory after the
		// first disk read and simply re-register those wrappers on every reload.
		List<ItemStack> cached = diskCachedItemStacks;
		if (cached == null) {
			if (SkyblockItemCache.isStale()) return;
			Map<String, ItemStack> loaded = SkyblockItemCache.loadIntoResolverCache();
			if (loaded.isEmpty()) return;
			cached = List.copyOf(loaded.values());
			diskCachedItemStacks = cached;
		}

		List<EntryStack<ItemStack>> entries = new ArrayList<>(cached.size());
		for (ItemStack stack : cached) {
			if (!stack.isEmpty()) entries.add(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack));
		}
		registry.addEntries(entries);
		SkyblockRecipeViewer.LOGGER.info(
			"Registered {} SkyBlock item entries from the persistent item cache.", entries.size());
	}

	/**
	 * Pushes whatever repo/skin data is currently loaded into REI's live entry list, if it's
	 * newer than what was last pushed. Safe to call from anywhere, any number of times - the
	 * lastLivePushedRepo guard makes repeat/redundant calls a cheap no-op.
	 *
	 * This is the piece that was missing for straight multiplayer joins: a Hypixel-join
	 * download (see NeuRepoManager.checkForUpdatesOnJoin / HypixelSkinManager.
	 * refreshFromNetwork) updates the underlying data directly, but neither of those know
	 * anything about REI - without an explicit call to this afterward, nothing tells REI new
	 * data exists until some unrelated event happens to trigger a full plugin reload (e.g.
	 * loading into a singleplayer world), which is exactly the "works after singleplayer,
	 * not on straight multiplayer" symptom this fixes.
	 *
	 * CRASH HISTORY: this used to fire the instant our own repo/skin data was ready, which on
	 * a join where everything was already cached (e.g. another mod like SkyHanni had already
	 * loaded the repo) could be within ~2 seconds of joining - faster than REI's own
	 * asynchronous first reload of *its* plugins. That let this reach
	 * SkyblockItemEntryDefinition (and, through it, VanillaEntryTypes.ITEM.getDefinition())
	 * before REI's DefaultPlugin had registered "minecraft:item" at all, throwing a
	 * NullPointerException out of a static initializer - which is unrecoverable for the rest
	 * of the session (a failed <clinit> permanently poisons the class) and crashed the whole
	 * client. Waiting on reiPluginsRegistered (set from registerCategories(), which REI only
	 * ever calls after its own entry types exist) closes that race at the source, instead of
	 * just guessing at a "should be long enough" delay.
	 */
	public static void tryLivePush() {
		// Bail out of the retry chain once we're no longer connected to a server (quit back to
		// the main menu, or disconnected). Without this, every join that happens to land before
		// REI's own plugin reload finishes (e.g. REI cancels and restarts that reload on every
		// disconnect - see "Player quit, clearing reload tasks!" - so a run of quick reconnects
		// can repeatedly outrace it) leaves its 2s retry loop running forever in the background,
		// and a session with several such joins ends up with several of these dangling loops
		// stacked on top of each other, spamming the log indefinitely.
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			SkyblockRecipeViewer.LOGGER.info(
				"No longer connected to a server - abandoning this live item push attempt.");
			return;
		}
		// Whether REI's own plugin machinery has ever actually run this session comes first,
		// and is checked independently of isStale() below - they answer different questions.
		// isStale() only tells us our *data* hasn't changed since we last wrote the cache; it
		// says nothing about whether REI itself has ever registered a single category/entry/
		// display this session. On Hypixel specifically (see forceReiReloadIfNeeded's
		// javadoc), that real reload may simply never have happened yet at all - and if the
		// repo also happens to be up to date (the common case on a second+ join, or any join
		// after the very first one this process has seen), isStale() alone would report
		// "nothing to do" and this method would return without ever forcing that reload,
		// leaving REI's category tab and crafting-recipe displays permanently absent for the
		// rest of the session even though the disk-cache fast path in registerEntries() shows
		// *items* just fine - exactly the "REI hasn't attempted to load this time" symptom.
		if (!reiPluginsRegistered) {
			// forceReiReloadIfNeeded() itself stays on the main thread explicitly here -
			// it's cheap (just calls REI's own reload-trigger methods, no per-item work),
			// and unlike the resolve loop below, its threading requirements were never
			// actually in question, so there's no reason to change how it's invoked while
			// fixing the freeze this method used to cause elsewhere.
			net.minecraft.client.Minecraft.getInstance().execute(SkyblockReiPlugin::forceReiReloadIfNeeded);
			SkyblockRecipeViewer.LOGGER.info(
				"REI hasn't finished registering its own plugins yet - deferring live item push "
					+ "by 2s rather than risk touching its entry-type registry too early.");
			CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(SkyblockReiPlugin::tryLivePush);
			return;
		}
		// The whole point of SkyblockItemCache: if the repo hasn't actually changed since we
		// last wrote this cache (same commit sha), whatever's already showing - either from
		// the disk-cache fast path in registerEntries(), or a normal reload elsewhere this
		// session - is already correct. Forcing a REI reload here regardless (the previous
		// behavior) meant every single Hypixel join forced one, even on the overwhelmingly
		// common case where nothing was actually new. Only bother touching REI at all once
		// we know there's a real reason to.
		if (repoBackedReiReloadNeeded) {
			requestRepoBackedReiReload();
		}
		if (!SkyblockItemCache.isStale()) {
			SkyblockRecipeViewer.LOGGER.info(
				"SkyBlock item repo unchanged since the local cache was last written - skipping "
					+ "any reload, the items already shown are already correct.");
			return;
		}
		try {
			NeuRepoManager manager = NeuRepoManager.getInstance();
			NEURepository loaded = manager.getLoadedRepoOrNull();
			if (loaded == null) return;
			synchronized (pushLock) {
				if (loaded == lastLivePushedRepo) return;
				lastLivePushedRepo = loaded;
			}
			// THE ACTUAL FREEZE FIX: this used to happen inside pushEntriesAndWriteCache(),
			// called from this same method while it ran entirely on the main thread (this
			// whole method was wrapped in Minecraft.execute(...) at its one and only call
			// site in registerEntries()). Resolving ~8000 items - SNBT parse +
			// DataFixerUpper upgrade + ItemStack.CODEC.parse + skull profile resolution,
			// per item - synchronously on the thread that draws every frame is exactly what
			// froze the game on a cold cache. This method is now called directly from a
			// background executor (see registerEntries()'s .thenRun, no more
			// Minecraft.execute() wrapper around the call to this method itself), so this
			// heavy work now happens off-thread; only the actual registry.addEntries/
			// refilter calls below get hopped onto the main thread, since those - and only
			// those - touch REI's live registry/rendering state.
			// This method runs asynchronously, independent of any single reload pass, so the
			// REI entry type may have been reset by the time the batch is handed back to the
			// client thread. Registering it again there is cheap and idempotent.
			// Only resolve vanilla ItemStacks on the background thread. Do NOT construct REI
			// EntryStacks here: the EntryType registry is REI state and belongs on the client
			// thread. The expensive part is the item resolution itself, so doing that as one
			// background batch keeps both REI initialization and rendering responsive.
			SkyblockItemResolver.invalidateCacheIfRepoChanged(loaded);
			List<ItemStack> resolvedStacks = resolveAllItemStacks(manager);
			String repoSha = SkyblockItemCache.currentRepoSha();
			Map<String, ItemStack> snapshot = SkyblockItemResolver.snapshotResolvedCache();

			net.minecraft.client.Minecraft.getInstance().execute(() -> {
				SkyblockItemEntryDefinition.registerType();
				EntryRegistry registry = EntryRegistry.getInstance();
				List<EntryStack<ItemStack>> entries = new ArrayList<>(resolvedStacks.size());
				for (ItemStack stack : resolvedStacks) {
					if (!stack.isEmpty()) entries.add(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack));
				}
				registry.addEntries(entries);
				registry.refilter();
				SkyblockRecipeViewer.LOGGER.info("Registered {} SkyBlock item entries in REI as one batch.", entries.size());
			});

			// Disk I/O, not render-thread-sensitive - fine to do right here, same as the
			// resolve loop above.
			SkyblockItemCache.write(repoSha, snapshot);
			if (repoBackedReiReloadNeeded) {
				requestRepoBackedReiReload();
			}
			// Every skull resolved during resolveAllItemStacks() above went through
			// SkullProfileResolver (see SkyblockItemResolver.applyDirectSkullProfile), which
			// populates SkullTextureCache in memory as it goes - persist that now so next
			// launch/reload against the same repo commit skips re-regexing every skull's raw
			// nbttag entirely (see SkullTextureCache's class javadoc for how this relates to
			// SkyblockItemCache's own, separate cache).
			SkullTextureCache.flush();
		} catch (Throwable t) {
			// Defense in depth only - reiPluginsRegistered should already rule out the one
			// known cause of this. Catching Throwable (not just Exception) specifically
			// because the historical crash here was an Error (ExceptionInInitializerError),
			// which a plain "catch (Exception e)" would NOT have caught.
			SkyblockRecipeViewer.LOGGER.error("Live item push into REI failed unexpectedly.", t);
		}
	}

	/**
	 * The initial REI reload can legitimately finish before the shared NEU repo is ready.
	 * Item entries can then be pushed later, but recipe/display registration only happens
	 * during an REI plugin reload. Once the repo is ready, schedule exactly one normal reload
	 * so the dynamic displays are populated automatically instead of requiring the user to
	 * press REI Reload. The short delay lets the REI reload that originally observed the empty
	 * repo finish cleanly before we start another one.
	 */
	private static void requestRepoBackedReiReload() {
		if (!repoBackedReiReloadNeeded) return;
		long now = System.currentTimeMillis();
		if (now - lastRepoBackedReloadMs < REPO_BACKED_RELOAD_COOLDOWN_MS) return;
		lastRepoBackedReloadMs = now;
		CompletableFuture.delayedExecutor(750, TimeUnit.MILLISECONDS).execute(() ->
			net.minecraft.client.Minecraft.getInstance().execute(() -> {
				if (net.minecraft.client.Minecraft.getInstance().level == null) return;
				if (!reiPluginsRegistered) {
					forceReiReloadIfNeeded();
					return;
				}
				repoBackedReiReloadNeeded = false;
				SkyblockRecipeViewer.LOGGER.info(
					"NEU repo is ready after an early REI reload; refreshing REI once to register "
						+ "SkyBlock recipe displays automatically.");
				me.shedaniel.rei.RoughlyEnoughItemsCoreClient.reloadPlugins(
					null, me.shedaniel.rei.api.common.registry.ReloadStage.START,
					net.minecraft.client.Minecraft.getInstance().level.registryAccess());
				me.shedaniel.rei.RoughlyEnoughItemsCoreClient.reloadPlugins(
					null, me.shedaniel.rei.api.common.registry.ReloadStage.END);
			}));
	}

	/**
	 * Confirmed by decompiling RoughlyEnoughItems-26.1.819.jar: REI only ever starts a
	 * plugin reload from two places, both in RoughlyEnoughItemsCoreClient - a
	 * PRE_UPDATE_RECIPES hook that calls
	 * {@code reloadPlugins(null, ReloadStage.START, registryAccess)}, and a tag-update hook
	 * that calls {@code reloadPlugins(sharedTimer, ReloadStage.END)}. Both are driven by
	 * vanilla ClientboundUpdateRecipesPacket/ClientboundUpdateTagsPacket - and across an
	 * entire Hypixel session, latest.log never contains a single "Loaded N recipes" line
	 * (the log message that fires when that recipes packet is handled), while it fires
	 * within ~2 seconds of every singleplayer world load. So on Hypixel this isn't a race or
	 * a cancellation - REI's reload machinery is never invoked at all, which is why
	 * reiPluginsRegistered (only ever set from registerCategories(), which only a real
	 * reload calls) stayed false for the whole session no matter how long tryLivePush's
	 * retry loop waited.
	 *
	 * The fix: call the exact same public entrypoint REI's own hooks call. This runs a real
	 * reload through REI's normal machinery - registerCategories/registerEntries/
	 * registerDisplays all fire properly - rather than continuing to hand-replicate pieces
	 * of it (tryLivePush's manual EntryRegistry push below only ever covered item entries;
	 * it has no equivalent for recipe displays, which is why those never appeared on
	 * Hypixel even with that workaround in place).
	 */
	private static void forceReiReloadIfNeeded() {
		if (reiPluginsRegistered) return;
		long now = System.currentTimeMillis();
		if (now - lastForcedReloadAttemptMs < FORCE_RELOAD_COOLDOWN_MS) return;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) return;
		lastForcedReloadAttemptMs = now;
		SkyblockRecipeViewer.LOGGER.info(
			"REI hasn't completed a real plugin reload yet and Hypixel never sends the "
				+ "recipe/tag packets REI normally waits on to start one itself - forcing a "
				+ "reload now.");
		// null timer on START: mirrors RoughlyEnoughItemsCoreClient's own recipe-update hook,
		// which also passes null here (no rate limit) - our own lastForcedReloadAttemptMs
		// cooldown above already prevents us from spamming this.
		me.shedaniel.rei.RoughlyEnoughItemsCoreClient.reloadPlugins(
			null, me.shedaniel.rei.api.common.registry.ReloadStage.START, mc.level.registryAccess());
		me.shedaniel.rei.RoughlyEnoughItemsCoreClient.reloadPlugins(
			null, me.shedaniel.rei.api.common.registry.ReloadStage.END);
	}

	/**
	 * The actual CPU-heavy part of resolving every SkyBlock item: SNBT parsing +
	 * DataFixerUpper upgrade + ItemStack.CODEC.parse + skull profile resolution, per item,
	 * over ~8000 items on a cold cache. Pure data work - nothing in here touches REI's
	 * registries, rendering state, or anything else that requires the main render thread, so
	 * callers are free to run this on a background thread. That distinction is why this is
	 * split out on its own: it used to be inlined directly inside pushEntries(), which
	 * tryLivePush() ran via Minecraft.execute(...) - meaning this entire multi-thousand-item
	 * loop ran synchronously on the thread that draws every frame, freezing the game for
	 * however long a cold resolve took. Only the (cheap) registry.addEntries/refilter calls
	 * actually need the main thread.
	 */
	private static List<ItemStack> resolveAllItemStacks(NeuRepoManager manager) {
		List<ItemStack> stacks = new ArrayList<>();
		for (NEUItem item : manager.getAllItems()) {
			ItemStack stack = SkyblockItemResolver.resolveItemStack(item, item.getSkyblockItemId());
			if (stack.isEmpty()) continue;
			stacks.add(stack);
		}
		return stacks;
	}

	@Override
	public void registerScreens(me.shedaniel.rei.api.client.registry.screen.ScreenRegistry registry) {
		registry.registerFocusedStack(new SkyblockFocusedStackProvider());
	}

	@Override
	public void registerDisplays(DisplayRegistry registry) {
		// Must happen unconditionally, before the early-return below: this generator checks
		// NeuRepoManager's *current* state on every call (see SkyblockCraftingDisplayGenerator),
		// not a snapshot from this reload pass, so it needs to be registered now regardless of
		// whether the repo happens to be ready yet - it's what answers "recipes for X"/
		// "usages of X" lookups later, e.g. after joining Hypixel, when no further reload
		// happens to re-run this method at all.
		registry.registerDisplayGenerator(SkyblockCraftingCategory.ID, SkyblockCraftingDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockReforgeCategory.ID, SkyblockReforgeDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockReforgeStoneCategory.ID, SkyblockReforgeStoneDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockForgeCategory.ID, SkyblockForgeDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockEssenceCategory.ID, SkyblockEssenceDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockPetUpgradeCategory.ID, SkyblockPetUpgradeDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockMobDropCategory.ID, SkyblockMobDropDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockNpcShopCategory.ID, SkyblockNpcShopDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockInfoCategory.ID, SkyblockInfoDisplayGenerator.INSTANCE);

		Identifier skyblockerInfoId = Identifier.fromNamespaceAndPath("skyblocker", "skyblock_info");
		registry.registerVisibilityPredicate(new DisplayVisibilityPredicate() {
			@Override
			public double getPriority() { return 1000.0; }

			@Override
			public EventResult handleDisplay(me.shedaniel.rei.api.client.registry.display.DisplayCategory<?> category, me.shedaniel.rei.api.common.display.Display display) {
				return category.getCategoryIdentifier().getIdentifier().equals(skyblockerInfoId)
					? EventResult.interruptFalse()
					: EventResult.pass();
			}
		});

		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository != null) {
			repoBackedReiReloadNeeded = false;
			registerFromRepo(registry, manager, repository);
		} else {
			repoBackedReiReloadNeeded = true;
			SkyblockRecipeViewer.LOGGER.info(
				"SkyBlock item repo is not loaded yet; dynamic display generators will use it "
					+ "as soon as the background repo load completes.");
		}
	}

	/**
	 * Used to eagerly register every crafting/reforge/forge/essence/pet-upgrade/mob-drop
	 * display directly via registry.add() here, in addition to each category's
	 * DynamicDisplayGenerator (registered above) separately answering the exact same
	 * "recipes for X"/"usages of X" lookups on demand. Both paths independently produced the
	 * same displays, and REI has no way to know two displays from two different registration
	 * paths are "the same recipe" - it just shows everything it's been given - so every recipe
	 * was appearing twice (once from this eager pass, once from the generator) whenever both
	 * had run, which in practice was almost always (this only stayed unnoticed on the very
	 * first Hypixel-join case the generators were built to fix, where this eager pass hadn't
	 * managed to run at all yet).
	 *
	 * Now that each generator's generate() (used for plain category-tab browsing, with no
	 * specific item focused) also returns the full list rather than nothing, the generators
	 * fully cover every case this eager pass used to - so this pass is just cache invalidation
	 * now, with no registration of its own left to do.
	 */
	private void registerFromRepo(DisplayRegistry registry, NeuRepoManager manager, NEURepository repository) {
		SkyblockItemResolver.invalidateCacheIfRepoChanged(repository);
	}

	static SkyblockCraftingDisplay toDisplay(NEURepository repository, NEUCraftingRecipe recipe) {
		// getInputs() returns a fixed-size NEUIngredient[9] (3x3 grid), not a List -
		// confirmed against Firmament's own SBCraftingRecipe.kt, the reference consumer
		// of this same parser library.
		NEUIngredient[] inputs = recipe.getInputs();
		if (inputs == null) return null;

		List<EntryIngredient> inputEntries = new ArrayList<>(9);
		for (int i = 0; i < 9; i++) {
			NEUIngredient ingredient = i < inputs.length ? inputs[i] : null;
			if (SkyblockItemResolver.isEmptySlot(ingredient)) {
				inputEntries.add(EntryIngredient.empty());
				continue;
			}
			ItemStack stack = SkyblockItemResolver.resolve(repository, ingredient);
			if (stack.isEmpty()) {
				inputEntries.add(EntryIngredient.empty());
			} else {
				inputEntries.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack)));
			}
		}

		NEUIngredient output = recipe.getOutput();
		List<EntryIngredient> outputEntries = new ArrayList<>(1);
		if (output != null) {
			ItemStack outStack = SkyblockItemResolver.resolve(repository, output);
			if (!outStack.isEmpty()) {
				outputEntries.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, outStack)));
			}
		}
		if (outputEntries.isEmpty()) return null;

		return new SkyblockCraftingDisplay(inputEntries, outputEntries);
	}

	static SkyblockReforgeDisplay toReforgeDisplay(NEURepository repository,
			moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData reforge) {
		return toReforgeDisplay(repository, reforge, List.of());
	}

	/**
	 * @param highlightRarities the specific rarity tiers to show for this lookup (e.g. an
	 *                          item's natural rarity plus its recombobulated one) - see
	 *                          {@link SkyblockReforgeDisplay#getHighlightRarities}. Empty when
	 *                          browsing the reforge category generally, with no specific item
	 *                          in context.
	 */
	static SkyblockReforgeDisplay toReforgeDisplay(NEURepository repository,
			moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData reforge, List<String> highlightRarities) {
		List<EntryIngredient> inputs = new ArrayList<>(1);
		if (reforge.reforgeStoneId() != null) {
			ItemStack stoneStack = SkyblockItemResolver.resolve(repository,
				NEUIngredient.fromString(reforge.reforgeStoneId() + ":1"));
			if (!stoneStack.isEmpty()) {
				inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stoneStack)));
			}
		}
		return new SkyblockReforgeDisplay(reforge, inputs, highlightRarities);
	}

	/**
	 * Stone-reforge counterpart of {@link #toReforgeDisplay} - returns null (skip this reforge)
	 * if its stone item can't actually be resolved to something displayable, since
	 * {@link SkyblockReforgeStoneCategory} has nothing to show without one (unlike the free-
	 * reforge category, which always has a generic Anvil icon to fall back on).
	 */
	static SkyblockReforgeStoneDisplay toReforgeStoneDisplay(NEURepository repository,
			moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData reforge, List<String> highlightRarities) {
		if (reforge.reforgeStoneId() == null) return null;
		ItemStack stoneStack = SkyblockItemResolver.resolve(repository,
			NEUIngredient.fromString(reforge.reforgeStoneId() + ":1"));
		if (stoneStack.isEmpty()) return null;
		List<EntryIngredient> inputs = List.of(
			EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stoneStack)));
		return new SkyblockReforgeStoneDisplay(reforge, inputs, highlightRarities);
	}

	static SkyblockForgeDisplay toForgeDisplay(NEURepository repository, io.github.moulberry.repo.data.NEUForgeRecipe recipe) {
		List<NEUIngredient> rawInputs = recipe.getInputs();
		if (rawInputs == null) return null;
		List<EntryIngredient> inputs = new ArrayList<>(rawInputs.size());
		for (NEUIngredient ingredient : rawInputs) {
			if (SkyblockItemResolver.isEmptySlot(ingredient)) continue;
			ItemStack stack = SkyblockItemResolver.resolve(repository, ingredient);
			if (!stack.isEmpty()) {
				inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack)));
			}
		}
		if (inputs.isEmpty()) return null;

		NEUIngredient output = recipe.getOutputStack();
		if (output == null) return null;
		ItemStack outStack = SkyblockItemResolver.resolve(repository, output);
		if (outStack.isEmpty()) return null;

		return new SkyblockForgeDisplay(recipe, inputs,
			List.of(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, outStack))));
	}

	static SkyblockEssenceDisplay toEssenceDisplay(NEURepository repository,
			moe.example.skyblockrecipeviewer.repo.essence.EssenceUpgradeRecipe recipe) {
		ItemStack itemStack = SkyblockItemResolver.resolve(repository,
			NEUIngredient.fromString(recipe.itemSkyblockId() + ":1"));
		if (itemStack.isEmpty()) return null;
		EntryIngredient itemEntry = EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, itemStack));

		List<EntryIngredient> inputs = new ArrayList<>();
		inputs.add(itemEntry);
		ItemStack essenceStack = SkyblockItemResolver.resolve(repository,
			NEUIngredient.fromString(recipe.essenceSkyblockId() + ":" + recipe.essenceCost()));
		if (!essenceStack.isEmpty()) {
			inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, essenceStack)));
		}
		for (String extraItemId : recipe.extraItemIds()) {
			NEUIngredient extraIngredient = NEUIngredient.fromString(extraItemId);
			if (SkyblockItemResolver.isEmptySlot(extraIngredient)) continue;
			ItemStack extraStack = SkyblockItemResolver.resolve(repository, extraIngredient);
			if (!extraStack.isEmpty()) {
				inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, extraStack)));
			}
		}

		return new SkyblockEssenceDisplay(recipe, inputs, List.of(itemEntry));
	}

	static SkyblockPetUpgradeDisplay toPetUpgradeDisplay(NEURepository repository,
			io.github.moulberry.repo.data.NEUKatUpgradeRecipe recipe) {
		NEUIngredient input = recipe.getInput();
		NEUIngredient output = recipe.getOutput();
		if (input == null || output == null) return null;

		ItemStack inputStack = SkyblockItemResolver.resolve(repository, input);
		ItemStack outputStack = SkyblockItemResolver.resolve(repository, output);
		if (inputStack.isEmpty() || outputStack.isEmpty()) return null;

		List<EntryIngredient> inputs = new ArrayList<>();
		inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, inputStack)));
		List<NEUIngredient> costItems = recipe.getItems();
		if (costItems != null) {
			for (NEUIngredient costItem : costItems) {
				if (SkyblockItemResolver.isEmptySlot(costItem)) continue;
				ItemStack costStack = SkyblockItemResolver.resolve(repository, costItem);
				if (!costStack.isEmpty()) {
					inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, costStack)));
				}
			}
		}

		return new SkyblockPetUpgradeDisplay(recipe, inputs,
			List.of(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, outputStack))));
	}

	static SkyblockMobDropDisplay toMobDropDisplay(NEURepository repository,
			io.github.moulberry.repo.data.NEUMobDropRecipe recipe) {
		if (recipe.getDrops() == null) return null;
		List<EntryIngredient> outputs = new ArrayList<>();
		List<String> chances = new ArrayList<>();
		List<List<String>> extras = new ArrayList<>();
		for (io.github.moulberry.repo.data.NEUMobDropRecipe.Drop drop : recipe.getDrops()) {
			NEUIngredient dropItem = drop.getDropItem();
			if (dropItem == null || SkyblockItemResolver.isEmptySlot(dropItem)) continue;
			ItemStack stack = SkyblockItemResolver.resolve(repository, dropItem);
			if (stack.isEmpty()) continue;
			String chance = drop.getChance();
			List<String> extra = drop.getExtra();
			// A drop with no chance at all is a "Hunting Drop" - obtained through some special
			// condition (e.g. "Catch with Pocket Black Hole") rather than a raw probability
			// roll, described in "extra" instead - confirmed against a real repo mob file
			// where exactly this pairing shows up (an attribute shard drop with no "chance" key
			// but a populated "extra" list). Baked directly into the item's own lore (same
			// proven-reliable technique already used for reforge stones) rather than via REI's
			// Widgets.withTooltip, whose append-vs-replace semantics against a Slot's own
			// existing item tooltip couldn't be confirmed from here.
			if (chance == null && extra != null && !extra.isEmpty()) {
				stack = withExtraLore(stack, "Hunting Drop", extra);
			} else if (chance != null) {
				// The chance is also drawn directly on the slot as a corner label (see
				// SkyblockMobDropCategory.DropChanceSlot) - it's baked into the lore here too,
				// on this per-display copy of the stack only (so it doesn't leak into every
				// other place this item shows up in REI), so it's still readable on hover even
				// once the on-slot label has to shrink/overlap for a long value like
				// "0.00004%".
				stack = withExtraLore(stack, null, List.of("Drop Chance: " + chance));
			}
			outputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack)));
			// Confirmed against a real repo mob file: a raw string like "100%"/"0.00004%",
			// already formatted for display - no numeric parsing needed. null (not every drop
			// has a chance at all - some are obtained through a special condition instead of a
			// raw probability roll, described in getExtra() instead - see SkyblockMobDropDisplay's
			// docs) just means no corner label for that drop.
			chances.add(chance);
			extras.add(extra);
		}
		if (outputs.isEmpty()) return null;
		return new SkyblockMobDropDisplay(recipe, outputs, chances, extras);
	}

	/**
	 * Appends extra lore lines to a copy of {@code stack}'s existing lore (same
	 * DataComponents.LORE technique already proven out for reforge stones). {@code heading}, if
	 * non-null, is added first as a bold gold line (e.g. "Hunting Drop") with a blank line
	 * before it; pass null to just append {@code lines} directly with no heading (used for the
	 * plain "Drop Chance: X%" line).
	 */
	private static ItemStack withExtraLore(ItemStack stack, String heading, List<String> lines) {
		List<Component> toAdd = new ArrayList<>();
		toAdd.add(Component.empty());
		if (heading != null) {
			toAdd.add(Component.literal(heading)
				.withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GOLD).withBold(true)));
		}
		for (String line : lines) {
			toAdd.add(Component.literal(line).withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GRAY)));
		}
		net.minecraft.world.item.component.ItemLore existing = stack.get(net.minecraft.core.component.DataComponents.LORE);
		List<Component> combined = new ArrayList<>();
		if (existing != null) combined.addAll(existing.lines());
		combined.addAll(toAdd);
		stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(combined));
		return stack;
	}
	@Override
	public double getPriority() {
		// Skyblocker uses -50, so our focused-stack provider is registered first.
		return 100.0;
	}

}
