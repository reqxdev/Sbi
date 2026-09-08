package moe.example.skyblockrecipeviewer.repo;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Parses SkyBlock's legacy "§"-formatted display name/lore strings into properly styled
 * Components - manually, rather than relying on the renderer to interpret embedded "§"
 * codes the way the old raw-string chat/GUI renderer used to. Modern MC's Component-based
 * tooltip rendering only respects a Component's actual Style objects; a literal "§6" sitting
 * inside a Component.literal(...) string is not re-interpreted as a color at render time.
 * This was the actual cause of lore/names not showing correctly - not the NBT/DataFixerUpper
 * path, which only ever covered ExtraAttributes/Unbreakable/skull-owner reconstruction.
 *
 * Ported from a working, in-production implementation (CosmicPings' NeuItemStackFactory,
 * an older-MC-version mod that also consumes NEU repo data via REI) rather than written from
 * scratch, specifically because getting this wrong silently produces "no visible lore" rather
 * than a compile error, exactly the symptom this is fixing. The color/format switch below and
 * the normalizeLegacyFormatting mojibake fixes are a direct behavioral port of that code,
 * adapted from that mod's (older, obfuscated Yarn-mapped) API calls to this project's Mojmap
 * equivalents (Component.literal/append/setStyle, Style.withColor/withBold/etc, all confirmed
 * against the MC 26.2 client jar). ChatFormatting doesn't expose an isColor()-style method
 * in this version, so color-vs-format is distinguished the same way the reference
 * implementation effectively does it: explicit format codes are enumerated, anything else
 * numeric/letter is treated as a color.
 */
public final class LegacyTextParser {

	private static final char LEGACY_COLOR_MARKER = '\u00a7';

	private LegacyTextParser() {
	}

	/**
	 * Fixes the classic UTF-8/Latin-1 mojibake for "§": if the repo JSON (or anything on the
	 * path to it) ever gets decoded with the wrong charset assumption once too often, a
	 * literal section-sign character turns into one of these multi-byte garbage sequences
	 * instead. Restoring the real "§" here means the parser below actually has something to
	 * recognise, regardless of what mangled it upstream.
	 */
	private static String normalizeLegacyFormatting(String value) {
		if (value == null || value.isBlank()) return "";
		return value
			.replace("\u00c3\u0192\u00e2\u20ac\u0161\u00c3\u201a\u00c2\u00a7", String.valueOf(LEGACY_COLOR_MARKER))
			.replace("\u00c3\u201a\u00c2\u00a7", String.valueOf(LEGACY_COLOR_MARKER))
			.replace("\u00c2\u00a7", String.valueOf(LEGACY_COLOR_MARKER))
			.replace("\u00c3\u201a", "")
			.replace("\u00c3\u0192\u00e2\u20ac\u0161", "")
			.replace("\u00c3\u0192\u00c6\u2019\u00c3\u00a2\u00e2\u201a\u00ac\u00c5\u00a1", "");
	}

	public static Component parseLegacyText(String rawText) {
		String input = normalizeLegacyFormatting(rawText);
		if (input.isBlank()) return Component.empty();

		MutableComponent root = Component.empty();
		StringBuilder buffer = new StringBuilder();
		Style currentStyle = Style.EMPTY;

		for (int i = 0; i < input.length(); i++) {
			char current = input.charAt(i);
			if (current == LEGACY_COLOR_MARKER && i + 1 < input.length()) {
				if (buffer.length() > 0) {
					root.append(Component.literal(buffer.toString()).setStyle(currentStyle));
					buffer.setLength(0);
				}
				ChatFormatting formatting = ChatFormatting.getByCode(input.charAt(++i));
				if (formatting == null) continue;
				if (formatting == ChatFormatting.RESET) {
					currentStyle = Style.EMPTY;
				} else if (formatting == ChatFormatting.BOLD) {
					currentStyle = currentStyle.withBold(true);
				} else if (formatting == ChatFormatting.ITALIC) {
					currentStyle = currentStyle.withItalic(true);
				} else if (formatting == ChatFormatting.UNDERLINE) {
					currentStyle = currentStyle.withUnderlined(true);
				} else if (formatting == ChatFormatting.STRIKETHROUGH) {
					currentStyle = currentStyle.withStrikethrough(true);
				} else if (formatting == ChatFormatting.OBFUSCATED) {
					currentStyle = currentStyle.withObfuscated(true);
				} else {
					// A color code resets prior bold/italic/etc, matching vanilla legacy
					// formatting semantics (a new color always clears the previous style).
					currentStyle = Style.EMPTY.withColor(formatting);
				}
				continue;
			}
			buffer.append(current);
		}
		if (buffer.length() > 0) {
			root.append(Component.literal(buffer.toString()).setStyle(currentStyle));
		}
		return root;
	}
}
