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
                "You are the security reviewer for a Minecraft Paper server. " +
                "Analyze only supplied evidence and be conservative. Never invent facts. " +
                "Return only JSON with keys verdict, category, confidence, severity, reason, actions. " +
                "verdict=SAFE|WATCH|CHEAT. category=NONE|XRAY|KILLAURA|REACH|FLY|SPEED|BARITONE|SPAM|BOT|OTHER. " +
                "actions may only be NONE|WATCH|KICK|BAN|MUTE|RESET_STRENGTH. Only recommend BAN for strong evidence.");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content",
                "Player: " + player + "\nEvent: " + event + "\nEvidence:\n" + clipped +
                "\nReturn the safest justified JSON decision.");
        messages.add(user);

        body.add("messages", messages);
        body.addProperty("temperature", 0.1);
        body.addProperty("max_tokens", 600);

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

                JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                return parse(msg.get("content").getAsString());
            } catch (Exception e) {
                return AIResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }, executor);
    }

    private AIResult parse(String raw) {
        String clean = raw.trim();
        int first = clean.indexOf('{');
        int last = clean.lastIndexOf('}');
        if (first >= 0 && last > first) clean = clean.substring(first, last + 1);

        try {
            JsonObject o = JsonParser.parseString(clean).getAsJsonObject();
            String verdict = get(o, "verdict", "WATCH").toUpperCase(Locale.ROOT);
            String category = get(o, "category", "OTHER").toUpperCase(Locale.ROOT);
            double confidence = number(o, "confidence", 0);
            int severity = (int) Math.round(number(o, "severity", 0));
            String reason = get(o, "reason", "No reason provided.");

            List<String> actions = new ArrayList<>();
            JsonArray arr = o.getAsJsonArray("actions");
            if (arr != null) for (JsonElement e : arr) actions.add(e.getAsString().toUpperCase(Locale.ROOT));

            return new AIResult(verdict, category, confidence, severity, reason, actions, false);
        } catch (Exception e) {
            return AIResult.error("AI returned invalid JSON: " + trim(raw, 500));
        }
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
