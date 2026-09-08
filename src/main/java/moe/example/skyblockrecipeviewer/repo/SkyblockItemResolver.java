package moe.example.skyblockrecipeviewer.repo;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUIngredient;
import io.github.moulberry.repo.data.NEUItem;
import moe.example.skyblockrecipeviewer.rei.SkyblockItemEntryDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a NEU recipe ingredient into a displayable vanilla ItemStack.
 *
 * NOTE: SkyBlock items are just re-skinned/renamed vanilla items in real gameplay (their look
 * comes from a texture pack + custom NBT). This resolves the correct base item and then, via
 * {@link SkyblockNbtApplier}, upgrades the repo's captured item NBT (ExtraAttributes, lore,
 * enchant glint, etc.) through vanilla's own DataFixerUpper into real data components - the
 * same approach Firmament's ItemCache uses. The plain custom-name-only path below is only a
 * fallback for items where that NBT is missing or fails to parse.
 */
public final class SkyblockItemResolver {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Items");

	/**
	 * Caches the fully-resolved ItemStack (base item + reconstructed NBT/components + skull
	 * skin) per SkyBlock item id. Without this, every recipe ingredient slot and every entry
	 * re-runs the full SNBT-parse + DataFixerUpper-upgrade + ItemStack.CODEC pipeline from
	 * scratch even for items that appear dozens of times across recipes (e.g. a single
	 * Hyperion recipe alone references GIANT_FRAGMENT_LASER 8 separate times) - that
	 * redundant work is what was stalling REI's reload for ~95s (see EntryRegistryImpl/
	 * DisplayRegistryImpl timings). Callers must NOT mutate the returned stack in place;
	 * {@link #resolve} and {@link #resolveItemStack} always hand back a fresh copy.
	 */
	private static final Map<String, ItemStack> RESOLVED_CACHE = new ConcurrentHashMap<>();
	// Identity of the NEURepository the cache above was built from - if a genuinely new repo
	// is ever loaded (e.g. the on-disk repo data was updated and reloaded at runtime), the
	// cached stacks would be stale, so we blow the cache away rather than trust old data.
	private static volatile NEURepository cachedForRepo = null;

	private SkyblockItemResolver() {
	}

	/**
	 * Drops all cached resolved stacks if {@code repository} isn't the same instance the
	 * cache was last built from. Cheap identity check - safe (and a near-instant no-op on
	 * repeat calls with the same repo) to call from every entry point that receives a
	 * NEURepository, including {@link #resolve}, so nothing needs to remember to invalidate
	 * manually on a genuine reload of the underlying repo data.
	 */
	public static void invalidateCacheIfRepoChanged(NEURepository repository) {
		if (repository != null && repository != cachedForRepo) {
			RESOLVED_CACHE.clear();
			cachedForRepo = repository;
		}
	}

	/**
	 * Inserts an already-resolved stack straight into the cache, skipping the SNBT/
	 * DataFixerUpper pipeline entirely - used by SkyblockItemCache to prime this cache from a
	 * local disk cache file before the live repo has even loaded this session.
	 * putIfAbsent (not put): if a real resolution already beat us here (e.g. this runs
	 * slightly late relative to a fast live-repo load), keep the fresher live result rather
	 * than clobbering it with older disk-cached data. A later invalidateCacheIfRepoChanged()
	 * call (which registerEntries/registerDisplays/resolve already make on every real repo
	 * load) still correctly wipes any disk-primed entry once the real NEURepository loads, so
	 * a stale one never lingers past that point either way.
	 */
	static void primeCache(String skyblockId, ItemStack stack) {
		if (skyblockId == null || stack == null || stack.isEmpty()) return;
		RESOLVED_CACHE.putIfAbsent(skyblockId, stack.copy());
	}

	/**
	 * Read-only snapshot of everything currently resolved, for SkyblockItemCache to persist to
	 * disk. A copy (not a live view) so what gets serialized can't be mutated by a concurrent
	 * resolve() on another thread mid-write.
	 */
	public static Map<String, ItemStack> snapshotResolvedCache() {
		return Map.copyOf(RESOLVED_CACHE);
	}

