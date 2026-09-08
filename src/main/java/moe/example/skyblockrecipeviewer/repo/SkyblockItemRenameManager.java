package moe.example.skyblockrecipeviewer.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Persists client-side custom names the player has assigned to their own SkyBlock items via
 * /sbirename, keyed by each item's real ExtraAttributes UUID (see SkyblockItemRealNbt) so the
 * rename survives a game restart or world change and follows that exact item instance, not
 * just "whatever's sitting in this inventory slot right now".
 *
 * Purely cosmetic/client-side: this never touches the actual item, its NBT, or anything sent
 * to the server - it only ever changes what this client's own tooltip renders locally (see
 * SkyblockItemRenameTooltipHandler).
 *
 * Same plain-Gson-JsonObject disk-cache pattern as SkyblockItemCache/HypixelSkinManager/
 * SkyblockPriceManager, for consistency.
 */
public final class SkyblockItemRenameManager {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Rename");
	private static final SkyblockItemRenameManager INSTANCE = new SkyblockItemRenameManager();

	private final Path saveFile = FabricLoader.getInstance().getConfigDir()
		.resolve("skyblockrecipeviewer").resolve("item-renames.json");
	private final Map<String, String> renames = new ConcurrentHashMap<>();
	private volatile boolean loaded = false;

	private SkyblockItemRenameManager() {
	}

	public static SkyblockItemRenameManager getInstance() {
		return INSTANCE;
	}

	private synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		if (!Files.exists(saveFile)) return;
		try {
			String json = Files.readString(saveFile, StandardCharsets.UTF_8);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			for (String uuid : root.keySet()) {
				renames.put(uuid, root.get(uuid).getAsString());
			}
		} catch (IOException | RuntimeException e) {
			// RuntimeException covers JsonParseException/IllegalStateException from malformed
			// or hand-edited JSON - same defensive-catch breadth as SkyblockPriceManager's own
			// disk-cache reads, so one bad file can't leave every rename silently unusable.
			LOGGER.warn("Could not read saved item renames ({}) - starting with none.", e.toString());
		}
	}

	private void save() {
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<String, String> entry : renames.entrySet()) {
				root.addProperty(entry.getKey(), entry.getValue());
			}
			Files.createDirectories(saveFile.getParent());
			Files.writeString(saveFile, root.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Could not save item renames ({}).", e.toString());
		}
	}

	public String getName(String itemUuid) {
		if (itemUuid == null) return null;
		ensureLoaded();
		return renames.get(itemUuid);
	}

	public void setName(String itemUuid, String name) {
		if (itemUuid == null) return;
		ensureLoaded();
		renames.put(itemUuid, name);
		save();
	}

	public void clearName(String itemUuid) {
		if (itemUuid == null) return;
		ensureLoaded();
		if (renames.remove(itemUuid) != null) {
			save();
		}
	}
}
