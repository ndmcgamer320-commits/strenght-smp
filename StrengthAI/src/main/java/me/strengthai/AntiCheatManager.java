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
        if (p.pathRepeats >= 3) {
            p.baritoneFlags++;
            p.score += 4;
        }

        if (p.score >= plugin.getConfig().getInt("ai.review-score", 35) &&
                p.aiPending.compareAndSet(false, true)) {
            plugin.queueAI(player, "MOVEMENT_REVIEW", evidence(player));
        }
    }

    public void blockBreak(Player player, Block block) {
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
        double banConfidence = plugin.getConfig().getDouble("ai.punish-confidence", 0.93);
        int banScore = plugin.getConfig().getInt("ai.ban-score", 90);

        if (auto && p.score >= banScore &&
                result.confidence() >= banConfidence &&
                result.actions().contains("BAN")) {
            plugin.executePunishment(player, "ban", result.category());
        } else if (auto && result.actions().contains("KICK") &&
                result.confidence() >= 0.97) {
            plugin.executePunishment(player, "kick", result.category());
        } else if (auto && result.actions().contains("MUTE") &&
                result.confidence() >= 0.90) {
            plugin.executePunishment(player, "mute", result.category());
        }
    }

    public void trust(Player player, boolean value) {
        if (value) trusted.add(player.getUniqueId());
        else trusted.remove(player.getUniqueId());
        plugin.getDataStore().setTrusted(trusted);
    }

    public boolean isTrusted(Player player) {
        return trusted.contains(player.getUniqueId());
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
        long lastJoin;
        long lastQuit;
        double lastConfidence;
        String lastCategory = "NONE";
        String lastReason = "";
        final Deque<Long> attackTimes = new ArrayDeque<>();
        final Deque<Long> chatTimes = new ArrayDeque<>();
        final Deque<Long> commandTimes = new ArrayDeque<>();
        final Deque<Long> killTimes = new ArrayDeque<>();
        final Deque<String> path = new ArrayDeque<>();
        final Deque<String> events = new ArrayDeque<>();
        final AtomicBoolean aiPending = new AtomicBoolean(false);
    }
}
