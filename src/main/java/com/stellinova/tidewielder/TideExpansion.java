package com.stellinova.tidewielder;

import com.example.evo.api.IEvoService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TideExpansion extends PlaceholderExpansion {

    private final TideWielderPlugin plugin;
    private final TideManager manager;
    private final IEvoService evo;

    public TideExpansion(TideWielderPlugin plugin, TideManager manager, IEvoService evo) {
        this.plugin = plugin;
        this.manager = manager;
        this.evo = evo;
    }

    @Override public @NotNull String getIdentifier() { return "tide"; }
    @Override public @NotNull String getAuthor() { return "Stellinova"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public @Nullable String onPlaceholderRequest(Player p, @NotNull String params) {
        if (p == null) return "";
        TidePlayerData d = manager.data(p);

        switch (params.toLowerCase()) {
            case "evo":
                return String.valueOf(evo != null ? evo.getEvoLevel(p.getUniqueId()) : TideEvoBridge.evo(p));
            case "maelstrom_cd":
                return String.valueOf(Math.max(0, d.getMaelstromReadyAt() - System.currentTimeMillis()));
            case "bubble_cd":
                return String.valueOf(Math.max(0, d.getBubbleReadyAt() - System.currentTimeMillis()));
            case "tidepool_cd":
                return String.valueOf(Math.max(0, d.getTidepoolReadyAt() - System.currentTimeMillis()));
            case "surge_cd":
                return String.valueOf(Math.max(0, d.getSurgeReadyAt() - System.currentTimeMillis()));
            case "typhoon_cd":
                return String.valueOf(Math.max(0, d.getTyphoonReadyAt() - System.currentTimeMillis()));
            case "maelstrom_mult": return fmt(TideEvoBridge.m(p, "maelstrom"));
            case "bubble_mult":    return fmt(TideEvoBridge.m(p, "bubble"));
            case "tidepool_mult":  return fmt(TideEvoBridge.m(p, "pool"));
            case "surge_mult":     return fmt(TideEvoBridge.m(p, "surge"));
            case "typhoon_mult":   return fmt(TideEvoBridge.m(p, "typhoon"));
            default:
                return "";
        }
    }

    private String fmt(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }
}