	/**
	 * The repo's raw JSON keys crafting-grid slots by name (A1, A2, ... C3) and simply omits
	 * keys for unused slots. The parser library normalizes that into a fixed 9-element array so
	 * grid position is preserved, filling gaps with this sentinel instance rather than a null -
	 * confirmed against Firmament's own SBCraftingRecipe.kt, which checks
	 * `item == NEUIngredient.SENTINEL_EMPTY` by reference on the whole ingredient (not a
	 * getter on it), so we do the same here.
	 */
	public static boolean isEmptySlot(NEUIngredient ingredient) {
		return ingredient == null || ingredient == NEUIngredient.SENTINEL_EMPTY;
	}

	/**
	 * SkyBlock coin costs (in forge/crafting recipes that need coins alongside items - e.g. a
	 * Gemstone Chamber unlock) are represented as an ordinary {@link NEUIngredient} slot whose
	 * item id is this literal pseudo-item, not a real one - confirmed against Firmament's own
	 * {@code SkyblockId.COINS = SkyblockId("SKYBLOCK_COIN")} (the reference consumer of this
	 * same neurepoparser library). There's no NEUItem for it in the repo's item catalog at
	 * all, so resolveBaseItem's normal "look up minecraft_item_id" path had nothing to find
	 * and always fell through to its generic BARRIER fallback - showing every coin cost as an
	 * unlabelled barrier icon instead of the actual amount needed.
	 */
	private static final String COINS_SKYBLOCK_ID = "SKYBLOCK_COIN";

