package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.moulberry.repo.NEURepository;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeStore;

/**
 * Live "recipe for X" / "usage of X" lookups for FREE Blacksmith reforges only (no reforge
 * stone item involved - {@code reforge.reforgeStoneId() == null}) - see
 * {@link SkyblockCraftingDisplayGenerator}'s class docs for why this pattern (rather than only
 * an upfront batch registration) is needed for Hypixel/multiplayer reliability, and see
 * {@link SkyblockReforgeStoneDisplayGenerator} for the reforge-stone half of this same lookup
 * (split out because the two are shown very differently - a handful of detailed full-page
 * displays here, versus a compact icon grid there).
 *
 * A reforge shows up for an item in two ways: the item is explicitly allow-listed by SkyBlock id
 * in the reforge's eligibility list (a unique-item-specific reforge), or the item's own category
 * matches one of the reforge's eligible item types (an ordinary reforge). Both "recipe for" and
 * "usage of" resolve identically here - there isn't a meaningful distinction between "how do I
 * get this reforge" and "what can I use this item for" the way there is for crafting
 * inputs/outputs.
 */
public final class SkyblockReforgeDisplayGenerator implements DynamicDisplayGenerator<SkyblockReforgeDisplay> {

	public static final SkyblockReforgeDisplayGenerator INSTANCE = new SkyblockReforgeDisplayGenerator();

	private SkyblockReforgeDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockReforgeDisplay>> getRecipeFor(EntryStack<?> entry) {
		return lookup(entry);
	}

	@Override
	public Optional<List<SkyblockReforgeDisplay>> getUsageFor(EntryStack<?> entry) {
		return lookup(entry);
	}

	@Override
	public Optional<List<SkyblockReforgeDisplay>> generate(ViewSearchBuilder builder) {
		if (!builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository == null) return Optional.empty();

		List<SkyblockReforgeDisplay> displays = new ArrayList<>();
		for (ReforgeData reforge : ReforgeStore.getInstance().getAllReforges()) {
			if (reforge.reforgeStoneId() != null) continue; // stone-based - SkyblockReforgeStoneCategory's job
			SkyblockReforgeDisplay display = SkyblockReiPlugin.toReforgeDisplay(repository, reforge);
			if (display != null) displays.add(display);
		}
		return Optional.of(displays);
	}

	private static Optional<List<SkyblockReforgeDisplay>> lookup(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();

		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository == null) return Optional.empty();

		Set<ReforgeData> matches = ReforgeLookupSupport.matchesFor(manager, skyblockId);
		matches.removeIf(reforge -> reforge.reforgeStoneId() != null);
		if (matches.isEmpty()) return Optional.empty();

		List<String> highlightRarities = ReforgeLookupSupport.highlightRaritiesFor(repository, skyblockId);

		List<SkyblockReforgeDisplay> displays = new ArrayList<>();
		for (ReforgeData reforge : matches) {
			SkyblockReforgeDisplay display = SkyblockReiPlugin.toReforgeDisplay(repository, reforge, highlightRarities);
			if (display != null) displays.add(display);
		}
		return Optional.of(displays);
	}

	/**
	 * Delegates to SkyblockItemEntryDefinition.getSkyblockId(EntryStack), which also resolves
	 * real gameplay items in the player's own inventory (a plain VanillaEntryTypes.ITEM entry,
	 * not one of our own), so R/U work there too, not just on items in REI's own panel.
	 */
	private static String skyblockIdOf(EntryStack<?> entry) {
		return SkyblockItemEntryDefinition.getSkyblockId(entry);
	}
}
