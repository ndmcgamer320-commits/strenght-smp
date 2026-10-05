package me.strengthai;

import com.google.gson.JsonArray;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public final class StrengthAIPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener, TabExecutor {
    private DataStore dataStore;
    private OpenRouterService ai;
    private AntiCheatManager antiCheat;
    private WebApi web;
    private BukkitTask periodicTask;
    private final Deque<String> recentEvents = new ConcurrentLinkedDeque<>();
    private final AtomicLong lastAIRequest = new AtomicLong(0);
    private long lastHealthLog;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dataStore = new DataStore(this);

        String envKey = System.getenv("OPENROUTER_API_KEY");
        if (dataStore.getSecret("openrouter-key").isBlank() && envKey != null && !envKey.isBlank()) {
            dataStore.setSecret("openrouter-key", envKey.trim());
            getLogger().info("Loaded OpenRouter API key from environment.");
        }

        if (dataStore.getSecret("web-token").isBlank()) {
            dataStore.setSecret("web-token", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        }

        ai = new OpenRouterService(this);
        antiCheat = new AntiCheatManager(this);
        web = new WebApi(this);

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("strengthai");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        web.start();

        periodicTask = getServer().getScheduler().runTaskTimer(
                this,
                () -> {
                    antiCheat.periodicReview();
                    logHealth();
                    dataStore.save();
                },
                20L * 30L,
                20L * 30L
        );

        getLogger().info("========================================");
        getLogger().info("StrengthAI enabled");
        getLogger().info("AI: " + (ai.configured() ? "configured" : "NOT CONFIGURED"));
        getLogger().info("Anti-cheat: " + (getConfig().getBoolean("anti-cheat.enabled", true) ? "enabled" : "disabled"));
        getLogger().info("Web API: " + (getConfig().getBoolean("web.enabled", true) ? "enabled" : "disabled"));
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        if (periodicTask != null) periodicTask.cancel();
        if (web != null) web.close();
        if (ai != null) ai.close();
        if (dataStore != null) dataStore.save();
    }

    public DataStore getDataStore() {
        return dataStore;
    }

    public OpenRouterService getAI() {
        return ai;
    }

    public AntiCheatManager getAntiCheat() {
        return antiCheat;
    }

    public void queueAI(Player player, String event, String evidence) {
        if (!getConfig().getBoolean("ai.enabled", true)) {
            antiCheat.profile(player.getUniqueId()).aiPending.set(false);
            return;
        }

        if (!getConfig().getBoolean("anti-cheat.enabled", true)) {
            antiCheat.profile(player.getUniqueId()).aiPending.set(false);
            return;
        }

        if (antiCheat.isTrusted(player)) {
            antiCheat.profile(player.getUniqueId()).aiPending.set(false);
            return;
        }

        if (!ai.configured()) {
            antiCheat.profile(player.getUniqueId()).aiPending.set(false);
            return;
        }

        double tps = Bukkit.getTPS()[0];
        if (tps > 0 && tps < getConfig().getDouble("health.pause-ai-below-tps", 14.0)) {
            antiCheat.profile(player.getUniqueId()).aiPending.set(false);
            addEvent("AI review skipped because TPS is low: " + String.format(Locale.US, "%.2f", tps));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = getConfig().getLong("ai.cooldown-seconds", 20L) * 1000L;

        if (!"PLAYER_JOIN".equals(event)) {
            long previous = lastAIRequest.get();
            if (now - previous < cooldown) {
                antiCheat.profile(player.getUniqueId()).aiPending.set(false);
                return;
            }
        }

        if (!lastAIRequest.compareAndSet(
                "PLAYER_JOIN".equals(event) ? lastAIRequest.get() : lastAIRequest.get(),
                now
        ) && !"PLAYER_JOIN".equals(event)) {
            antiCheat.profile(player.getUniqueId()).aiPending.set(false);
            return;
        }

        addEvent("AI review queued: " + player.getName() + " | " + event);

        ai.analyze(player.getName(), event, evidence).thenAccept(result ->
                getServer().getScheduler().runTask(this, () -> {
                    antiCheat.profile(player.getUniqueId()).aiPending.set(false);

                    if (!player.isOnline()) {
                        addEvent("AI result received after player left: " + player.getName());
                        return;
                    }

                    if (result.error()) {
                        addEvent("AI error for " + player.getName() + ": " + result.reason());
                        return;
                    }

                    addEvent(
                            "AI result: " + player.getName() +
                            " | " + result.verdict() +
                            " | " + result.category() +
                            " | " + String.format(Locale.US, "%.0f%%", result.confidence() * 100)
                    );

                    antiCheat.applyAI(player, result);
                })
        );
    }

    public void resetPlayer(Player player) {
        dataStore.resetKills(player.getUniqueId());
        antiCheat.profile(player.getUniqueId()).kills = 0;

        for (String raw : getConfig().getStringList("reset-commands")) {
            String cmd = raw
                    .replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .trim();

            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }

        player.sendMessage(prefix() + "&eYour combat statistics were reset.");
        addEvent("Stats reset: " + player.getName());
    }

    public void executePunishment(Player player, String kind, String category) {
        String cmd = getConfig().getString(
                "punishment." + kind + "-command",
                ""
        );

        if (cmd == null || cmd.isBlank()) {
            getLogger().warning("No punishment command configured for " + kind);
            return;
        }

        cmd = cmd
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("%category%", category);

        if (cmd.startsWith("/")) cmd = cmd.substring(1);

        Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                cmd
        );

        addEvent(
                "Punishment: " + kind +
                " " + player.getName() +
                " category=" + category
        );
    }

    public String getRecentEventsJson() {
        JsonArray array = new JsonArray();

        for (String value : recentEvents) {
            array.add(value);
        }

        return array.toString();
    }

    public String recentEventsText(int max) {
        StringBuilder builder = new StringBuilder();

        int count = 0;
        for (String event : recentEvents) {
            builder.append(event).append("\n");
            count++;
            if (count >= max) break;
        }

        return builder.toString();
    }

    private void addEvent(String value) {
        recentEvents.addFirst(new Date() + " | " + value);

        while (recentEvents.size() > 100) {
            recentEvents.removeLast();
        }

        getLogger().info(value);
    }

    private void logHealth() {
        if (!getConfig().getBoolean("health.enabled", true)) return;

        long now = System.currentTimeMillis();
        long interval =
                getConfig().getLong("health.log-every-seconds", 60L) * 1000L;

        if (now - lastHealthLog < interval) return;

        lastHealthLog = now;

        double tps = Bukkit.getTPS()[0];

        addEvent(
                String.format(
                        Locale.US,
                        "Health: TPS=%.2f players=%d",
                        tps,
                        Bukkit.getOnlinePlayers().size()
                )
        );

        if (tps < getConfig().getDouble("health.low-tps-threshold", 17.0)) {
            getLogger().warning(
                    String.format(
                            Locale.US,
                            "Low TPS detected: %.2f",
                            tps
                    )
            );
        }
    }

    private String prefix() {
        return ChatColor.translateAlternateColorCodes(
                '&',
                getConfig().getString(
                        "messages.prefix",
                        "&8[&bStrengthAI&8] &f"
                )
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        antiCheat.join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        antiCheat.quit(event.getPlayer());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        antiCheat.chat(event.getPlayer());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        antiCheat.command(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        antiCheat.move(event);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        antiCheat.blockBreak(event.getPlayer(), event.getBlock());
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        antiCheat.attack(event);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            antiCheat.death(event.getEntity(), killer);
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("strengthai.admin")) {
            sender.sendMessage(prefix() + "&cNo permission.");
            return true;
        }

        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);

            case "status" -> {
                sender.sendMessage(prefix() + "&bStrengthAI &7v" + getDescription().getVersion());
                sender.sendMessage(prefix() + "AI: &f" +
                        (ai.configured() ? "configured" : "not configured") +
                        " &7| model=&f" +
                        getConfig().getString("ai.model", "openrouter/auto"));
                sender.sendMessage(prefix() + "Anti-cheat: &f" +
                        (getConfig().getBoolean("anti-cheat.enabled", true) ? "ON" : "OFF"));
                sender.sendMessage(prefix() + "Web API: &f" +
                        (getConfig().getBoolean("web.enabled", true) ? "ON" : "OFF"));
                sender.sendMessage(prefix() + "Players: &f" +
                        Bukkit.getOnlinePlayers().size());
                sender.sendMessage(prefix() + "TPS: &f" +
                        String.format(Locale.US, "%.2f", Bukkit.getTPS()[0]));
            }

            case "api" -> apiCommand(sender, args);

            case "scan", "inspect" -> {
                if (args.length < 2) {
                    sender.sendMessage(prefix() + "&cUsage: /strengthai scan <player>");
                    return true;
                }

                Player player = Bukkit.getPlayerExact(args[1]);

                if (player == null) {
                    sender.sendMessage(prefix() + "&cPlayer not online.");
                    return true;
                }

                sender.sendMessage(prefix() + "&7Local evidence: &f" + antiCheat.summary(player));

                antiCheat.profile(player.getUniqueId()).aiPending.set(true);
                queueAI(player, "ADMIN_SCAN", antiCheat.evidence(player));

                sender.sendMessage(prefix() + "&aAI scan queued.");
            }

            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage(prefix() + "&cUsage: /strengthai reset <player>");
                    return true;
                }

                Player player = Bukkit.getPlayerExact(args[1]);

                if (player == null) {
                    sender.sendMessage(prefix() + "&cPlayer not online.");
                    return true;
                }

                resetPlayer(player);
            }

            case "punish" -> {
                if (args.length < 3) {
                    sender.sendMessage(prefix() + "&cUsage: /strengthai punish <player> <ban|kick|mute>");
                    return true;
                }

                Player player = Bukkit.getPlayerExact(args[1]);

                if (player == null) {
                    sender.sendMessage(prefix() + "&cPlayer not online.");
                    return true;
                }

                String kind = args[2].toLowerCase(Locale.ROOT);

                if (!Set.of("ban", "kick", "mute").contains(kind)) {
                    sender.sendMessage(prefix() + "&cUnknown punishment.");
                    return true;
                }

                executePunishment(player, kind, "MANUAL");
            }

            case "trust", "untrust" -> {
                if (args.length < 2) {
                    sender.sendMessage(prefix() + "&cUsage: /strengthai " + args[0] + " <player>");
                    return true;
                }

                Player player = Bukkit.getPlayerExact(args[1]);

                if (player == null) {
                    sender.sendMessage(prefix() + "&cPlayer not online.");
                    return true;
                }

                boolean value = args[0].equalsIgnoreCase("trust");
                antiCheat.trust(player, value);

                sender.sendMessage(
                        prefix() +
                        (value ? "&aTrusted " : "&eUntrusted ") +
                        player.getName()
                );
            }

            case "stats" -> {
                if (args.length < 2) {
                    sender.sendMessage(prefix() + "&cUsage: /strengthai stats <player>");
                    return true;
                }

                Player player = Bukkit.getPlayerExact(args[1]);

                if (player == null) {
                    sender.sendMessage(prefix() + "&cPlayer not online.");
                    return true;
                }

                sender.sendMessage(prefix() + "&7" + antiCheat.evidence(player));
            }

            case "watch" -> {
                if (args.length < 2) {
                    sender.sendMessage(prefix() + "&cUsage: /strengthai watch <player>");
                    return true;
                }
                Player player = Bukkit.getPlayerExact(args[1]);
                if (player == null) {
                    sender.sendMessage(prefix() + "&cPlayer not online.");
                    return true;
                }
                antiCheat.watch(player, true);
                sender.sendMessage(prefix() + "&aAI watch enabled for " + player.getName());
            }

            case "unwatch" -> {
                if (args.length < 2) {
                    sender.sendMessage(prefix() + "&cUsage: /strengthai unwatch <player>");
                    return true;
                }
                Player player = Bukkit.getPlayerExact(args[1]);
                if (player == null) {
                    sender.sendMessage(prefix() + "&cPlayer not online.");
                    return true;
                }
                antiCheat.watch(player, false);
                sender.sendMessage(prefix() + "&eAI watch disabled for " + player.getName());
            }

            case "events" -> {
                int max = 15;
                if (args.length >= 2) {
                    try { max = Math.max(1, Math.min(50, Integer.parseInt(args[1]))); }
                    catch (NumberFormatException ignored) { }
                }

                for (String line : recentEvents) {
                    sender.sendMessage(prefix() + "&7" + line);
                    if (--max <= 0) break;
                }
            }

            case "webtoken" -> {
                String token = dataStore.getSecret("web-token");
                if (args.length >= 2 && args[1].equalsIgnoreCase("rotate")) {
                    token = UUID.randomUUID().toString().replace("-", "") +
                            UUID.randomUUID().toString().replace("-", "");
                    dataStore.setSecret("web-token", token);
                    sender.sendMessage(prefix() + "&aWeb API token rotated.");
                } else {
                    sender.sendMessage(prefix() + "&7Web API token is configured. Use &f/strengthai webtoken rotate&7 to rotate it.");
                }
            }

            case "reload" -> {
                reloadConfig();
                sender.sendMessage(prefix() + "&aStrengthAI config reloaded.");
            }

            default -> sender.sendMessage(prefix() + "&cUnknown command. Use /strengthai help");
        }

        return true;
    }

    private void apiCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(prefix() + "&7/api set <key> | status | clear | model <provider/model>");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(prefix() + "&cUsage: /api set <OpenRouter key>");
                    return;
                }

                dataStore.setSecret("openrouter-key", args[2].trim());
                sender.sendMessage(prefix() + "&aOpenRouter key saved. The key is not displayed by StrengthAI.");
            }

            case "clear" -> {
                dataStore.clearSecret("openrouter-key");
                sender.sendMessage(prefix() + "&eOpenRouter key cleared.");
            }

            case "status" -> sender.sendMessage(
                    prefix() + "&7OpenRouter: &f" +
                    (ai.configured() ? "configured" : "not configured") +
                    " &7| model=&f" +
                    getConfig().getString("ai.model", "openrouter/auto")
            );

            case "model" -> {
                if (args.length < 3) {
                    sender.sendMessage(prefix() + "&cUsage: /api model <provider/model>");
                    return;
                }

                getConfig().set("ai.model", args[2]);
                saveConfig();
                sender.sendMessage(prefix() + "&aAI model changed to &f" + args[2]);
            }

            default -> sender.sendMessage(prefix() + "&cUnknown API command.");
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(prefix() + "&bStrengthAI commands");
        sender.sendMessage("&f/api set <key> &7- configure OpenRouter");
        sender.sendMessage("&f/api status &7- check AI configuration");
        sender.sendMessage("&f/api model <provider/model> &7- choose AI model");
        sender.sendMessage("&f/sai scan <player> &7- AI anti-cheat scan");
        sender.sendMessage("&f/sai inspect <player> &7- evidence + AI scan");
        sender.sendMessage("&f/sai stats <player> &7- detailed local evidence");
        sender.sendMessage("&f/sai reset <player> &7- reset kills/strength hooks");
        sender.sendMessage("&f/sai punish <player> <ban|kick|mute>");
        sender.sendMessage("&f/sai trust|untrust <player>");
        sender.sendMessage("&f/sai watch|unwatch <player>");
        sender.sendMessage("&f/sai webtoken rotate &7- rotate website API token");
        sender.sendMessage("&f/sai status &7- plugin/server status");
        sender.sendMessage("&f/sai events [count] &7- recent AI events");
        sender.sendMessage("&f/sai reload &7- reload config");
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            return List.of(
                    "help",
                    "status",
                    "api",
                    "scan",
                    "inspect",
                    "stats",
                    "reset",
                    "punish",
                    "trust",
                    "untrust",
                    "watch",
                    "unwatch",
                    "webtoken",
                    "events",
                    "reload"
            ).stream()
                    .filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2 &&
                Set.of("scan", "inspect", "stats", "reset", "punish", "trust", "untrust", "watch", "unwatch")
                        .contains(args[0].toLowerCase(Locale.ROOT))) {
            return Bukkit.getOnlinePlayers()
                    .stream()
                    .map(Player::getName)
                    .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("api")) {
            return List.of("set", "status", "clear", "model")
                    .stream()
                    .filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("punish")) {
            return List.of("ban", "kick", "mute")
                    .stream()
                    .filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return List.of();
    }
}
