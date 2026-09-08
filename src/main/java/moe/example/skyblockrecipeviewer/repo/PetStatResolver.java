package moe.example.skyblockrecipeviewer.repo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves a pet item's {LVL}/{DEFENSE}/{0}/{1}/... lore placeholders against the repo's own
 * constants/petnums.json - the same file (and the same per-tier "1"/"100" checkpoint shape,
 * each holding a {@code statNums} object for named placeholders and an {@code otherNums}
 * array for positional {0}/{1}/... ones) NotEnoughUpdates' own NEUManager.
 * getPetLoreReplacements uses for this exact purpose (confirmed against that method's public
 * source - search "getPetLoreReplacements" in Moulberry/NotEnoughUpdates's NEUManager.java).
 * That method also does level-1-to-100 interpolation for whatever level the player's actual
 * pet instance happens to be at; this resolver doesn't need any of that; REI only ever wants
 * to show one fixed reference level (100 - see {@link #DISPLAY_LEVEL}, the level every normal
 * pet caps at) rather than tracking a real pet's real level, so it always reads the "100"
 * checkpoint straight out of petnums.json with no interpolation math at all.
 *
 * <h2>Where the pet type/tier come from</h2>
 * Straight out of the item's own ExtraAttributes.id - a pet's is always "TYPE;N" (e.g.
 * "ARMADILLO;5"), where N 0-5 is the tier as a bare digit (COMMON..MYTHIC) - the exact same
 * "does this item's internal id end in ;[0-5]" convention NEU's own NEUOverlay.java uses to
 * recognise pet items in the first place (its {@code petRegex} field). Read via a plain regex
 * against the item's raw nbttag text, the same way {@link SkullProfileResolver} reads
 * SkullOwner - not routed through {@link SkyblockNbtApplier}'s DataFixerUpper pipeline, so a
 * pet's lore doesn't depend on that pipeline succeeding first.
 *
 * <h2>Where petnums.json comes from</h2>
 * It's already on disk: RepoDownloader pulls the *entire* NotEnoughUpdates-REPO archive, not
 * just the items/ folder NEURepository itself parses, so constants/petnums.json sits right
 * there at {@code <repoDir>/constants/petnums.json} the moment the repo has been downloaded
 * once - nothing new to fetch, just read directly with Gson the same way SkyblockItemCache
 * already reads its own JSON files straight off disk.
 */
public final class PetStatResolver {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Pets");

	/** Every pet REI shows is resolved as if it were exactly this level. */
	public static final int DISPLAY_LEVEL = 100;

	private static final String[] TIER_BY_DIGIT = {
		"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"
	};

	// Matches e.g. id:"ARMADILLO;5" inside ExtraAttributes - see class javadoc for why this
	// (rather than ExtraAttributes.petInfo's doubly-escaped embedded JSON) is what this reads.
	private static final Pattern PET_ID_PATTERN = Pattern.compile("id:\"([A-Z0-9_]+);([0-5])\"");
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");

	private static volatile JsonObject petnumsCache;
	private static volatile Path petnumsCachedFrom;

	private PetStatResolver() {
	}

	public record PetIdentity(String type, String tier) {
	}

	/**
	 * @return this item's pet type+tier, or empty if {@code rawNbtTag} has no
	 * {@code ExtraAttributes.id} matching the "TYPE;0-5" pet convention at all - true for the
	 * overwhelming majority of items, which simply aren't pets, not a failure case.
	 */
	public static Optional<PetIdentity> identifyPet(String rawNbtTag) {
		if (rawNbtTag == null || rawNbtTag.isBlank() || !rawNbtTag.contains("ExtraAttributes")) {
			return Optional.empty();
		}
		Matcher matcher = PET_ID_PATTERN.matcher(rawNbtTag);
		if (!matcher.find()) return Optional.empty();
		String type = matcher.group(1);
		int tierDigit = Integer.parseInt(matcher.group(2));
		return Optional.of(new PetIdentity(type, TIER_BY_DIGIT[tierDigit]));
	}

	/**
	 * @return the {@code {"LVL": "100", "DEFENSE": "85", "0": "5", ...}} replacements for this
	 * pet at {@link #DISPLAY_LEVEL}. "LVL" is always present; the rest are only as complete as
	 * petnums.json's data for this pet+tier is - a pet with no level-100 checkpoint yet (a
	 * very recently added pet the repo hasn't caught up on) just comes back with "LVL" alone,
	 * which is a normal, non-error outcome callers handle by leaving any other {TOKEN} as-is.
	 */
	public static Map<String, String> resolveReplacements(Path repoDir, PetIdentity pet) {
		Map<String, String> replacements = new LinkedHashMap<>();
		replacements.put("LVL", String.valueOf(DISPLAY_LEVEL));
		if (pet == null) return replacements;

		JsonObject petnums = loadPetnums(repoDir);
		if (petnums == null) return replacements;
		try {
			if (!petnums.has(pet.type())) return replacements;
			JsonObject petEntry = petnums.getAsJsonObject(pet.type());
			if (!petEntry.has(pet.tier())) return replacements;
			JsonObject tierEntry = petEntry.getAsJsonObject(pet.tier());
			String levelKey = String.valueOf(DISPLAY_LEVEL);
			if (!tierEntry.has(levelKey)) return replacements;
			JsonObject checkpoint = tierEntry.getAsJsonObject(levelKey);

			if (checkpoint.has("statNums")) {
				for (Map.Entry<String, JsonElement> entry : checkpoint.getAsJsonObject("statNums").entrySet()) {
					replacements.put(entry.getKey(), formatNumber(entry.getValue().getAsFloat()));
				}
			}
			if (checkpoint.has("otherNums")) {
				var otherNums = checkpoint.getAsJsonArray("otherNums");
				for (int i = 0; i < otherNums.size(); i++) {
					replacements.put(String.valueOf(i), formatNumber(otherNums.get(i).getAsFloat()));
				}
			}
		} catch (Exception e) {
			LOGGER.debug("Could not read petnums.json stats for {} {} ({}).", pet.type(), pet.tier(), e.toString());
		}
		return replacements;
	}

	/**
	 * Substitutes every {@code {TOKEN}} in {@code text} found in {@code replacements}, leaving
	 * any token this pet/level has no data for untouched rather than blanking it out - a
	 * visibly unresolved {@code {TOKEN}} is a more useful signal, both to a player and to
	 * whoever's debugging a repo data gap later, than lore that silently goes missing.
	 */
	public static String substitute(String text, Map<String, String> replacements) {
		if (text == null || text.indexOf('{') < 0 || replacements.isEmpty()) return text;
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String value = replacements.get(matcher.group(1));
			matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/**
	 * Whole numbers print bare ("100" not "100.0"), matching how Hypixel's own lore always
	 * shows these - same intent as NEUManager's own (private, other-mod) removeUnusedDecimal.
	 */
	private static String formatNumber(float value) {
		if (!Float.isInfinite(value) && !Float.isNaN(value) && value == Math.floor(value)) {
			return String.valueOf((long) value);
		}
		return String.valueOf(value);
	}

	private static synchronized JsonObject loadPetnums(Path repoDir) {
		if (petnumsCache != null && repoDir.equals(petnumsCachedFrom)) return petnumsCache;
		Path file = repoDir.resolve("constants").resolve("petnums.json");
		try {
			if (!Files.exists(file)) {
				LOGGER.debug("No constants/petnums.json on disk yet - pet lore will show raw "
					+ "{{TOKEN}} placeholders until the repo has been downloaded.");
				return null;
			}
			JsonObject parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
				.getAsJsonObject();
			petnumsCache = parsed;
			petnumsCachedFrom = repoDir;
			return parsed;
		} catch (Exception e) {
			LOGGER.warn("Failed to read constants/petnums.json ({}).", e.toString());
			return null;
		}
	}
}
