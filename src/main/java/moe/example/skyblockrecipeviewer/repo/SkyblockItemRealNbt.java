package moe.example.skyblockrecipeviewer.repo;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Reads fields out of a REAL, server-sent SkyBlock item's ExtraAttributes - as opposed to
 * SkyblockNbtApplier, which reconstructs a *repo* item's NBT from scratch for display purposes
 * only, or SkyblockItemEntryDefinition, which stamps and reads back this mod's own marker on
 * its own virtual REI-list entries. This is for real items the player actually holds/owns.
 *
 * Handles the same flattened-vs-nested CustomData ambiguity documented in
 * SkyblockItemEntryDefinition#getSkyblockId(EntryStack) - confirmed against Skyblocker's own
 * source (LegacyItemStackFixer.fixLegacyStack): if Skyblocker is also installed, it rewrites
 * every real item's CustomData early, replacing it with just the *inner* ExtraAttributes
 * compound, so a field like "id" or "uuid" ends up sitting at CustomData's own top level
 * instead of nested under an "ExtraAttributes" key. Checked flattened form first (the more
 * likely case for anyone who also has Skyblocker), falling back to the raw nested form.
 */
public final class SkyblockItemRealNbt {
	private SkyblockItemRealNbt() {
	}

	/** The item's own per-instance SkyBlock id (e.g. "HYPERION"), read from a real item. */
	public static String getId(ItemStack stack) {
		return getExtraAttributeString(stack, "id");
	}

	/**
	 * The item's own persistent UUID - Hypixel assigns one to essentially every
	 * non-stackable item so it can track auction history, anvil uses, etc. for that specific
	 * instance. Used here purely as a stable local key for /sbirename, unrelated to anything
	 * Hypixel itself does with it.
	 */
	public static String getUuid(ItemStack stack) {
		return getExtraAttributeString(stack, "uuid");
	}

	private static String getExtraAttributeString(ItemStack stack, String key) {
		if (stack.isEmpty()) return null;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		CompoundTag tag = data.copyTag();

		String flat = tag.getString(key).orElse(null);
		if (flat != null && !flat.isBlank()) return flat;

		return tag.getCompound("ExtraAttributes")
			.flatMap(extraAttributes -> extraAttributes.getString(key))
			.filter(s -> !s.isBlank())
			.orElse(null);
	}
}
