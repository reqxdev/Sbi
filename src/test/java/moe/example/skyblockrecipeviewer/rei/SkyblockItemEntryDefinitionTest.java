package moe.example.skyblockrecipeviewer.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class SkyblockItemEntryDefinitionTest {
	@Test
	void normalizesVariantIdsToValidDistinctReiPaths() {
		String first = ReiIdentifierPath.normalize("POTION_MANA;1");
		String second = ReiIdentifierPath.normalize("POTION_MANA;2");

		assertEquals("potion_mana_3b_1", first);
		assertEquals("potion_mana_3b_2", second);
		assertTrue(Identifier.isValidPath(first));
		assertTrue(Identifier.isValidPath(second));
	}
}
