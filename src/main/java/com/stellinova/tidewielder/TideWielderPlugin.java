package com.stellinova.tidewielder;

import com.example.evo.api.IEvoService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class TideWielderPlugin extends JavaPlugin {

    private TideManager manager;
    private TideScoreboardHud hud;
    private IEvoService evo;

    @Override
    public void onEnable() {
        try {
            evo = Bukkit.getServicesManager().load(IEvoService.class);
        } catch (Throwable ignored) {
            evo = null;
        }

        try {
            TideAccessBridge.init(this);
        } catch (Throwable ignored) {}

        manager = new TideManager(this, evo);
        hud = new TideScoreboardHud(this, manager, evo);

        try {
            if (getCommand("tidewielder") != null) {
                getCommand("tidewielder").setExecutor(new TideCommand(this, manager, hud, evo));
            }
        } catch (Throwable ignored) {}

        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                new TideExpansion(this, manager, evo).register();
            }
        } catch (Throwable ignored) {}

        Bukkit.getOnlinePlayers().forEach(p -> {
            try { hud.refresh(p); } catch (Throwable ignored) {}
        });

        getLogger().info("TideWielder enabled.");
    }

    @Override
    public void onDisable() {
        try { if (manager != null) manager.shutdown(); } catch (Throwable ignored) {}
        try { if (hud != null) hud.shutdown(); } catch (Throwable ignored) {}
        getLogger().info("TideWielder disabled.");
    }

    public TideManager getManager() {
        return manager;
    }

    public TideScoreboardHud getHud() {
        return hud;
    }

    public IEvoService getEvo() {
        return evo;
    }
}
