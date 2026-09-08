package moe.example.skyblockrecipeviewer.repo;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.world.item.component.ResolvableProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Builds a skull's {@link GameProfile} straight from the NEU repo's own captured item NBT -
 * the same way CosmicPings (a deprecated-but-previously-working mod pointed at this same
 * public repo) does it: regex the base64 "textures" property value straight out of the raw
 * {@code SkullOwner:{...}} text, rather than going through vanilla's DataFixerUpper to
 * convert the legacy SkullOwner NBT into a modern "minecraft:profile" component the way
 * {@link SkyblockNbtApplier} otherwise would.
 *
 * (Decompiled CosmicPings' NeuItemStackFactory.buildProfileFromRawNbt/buildProfile for
 * reference - see SKULL_ID_PATTERN/TEXTURE_VALUE_PATTERN below, which reproduce its regexes
 * exactly. It has no equivalent of SkyblockNbtApplier at all: it never runs captured NBT
 * through the DataFixerUpper for *any* purpose, it just reads the plain repo fields it needs
 * - displayName/lore directly, and this regex for skulls - straight off the JSON.)
 *
 * <h2>Why not just trust the DataFixerUpper (SkyblockNbtApplier) for this?</h2>
 * In principle it should already work: {@code ItemStackComponentizationFix} (confirmed via
 * decompile of the actual game jar) explicitly looks for a "SkullOwner" tag on any item whose
 * id {@code is("minecraft:player_head")}, and reads Id/Name/Properties.textures[].Value/
 * Signature in exactly the shape the NEU repo already uses. But that conversion depends on a
 * long chain of things all lining up on every single item - the exact legacy data version
 * picked, every fixer between it and the current version behaving as expected,
 * stripLegacyListIndices() perfectly reproducing modern SNBT grammar for every nbttag string
 * in the repo, the base item having actually resolved to "minecraft:player_head" rather than
 * something else, etc. - and when any single link breaks, the failure is silent: the item
 * just quietly keeps whatever ItemStack it already had (see SkyblockNbtApplier.apply()'s
 * catch-and-return-original-stack behavior), which for a player head with no profile at all
 * renders as vanilla's built-in placeholder - the well-known default "Steve" face - not a
 * crash or a logged error anyone would notice.
 *
 * This class sidesteps that whole chain for the one specific thing that actually matters for
 * rendering - the base64 "textures" property - by reading it straight out of the same
 * plain-text nbttag string SkyblockNbtApplier already has, with zero dependency on DFU, on
 * data versions, or on the SkullOwner tag surviving stripLegacyListIndices() unchanged.
 * Deliberately kept independent of SkyblockNbtApplier so a future regression in one can never
 * take the other down with it; SkyblockItemResolver runs this unconditionally after
 * SkyblockNbtApplier and lets it override whatever (if anything) DFU produced for the profile
 * component.
 *
 * <h2>Why this doesn't download or cache any image data itself</h2>
 * Setting a real base64 "textures" property is the *entire* job. Vanilla's own client
 * (SkinManager / the player-skin texture machinery behind PLAYER_HEAD rendering) is what
 * actually downloads the PNG that property's URL points at from textures.minecraft.net and
 * caches it on disk, completely independently of this mod - the exact same way it already
 * does for any other custom-skull NBT (a player-placed head, a banner pattern, etc.), and the
 * exact same way CosmicPings' own (decompiled) rendering code relies on it too:
 * CosmicPingsSkyBlockEntryRenderer does nothing but a single plain GuiGraphics.renderItem()
 * call - no image download/cache code of its own anywhere in that mod. Re-downloading or
 * re-caching that PNG data ourselves here would just duplicate (and likely race) a cache
 * Minecraft already maintains. What *does* get cached by this mod is the small, cheap
 * extraction result (the uuid/base64-value/signature triple) - see {@link SkullTextureCache}
 * - so repeated resolves don't need to re-run these regexes against the same raw NBT text.
 */
public final class SkullProfileResolver {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Skulls");

	// Mirrors CosmicPings' NeuItemStackFactory.SKULL_ID_PATTERN/TEXTURE_VALUE_PATTERN exactly
	// (decompiled from cosmicpings-rebuild-3_2_0-rebuild.jar's <clinit> for reference) -
	// proven against the same NotEnoughUpdates-REPO data this mod also consumes. DOTALL on
	// the id pattern because "SkullOwner:{...Id:"..."" can have Properties/textures data
	// (itself possibly multi-line once pretty-printed) between "SkullOwner:{" and "Id:" in
	// some captures - "." must match newlines there. The Value/Signature patterns aren't
	// scoped to only match inside the SkullOwner block (same simplification CosmicPings
	// makes); this is safe in practice because "Value"/"Signature" keys don't appear
	// anywhere else in the repo's nbttag snapshots, and HAS_SKULL_OWNER below already gates
	// out the ~99% of items that have no SkullOwner tag at all before either ever runs.
	private static final Pattern SKULL_ID_PATTERN =
		Pattern.compile("SkullOwner:\\{.*?Id:\"([^\"]+)\"", Pattern.DOTALL);
	private static final Pattern TEXTURE_VALUE_PATTERN = Pattern.compile("Value:\"([^\"]+)\"");
	private static final Pattern TEXTURE_SIGNATURE_PATTERN = Pattern.compile("Signature:\"([^\"]+)\"");
	private static final Pattern HAS_SKULL_OWNER = Pattern.compile("SkullOwner:\\{");

