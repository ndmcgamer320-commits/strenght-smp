package me.strengthai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class WebApi implements AutoCloseable {
    private final StrengthAIPlugin plugin;
    private HttpServer server;

    public WebApi(StrengthAIPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("web.enabled", true)) return;

        try {
            int port = plugin.getConfig().getInt("web.port", 8080);
            server = HttpServer.create(
                    new InetSocketAddress(
                            plugin.getConfig().getString("web.host", "0.0.0.0"),
                            port
                    ),
                    0
            );

            server.createContext("/health", this::health);
            server.createContext("/api/status", this::status);
            server.createContext("/api/players", this::players);
            server.createContext("/api/events", this::events);
            server.createContext("/api/action", this::action);

            server.setExecutor(
                    Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "StrengthAI-Web");
                        t.setDaemon(true);
                        return t;
                    })
            );

            server.start();
            plugin.getLogger().info("Web API listening on port " + port);
        } catch (IOException e) {
            plugin.getLogger().warning("Web API failed to start: " + e.getMessage());
        }
    }

    private boolean authenticate(HttpExchange exchange) {
        String token = plugin.getDataStore().getSecret("web-token");

        if (token.isBlank()) {
            send(exchange, 503, "{\"error\":\"web token is not configured\"}");
            return false;
        }

        String supplied = exchange.getRequestHeaders().getFirst("X-API-Key");

        if (supplied == null || !token.equals(supplied)) {
            send(exchange, 401, "{\"error\":\"unauthorized\"}");
            return false;
        }

        return true;
    }

    private void health(HttpExchange exchange) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("plugin", "StrengthAI");
        json.addProperty("players", Bukkit.getOnlinePlayers().size());
        json.addProperty("server", Bukkit.getVersion());
        send(exchange, 200, json.toString());
    }

    private void status(HttpExchange exchange) {
        if (!authenticate(exchange)) return;

        JsonObject json = new JsonObject();
        json.addProperty("plugin", "StrengthAI");
        json.addProperty("aiConfigured", plugin.getAI().configured());
        json.addProperty("model", plugin.getConfig().getString("ai.model", "openrouter/auto"));
        json.addProperty("players", Bukkit.getOnlinePlayers().size());
        json.addProperty("tps", Bukkit.getTPS()[0]);
        send(exchange, 200, json.toString());
    }

    private void players(HttpExchange exchange) {
        if (!authenticate(exchange)) return;

        var array = new com.google.gson.JsonArray();

        for (Player player : Bukkit.getOnlinePlayers()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", player.getName());
            json.addProperty("uuid", player.getUniqueId().toString());
            json.addProperty("ping", player.getPing());
            json.addProperty("world", player.getWorld().getName());
            json.addProperty("trusted", plugin.getAntiCheat().isTrusted(player));
            json.addProperty("evidence", plugin.getAntiCheat().summary(player));
            array.add(json);
        }

        send(exchange, 200, array.toString());
    }

    private void events(HttpExchange exchange) {
        if (!authenticate(exchange)) return;
        send(exchange, 200, plugin.getRecentEventsJson());
    }

    private void action(HttpExchange exchange) {
        if (!authenticate(exchange)) return;

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, "");
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"POST required\"}");
            return;
        }

        try {
            JsonObject input = JsonParser.parseString(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            ).getAsJsonObject();

            String type = input.has("type")
                    ? input.get("type").getAsString().toLowerCase()
                    : "";

            String playerName = input.has("player")
                    ? input.get("player").getAsString()
                    : "";

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = playerName.isBlank()
                        ? null
                        : Bukkit.getPlayerExact(playerName);

                switch (type) {
                    case "broadcast" -> Bukkit.broadcastMessage(
                            ChatColor.translateAlternateColorCodes(
                                    '&',
                                    input.has("message")
                                            ? input.get("message").getAsString()
                                            : "&bStrengthAI&f broadcast"
                            )
                    );

                    case "scan" -> {
                        if (player != null) {
                            plugin.queueAI(
                                    player,
                                    "WEB_SCAN",
                                    plugin.getAntiCheat().evidence(player)
                            );
                        }
                    }

                    case "reset" -> {
                        if (player != null) plugin.resetPlayer(player);
                    }

                    case "kick" -> {
                        if (player != null) plugin.executePunishment(player, "kick", "WEB");
                    }

                    case "ban" -> {
                        if (player != null) plugin.executePunishment(player, "ban", "WEB");
                    }

                    default -> {
                    }
                }
            });

            send(exchange, 202, "{\"ok\":true}");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"invalid action JSON\"}");
        }
    }

    private void send(HttpExchange exchange, int code, String body) {
        try {
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            exchange.getResponseHeaders().set(
                    "Access-Control-Allow-Origin",
                    plugin.getConfig().getString("web.cors-origin", "*")
            );
            exchange.getResponseHeaders().set(
                    "Access-Control-Allow-Headers",
                    "Content-Type,X-API-Key"
            );
            exchange.getResponseHeaders().set(
                    "Access-Control-Allow-Methods",
                    "GET,POST,OPTIONS"
            );

            byte[] bytes =
                    body.getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(
                    code,
                    bytes.length
            );

            try (OutputStream output =
                         exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
