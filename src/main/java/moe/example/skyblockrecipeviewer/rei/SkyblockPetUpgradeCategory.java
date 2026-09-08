package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

import io.github.moulberry.repo.data.NEUKatUpgradeRecipe;
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

/** REI category for Kat pet-rarity-upgrade recipes (pet + coins + items -> higher-rarity pet). */
public class SkyblockPetUpgradeCategory implements DisplayCategory<SkyblockPetUpgradeDisplay> {

	public static final CategoryIdentifier<SkyblockPetUpgradeDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "pet_upgrade"));

	@Override
	public CategoryIdentifier<? extends SkyblockPetUpgradeDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Pet Upgrades (Kat)");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.CAT_SPAWN_EGG));
	}

	@Override
	public int getDisplayWidth(SkyblockPetUpgradeDisplay display) {
		return 150;
	}

	@Override
	public int getDisplayHeight() {
		return 60;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockPetUpgradeDisplay display, Rectangle bounds) {
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createRecipeBase(bounds));

		List<EntryIngredient> inputs = display.getInputEntries();
		List<EntryIngredient> outputs = display.getOutputEntries();

		int left = bounds.getX() + 6;
		int top = bounds.getY() + 6;
		if (!inputs.isEmpty()) {
			Slot petSlot = Widgets.createSlot(new Point(left, top)).markInput();
			petSlot.entries(inputs.get(0));
			widgets.add(petSlot);
		}

		int arrowX = bounds.getX() + 60;
		WidgetWithBounds arrow = Widgets.createArrow(new Point(arrowX, top));
		NEUKatUpgradeRecipe recipe = display.getRecipe();
		List<Component> tooltip = List.of(
			Component.literal("Kat upgrade time: " + RecipeFormatting.duration(recipe.getSeconds())),
			Component.literal("Cost: " + RecipeFormatting.coins(recipe.getCoins()) + " coins"));
		widgets.add(Widgets.withTooltip(arrow, tooltip));

		if (!outputs.isEmpty()) {
			Slot outputSlot = Widgets.createSlot(new Point(bounds.getX() + 90, top)).markOutput();
			outputSlot.entries(outputs.get(0));
			widgets.add(outputSlot);
		}

		int costX = left;
		int costY = top + 20;
		for (int i = 1; i < inputs.size(); i++) {
			Slot costSlot = Widgets.createSlot(new Point(costX, costY)).markInput();
			costSlot.entries(inputs.get(i));
			widgets.add(costSlot);
			costX += 18;
		}

		return widgets;
	}
}
