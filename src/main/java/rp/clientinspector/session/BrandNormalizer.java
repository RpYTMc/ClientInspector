package rp.clientinspector.session;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Helpers for the raw brand string. Rules match the raw string; these are for display and for the
 * "which loader" summary. Fabric Loader appends ",fabric" to any brand that is not "vanilla" and
 * turns "vanilla" into "fabric"; a Velocity proxy appends " (Velocity)".
 */
public final class BrandNormalizer {
	private static final String VELOCITY_SUFFIX = " (Velocity)";

	private BrandNormalizer() {
	}

	/** Trims and removes a Velocity proxy suffix. Null stays null. */
	public static @Nullable String clean(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.strip();
		if (s.endsWith(VELOCITY_SUFFIX)) {
			s = s.substring(0, s.length() - VELOCITY_SUFFIX.length()).strip();
		}
		return s;
	}

	/** "fabric", "quilt", "forge", "neoforge", "vanilla", "none" or "other". */
	public static String loader(@Nullable String raw) {
		String s = clean(raw);
		if (s == null || s.isEmpty()) {
			return "none";
		}
		String lower = s.toLowerCase(Locale.ROOT);
		if (lower.equals("vanilla")) {
			return "vanilla";
		}
		if (lower.equals("fabric") || lower.endsWith(",fabric")) {
			return "fabric";
		}
		if (lower.equals("quilt") || lower.endsWith(",quilt")) {
			return "quilt";
		}
		if (lower.startsWith("neoforge")) {
			return "neoforge";
		}
		if (lower.startsWith("forge")) {
			return "forge";
		}
		return "other";
	}

	/** The brand without the loader suffix: "lunarclient:v2.23.12-2644" from "lunarclient:v2.23.12-2644,fabric". */
	public static @Nullable String base(@Nullable String raw) {
		String s = clean(raw);
		if (s == null) {
			return null;
		}
		int comma = s.lastIndexOf(',');
		if (comma > 0) {
			String suffix = s.substring(comma + 1).toLowerCase(Locale.ROOT);
			if (suffix.equals("fabric") || suffix.equals("forge") || suffix.equals("quilt") || suffix.equals("neoforge")) {
				return s.substring(0, comma);
			}
		}
		return s;
	}
}
