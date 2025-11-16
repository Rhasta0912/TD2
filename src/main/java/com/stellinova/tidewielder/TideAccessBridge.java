package com.stellinova.tidewielder;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TideAccessBridge {

    private static JavaPlugin plugin;
    private static NamespacedKey keyEnabled;

    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

    private TideAccessBridge() {}

    public static void init(JavaPlugin pl) {
        plugin = pl;
        keyEnabled = new NamespacedKey(plugin, "tidewielder_enabled");
    }

    public static boolean canUseTide(Player p) {
        if (ENABLED.contains(p.getUniqueId())) return true;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Integer val = pdc.get(keyEnabled, PersistentDataType.INTEGER);
        boolean enabled = (val != null && val == 1);
        if (enabled) ENABLED.add(p.getUniqueId());
        return enabled;
    }

    public static boolean isTide(Player p) {
        return canUseTide(p);
    }

    public static void grant(Player p) {
        setState(p, true);
    }

    public static void revoke(Player p) {
        setState(p, false);
    }

    private static void setState(Player p, boolean enabled) {
        if (plugin == null || keyEnabled == null) {
            throw new IllegalStateException("TideAccessBridge.init(plugin) was not called");
        }

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (enabled) {
            pdc.set(keyEnabled, PersistentDataType.INTEGER, 1);
            ENABLED.add(p.getUniqueId());
        } else {
            pdc.set(keyEnabled, PersistentDataType.INTEGER, 0);
            ENABLED.remove(p.getUniqueId());
        }
    }

    public static void warm(Player p) {
        if (canUseTide(p)) ENABLED.add(p.getUniqueId());
        else ENABLED.remove(p.getUniqueId());
    }
}
