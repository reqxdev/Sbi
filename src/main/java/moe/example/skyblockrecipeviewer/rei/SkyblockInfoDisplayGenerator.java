package moe.example.skyblockrecipeviewer.rei;

import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import moe.example.skyblockrecipeviewer.repo.SkyblockWikiManager;

import java.util.List;
import java.util.Optional;

/**
 * Supplies the small SkyBlock information page on demand, independently of Skyblocker.
 */
public final class SkyblockInfoDisplayGenerator implements DynamicDisplayGenerator<SkyblockInfoDisplay> {
	public static final SkyblockInfoDisplayGenerator INSTANCE = new SkyblockInfoDisplayGenerator();

	private SkyblockInfoDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockInfoDisplay>> getRecipeFor(EntryStack<?> entry) {
		return create(entry);
	}

	@Override
	public Optional<List<SkyblockInfoDisplay>> getUsageFor(EntryStack<?> entry) {
		return create(entry);
	}

	@Override
	public Optional<List<SkyblockInfoDisplay>> generate(ViewSearchBuilder builder) {
		// This is an item-information page, not a browsable recipe category.
		return Optional.empty();
	}

	private static Optional<List<SkyblockInfoDisplay>> create(EntryStack<?> entry) {
		String id = SkyblockItemEntryDefinition.getSkyblockId(entry);
		if (id == null || SkyblockWikiManager.getInstance().getWikiUrl(id).isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(List.of(new SkyblockInfoDisplay(EntryIngredient.of(entry))));
	}
}
