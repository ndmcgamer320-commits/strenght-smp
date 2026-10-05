package me.strengthai;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class DataStore {
    private final StrengthAIPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public DataStore(StrengthAIPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    public synchronized void load() {
        data = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    public synchronized int getKills(UUID uuid) {
        return data.getInt("kills." + uuid, 0);
    }

    public synchronized void setKills(UUID uuid, int kills) {
        data.set("kills." + uuid, Math.max(0, kills));
        save();
    }

    public synchronized void addKill(UUID uuid) {
        setKills(uuid, getKills(uuid) + 1);
    }

    public synchronized void resetKills(UUID uuid) {
        setKills(uuid, 0);
    }

    public synchronized Set<UUID> getTrusted() {
        Set<UUID> result = new HashSet<>();
        for (String key : data.getStringList("trusted")) {
            try {
                result.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    public synchronized void setTrusted(Set<UUID> trusted) {
        data.set("trusted", trusted.stream().map(UUID::toString).toList());
        save();
    }

    public synchronized String getSecret(String key) {
        return data.getString("secrets." + key, "");
    }

    public synchronized void setSecret(String key, String value) {
        data.set("secrets." + key, value);
        save();
    }

    public synchronized void clearSecret(String key) {
        data.set("secrets." + key, null);
        save();
    }
}
