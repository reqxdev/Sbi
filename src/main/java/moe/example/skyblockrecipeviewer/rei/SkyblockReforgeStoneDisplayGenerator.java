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
 * The reforge-STONE half of {@link SkyblockReforgeDisplayGenerator} (see its class docs for the
 * general "how does a reforge match an item" explanation, shared via {@link
 * ReforgeLookupSupport}) - restricted to reforges that have a physical stone item
 * ({@code reforge.reforgeStoneId() != null}), shown as a compact icon-grid via
 * {@link SkyblockReforgeStoneCategory} instead of {@link SkyblockReforgeCategory}'s detailed
 * full-page-per-reforge layout.
 */
public final class SkyblockReforgeStoneDisplayGenerator implements DynamicDisplayGenerator<SkyblockReforgeStoneDisplay> {

	public static final SkyblockReforgeStoneDisplayGenerator INSTANCE = new SkyblockReforgeStoneDisplayGenerator();

	private SkyblockReforgeStoneDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockReforgeStoneDisplay>> getRecipeFor(EntryStack<?> entry) {
		return lookup(entry);
	}

	@Override
	public Optional<List<SkyblockReforgeStoneDisplay>> getUsageFor(EntryStack<?> entry) {
		return lookup(entry);
	}

	@Override
	public Optional<List<SkyblockReforgeStoneDisplay>> generate(ViewSearchBuilder builder) {
		if (!builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository == null) return Optional.empty();

		List<SkyblockReforgeStoneDisplay> displays = new ArrayList<>();
		for (ReforgeData reforge : ReforgeStore.getInstance().getAllReforges()) {
			if (reforge.reforgeStoneId() == null) continue; // free reforge - SkyblockReforgeCategory's job
			SkyblockReforgeStoneDisplay display = SkyblockReiPlugin.toReforgeStoneDisplay(repository, reforge, List.of());
			if (display != null) displays.add(display);
		}
		return Optional.of(displays);
	}

	private static Optional<List<SkyblockReforgeStoneDisplay>> lookup(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();

		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository == null) return Optional.empty();

		Set<ReforgeData> matches = ReforgeLookupSupport.matchesFor(manager, skyblockId);
		matches.removeIf(reforge -> reforge.reforgeStoneId() == null);
		if (matches.isEmpty()) return Optional.empty();

		List<String> highlightRarities = ReforgeLookupSupport.highlightRaritiesFor(repository, skyblockId);

		List<SkyblockReforgeStoneDisplay> displays = new ArrayList<>();
		for (ReforgeData reforge : matches) {
			SkyblockReforgeStoneDisplay display =
				SkyblockReiPlugin.toReforgeStoneDisplay(repository, reforge, highlightRarities);
			if (display != null) displays.add(display);
		}
		return Optional.of(displays);
	}

	/** See SkyblockReforgeDisplayGenerator's identical method for why this delegates here. */
	private static String skyblockIdOf(EntryStack<?> entry) {
		return SkyblockItemEntryDefinition.getSkyblockId(entry);
	}
}
