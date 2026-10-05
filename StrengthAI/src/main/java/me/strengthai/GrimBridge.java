package me.strengthai;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class GrimBridge {
    private final StrengthAIPlugin plugin;
    private GrimPlugin grimPlugin;

    public GrimBridge(StrengthAIPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enable() {
        if (Bukkit.getPluginManager().getPlugin("GrimAC") == null) {
            plugin.getLogger().info("GrimAC not installed; continuing without Grim integration.");
            return false;
        }

        try {
            GrimAbstractAPI api = GrimAPIProvider.get();
            grimPlugin = api.getGrimPlugin(plugin);
            EventBus bus = api.getEventBus();

            bus.get(FlagEvent.class).onFlag(
                    grimPlugin,
                    (user, check, verbose, cancelled) -> {
                        Player player = Bukkit.getPlayer(user.getUniqueId());
                        if (player != null) {
                            String checkName = check.getCheckName();
                            String detail = verbose == null ? "" : verbose.toString();

                            Bukkit.getScheduler().runTask(
                                    plugin,
                                    () -> plugin.getAntiCheat().recordGrimFlag(
                                            player,
                                            checkName,
                                            detail
                                    )
                            );
                        }

                        return cancelled;
                    }
            );

            plugin.getLogger().info("GrimAC integration enabled.");
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("GrimAC integration unavailable: " + error.getClass().getSimpleName());
            return false;
        }
    }

    public boolean installed() {
        return grimPlugin != null;
    }
}
