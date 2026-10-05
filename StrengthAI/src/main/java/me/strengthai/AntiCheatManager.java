package me.strengthai;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.util.Vector;
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
        p.clientBrand = normalizeClientBrand(player.getClientBrandName());
        notifyAdmins("&b" + player.getName() + " &7joined. StrengthAI monitoring started.");

        // Paper exposes the client brand after the connection has finished
        // negotiating. Capture it again shortly after join.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) recordClientBrand(player, player.getClientBrandName());
        }, 40L);

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

        Entity targetEntity = event.getEntity();

        if (targetEntity instanceof Player target) {
            p.targetHits.merge(target.getUniqueId(), 1, Integer::sum);

            if (p.lastTarget != null && !p.lastTarget.equals(target.getUniqueId())) {
                p.targetSwitches++;
            }
            p.lastTarget = target.getUniqueId();

            // A legal attack packet should point roughly toward the target.
            // Large repeated mismatches are useful corroboration for aura/aim
            // modules, but are never a ban signal by themselves.
            double angle = angleToTarget(player, target);
            if (angle >= plugin.getConfig().getDouble("anti-cheat.aim-mismatch-degrees", 70.0)) {
                p.aimMismatchFlags++;
                p.score += 8;

                if (p.aimMismatchFlags % 2 == 0) {
                    plugin.queueAI(
                            player,
                            "AIM_REVIEW",
                            evidence(player) +
                                    "\naimMismatchDegrees=" +
                                    String.format(Locale.US, "%.1f", angle) +
                                    "\ntarget=" + target.getName()
                    );
                }
            }

            if (sameTargetRecentHits(p, target.getUniqueId()) >=
                    plugin.getConfig().getInt("anti-cheat.same-target-fast-hits", 12)) {
                p.sameTargetBursts++;
                p.botScore += 4;
                p.score += 4;
            }
        }

        double reach = targetEntity.getLocation().distance(player.getLocation());
        if (reach > plugin.getConfig().getDouble("anti-cheat.max-reach", 4.15)) {
            p.reachFlags++;
            p.score += 18;
            plugin.queueAI(player, "REACH", evidence(player));
        }

        int maxHits = plugin.getConfig().getInt("anti-cheat.combat-max-hits", 24);
        boolean rapid = p.attackTimes.size() > maxHits;
        boolean suspiciousTargeting =
                p.targetSwitches >= 3 || p.aimMismatchFlags >= 2 || p.sameTargetBursts >= 2;

        if (rapid && suspiciousTargeting) {
            p.killauraFlags++;
            p.score += 15;

            if (p.killauraFlags % 2 == 0) {
                plugin.queueAI(player, "KILLAURA", evidence(player));
            }
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

        String liveBrand = normalizeClientBrand(player.getClientBrandName());
        if (!"UNKNOWN".equals(liveBrand)) p.clientBrand = liveBrand;

        double x = event.getTo().getX();
        double y = event.getTo().getY();
        double z = event.getTo().getZ();

        double dx = x - event.getFrom().getX();
        double dz = z - event.getFrom().getZ();
        double dy = y - event.getFrom().getY();
        double distanceXZ = Math.sqrt(dx * dx + dz * dz);

        long now = System.currentTimeMillis();

        if (p.lastMoveAt > 0) {
            double seconds = (now - p.lastMoveAt) / 1000.0;

            if (seconds >= 0.045 && seconds <= 0.35 && distanceXZ > 0.02 &&
                    now >= p.recentTeleportUntil &&
                    now >= p.recentVelocityUntil) {

                double speed = distanceXZ / seconds;
                double allowed = 7.0;

                var speedEffect = player.getPotionEffect(
                        org.bukkit.potion.PotionEffectType.SPEED
                );
                if (speedEffect != null) {
                    allowed += (speedEffect.getAmplifier() + 1) * 0.85;
                }

                // Require several repeated high-excess windows before scoring.
                if (speed > allowed * 1.60) {
                    p.speedStreak++;

                    if (p.speedStreak >= 4) {
                        p.speedFlags++;
                        p.speedEvidenceWindows++;
                        p.speedStreak = 0;
                        p.score += 8;

                        plugin.queueAI(
                                player,
                                "SPEED_REVIEW",
                                evidence(player) +
                                        "\nmeasuredBlocksPerSecond=" +
                                        String.format(Locale.US, "%.2f", speed) +
                                        "\nallowedApprox=" +
                                        String.format(Locale.US, "%.2f", allowed)
                        );
                    }
                } else {
                    p.speedStreak = Math.max(0, p.speedStreak - 1);
                }
            }
        }

        // Inventory/container movement: vanilla clients normally stop normal
        // movement while a non-player container is open. Require sustained
        // travel and rate-limit the signal to prevent false positives.
        boolean containerOpen =
                player.getOpenInventory() != null &&
                player.getOpenInventory().getTopInventory() != null &&
                player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING;

        if (containerOpen && distanceXZ > 0.08 &&
                now >= p.recentTeleportUntil &&
                now >= p.recentVelocityUntil) {
            if (p.inventoryMoveWindowStart == 0 ||
                    now - p.inventoryMoveWindowStart > 1800L) {
                p.inventoryMoveWindowStart = now;
                p.inventoryMoveDistance = 0.0;
            }
            p.inventoryMoveDistance += distanceXZ;

            if (p.inventoryMoveDistance >=
                    plugin.getConfig().getDouble("anti-cheat.inventory-move-min-distance", 1.2) &&
                    now - p.lastInventoryMovementFlagAt >= 2500L) {
                p.inventoryMovementFlags++;
                p.score += 5;
                p.lastInventoryMovementFlagAt = now;

                plugin.queueAI(player, "INVENTORY_MOVEMENT_REVIEW", evidence(player));
            }
        }

        p.lastMoveAt = now;
        p.lastX = x;
        p.lastY = y;
        p.lastZ = z;

        boolean specialMovement =
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION) ||
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING) ||
                isClimbable(player.getLocation().getBlock().getType());

        if (!player.isOnGround() && !specialMovement && now >= p.recentVelocityUntil) {
            p.airTicks++;

            if (Math.abs(dy) < 0.045 && ++p.airStillSamples >= 16) {
                p.flyFlags++;
                p.score += 6;
                p.airStillSamples = 0;

                plugin.queueAI(
                        player,
                        "FLIGHT_REVIEW",
                        evidence(player) + "\nairTicks=" + p.airTicks
                );
            }
        } else {
            p.airTicks = 0;
            p.airStillSamples = 0;
        }

        String point =
                (event.getTo().getBlockX() / 2) + ":" +
                (event.getTo().getBlockY() / 2) + ":" +
                (event.getTo().getBlockZ() / 2);

        p.path.addLast(point);

        int max = plugin.getConfig().getInt("anti-cheat.path-history-size", 120);
        while (p.path.size() > max) p.path.removeFirst();

        p.pathDistance += distanceXZ;

        // The previous implementation incremented baritoneFlags on nearly every
        // PlayerMoveEvent, which produced thousands of false "Baritone" flags.
        // Analyze the path only every few seconds and require real travel.
        if (now - p.lastPathAnalysisAt >= 3000L) {
            p.lastPathAnalysisAt = now;
            p.pathRepeats = repeatedWindows(p.path);

            boolean enoughTravel = p.pathDistance >= 18.0;
            boolean repeatedPath = p.path.size() >= 80 &&
                    p.pathRepeats >= plugin.getConfig().getInt("anti-cheat.min-path-repeats", 7);
            boolean looksLikeClientBaritone = containsCheatBrand(p.clientBrand);

            if (plugin.getConfig().getBoolean("anti-cheat.automation", true) &&
                    ((enoughTravel && repeatedPath) || looksLikeClientBaritone) &&
                    now - p.lastAutomationEvidenceAt >= 5000L) {

                p.baritoneFlags++;
                p.automationEvidence++;
                p.score += looksLikeClientBaritone ? 6 : 2;
                p.lastAutomationEvidenceAt = now;

                if (p.automationEvidence == 1 || p.automationEvidence % 2 == 0) {
                    plugin.queueAI(player, "AUTOMATION_REVIEW", evidence(player));
                }
            }

            p.pathDistance = 0.0;
        }

        if (p.score >= plugin.getConfig().getInt("ai.review-score", 35) &&
                p.aiPending.compareAndSet(false, true)) {
            plugin.queueAI(player, "MOVEMENT_REVIEW", evidence(player));
        }
    }

    private static boolean isClimbable(Material material) {
        return material == Material.LADDER ||
                material == Material.VINE ||
                material == Material.WEEPING_VINES ||
                material == Material.TWISTING_VINES ||
                material == Material.CAVE_VINES;
    }

    public void blockBreak(Player player, Block block) {
        if (!plugin.getConfig().getBoolean("anti-cheat.mining", true)) return;

        Profile p = profile(player.getUniqueId());
        p.blocksBroken++;

        Material type = block.getType();

        if (isOre(type)) {
            p.oresBroken++;

            if (isValuable(type)) {
                p.valuableOres++;
                p.oreYTotal += block.getY();

                int airNeighbors = 0;
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            if (ox == 0 && oy == 0 && oz == 0) continue;
                            if (block.getRelative(ox, oy, oz).getType().isAir()) airNeighbors++;
                        }
                    }
                }

                if (airNeighbors > 0) {
                    p.exposedValuableOres++;
                }
            }
        }

        int blocksWindow = plugin.getConfig().getInt(
                "anti-cheat.ore-review-min-blocks",
                180
        );

        int valuableWindow = plugin.getConfig().getInt(
                "anti-cheat.ore-review-min-valuable",
                8
        );

        if (p.blocksBroken >= blocksWindow &&
                p.valuableOres >= valuableWindow) {

            int hidden =
                    Math.max(
                            0,
                            p.valuableOres -
                            p.exposedValuableOres
                    );

            double hiddenRatio =
                    p.valuableOres == 0
                            ? 0.0
                            : hidden / (double) p.valuableOres;

            if (hiddenRatio >= 0.75) {
                p.xrayFlags++;
                p.score += 10;

                plugin.queueAI(
                        player,
                        "MINING_REVIEW",
                        evidence(player) +
                                "\nhiddenValuableRatio=" +
                                String.format(Locale.US, "%.3f", hiddenRatio)
                );
            } else {
                p.score += 2;
            }

            p.blocksBroken = 0;
            p.oresBroken = 0;
            p.valuableOres = 0;
            p.exposedValuableOres = 0;
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
                result.confidence() >= plugin.getConfig().getDouble("ai.review-confidence", 0.90);

        if (!confirmed) return;

        watch.add(player.getUniqueId());

        notifyAdmins("&cAI flag: &f" + player.getName() +
                " &7=> &e" + result.category() +
                " &7(" + String.format(Locale.US, "%.0f%%", result.confidence() * 100) +
                ") &8- &f" + result.reason());

        if (result.actions().contains("RESET_STRENGTH") &&
                result.confidence() >= plugin.getConfig().getDouble("fair-bans.reset-confidence", 0.95) &&
                p.score >= plugin.getConfig().getInt("fair-bans.reset-score", 80)) {
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

    public void recordTeleport(Player player) {
        Profile p = profile(player.getUniqueId());
        p.recentTeleportUntil = System.currentTimeMillis() + 2500L;
        p.path.clear();
        p.pathDistance = 0.0;
        p.pathRepeats = 0;
    }

    public void recordVelocity(Player player) {
        profile(player.getUniqueId()).recentVelocityUntil =
                System.currentTimeMillis() + 1800L;
    }

    public void recordGrimFlag(Player player, String checkName, String verbose) {
        Profile p = profile(player.getUniqueId());
        String name = checkName == null ? "UNKNOWN" : checkName.toUpperCase(Locale.ROOT);

        p.grimFlags++;
        p.grimChecks.merge(name, 1, Integer::sum);
        p.score += 5;
        p.lastGrimCheck = name;
        p.lastGrimVerbose = verbose == null
                ? ""
                : verbose.substring(0, Math.min(300, verbose.length()));
        p.lastGrimAt = System.currentTimeMillis();

        p.events.addFirst("GRIM:" + name);
        while (p.events.size() > 30) p.events.removeLast();

        // Authoritative Grim checks are important enough to review immediately
        // for critical families such as AntiKB, TimerLimit, FastBreak, Simulation,
        // Reach and combat/movement checks.
        boolean critical = isCriticalGrimCheck(name);
        if (critical) {
            p.criticalGrimFlags++;
        }

        if ((critical || p.grimFlags % 5 == 0) &&
                p.aiPending.compareAndSet(false, true)) {
            plugin.queueAI(player, critical ? "GRIM_CRITICAL_REVIEW" : "GRIM_REVIEW", evidence(player));
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

    public int independentSignals(Player player) {
        Profile p = profile(player.getUniqueId());
        int signals = 0;

        if (p.grimFlags >= plugin.getConfig().getInt("fair-bans.min-grim-flags", 8)) signals++;
        if (p.reachFlags >= 4 || p.killauraFlags >= 4 || p.aimMismatchFlags >= 4) signals++;
        if (p.speedEvidenceWindows >= 2 || p.flyFlags >= 4) signals++;
        if (p.xrayFlags >= 2) signals++;
        if (p.botScore >= 30 && p.killTimes.size() >= 10) signals++;
        if (p.inventoryMovementFlags >= 3) signals++;

        // Repeated severe Grim families provide a second independent family,
        // while a single weak check remains only a review signal.
        if (hardGrimEligible(p)) signals += 2;

        // Baritone/path telemetry is never a permanent-ban signal by itself.
        return signals;
    }

    public boolean banEligible(Player player, OpenRouterService.AIResult result) {
        Profile p = profile(player.getUniqueId());

        if (!plugin.getConfig().getBoolean("fair-bans.enabled", true)) return false;
        if (!plugin.getConfig().getBoolean("fair-bans.auto-ban", true)) return false;
        if (!player.isOnline()) return false;
        if (isTrusted(player)) return false;

        long now = System.currentTimeMillis();

        long grace = plugin.getConfig().getLong("fair-bans.join-grace-seconds", 120L) * 1000L;
        if (now - p.lastJoin < grace) return false;

        if (p.recentTeleportUntil > now ||
                p.recentVelocityUntil > now) {
            return false;
        }

        double tps = Bukkit.getTPS()[0];
        if (tps > 0 && tps < plugin.getConfig().getDouble("fair-bans.min-tps", 19.0)) {
            return false;
        }

        if (!"CHEAT".equalsIgnoreCase(result.verdict())) return false;
        if (!result.actions().contains("BAN")) return false;

        double minConfidence = plugin.getConfig().getDouble("fair-bans.min-ai-confidence", 0.98);
        int minScore = plugin.getConfig().getInt("fair-bans.min-score", 150);
        int minSignals = plugin.getConfig().getInt("fair-bans.min-independent-signals", 3);

        if (result.confidence() < minConfidence || p.score < minScore) return false;

        int signals = independentSignals(player);
        boolean hardGrim = hardGrimEligible(p);

        // Strong Grim evidence is allowed to stand on its own after the normal
        // safety gates. This catches cases such as repeated AntiKB + TimerLimit
        // that the AI may otherwise classify as WATCH.
        if (hardGrim &&
                result.confidence() >= Math.max(0.95, minConfidence - 0.03)) {
            return true;
        }

        // Baritone/automation and BOT heuristics still require independent support.
        if (("BARITONE".equalsIgnoreCase(result.category()) ||
             "BOT".equalsIgnoreCase(result.category())) && signals < minSignals) {
            return false;
        }

        return signals >= minSignals;
    }

    private boolean hardGrimEligible(Profile p) {
        int min = plugin.getConfig().getInt("fair-bans.min-hard-grim-flags", 20);
        int minPerCheck = plugin.getConfig().getInt("fair-bans.min-hard-grim-check-flags", 12);

        if (p.criticalGrimFlags < min) return false;

        int distinct = 0;
        boolean repeatedSingle = false;

        for (Map.Entry<String, Integer> entry : p.grimChecks.entrySet()) {
            if (!isCriticalGrimCheck(entry.getKey())) continue;
            if (entry.getValue() >= 2) distinct++;
            if (entry.getValue() >= minPerCheck) repeatedSingle = true;
        }

        return repeatedSingle || distinct >= 2;
    }

    private static boolean isCriticalGrimCheck(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.ROOT);
        return n.contains("ANTIKB") ||
                n.contains("TIMER") ||
                n.contains("FASTBREAK") ||
                n.contains("SIMULATION") ||
                n.contains("REACH") ||
                n.contains("KILLAURA") ||
                n.contains("AURA") ||
                n.contains("FLY") ||
                n.contains("SPEED") ||
                n.contains("MULTIACTION") ||
                n.contains("BADPACKET") ||
                n.contains("NOSLOW");
    }

    private static double angleToTarget(Player player, Player target) {
        Vector look = player.getEyeLocation().getDirection().normalize();
        Vector to = target.getLocation().clone()
                .add(0, Math.max(0.4, target.getHeight() * 0.5), 0)
                .toVector()
                .subtract(player.getEyeLocation().toVector())
                .normalize();

        double dot = Math.max(-1.0, Math.min(1.0, look.dot(to)));
        return Math.toDegrees(Math.acos(dot));
    }

    private static int sameTargetRecentHits(Profile p, UUID target) {
        int count = 0;
        for (Map.Entry<UUID, Integer> entry : p.targetHits.entrySet()) {
            if (entry.getKey().equals(target)) count = entry.getValue();
        }
        return count;
    }

    private static String normalizeClientBrand(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    private static boolean containsCheatBrand(String brand) {
        if (brand == null) return false;
        String n = brand.toLowerCase(Locale.ROOT);
        return n.contains("baritone") ||
                n.contains("meteor") ||
                n.contains("impact") ||
                n.contains("wurst") ||
                n.contains("liquidbounce") ||
                n.contains("bleach") ||
                n.contains("inertia") ||
                n.contains("aristois") ||
                n.contains("rusherhack");
    }

    public void recordClientBrand(Player player, String brand) {
        Profile p = profile(player.getUniqueId());
        String normalized = normalizeClientBrand(brand);
        if ("UNKNOWN".equals(normalized)) return;

        if (!normalized.equalsIgnoreCase(p.clientBrand)) {
            p.clientBrand = normalized;
            p.events.addFirst("CLIENT:" + normalized);
            while (p.events.size() > 30) p.events.removeLast();

            if (containsCheatBrand(normalized)) {
                p.suspiciousClientSignals++;
                plugin.getLogger().info("[StrengthAI] " + player.getName() +
                        " reported client brand: " + normalized);
            }
        }
    }

    public boolean currentBanEligible(Player player) {
        Profile p = profile(player.getUniqueId());
        OpenRouterService.AIResult synthetic =
                new OpenRouterService.AIResult(
                        "CHEAT",
                        p.lastCategory,
                        p.lastConfidence,
                        0,
                        p.lastReason,
                        List.of("BAN"),
                        false
                );
        return banEligible(player, synthetic);
    }

    public void clearFlags(Player player) {
        Profile old = profiles.get(player.getUniqueId());
        if (old == null) return;

        profiles.put(
                player.getUniqueId(),
                new Profile()
        );
        profiles.get(player.getUniqueId()).lastJoin = old.lastJoin;
        profiles.get(player.getUniqueId()).aiPending.set(false);
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
                ",xrayFlags=" + p.xrayFlags +
                ",speedWindows=" + p.speedEvidenceWindows +
                ",scaffoldBursts=" + p.scaffoldBursts +
                ",inventoryBursts=" + p.inventoryBursts +
                ",inventoryMoveFlags=" + p.inventoryMovementFlags +
                ",aimMismatch=" + p.aimMismatchFlags +
                ",criticalGrimFlags=" + p.criticalGrimFlags +
                ",clientBrand=" + p.clientBrand +
                ",suspiciousClientSignals=" + p.suspiciousClientSignals +
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

        long cutoff = System.currentTimeMillis() - 120000L;
        p.attackTimes.removeIf(x -> x < cutoff);
        p.killTimes.removeIf(x -> x < cutoff);
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
        int xrayFlags;
        int exposedValuableOres;
        int speedEvidenceWindows;
        int speedStreak;
        int airTicks;
        int airStillSamples;
        int automationEvidence;
        double pathDistance;
        double inventoryMoveDistance;
        long inventoryMoveWindowStart;
        long lastInventoryMovementFlagAt;
        long lastAutomationEvidenceAt;
        long lastPathAnalysisAt;
        long lastGrimAt;
        long lastMoveAt;
        double lastX;
        double lastY;
        double lastZ;
        int blocksPlaced;
        int scaffoldBursts;
        int inventoryBursts;
        int inventoryMovementFlags;
        int aimMismatchFlags;
        int targetSwitches;
        int sameTargetBursts;
        int criticalGrimFlags;
        int suspiciousClientSignals;
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
        String clientBrand = "UNKNOWN";
        final Deque<Long> attackTimes = new ArrayDeque<>();
        final Map<UUID, Integer> targetHits = new HashMap<>();
        UUID lastTarget;
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
