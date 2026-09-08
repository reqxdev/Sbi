package moe.example.skyblockrecipeviewer.repo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small local override table for pet acquisition methods. Pet item IDs carry rarity
 * suffixes (for example BABY_YETI;3), so lookups are made against the base pet ID.
 * Unknown pets intentionally return null so the existing generic REI generators keep
 * their previous behaviour until a pet is explicitly classified here.
 */
public final class PetAcquisitionStore {
	public enum Method { CRAFT, FORGE, DROP, KAT, NPC }

	private static final Map<String, Set<Method>> METHODS = load();

	private PetAcquisitionStore() {}

	public static Set<Method> getMethods(String skyblockId) {
		if (skyblockId == null) return null;
		String base = baseId(skyblockId);
		return METHODS.get(base);
	}

	public static boolean hasMethod(String skyblockId, Method method) {
		Set<Method> methods = getMethods(skyblockId);
		return methods != null && methods.contains(method);
	}

	public static boolean isClassified(String skyblockId) {
		return getMethods(skyblockId) != null;
	}

	public static String baseId(String skyblockId) {
		int separator = skyblockId.indexOf(';');
		return separator >= 0 ? skyblockId.substring(0, separator) : skyblockId;
	}

	private static Map<String, Set<Method>> load() {
		Map<String, Set<Method>> result = new ConcurrentHashMap<>();
		try (InputStream stream = PetAcquisitionStore.class.getResourceAsStream(
			"/data/skyblockrecipeviewer/pet_acquisition.json")) {
			if (stream == null) return result;
			JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
				.getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				if (!entry.getValue().isJsonArray()) continue;
				EnumSet<Method> methods = EnumSet.noneOf(Method.class);
				for (JsonElement method : entry.getValue().getAsJsonArray()) {
					try {
						methods.add(Method.valueOf(method.getAsString().toUpperCase(Locale.ROOT)));
					} catch (IllegalArgumentException ignored) {
						// Ignore an unknown local-table value rather than breaking all pet lookups.
					}
				}
				if (!methods.isEmpty()) result.put(baseId(entry.getKey()), Set.copyOf(methods));
			}
		} catch (Exception ignored) {
			// The acquisition table is only an override; never let a malformed local file
			// disable the normal REI generators.
		}
		return Map.copyOf(result);
	}
}
