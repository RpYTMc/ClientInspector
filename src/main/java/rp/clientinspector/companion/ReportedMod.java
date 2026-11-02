package rp.clientinspector.companion;

import org.jspecify.annotations.Nullable;

/**
 * One entry of a client's mod list as the client part reports it.
 *
 * @param id      the Fabric mod id
 * @param version the version string from the mod's fabric.mod.json
 * @param sha256  lower-case hex SHA-256 of the jar the mod was loaded from, or null when there is no
 *                single jar (development classes, built-in mods) or the report had to leave hashes out
 * @param nested  true for a jar-in-jar mod; the hash is then the one of the outermost jar
 */
public record ReportedMod(String id, String version, @Nullable String sha256, boolean nested) {
	public ReportedMod {
		id = CompanionProtocol.clip(id, CompanionProtocol.MAX_ID_LENGTH);
		version = CompanionProtocol.clip(version, CompanionProtocol.MAX_VERSION_LENGTH);
	}

	public ReportedMod withoutHash() {
		return sha256 == null ? this : new ReportedMod(id, version, null, nested);
	}
}
