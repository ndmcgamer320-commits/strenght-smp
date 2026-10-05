package me.strengthai;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AntiCheatManager {
    private final StrengthAIPlugin plugin;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private final Set<UUID> trusted = ConcurrentHashMap.newKeySet();
    private final Set<UUID> watch = ConcurrentHashMap.newKeySet();

    public AntiCheatManager(StrengthAIPlugin plugin) {
        this.plugin = plugin;
        trusted.addAll(plugin.getDataStore().getTrusted());
    }

    public Profile profile(UUID id) {
        return profiles.computeIfAbsent(id, k -> new Profile());
    }

    public void join(Player player) {
        Profile p = profile(player.getUniqueId());
        p.joinCount++;
        p.lastJoin = System.currentTimeMillis();
        p.events.addFirst("JOIN");
        notifyAdmins("&b" + player.getName() + " &7joined. StrengthAI monitoring started.");
        plugin.queueAI(player, "PLAYER_JOIN", evidence(player));
    }

    public void quit(Player player) {
        profile(player.getUniqueId()).lastQuit = System.currentTimeMillis();
    }

    public void chat(Player player) {
        if (!plugin.getConfig().getBoolean("anti-cheat.spam", true)) return;

        long now = System.currentTimeMillis();
        Profile p = profile(player.getUniqueId());
        p.chatTimes.addLast(now);
        prune(p.chatTimes, now - plugin.getConfig().getLong("anti-cheat.spam-window-ms", 6000));
        if (p.chatTimes.size() > plugin.getConfig().getInt("anti-cheat.spam-max-messages", 6)) {
            p.spamScore += 20;
            p.score += 10;
            plugin.queueAI(player, "SPAM", evidence(player));
        }
    }

    public void command(Player player) {
        if (!plugin.getConfig().getBoolean("anti-cheat.spam", true)) return;

        Profile p = profile(player.getUniqueId());
        p.commandBursts++;
        p.commandTimes.addLast(System.currentTimeMillis());
        prune(p.commandTimes, System.currentTimeMillis() - 6000);
        if (p.commandTimes.size() > 10) {
            p.spamScore += 10;
            plugin.queueAI(player, "COMMAND_SPAM", evidence(player));
        }
    }

    public void death(Player dead, Player killer) {
        if (!plugin.getConfig().getBoolean("anti-cheat.bot", true)) return;
        if (killer == null || killer.getUniqueId().equals(dead.getUniqueId())) return;
        plugin.getDataStore().addKill(killer.getUniqueId());
        Profile p = profile(killer.getUniqueId());
        p.kills++;
        p.killTimes.addLast(System.currentTimeMillis());
        prune(p.killTimes, System.currentTimeMillis() - 60000);
        if (p.killTimes.size() > 15) {
            p.botScore += 10;
            p.score += 8;
            plugin.queueAI(killer, "COMBAT_BOT", evidence(killer));
        }
    }

    public void attack(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("anti-cheat.combat", true)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        Profile p = profile(player.getUniqueId());
        long now = System.currentTimeMillis();
        p.attackTimes.addLast(now);
        prune(p.attackTimes, now - plugin.getConfig().getLong("anti-cheat.combat-window-ms", 3000));

        if (event.getEntity().getLocation().distance(player.getLocation()) >
                plugin.getConfig().getDouble("anti-cheat.max-reach", 4.15)) {
            p.reachFlags++;
            p.score += 18;
            plugin.queueAI(player, "REACH", evidence(player));
        }

        if (p.attackTimes.size() >
                plugin.getConfig().getInt("anti-cheat.combat-max-hits", 18)) {
            p.killauraFlags++;
            p.score += 15;
            plugin.queueAI(player, "KILLAURA", evidence(player));
        }
    }

    public void move(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("anti-cheat.movement", true)) return;
        if (event.getTo() == null) return;
        Player player = event.getPlayer();

        if (player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() ||
                player.isInsideVehicle() ||
                player.isGliding() ||
                player.isSwimming()) return;

        Profile p = profile(player.getUniqueId());

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double dy = event.getTo().getY() - event.getFrom().getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (horizontal > plugin.getConfig().getDouble("anti-cheat.max-horizontal-speed", 0.72)) {
            p.speedFlags++;
            p.score += 5;
        }

        if (!player.isOnGround() &&
                dy > plugin.getConfig().getDouble("anti-cheat.max-air-y-delta", 1.25) &&
                !player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) {
            p.flyFlags++;
            p.score += 8;
        }

        String point = (event.getTo().getBlockX() / 2) + ":" +
                (event.getTo().getBlockY() / 2) + ":" +
                (event.getTo().getBlockZ() / 2);

        p.path.addLast(point);
        int max = plugin.getConfig().getInt("anti-cheat.path-history-size", 120);
        while (p.path.size() > max) p.path.removeFirst();

        p.pathRepeats = repeatedWindows(p.path);
        if (plugin.getConfig().getBoolean("anti-cheat.automation", true) &&
                p.pathRepeats >= 3) {
            p.baritoneFlags++;
            p.score += 4;
        }

        if (p.score >= plugin.getConfig().getInt("ai.review-score", 35) &&
                p.aiPending.compareAndSet(false, true)) {
            plugin.queueAI(player, "MOVEMENT_REVIEW", evidence(player));
        }
    }

    public void blockBreak(Player player, Block block) {
        if (!plugin.getConfig().getBoolean("anti-cheat.mining", true)) return;

        Profile p = profile(player.getUniqueId());
        p.blocksBroken++;

        Material type = block.getType();
        if (isOre(type)) {
            p.oresBroken++;
            if (isValuable(type)) p.valuableOres++;
            p.oreYTotal += block.getY();

            if (block.getRelative(1, 0, 0).getType().isAir() ||
                    block.getRelative(-1, 0, 0).getType().isAir() ||
                    block.getRelative(0, 0, 1).getType().isAir() ||
                    block.getRelative(0, 0, -1).getType().isAir()) {
                p.exposedOres++;
            }
        }

        if (p.blocksBroken >= plugin.getConfig().getInt("anti-cheat.ore-review-min-blocks", 80) &&
                p.valuableOres >= plugin.getConfig().getInt("anti-cheat.ore-review-min-valuable", 4)) {

            p.score += 12;
            plugin.queueAI(player, "MINING_REVIEW", evidence(player));

            p.blocksBroken = 0;
            p.oresBroken = 0;
            p.valuableOres = 0;
            p.exposedOres = 0;
            p.oreYTotal = 0;
        }
    }

    public void periodicReview() {
        if (!plugin.getConfig().getBoolean("anti-cheat.enabled", true)) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile p = profile(player.getUniqueId());
            decay(p);

            if (p.score >= plugin.getConfig().getInt("ai.review-score", 35) &&
                    p.aiPending.compareAndSet(false, true)) {
                plugin.queueAI(player, "PERIODIC", evidence(player));
            }
        }
    }

    public void applyAI(Player player, OpenRouterService.AIResult result) {
        Profile p = profile(player.getUniqueId());
        p.aiPending.set(false);

        if (result.error()) return;

        p.lastCategory = result.category();
        p.lastReason = result.reason();
        p.lastConfidence = result.confidence();

        boolean confirmed =
                "CHEAT".equalsIgnoreCase(result.verdict()) &&
                result.confidence() >= plugin.getConfig().getDouble("ai.reset-confidence", 0.85);

        if (!confirmed) return;

        watch.add(player.getUniqueId());

        notifyAdmins("&cAI flag: &f" + player.getName() +
                " &7=> &e" + result.category() +
                " &7(" + String.format(Locale.US, "%.0f%%", result.confidence() * 100) +
                ") &8- &f" + result.reason());

        if (result.actions().contains("RESET_STRENGTH") ||
                result.confidence() >= plugin.getConfig().getDouble("ai.reset-confidence", 0.85)) {
            plugin.resetPlayer(player);
        }

        boolean auto = plugin.getConfig().getBoolean("anti-cheat.auto-actions", true);
        boolean autoBan = plugin.getConfig().getBoolean("fair-bans.auto-ban", true);

        // Never punish an offline player from a late AI response.
        if (!player.isOnline()) return;

        if (autoBan &&
                result.actions().contains("BAN") &&
                banEligible(player, result)) {
            plugin.executePunishment(player, "ban", result.category());
            p.score = 0;
            return;
        }

        if (auto && result.actions().contains("KICK") &&
                result.confidence() >= plugin.getConfig().getDouble("fair-bans.kick-confidence", 0.99) &&
                p.score >= plugin.getConfig().getInt("fair-bans.kick-score", 70)) {
            plugin.executePunishment(player, "kick", result.category());
        } else if (auto && result.actions().contains("MUTE") &&
                result.confidence() >= 0.95 &&
                p.spamScore >= 25) {
            plugin.executePunishment(player, "mute", result.category());
        }
    }

    public void recordGrimFlag(Player player, String checkName, String verbose) {
        Profile p = profile(player.getUniqueId());
        String name = checkName == null ? "UNKNOWN" : checkName.toUpperCase(Locale.ROOT);
        p.grimFlags++;
        p.grimChecks.merge(name, 1, Integer::sum);
        p.score += 3;
        p.lastGrimCheck = name;
        p.lastGrimVerbose = verbose == null ? "" : verbose.substring(0, Math.min(300, verbose.length()));
        p.events.addFirst("GRIM:" + name);
        while (p.events.size() > 30) p.events.removeLast();

        if (p.grimFlags % 5 == 0 && p.aiPending.compareAndSet(false, true)) {
            plugin.queueAI(player, "GRIM_REVIEW", evidence(player));
        }
    }

    public void recordTotem(Player player, boolean cancelled, String hand) {
        Profile p = profile(player.getUniqueId());
        if (cancelled) return;
        long now = System.currentTimeMillis();
        p.totemPops++;
        p.totemTimes.addLast(now);
        prune(p.totemTimes, now - 15000);
        p.lastTotemHand = hand == null ? "UNKNOWN" : hand;
        p.events.addFirst("TOTEM:" + p.lastTotemHand);
        while (p.events.size() > 30) p.events.removeLast();

        // Totems are normal gameplay telemetry, never a ban trigger by themselves.
        if (p.totemTimes.size() >= 3 && p.aiPending.compareAndSet(false, true)) {
            plugin.queueAI(player, "TOTEM_REVIEW", evidence(player));
        }
    }

    public void recordBlockPlace(Player player, Block block) {
        Profile p = profile(player.getUniqueId());
        p.blocksPlaced++;
        long now = System.currentTimeMillis();
        p.placeTimes.addLast(now);
        prune(p.placeTimes, now - 1500);

        if (p.placeTimes.size() >= 10) {
            p.scaffoldBursts++;
            p.score += 2;
        }
    }

    public void recordInventoryAction(Player player) {
        Profile p = profile(player.getUniqueId());
        long now = System.currentTimeMillis();
        p.inventoryTimes.addLast(now);
        prune(p.inventoryTimes, now - 1000);

        if (p.inventoryTimes.size() >= 18) {
            p.inventoryBursts++;
            p.score += 2;
        }
    }

    public boolean banEligible(Player player, OpenRouterService.AIResult result) {
        Profile p = profile(player.getUniqueId());

        if (!player.isOnline()) return false;
        if (isTrusted(player)) return false;

        long grace = plugin.getConfig().getLong("fair-bans.join-grace-seconds", 60L) * 1000L;
        if (System.currentTimeMillis() - p.lastJoin < grace) return false;

        if (p.recentTeleportUntil > System.currentTimeMillis() ||
                p.recentVelocityUntil > System.currentTimeMillis()) {
            return false;
        }

        double tps = Bukkit.getTPS()[0];
        if (tps > 0 && tps < plugin.getConfig().getDouble("fair-bans.min-tps", 18.0)) {
            return false;
        }

        if (!"CHEAT".equalsIgnoreCase(result.verdict())) return false;

        double minConfidence = plugin.getConfig().getDouble("fair-bans.min-ai-confidence", 0.98);
        int minScore = plugin.getConfig().getInt("fair-bans.min-score", 120);
        int minSignals = plugin.getConfig().getInt("fair-bans.min-independent-signals", 2);

        if (result.confidence() < minConfidence || p.score < minScore) return false;

        int signals = 0;
        if (p.grimFlags >= plugin.getConfig().getInt("fair-bans.min-grim-flags", 5)) signals++;
        if (p.reachFlags >= 3 || p.killauraFlags >= 3) signals++;
        if (p.speedFlags >= 4 || p.flyFlags >= 3) signals++;
        if (p.valuableOres >= 8 && p.blocksBroken >= 150) signals++;
        if (p.scaffoldBursts >= 4 || p.inventoryBursts >= 4) signals++;
        if (p.spamScore >= 30 || p.botScore >= 20) signals++;

        // Baritone/automation is never sufficient as a standalone permanent-ban signal.
        if ("BARITONE".equalsIgnoreCase(result.category()) && signals < minSignals) return false;

        return signals >= minSignals;
    }

    public String banReview(Player player) {
        Profile p = profile(player.getUniqueId());
        return "online=" + player.isOnline() +
                ",score=" + p.score +
                ",grimFlags=" + p.grimFlags +
                ",reach=" + p.reachFlags +
                ",killaura=" + p.killauraFlags +
                ",speed=" + p.speedFlags +
                ",fly=" + p.flyFlags +
                ",valuableOre=" + p.valuableOres +
                ",scaffoldBursts=" + p.scaffoldBursts +
                ",inventoryBursts=" + p.inventoryBursts +
                ",totems=" + p.totemPops +
                ",lastGrim=" + p.lastGrimCheck;
    }

    public void trust(Player player, boolean value) {
        if (value) trusted.add(player.getUniqueId());
        else trusted.remove(player.getUniqueId());
        plugin.getDataStore().setTrusted(trusted);
    }

    public boolean isTrusted(Player player) {
        return trusted.contains(player.getUniqueId());
    }

    public void watch(Player player, boolean value) {
        if (value) watch.add(player.getUniqueId());
        else watch.remove(player.getUniqueId());
    }

    public String evidence(Player player) {
        Profile p = profile(player.getUniqueId());
        return "score=" + p.score +
                "\nkills=" + p.kills +
                "\nspamScore=" + p.spamScore +
                "\nbotScore=" + p.botScore +
                "\nreachFlags=" + p.reachFlags +
                "\nkillauraFlags=" + p.killauraFlags +
                "\nspeedFlags=" + p.speedFlags +
                "\nflyFlags=" + p.flyFlags +
                "\nbaritoneFlags=" + p.baritoneFlags +
                "\nblocksBroken=" + p.blocksBroken +
                "\noresBroken=" + p.oresBroken +
                "\nvaluableOres=" + p.valuableOres +
                "\nexposedOres=" + p.exposedOres +
                "\npathRepeats=" + p.pathRepeats +
                "\ncommands=" + p.commandBursts +
                "\ngrimFlags=" + p.grimFlags +
                "\ngrimChecks=" + p.grimChecks +
                "\nlastGrimCheck=" + p.lastGrimCheck +
                "\nlastGrimVerbose=" + p.lastGrimVerbose +
                "\nblocksPlaced=" + p.blocksPlaced +
                "\nscaffoldBursts=" + p.scaffoldBursts +
                "\ninventoryBursts=" + p.inventoryBursts +
                "\ntotemPops=" + p.totemPops +
                "\nlastTotemHand=" + p.lastTotemHand +
                "\nlastCategory=" + p.lastCategory +
                "\nlastConfidence=" + String.format(Locale.US, "%.3f", p.lastConfidence) +
                "\nlastReason=" + p.lastReason;
    }

    public String summary(Player player) {
        Profile p = profile(player.getUniqueId());
        return "score=" + p.score +
                ", kills=" + p.kills +
                ", reach=" + p.reachFlags +
                ", killaura=" + p.killauraFlags +
                ", speed=" + p.speedFlags +
                ", fly=" + p.flyFlags +
                ", baritone=" + p.baritoneFlags +
                ", valuableOre=" + p.valuableOres +
                ", AI=" + p.lastCategory + " " +
                String.format(Locale.US, "%.0f%%", p.lastConfidence * 100);
    }

    public void notifyAdmins(String message) {
        if (!plugin.getConfig().getBoolean("anti-cheat.notify-admins", true)) return;

        String rendered = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("messages.prefix", "&8[&bStrengthAI&8] &f") + message);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("strengthai.notify")) {
                player.sendMessage(rendered);
            }
        }

        plugin.getLogger().info(ChatColor.stripColor(rendered));
    }

    private static void prune(Deque<Long> q, long min) {
        while (!q.isEmpty() && q.peekFirst() < min) q.removeFirst();
    }

    private static void decay(Profile p) {
        if (p.score > 0) p.score = Math.max(0, p.score - 3);
        if (p.spamScore > 0) p.spamScore--;
    }

    private static int repeatedWindows(Deque<String> path) {
        List<String> a = new ArrayList<>(path);
        int repeats = 0;

        for (int len = 4; len <= 8; len++) {
            for (int i = 0; i + len * 2 <= a.size(); i++) {
                if (a.subList(i, i + len).equals(a.subList(i + len, i + len * 2))) repeats++;
            }
        }

        return Math.min(10, repeats);
    }

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    private static boolean isValuable(Material m) {
        return switch (m) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                 EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                 ANCIENT_DEBRIS, GOLD_ORE, DEEPSLATE_GOLD_ORE -> true;
            default -> false;
        };
    }

    public static final class Profile {
        int score;
        int joinCount;
        int kills;
        int spamScore;
        int botScore;
        int reachFlags;
        int killauraFlags;
        int speedFlags;
        int flyFlags;
        int baritoneFlags;
        int blocksBroken;
        int oresBroken;
        int valuableOres;
        int exposedOres;
        int oreYTotal;
        int commandBursts;
        int pathRepeats;
        int grimFlags;
        int blocksPlaced;
        int scaffoldBursts;
        int inventoryBursts;
        int totemPops;
        int autoclickFlags;
        long recentTeleportUntil;
        long recentVelocityUntil;
        String lastGrimCheck = "NONE";
        String lastGrimVerbose = "";
        String lastTotemHand = "NONE";
        final Map<String, Integer> grimChecks = new HashMap<>();
        long lastJoin;
        long lastQuit;
        double lastConfidence;
        String lastCategory = "NONE";
        String lastReason = "";
        final Deque<Long> attackTimes = new ArrayDeque<>();
        final Deque<Long> chatTimes = new ArrayDeque<>();
        final Deque<Long> commandTimes = new ArrayDeque<>();
        final Deque<Long> killTimes = new ArrayDeque<>();
        final Deque<Long> totemTimes = new ArrayDeque<>();
        final Deque<Long> placeTimes = new ArrayDeque<>();
        final Deque<Long> inventoryTimes = new ArrayDeque<>();
        final Deque<String> path = new ArrayDeque<>();
        final Deque<String> events = new ArrayDeque<>();
        final AtomicBoolean aiPending = new AtomicBoolean(false);
    }
}
