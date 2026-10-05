package me.strengthai;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class OpenRouterService implements AutoCloseable {
    private final StrengthAIPlugin plugin;
    private final HttpClient client;
    private final ExecutorService executor;

    public OpenRouterService(StrengthAIPlugin plugin) {
        this.plugin = plugin;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "StrengthAI-OpenRouter");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean configured() {
        return !plugin.getDataStore().getSecret("openrouter-key").isBlank();
    }

    public CompletableFuture<AIResult> analyze(String player, String event, String evidence) {
        String key = plugin.getDataStore().getSecret("openrouter-key");
        if (key.isBlank()) return CompletableFuture.completedFuture(AIResult.error("OpenRouter API key is not configured."));

        int max = plugin.getConfig().getInt("ai.max-evidence-characters", 7000);
        String clipped = evidence.length() > max ? evidence.substring(0, max) : evidence;

        JsonObject body = new JsonObject();
        body.addProperty("model", plugin.getConfig().getString("ai.model", "openrouter/auto"));
        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content",
                "You are the security classifier for a Minecraft Paper server. " +
                "Use only the supplied evidence; never invent facts. " +
                "Return exactly one JSON object matching the requested schema. " +
                "Do not write analysis, Markdown, explanations outside the JSON, or arrays for actions. " +
                "verdict=SAFE|WATCH|CHEAT. category=NONE|XRAY|KILLAURA|REACH|FLY|SPEED|BARITONE|SPAM|BOT|OTHER. " +
                "actions is one value: NONE|WATCH|KICK|BAN|MUTE|RESET_STRENGTH. " +
                "Treat repeated authoritative Grim flags as stronger evidence than heuristic Baritone/path telemetry. " +
                "Never recommend BAN for a single weak heuristic.");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content",
                "Player: " + player + "\nEvent: " + event + "\nEvidence:\n" + clipped +
                "\nReturn the safest justified JSON decision.");
        messages.add(user);

        body.add("messages", messages);
        body.addProperty("temperature", 0.1);
        body.addProperty("max_tokens", plugin.getConfig().getInt("ai.max-output-tokens", 1200));

        JsonObject provider = new JsonObject();
        provider.addProperty("require_parameters", true);
        body.add("provider", provider);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");

        JsonObject schemaWrapper = new JsonObject();
        schemaWrapper.addProperty("name", "strengthai_decision");
        schemaWrapper.addProperty("strict", true);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject verdict = new JsonObject();
        verdict.addProperty("type", "string");
        properties.add("verdict", verdict);

        JsonObject category = new JsonObject();
        category.addProperty("type", "string");
        properties.add("category", category);

        JsonObject confidence = new JsonObject();
        confidence.addProperty("type", "number");
        confidence.addProperty("description", "Confidence from 0 to 1.");
        properties.add("confidence", confidence);

        JsonObject severity = new JsonObject();
        severity.addProperty("type", "string");
        properties.add("severity", severity);

        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        properties.add("reason", reason);

        JsonObject actions = new JsonObject();
        actions.addProperty("type", "string");
        actions.addProperty("description", "One action only: NONE, WATCH, KICK, BAN, MUTE, or RESET_STRENGTH.");
        properties.add("actions", actions);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        for (String keyName : List.of("verdict", "category", "confidence", "severity", "reason", "actions")) {
            required.add(keyName);
        }
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);

        schemaWrapper.add("schema", schema);
        responseFormat.add("json_schema", schemaWrapper);
        body.add("response_format", responseFormat);

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(plugin.getConfig().getString("ai.endpoint", "https://openrouter.ai/api/v1/chat/completions")))
                .timeout(Duration.ofSeconds(plugin.getConfig().getInt("ai.timeout-seconds", 25)))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));

        String referer = plugin.getConfig().getString("ai.referer", "");
        String title = plugin.getConfig().getString("ai.title", "Strength SMP AI");
        if (!referer.isBlank()) b.header("HTTP-Referer", referer);
        if (!title.isBlank()) b.header("X-Title", title);

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> response = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    return AIResult.error("OpenRouter HTTP " + response.statusCode() + ": " + trim(response.body(), 400));

                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) return AIResult.error("OpenRouter returned no choices.");

                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject msg = choice.has("message") && choice.get("message").isJsonObject()
                        ? choice.getAsJsonObject("message")
                        : new JsonObject();

                String content = extractMessageText(msg);

                if (content.isBlank()) {
                    String finishReason = choice.has("finish_reason")
                            ? choice.get("finish_reason").getAsString()
                            : "unknown";
                    return AIResult.error(
                            "OpenRouter returned empty message content (finish_reason=" +
                            finishReason + ")."
                    );
                }

                return parse(content);
            } catch (Exception e) {
                return AIResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }, executor);
    }

    private String extractMessageText(JsonObject message) {
        if (message == null || message.isEmpty()) return "";

        JsonElement content = message.get("content");

        if (content != null && !content.isJsonNull()) {
            if (content.isJsonPrimitive()) {
                return content.getAsString();
            }

            if (content.isJsonArray()) {
                StringBuilder out = new StringBuilder();

                for (JsonElement item : content.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        out.append(item.getAsString());
                    } else if (item.isJsonObject()) {
                        JsonObject obj = item.getAsJsonObject();
                        if (obj.has("text") && !obj.get("text").isJsonNull()) {
                            out.append(obj.get("text").getAsString());
                        }
                    }
                }

                if (!out.isEmpty()) return out.toString();
            }
        }

        return "";
    }

    private AIResult parse(String raw) {
        String clean = raw == null ? "" : raw.trim();

        // Remove an optional Markdown JSON fence.
        if (clean.startsWith("```")) {
            int newline = clean.indexOf('\n');
            int closing = clean.lastIndexOf("```");

            if (newline > 0 && closing > newline) {
                clean = clean.substring(newline + 1, closing).trim();
            }
        }

        // Ignore any prose around the JSON object.
        int first = clean.indexOf('{');
        int last = clean.lastIndexOf('}');

        if (first >= 0 && last > first) {
            clean = clean.substring(first, last + 1).trim();
        }

        try {
            JsonObject o = JsonParser.parseString(clean).getAsJsonObject();

            String verdict =
                    get(o, "verdict", "WATCH")
                            .toUpperCase(Locale.ROOT);

            String category =
                    get(o, "category", "OTHER")
                            .toUpperCase(Locale.ROOT);

            double confidence =
                    number(o, "confidence", 0);

            int severity = severityValue(o.get("severity"));

            String reason =
                    get(
                            o,
                            "reason",
                            "No reason provided."
                    );

            List<String> actions = new ArrayList<>();
            JsonElement actionsElement = o.get("actions");

            if (actionsElement != null && !actionsElement.isJsonNull()) {
                if (actionsElement.isJsonArray()) {
                    for (JsonElement e : actionsElement.getAsJsonArray()) {
                        if (e != null && !e.isJsonNull()) {
                            actions.add(e.getAsString().toUpperCase(Locale.ROOT));
                        }
                    }
                } else if (actionsElement.isJsonPrimitive()) {
                    String value = actionsElement.getAsString().trim();
                    if (!value.isBlank()) {
                        actions.add(value.toUpperCase(Locale.ROOT));
                    }
                }
            }

            if (actions.isEmpty()) actions.add("NONE");

            confidence =
                    Math.max(
                            0.0,
                            Math.min(1.0, confidence)
                    );

            severity =
                    Math.max(
                            0,
                            Math.min(100, severity)
                    );

            return new AIResult(
                    verdict,
                    category,
                    confidence,
                    severity,
                    reason,
                    actions,
                    false
            );
        } catch (Exception e) {
            return AIResult.error(
                    "AI returned invalid JSON: " +
                    trim(raw, 800)
            );
        }
    }

    private static int severityValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return 0;

        try {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                return (int) Math.round(element.getAsDouble());
            }
        } catch (Exception ignored) {
        }

        String value;
        try {
            value = element.getAsString().trim().toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return 0;
        }

        return switch (value) {
            case "LOW" -> 25;
            case "MEDIUM", "MED" -> 50;
            case "HIGH" -> 80;
            case "CRITICAL", "SEVERE" -> 100;
            default -> 0;
        };
    }

    private static String get(JsonObject o, String key, String fallback) {
        try { return o.has(key) ? o.get(key).getAsString() : fallback; } catch (Exception e) { return fallback; }
    }

    private static double number(JsonObject o, String key, double fallback) {
        try { return o.has(key) ? o.get(key).getAsDouble() : fallback; } catch (Exception e) { return fallback; }
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override public void close() { executor.shutdownNow(); }

    public record AIResult(String verdict, String category, double confidence, int severity, String reason, List<String> actions, boolean error) {
        public static AIResult error(String reason) {
            return new AIResult("ERROR", "OTHER", 0, 0, reason, List.of("NONE"), true);
        }
    }
}
