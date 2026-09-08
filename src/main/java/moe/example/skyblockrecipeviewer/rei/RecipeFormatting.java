package moe.example.skyblockrecipeviewer.rei;

import java.util.Locale;

/** Small formatting helpers shared by the reforge/forge/essence/pet-upgrade/mob-drop GUIs. */
public final class RecipeFormatting {
	private RecipeFormatting() {
	}

	/** {@code 1234567.0} -> {@code "1,234,567"}. */
	public static String coins(double amount) {
		return String.format(Locale.ROOT, "%,d", Math.round(amount));
	}

	/** {@code 5400} (seconds) -> {@code "1h 30m"}; drops leading zero units. */
	public static String duration(long totalSeconds) {
		if (totalSeconds < 0) totalSeconds = 0;
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		StringBuilder sb = new StringBuilder();
		if (hours > 0) sb.append(hours).append("h ");
		if (hours > 0 || minutes > 0) sb.append(minutes).append("m ");
		sb.append(seconds).append("s");
		return sb.toString();
	}

	/** {@code "critical_damage"} -> {@code "Critical Damage"}, for stat-id labels. */
	public static String prettifyStatId(String statId) {
		if (statId == null || statId.isEmpty()) return "";
		String[] parts = statId.replace('_', ' ').split(" ");
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) continue;
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}

	/** {@code +12.5} -> {@code "+12.5"}, {@code -3.0} -> {@code "-3"} (drops trailing ".0"). */
	public static String signedNumber(double value) {
		String sign = value >= 0 ? "+" : "";
		if (value == Math.rint(value)) {
			return sign + (long) value;
		}
		return sign + value;
	}
}
