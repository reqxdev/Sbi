package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * REI category for FREE Blacksmith reforges only (no reforge stone item involved) - cost,
 * ability text, and per-rarity stat bonuses shown as static text rows, one full page per
 * reforge. There are only a handful of these (see {@link ReforgeStore#reload}'s "basic" count),
 * so a detailed one-reforge-per-page layout is appropriate here in a way it isn't for the ~80
 * reforge-stone reforges - those live in {@link SkyblockReforgeStoneCategory} instead, shown
 * as a compact icon grid with the same stat info moved into each stone's own tooltip, since
 * cramming dozens of full detailed pages one after another is a much worse browsing experience
 * than the SkyBlock Essence Upgrades category's dense per-item rows.
 *
 * Unlike Firmament's reference implementation, this doesn't render a live 3D Blacksmith-villager
 * entity - that needs an entity-rendering widget this mod doesn't have.
 */
public class SkyblockReforgeCategory implements DisplayCategory<SkyblockReforgeDisplay> {

	public static final CategoryIdentifier<SkyblockReforgeDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "reforge"));

	@Override
	public CategoryIdentifier<? extends SkyblockReforgeDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Blacksmith Reforges");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.ANVIL));
	}

	@Override
	public int getDisplayWidth(SkyblockReforgeDisplay display) {
		return 190;
	}

	/**
	 * REI asks the category for one fixed height up front, without a specific display instance,
	 * so this can't actually vary per-reforge - sized generously enough for the largest
	 * realistic stat table (one line per rarity, up to {@link ReforgeStatFormatting#RARITY_ORDER}'s
	 * length). Fine to be generous here since, unlike reforge stones, there are only a handful
	 * of these and they aren't meant to pack tightly onto one page.
	 */
	@Override
	public int getDisplayHeight() {
		return 20 + ReforgeStatFormatting.RARITY_ORDER.size() * 11;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockReforgeDisplay display, Rectangle bounds) {
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createRecipeBase(bounds));

		ReforgeData reforge = display.getReforge();
		int left = bounds.getX() + 10;
		int top = bounds.getY() + 8;

		widgets.add(Widgets.createSlot(new Point(left, top))
			.entries(me.shedaniel.rei.api.common.entry.EntryIngredient.of(
				EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.ANVIL)))));

		widgets.add(Widgets.createLabel(new Point(left + 20, top + 2), Component.literal(reforge.reforgeName()))
			.leftAligned());
		String subtitle = "Free reforge, available at the Blacksmith";
		widgets.add(Widgets.withTooltip(
			Widgets.createLabel(new Point(left + 20, top + 12), Component.literal(subtitle).withStyle(s -> s.withItalic(true)))
				.leftAligned(),
			Component.literal(subtitle)));

		List<String> rarities = !display.getHighlightRarities().isEmpty()
			? display.getHighlightRarities()
			: ReforgeStatFormatting.orderedRarities(reforge);
		int rowY = top + 26;
		if (!display.getHighlightRarities().isEmpty()) {
			// Looked up for a specific item (see SkyblockReforgeDisplayGenerator.lookup): show
			// just its natural rarity and, if applicable, the Recombobulator-bumped tier one
			// above it, each as its own bolded header line followed by an indented stat line,
			// instead of the full rarity table nobody asked for.
			for (String rarity : rarities) {
				widgets.add(Widgets.createLabel(new Point(left, rowY),
					Component.literal(rarity).withStyle(s -> s.withBold(true)))
					.leftAligned());
				rowY += 10;
				widgets.add(Widgets.createLabel(new Point(left + 8, rowY),
					Component.literal(ReforgeStatFormatting.statsLine(reforge, rarity)))
					.leftAligned());
				rowY += 13;
			}
		} else {
			for (String rarity : rarities) {
				widgets.add(Widgets.createLabel(new Point(left, rowY),
					Component.literal(ReforgeStatFormatting.labelledLine(reforge, rarity)))
					.leftAligned());
				rowY += 11;
			}
			if (rarities.isEmpty()) {
				// A reforge with no per-rarity data (rare, but the JSON allows a flat-only value) -
				// still show whatever flat cost/ability/stats it has under the "*" bucket.
				widgets.add(Widgets.createLabel(new Point(left, rowY),
					Component.literal(ReforgeStatFormatting.labelledLine(reforge, ReforgeData.RARITY_ANY)))
					.leftAligned());
			}
		}

		return widgets;
	}
}
