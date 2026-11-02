package rp.doorman.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import rp.doorman.Doorman;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Fire-and-forget Discord embed. Failures are logged at most once a minute so a dead URL cannot spam the log. */
public final class DiscordWebhook {
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final AtomicLong LAST_FAILURE_LOG = new AtomicLong();

	private DiscordWebhook() {
	}

	/** A field as Discord expects it: name, value, inline. */
	public record Field(String name, String value, boolean inline) {
	}

	public static void send(String url, String title, String description, int color, List<Field> fields) {
		if (url == null || url.isBlank()) {
			return;
		}
		URI uri;
		try {
			uri = URI.create(url.strip());
		} catch (IllegalArgumentException e) {
			logFailure("invalid webhook url", e);
			return;
		}
		JsonObject embed = new JsonObject();
		embed.addProperty("title", title);
		embed.addProperty("description", description);
		embed.addProperty("color", color);
		JsonArray arr = new JsonArray();
		for (Field f : fields) {
			JsonObject o = new JsonObject();
			o.addProperty("name", f.name());
			o.addProperty("value", f.value().isBlank() ? "-" : f.value());
			o.addProperty("inline", f.inline());
			arr.add(o);
		}
		embed.add("fields", arr);
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		JsonObject body = new JsonObject();
		body.addProperty("username", "Doorman");
		body.add("embeds", embeds);

		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
			if (error != null) {
				logFailure("request failed", error);
			} else if (response.statusCode() / 100 != 2) {
				logFailure("HTTP " + response.statusCode() + " " + response.body(), null);
			}
		});
	}

	private static void logFailure(String what, Throwable error) {
		long now = System.currentTimeMillis();
		long last = LAST_FAILURE_LOG.get();
		if (now - last < 60_000 || !LAST_FAILURE_LOG.compareAndSet(last, now)) {
			return;
		}
		if (error != null) {
			Doorman.LOGGER.warn("Discord webhook: {} ({})", what, error.toString());
		} else {
			Doorman.LOGGER.warn("Discord webhook: {}", what);
		}
	}
}