	public static ItemStack resolve(NEURepository repository, NEUIngredient ingredient) {
		if (isEmptySlot(ingredient)) return ItemStack.EMPTY;
		if (COINS_SKYBLOCK_ID.equalsIgnoreCase(ingredient.getItemId())) {
			// The amount here is the actual coin cost (frequently in the thousands/millions -
			// nothing at all like a real inventory stack size), so unlike every other
			// ingredient below, it's carried in the display name text rather than truncated
			// into ItemStack's 1-99 count.
			return buildCoinStack(ingredient.getAmount());
		}
		invalidateCacheIfRepoChanged(repository);
		// Verified against RepoParser's actual source (NEUIngredient.java / NEUItem.java):
		// NEUIngredient exposes getItemId(), NEUItem exposes getSkyblockItemId() (not
		// getSkyblockId() - that one doesn't exist and was the other build-breaking typo).
		String skyblockId = ingredient.getItemId();
		NEUItem neuItem = repository.getItems().getItemBySkyblockId(skyblockId);
		ItemStack stack = resolveItemStack(neuItem, skyblockId);
		int amount = Math.max(1, (int) Math.round(ingredient.getAmount()));
		if (amount > stack.getMaxStackSize()) {
			// Same underlying problem buildCoinStack's "1,234,567 Coins" treatment above
			// solves, just for an item that (unlike coins) already has a real repo icon/name:
			// real recipe/upgrade costs routinely need more of something than a real stack
			// could ever hold - Essence upgrade steps in particular can cost hundreds, even
			// thousands, of Essence (see EssenceStore) - and a vanilla stack-count badge simply
			// can't represent a number bigger than the item's own max stack size at all, so the
			// previous unconditional setCount(Math.min(amount, maxStackSize)) below silently
			// showed a wrong (truncated-to-64-or-whatever-the-cap-is) amount with no indication
			// anything was off. Keep the real icon, but show the true amount as text instead.
			stack.setCount(1);
			Component baseName = stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
				? stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
				: Component.translatable(stack.getItem().getDescriptionId());
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				Component.empty().append(baseName).append(Component.literal(
					" x" + moe.example.skyblockrecipeviewer.rei.RecipeFormatting.coins(amount))
					.withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY).withItalic(false))));
		} else {
			stack.setCount(amount);
		}
		return stack;
	}

	/**
	 * Same resolution logic as {@link #resolve}, but for a NEUItem directly rather than one
	 * wrapped in a recipe-slot NEUIngredient - used by registerEntries, which walks every
	 * known item in the repo rather than just the ones appearing in some recipe's grid.
	 */
	public static ItemStack resolveItemStack(NEUItem neuItem, String skyblockIdHint) {
		// See RESOLVED_CACHE javadoc: this is the expensive path (SNBT parse + DataFixerUpper
		// upgrade + ItemStack.CODEC parse + skin lookup), so skip it entirely on a cache hit.
		// A copy() is returned (never the cached instance itself) since callers such as
		// resolve() go on to mutate stack count in place.
		if (skyblockIdHint != null) {
			ItemStack cached = RESOLVED_CACHE.get(skyblockIdHint);
			if (cached != null) return cached.copy();
		}

		ItemStack stack = resolveBaseItem(neuItem, skyblockIdHint);
		// Overlays the real captured SkyBlock NBT (ExtraAttributes, lore, enchant glint,
		// unbreakable, etc.) via the DataFixerUpper - see SkyblockNbtApplier for how/why.
		// Returns the original stack untouched if there's no nbttag or it fails to parse/
		// upgrade, so the plain-display-name fallback below still covers that case.
		stack = SkyblockNbtApplier.apply(stack, neuItem);
		// CosmicPings (decompiled for comparison - see NeuItemStackFactory.
		// applySkyBlockComponents) never runs captured NBT through DataFixerUpper at all; it
		// builds CUSTOM_NAME/LORE directly from the repo's own clean displayName/lore fields
		// every time. We only used DFU for the ExtraAttributes/model/attribute-modifier
		// reconstruction, but were previously trusting whatever DFU produced for name/lore
		// too, only overriding it when DFU's output was completely empty - if DFU's legacy
		// HideFlags-to-TooltipDisplay conversion (see TooltipDisplayComponentFix, confirmed
		// present in the client jar) ever produced a *non-empty but hidden* result instead of
		// nothing, that conditional never caught it. Setting these unconditionally, the same
		// way CosmicPings does, removes the dependency on DFU's output for this entirely.
		// Pets (repo items whose ExtraAttributes.id is "TYPE;0-5") carry unresolved
		// {LVL}/{DEFENSE}/{0}/{1}/... placeholders in their raw displayname/lore - Hypixel
		// only ever fills those in for a *specific* pet instance at *its* actual level, which
		// obviously doesn't exist for a repo browsing entry. Resolve them all against the
		// repo's own constants/petnums.json at a fixed reference level instead (see
		// PetStatResolver) - substituting on the raw strings here, before LegacyTextParser
		// ever sees them, since placeholder tokens are plain text, not "§"-formatting.
		String rawNbtTag = neuItem != null ? neuItem.getNbttag() : null;
		java.util.Map<String, String> petReplacements = PetStatResolver.identifyPet(rawNbtTag)
			.map(pet -> PetStatResolver.resolveReplacements(NeuRepoManager.getInstance().getRepoDir(), pet))
			.orElse(java.util.Map.of());

		if (neuItem != null && neuItem.getDisplayName() != null) {
			String displayName = PetStatResolver.substitute(neuItem.getDisplayName(), petReplacements);
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				LegacyTextParser.parseLegacyText(displayName));
		}
		if (neuItem != null) {
			List<String> lore = neuItem.getLore();
			if (lore != null && !lore.isEmpty()) {
				List<Component> loreLines = new ArrayList<>(lore.size());
				for (String line : lore) {
					loreLines.add(LegacyTextParser.parseLegacyText(PetStatResolver.substitute(line, petReplacements)));
				}
				stack.set(net.minecraft.core.component.DataComponents.LORE, new ItemLore(loreLines));
			}
		}
		// MC 26.1.2 has a component (minecraft:tooltip_display / net.minecraft.world.item.
		// component.TooltipDisplay - confirmed present via decompile) that independently
		// controls which components actually render in the tooltip, separate from whether
		// the component's data exists on the stack. There's a dedicated DFU fixer
		// (TooltipDisplayComponentFix, also confirmed present) that converts the old-format
		// legacy "HideFlags" byte SkyBlock's captured nbttag snapshots commonly carry (see
		// SkyblockNbtApplier's javadoc - HideFlags:254 shows up on real captured items) into
		// this new component. If that conversion puts LORE or CUSTOM_NAME in the resulting
		// hiddenComponents set - plausible, since HideFlags was never designed with this
        // newer per-component-type model in mind - the Lore/name data we just went to all
		// this trouble to reconstruct above would sit on the stack correctly and still never
		// render. Force it back open explicitly, the same defensive pattern already used for
		// ATTRIBUTE_MODIFIERS above: don't trust what DFU produced for this, override it.
		net.minecraft.world.item.component.TooltipDisplay currentDisplay =
			stack.get(net.minecraft.core.component.DataComponents.TOOLTIP_DISPLAY);
		if (currentDisplay != null && (currentDisplay.hideTooltip()
			|| !currentDisplay.shows(net.minecraft.core.component.DataComponents.LORE)
			|| !currentDisplay.shows(net.minecraft.core.component.DataComponents.CUSTOM_NAME))) {
			stack.set(net.minecraft.core.component.DataComponents.TOOLTIP_DISPLAY,
				net.minecraft.world.item.component.TooltipDisplay.DEFAULT);
		}
		// Repo-embedded skull texture (see SkullProfileResolver) runs first and unconditionally
		// - it doesn't depend on DFU, on data versions, or on ever having joined Hypixel, so it
		// is the one path that should reliably work for every skull the repo actually has
		// texture data for. Hypixel's own item resource (applySkullSkinIfPresent, network/
		// join-gated - see HypixelSkinManager) then gets the final say where it has data,
		// since that's Hypixel's own authoritative source and may be newer/different from
		// what's captured in the community-maintained NEU repo.
		stack = applyDirectSkullProfile(stack, neuItem, skyblockIdHint);
		ItemStack resolved = applySkullSkinIfPresent(stack, skyblockIdHint);
		if (skyblockIdHint != null) {
			SkyblockItemEntryDefinition.tagWithSkyblockId(resolved, skyblockIdHint);
		}

		if (skyblockIdHint != null) {
			RESOLVED_CACHE.put(skyblockIdHint, resolved.copy());
		}
		return resolved;
	}

	/**
	 * Hypixel's own item resource (see HypixelSkinManager) is the authoritative source for
	 * skull textures - when it has one for this id, force the display to a player head with
	 * that real texture rather than whatever NEU's minecraft_item_id/material mapping guessed
	 * (which for skull-based items is very often just a generic skull, not the actual
	 * cosmetic skin players see in-game).
	 *
	 * createResolved() (rather than createUnresolved()) tells the client this profile's
	 * texture is already fully known, so it renders immediately with no session-server
	 * lookup - the same as how NBT-defined custom skulls have always worked.
	 *
	 * Returns the stack to use going forward (a swap to a new PLAYER_HEAD stack doesn't
	 * mutate the caller's original reference, so the result must be returned, not just
	 * mutated in place).
	 */
	private static ItemStack applySkullSkinIfPresent(ItemStack stack, String skyblockId) {
		var skinOpt = HypixelSkinManager.getInstance().getSkin(skyblockId);
		if (skinOpt.isEmpty()) return stack;
		var skin = skinOpt.get();

		// Hypixel's item resource never carries a "signature" for these (see
		// HypixelSkinManager.SkullSkin javadoc) - an unsigned Property is fine here since
		// createResolved() below renders this purely client-side with no session-server
		// verification, exactly like any other NBT-defined custom skull.
		SkullTextureCache.Entry entry = new SkullTextureCache.Entry(
			java.util.UUID.nameUUIDFromBytes(skin.value().getBytes()).toString(), skin.value(), null);
		return applyProfile(stack, SkullProfileResolver.buildProfile(entry));
	}

	/**
	 * The repo-embedded-texture path: see {@link SkullProfileResolver}'s class javadoc for why
	 * this exists as a separate, DFU-independent mechanism alongside {@link SkyblockNbtApplier}
	 * rather than relying on that one path alone. Checks {@link SkullTextureCache} first so a
	 * skull's raw nbttag text only ever gets regexed once per repo commit, not once per resolve.
	 *
	 * No-ops (returns {@code stack} unchanged) for the vast majority of items, which have no
	 * SkullOwner data at all - this is a normal, expected outcome, not a failure.
	 */
	private static ItemStack applyDirectSkullProfile(ItemStack stack, NEUItem neuItem, String skyblockId) {
		if (neuItem == null) return stack;
		String repoSha = SkyblockItemCache.currentRepoSha();

		SkullTextureCache.Entry entry = SkullTextureCache.get(repoSha, skyblockId).orElse(null);
		if (entry == null) {
			var resolved = SkullProfileResolver.resolveFromRawNbt(skyblockId, neuItem.getNbttag());
			if (resolved.isEmpty()) return stack;
			entry = resolved.get();
			if (skyblockId != null) {
				SkullTextureCache.put(repoSha, skyblockId, entry);
			}
		}
		return applyProfile(stack, SkullProfileResolver.buildProfile(entry));
	}

	/**
	 * Shared by both skull-texture paths above: swaps to a real PLAYER_HEAD stack if the
	 * resolved base item isn't already one (a defensive fallback for a stale/incomplete
	 * minecraft_item_id mapping, not the expected common case - see resolveBaseItem), carrying
	 * over the name/lore already built onto {@code stack} so the swap doesn't lose them, then
	 * sets the given resolved profile on it.
	 *
	 * Returns the stack to use going forward (a swap to a new stack doesn't mutate the caller's
	 * original reference, so the result must be returned, not just mutated in place).
	 */
	private static ItemStack applyProfile(ItemStack stack, net.minecraft.world.item.component.ResolvableProfile profile) {
		ItemStack headStack = stack;
		if (stack.getItem() != Items.PLAYER_HEAD) {
			headStack = new ItemStack(Items.PLAYER_HEAD, stack.getCount());
			if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
				headStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
					stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
			}
			if (stack.has(net.minecraft.core.component.DataComponents.LORE)) {
				headStack.set(net.minecraft.core.component.DataComponents.LORE,
					stack.get(net.minecraft.core.component.DataComponents.LORE));
			}
		}
		headStack.set(net.minecraft.core.component.DataComponents.PROFILE, profile);
		return headStack;
	}

	/**
	 * Builds a display stand-in for a coin cost: a PLAYER_HEAD wearing one of Hypixel's actual
	 * in-game coin-pile skins (tier picked by amount, same three thresholds/textures/UUIDs
	 * Firmament's {@code ItemCache.coinItem()} uses) with the real amount spelled out in its
	 * name - e.g. "1,234,567 Coins" - since that number needs to actually be read, not
	 * squeezed into ItemStack's 1-99 count like a normal ingredient's amount is.
	 */
	private static ItemStack buildCoinStack(double amount) {
		String uuid = "2070f6cb-f5db-367a-acd0-64d39a7e5d1b";
		String textureUrl = "http://textures.minecraft.net/texture/"
			+ "538071721cc5b4cd406ce431a13f86083a8973e1064d2f8897869930ee6e5237";
		if (amount >= 10_000_000) {
			uuid = "0af8df1f-098c-3b72-ac6b-65d65fd0b668";
			textureUrl = "http://textures.minecraft.net/texture/"
				+ "7b951fed6a7b2cbc2036916dec7a46c4a56481564d14f945b6ebc03382766d3b";
		} else if (amount >= 100_000) {
			uuid = "94fa2455-2881-31fe-bb4e-e3e24d58dbe3";
			textureUrl = "http://textures.minecraft.net/texture/"
				+ "c9b77999fed3a2758bfeaf0793e52283817bea64044bf43ef29433f954bb52f6";
		}
		String texturesJson = "{\"textures\":{\"SKIN\":{\"url\":\"" + textureUrl + "\"}}}";
		String base64Value = java.util.Base64.getEncoder()
			.encodeToString(texturesJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		stack.set(net.minecraft.core.component.DataComponents.PROFILE,
			SkullProfileResolver.buildProfile(new SkullTextureCache.Entry(uuid, base64Value, null)));
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
			Component.literal(moe.example.skyblockrecipeviewer.rei.RecipeFormatting.coins(amount) + " Coins")
				.withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GOLD).withItalic(false)));
		return stack;
	}

	private static ItemStack resolveBaseItem(NEUItem neuItem, String skyblockIdHint) {
		String minecraftId = neuItem != null ? neuItem.getMinecraftItemId() : null;
		if (minecraftId == null || minecraftId.isBlank()) {
			LOGGER.debug("No minecraft item id for skyblock item {}", skyblockIdHint);
			return new ItemStack(Items.BARRIER);
		}
		String namespaced = minecraftId.indexOf(':') >= 0 ? minecraftId : "minecraft:" + minecraftId;
		Identifier id = Identifier.parse(namespaced.toLowerCase(java.util.Locale.ROOT));
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR) {
			LOGGER.debug("Unknown minecraft item id '{}' for skyblock item {}", minecraftId, skyblockIdHint);
			return new ItemStack(Items.BARRIER);
		}
		return new ItemStack(item);
	}
}
