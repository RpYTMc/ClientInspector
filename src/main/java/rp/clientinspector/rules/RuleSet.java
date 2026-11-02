package rp.clientinspector.rules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import rp.clientinspector.ClientInspector;
import rp.clientinspector.ClientInspectorConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** config/clientinspector/rules.json: a JSON array of {@link Rule}. Written with the defaults on first run. */
public final class RuleSet {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Type LIST_TYPE = new TypeToken<List<Rule>>() {
	}.getType();
	private static final String FILE_NAME = "rules.json";

	private final List<Rule> rules;

	public RuleSet(List<Rule> rules) {
		this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
	}

	public List<Rule> rules() {
		return rules;
	}

	public static Path path() {
		return ClientInspectorConfig.dir().resolve(FILE_NAME);
	}

	/**
	 * Strict load for /clientinspector reload. Duplicate ids are renamed with a numeric suffix and logged.
	 * A file from an older version gets the default rules added since, once (config.rules_version).
	 */
	public static RuleSet loadOrThrow(ClientInspectorConfig config) throws IOException {
		Path file = path();
		if (!Files.exists(file)) {
			RuleSet defaults = new RuleSet(DefaultRules.create());
			defaults.save();
			markCurrent(config);
			return defaults;
		}
		List<Rule> parsed;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			parsed = GSON.fromJson(reader, LIST_TYPE);
		}
		if (parsed == null) {
			throw new JsonSyntaxException("file is empty (delete it to regenerate defaults)");
		}
		List<Rule> clean = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		int anonymous = 0;
		for (Rule rule : parsed) {
			if (rule == null) {
				continue;
			}
			rule.sanitize();
			if (rule.id.isBlank()) {
				rule.id = "rule-" + (++anonymous);
			}
			String base = rule.id;
			int n = 2;
			while (!ids.add(rule.id)) {
				rule.id = base + "-" + n++;
				ClientInspector.LOGGER.warn("rules.json: duplicate rule id '{}' renamed to '{}'", base, rule.id);
			}
			if (!rule.hasCondition()) {
				ClientInspector.LOGGER.warn("rules.json: rule '{}' has no condition and will never match", rule.id);
			}
			clean.add(rule);
		}
		for (String bad : RuleMatcher.invalidBrandRegexes(clean)) {
			ClientInspector.LOGGER.warn("rules.json: rule '{}' has an invalid brand regex and will never match", bad);
		}
		List<String> changes = DefaultRules.upgrade(clean, config.rules_version);
		RuleSet loaded = new RuleSet(clean);
		if (!changes.isEmpty()) {
			loaded.save();
			ClientInspector.LOGGER.info("rules.json: brought up to the current default rules: {}", String.join(", ", changes));
		}
		markCurrent(config);
		return loaded;
	}

	private static void markCurrent(ClientInspectorConfig config) {
		if (config.rules_version < DefaultRules.VERSION && config.fromFile()) {
			config.rules_version = DefaultRules.VERSION;
			config.save();
		}
	}

	/** Startup path: never throws; falls back to the built-in defaults without touching the file. */
	public static RuleSet load(ClientInspectorConfig config) {
		try {
			return loadOrThrow(config);
		} catch (IOException | RuntimeException e) {
			ClientInspector.LOGGER.error("Failed to read {} ({}); using built-in default rules. Fix the file and run /clientinspector reload",
					path(), ClientInspectorConfig.describe(e), e);
			return new RuleSet(DefaultRules.create());
		}
	}

	public void save() {
		Path file = path();
		try {
			String json = GSON.toJson(rules, LIST_TYPE);
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				writer.write(json);
			}
		} catch (IOException | RuntimeException e) {
			ClientInspector.LOGGER.error("Failed to write {}", file, e);
		}
	}
}
