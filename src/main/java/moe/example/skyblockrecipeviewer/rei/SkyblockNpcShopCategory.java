package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.world.item.Items;

/**
 * REI category for items purchasable from an NPC shop: item-based costs (if any) laid out
 * in an arc on the left (same layout style as SkyblockForgeCategory, since both are
 * "one or more cost items -> one output item" shapes), an arrow in the middle showing the
 * coin cost as a tooltip, and the purchased item as the output slot on the right.
 */
public class SkyblockNpcShopCategory implements DisplayCategory<SkyblockNpcShopDisplay> {

	public static final CategoryIdentifier<SkyblockNpcShopDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "npc_shop"));

	@Override
	public CategoryIdentifier<? extends SkyblockNpcShopDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock NPC Shops");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.EMERALD));
	}

	@Override
	public int getDisplayWidth(SkyblockNpcShopDisplay display) {
		return 150;
	}

	@Override
	public int getDisplayHeight() {
		return 90;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockNpcShopDisplay display, Rectangle bounds) {
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

		int arrowX = bounds.getX() + 100;
		int arrowY = bounds.getY() + 45 - 8;
		WidgetWithBounds arrow = Widgets.createArrow(new Point(arrowX, arrowY));
		List<Component> tooltip = new ArrayList<>();
		long coinCost = display.getCoinCost();
		if (coinCost > 0) {
			tooltip.add(Component.literal("Cost: " + RecipeFormatting.coins(coinCost) + " coins"));
		} else if (inputs.isEmpty()) {
			// Genuinely free, or the repo just didn't list a cost for this entry - worth
			// distinguishing from "has a real coin cost" rather than silently showing nothing.
			tooltip.add(Component.literal("No listed cost"));
		}
		tooltip.add(Component.literal("Purchasable from an NPC shop")
			.withStyle(net.minecraft.ChatFormatting.GRAY));
		widgets.add(Widgets.withTooltip(arrow, tooltip));

		Slot outputSlot = Widgets.createSlot(new Point(bounds.getX() + 125, bounds.getY() + 37)).markOutput();
		List<EntryIngredient> outputs = display.getOutputEntries();
		if (!outputs.isEmpty()) outputSlot.entries(outputs.get(0));
		widgets.add(outputSlot);

		return widgets;
	}
}
