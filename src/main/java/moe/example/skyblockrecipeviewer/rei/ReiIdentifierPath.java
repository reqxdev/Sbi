package moe.example.skyblockrecipeviewer.rei;

import java.util.Locale;

final class ReiIdentifierPath {
	private ReiIdentifierPath() {
	}

	static String normalize(String skyblockId) {
		String lower = skyblockId.toLowerCase(Locale.ROOT);
		StringBuilder normalized = new StringBuilder(lower.length());
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '/' || c == '.' || c == '_' || c == '-') {
				normalized.append(c);
			} else {
				normalized.append('_').append(Integer.toHexString(c)).append('_');
			}
		}
		return normalized.isEmpty() ? "unknown" : normalized.toString();
	}
}
