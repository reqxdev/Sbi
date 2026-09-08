package moe.example.skyblockrecipeviewer.rei;

import java.util.List;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import moe.example.skyblockrecipeviewer.repo.SkyblockItemRealNbt;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemRenameManager;

/**
 * Applies the player's own /sbirename custom name to the tooltip's title line (index 0 -
 * vanilla always puts the item's hover name there) for any real item whose ExtraAttributes
 * UUID has a saved rename. Purely visual/client-side - never touches the actual item, its
 * NBT, or anything sent to the server.
 *
 * Deliberately does NOT rename what shows in the hotbar/action-bar ("Now holding: <item>"
 * message) or the floating item-name label above the hotbar - those read
 * ItemStack.getHoverName() directly rather than going through this tooltip line list, and
 * changing those would need a Mixin (this project has none currently - see
 * SkyblockPriceTooltipHandler's own class docs on why the tooltip-line approach was chosen
 * over a lower-level mechanism for its price-lookup feature too). If that broader scope is
 * ever wanted, this is the piece that would need to grow into a proper Mixin.
 *
 * Registered as its own callback (rather than folded into SkyblockPriceTooltipHandler) so
 * each tooltip feature stays independently toggleable/removable, matching this project's
 * existing one-handler-per-concern convention.
 */
public final class SkyblockItemRenameTooltipHandler {
	private SkyblockItemRenameTooltipHandler() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register(SkyblockItemRenameTooltipHandler::onTooltip);
	}

	/**
	 * Same signature-uncertainty note as SkyblockPriceTooltipHandler.onTooltip (see that
	 * class's own docs): written to match ItemStack.getTooltipLines's confirmed real
	 * signature for this MC version (Item.TooltipContext + TooltipFlag), not any newer
	 * TooltipType-based shape some Fabric API versions use. A type-only fix if this doesn't
	 * match, not a logic change.
	 */
	private static void onTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag flag,
			List<Component> lines) {
		if (lines.isEmpty()) return;
		String uuid = SkyblockItemRealNbt.getUuid(stack);
		if (uuid == null) return;
		String customName = SkyblockItemRenameManager.getInstance().getName(uuid);
		if (customName == null || customName.isBlank()) return;

		// Keep whatever Style the real title line already had (rarity color, bold, etc.) -
		// only the text itself changes.
		Component original = lines.get(0);
		lines.set(0, Component.literal(customName).setStyle(original.getStyle()));
	}
}
