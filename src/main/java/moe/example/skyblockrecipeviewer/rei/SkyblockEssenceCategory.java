package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Slot;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import moe.example.skyblockrecipeviewer.repo.essence.EssenceUpgradeRecipe;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** REI category for Essence upgrades (item -> essence + extra items -> item, one star higher). */
public class SkyblockEssenceCategory implements DisplayCategory<SkyblockEssenceDisplay> {

	public static final CategoryIdentifier<SkyblockEssenceDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "essence"));

	@Override
	public CategoryIdentifier<? extends SkyblockEssenceDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Essence Upgrades");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.NETHER_STAR));
	}

	@Override
	public int getDisplayWidth(SkyblockEssenceDisplay display) {
		return Math.max(150, 40 + display.getInputEntries().size() * 20);
	}

	@Override
	public int getDisplayHeight() {
		return 60;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockEssenceDisplay display, Rectangle bounds) {
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createRecipeBase(bounds));

		EssenceUpgradeRecipe recipe = display.getRecipe();
		List<EntryIngredient> inputs = display.getInputEntries();
		List<EntryIngredient> outputs = display.getOutputEntries();

		int left = bounds.getX() + 6;
		int top = bounds.getY() + 6;
		if (!inputs.isEmpty()) {
			Slot itemSlot = Widgets.createSlot(new Point(left, top)).markInput();
			itemSlot.entries(inputs.get(0));
			widgets.add(itemSlot);
		}

		widgets.add(Widgets.createLabel(new Point(left + 20, top + 4),
				Component.literal("★" + (recipe.starCountAfter() - 1) + " → ★" + recipe.starCountAfter()))
			.leftAligned());

		int costX = left;
		int costY = top + 20;
		for (int i = 1; i < inputs.size(); i++) {
			Slot costSlot = Widgets.createSlot(new Point(costX, costY)).markInput();
			costSlot.entries(inputs.get(i));
			widgets.add(costSlot);
			costX += 18;
		}

		if (!outputs.isEmpty()) {
			Slot outputSlot = Widgets.createSlot(new Point(bounds.getMaxX() - 24, top)).markOutput();
			outputSlot.entries(outputs.get(0));
			widgets.add(outputSlot);
		}

		return widgets;
	}
}