	private SkullProfileResolver() {
	}

	/**
	 * @return the {uuid, base64 texture value, signature} triple extracted from
	 * {@code rawNbtTag}, or empty if it has no SkullOwner/readable-texture data at all - a
	 * completely normal, expected result for the vast majority of items, which aren't skulls
	 * to begin with. Never throws - any regex/parse hiccup on one item's snapshot just means
	 * that one item falls through to whatever SkyblockNbtApplier/HypixelSkinManager produce
	 * instead, not a broken resolve for everything else.
	 */
	public static Optional<SkullTextureCache.Entry> resolveFromRawNbt(String skyblockId, String rawNbtTag) {
		if (rawNbtTag == null || rawNbtTag.isBlank() || !HAS_SKULL_OWNER.matcher(rawNbtTag).find()) {
			return Optional.empty();
		}
		try {
			Matcher valueMatcher = TEXTURE_VALUE_PATTERN.matcher(rawNbtTag);
			if (!valueMatcher.find()) {
				LOGGER.debug("SkyBlock item {} has a SkullOwner tag but no readable textures "
					+ "Value - leaving its head unskinned.", skyblockId);
				return Optional.empty();
			}
			String value = sanitizeBase64(valueMatcher.group(1));
			if (value.isBlank()) return Optional.empty();

			String signature = null;
			Matcher signatureMatcher = TEXTURE_SIGNATURE_PATTERN.matcher(rawNbtTag);
			if (signatureMatcher.find()) {
				signature = sanitizeBase64(signatureMatcher.group(1));
				if (signature.isBlank()) signature = null;
			}

			String uuidString = null;
			Matcher idMatcher = SKULL_ID_PATTERN.matcher(rawNbtTag);
			if (idMatcher.find()) {
				UUID parsed = parseUuid(idMatcher.group(1));
				if (parsed != null) uuidString = parsed.toString();
			}
			if (uuidString == null) {
				// No real UUID in the snapshot (or it didn't parse) - derive a stable one from
				// the texture itself, the same fallback CosmicPings uses
				// (UUID.nameUUIDFromBytes on the raw base64 string), so re-resolving the same
				// item always yields the same profile identity instead of a fresh random one
				// on every resolve.
				uuidString = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
			}
			return Optional.of(new SkullTextureCache.Entry(uuidString, value, signature));
		} catch (Exception e) {
			LOGGER.warn("Failed to extract skull texture for SkyBlock item {} ({}).", skyblockId, e.toString());
			return Optional.empty();
		}
	}

	/**
	 * Turns a cached (or freshly extracted) {uuid, value, signature} triple into a component
	 * ready to set on {@code DataComponents.PROFILE}. createResolved() (rather than
	 * createUnresolved()) tells the client this profile's texture is already fully known, so
	 * it renders immediately with no session-server lookup - exactly how any NBT-defined
	 * custom skull has always worked.
	 */
	public static ResolvableProfile buildProfile(SkullTextureCache.Entry entry) {
		Multimap<String, Property> multimap = HashMultimap.create();
		// Passing the signature through when we have one (unlike HypixelSkinManager's
		// resource, the NEU repo's captured textures very often do carry a real Mojang
		// session-server signature) is harmless either way: createResolved() renders this
		// purely client-side with no signature verification regardless.
		multimap.put("textures", new Property("textures", entry.value(), entry.signature()));
		PropertyMap properties = new PropertyMap(multimap);
		UUID id;
		try {
			id = UUID.fromString(entry.uuid());
		} catch (Exception badUuid) {
			id = UUID.nameUUIDFromBytes(entry.value().getBytes(StandardCharsets.UTF_8));
		}
		GameProfile profile = new GameProfile(id, "SkyblockItem", properties);
		return ResolvableProfile.createResolved(profile);
	}

	/**
	 * The repo's captured strings occasionally carry stray whitespace/newlines inside the
	 * base64 blob (real example, NotEnoughUpdates-REPO/items/ADAPTIVE_HELMET.json: the Value
	 * ends in a base64-encoded trailing "\n" right before the closing quote) - strip anything
	 * that isn't a valid base64 character before handing this to the client's texture
	 * decoder, which is stricter about this than SNBT parsing is.
	 */
	private static String sanitizeBase64(String raw) {
		if (raw == null) return "";
		return raw.replaceAll("[^A-Za-z0-9+/=]", "");
	}

	private static UUID parseUuid(String raw) {
		if (raw == null || raw.isBlank()) return null;
		try {
			if (raw.indexOf('-') >= 0) {
				return UUID.fromString(raw);
			}
			// Some captures store the id as a bare 32-hex-digit string with no dashes -
			// UUID.fromString requires the dashed form, so insert them at the standard
			// 8-4-4-4-12 positions first.
			if (raw.length() == 32) {
				String dashed = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
					+ raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
				return UUID.fromString(dashed);
			}
		} catch (Exception ignored) {
			// Falls through to null - caller derives a stable UUID from the texture instead.
		}
		return null;
	}
}
