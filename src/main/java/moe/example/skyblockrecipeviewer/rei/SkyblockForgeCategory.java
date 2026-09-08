package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

import io.github.moulberry.repo.data.NEUForgeRecipe;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Slot;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.WidgetWithBounds;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** REI category for Forge recipes (the Foundry anvil), laid out inputs-in-an-arc -> output. */
public class SkyblockForgeCategory implements DisplayCategory<SkyblockForgeDisplay> {

	public static final CategoryIdentifier<SkyblockForgeDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "forge"));

	@Override
	public CategoryIdentifier<? extends SkyblockForgeDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Forge");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Blocks.ANVIL.asItem()));
	}

	@Override
	public int getDisplayWidth(SkyblockForgeDisplay display) {
		return 150;
	}

	@Override
	public int getDisplayHeight() {
		return 90;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockForgeDisplay display, Rectangle bounds) {
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createRecipeBase(bounds));

		Point center = new Point(bounds.getX() + 45, bounds.getY() + 45);
		List<EntryIngredient> inputs = display.getInputEntries();
		int count = Math.max(1, inputs.size());
		for (int i = 0; i < inputs.size(); i++) {
			double rad = Math.PI * 2 * i / count;
			int x = (int) Math.round(center.x + Math.cos(rad) * 26) - 8;
			int y = (int) Math.round(center.y + Math.sin(rad) * 26) - 8;
			Slot slot = Widgets.createSlot(new Point(x, y)).markInput();
			slot.entries(inputs.get(i));
			widgets.add(slot);
		}

		NEUForgeRecipe recipe = display.getRecipe();
		int arrowX = bounds.getX() + 100;
		int arrowY = bounds.getY() + 45 - 8;
		WidgetWithBounds arrow = Widgets.createArrow(new Point(arrowX, arrowY));
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(Component.literal("Forge time: " + RecipeFormatting.duration(recipe.getDuration())));
		if (recipe.getExtraText() != null && !recipe.getExtraText().isBlank()) {
			tooltip.add(Component.literal(recipe.getExtraText()));
		}
		widgets.add(Widgets.withTooltip(arrow, tooltip));

		Slot outputSlot = Widgets.createSlot(new Point(bounds.getX() + 125, bounds.getY() + 37)).markOutput();
		List<EntryIngredient> outputs = display.getOutputEntries();
		if (!outputs.isEmpty()) outputSlot.entries(outputs.get(0));
		widgets.add(outputSlot);

		return widgets;
	}
}
