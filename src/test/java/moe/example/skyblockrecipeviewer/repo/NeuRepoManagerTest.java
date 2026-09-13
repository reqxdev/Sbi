package moe.example.skyblockrecipeviewer.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeuRepoManagerTest {
	private static final List<String> REQUIRED_CONSTANTS = List.of(
		"bonuses.json", "parents.json", "enchants.json", "essencecosts.json",
		"fairy_souls.json", "misc.json", "leveling.json", "pets.json", "petnums.json");

	@TempDir
	Path tempDir;

	@Test
	void reloadsRecipesWhenReadOnlySharedRepoChangesAfterInitialization() throws Exception {
		Path repo = tempDir.resolve("repo");
		Path items = repo.resolve("items");
		Path constants = repo.resolve("constants");
		Files.createDirectories(items);
		Files.createDirectories(constants);
		for (String name : REQUIRED_CONSTANTS) {
			Files.writeString(constants.resolve(name), "{}");
		}
		Files.writeString(constants.resolve("fairy_souls.json"), "{\"Max Souls\":0}");
		Files.writeString(constants.resolve("reforges.json"), "{}");
		Files.writeString(constants.resolve("reforgestones.json"), "{}");
		Files.writeString(tempDir.resolve("currentCommit.json"), "{\"sha\":\"old-sha\"}");

		Path target = items.resolve("TEST_OUTPUT.json");
		Files.writeString(target, itemJson(false));
		Files.writeString(items.resolve("TEST_INPUT.json"),
			"{\"itemid\":\"minecraft:stone\",\"displayname\":\"Input\","
				+ "\"internalname\":\"TEST_INPUT\",\"lore\":[]}");

		NeuRepoManager manager = new NeuRepoManager(tempDir, false);
		var original = manager.ensureLoaded().get(10, TimeUnit.SECONDS);
		assertTrue(manager.getCraftingRecipes().isEmpty());

		Files.writeString(target, itemJson(true));
		Files.setLastModifiedTime(target,
			FileTime.fromMillis(Files.getLastModifiedTime(target).toMillis() + 2_000));

		assertTrue(manager.checkForDiskChanges().get(10, TimeUnit.SECONDS));
		assertNotSame(original, manager.getLoadedRepoOrNull());
		assertEquals(1, manager.getCraftingRecipes().size());
		assertEquals("TEST_OUTPUT", manager.getCraftingRecipes().getFirst().getOutput().getItemId());
		assertEquals("{\"sha\":\"old-sha\"}", Files.readString(tempDir.resolve("currentCommit.json")));
	}

	private static String itemJson(boolean withRecipe) {
		return "{\"itemid\":\"minecraft:stone\",\"displayname\":\"Output\","
			+ "\"internalname\":\"TEST_OUTPUT\",\"lore\":[]"
			+ (withRecipe ? ",\"recipe\":{\"type\":\"crafting\",\"A1\":\"TEST_INPUT:1\"}" : "")
			+ "}";
	}
}
