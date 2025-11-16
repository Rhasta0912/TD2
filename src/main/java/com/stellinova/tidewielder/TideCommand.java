package com.stellinova.tidewielder;

import com.example.evo.api.IEvoService;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class TideCommand implements CommandExecutor {

    private final TideWielderPlugin plugin;
    private final TideManager manager;
    private final TideScoreboardHud hud;
    private final IEvoService evo;

    public TideCommand(TideWielderPlugin plugin, TideManager manager, TideScoreboardHud hud, IEvoService evo) {
        this.plugin = plugin;
        this.manager = manager;
        this.hud = hud;
        this.evo = evo;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "/tide status");
            sender.sendMessage(ChatColor.AQUA + "/tide rune" + ChatColor.GRAY + " — enable TideWielder for yourself");
            sender.sendMessage(ChatColor.AQUA + "/tide reset" + ChatColor.GRAY + " — disable TideWielder");
            sender.sendMessage(ChatColor.AQUA + "/tide reload" + ChatColor.GRAY + " — rebuild HUD");
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            TidePlayerData d = manager.data(p);
            int lvl = (evo != null ? evo.getEvoLevel(p.getUniqueId()) : TideEvoBridge.evo(p));
            boolean can = TideAccessBridge.canUseTide(p);
            sender.sendMessage(ChatColor.AQUA + "TideWielder Status");
            sender.sendMessage(ChatColor.GRAY + "  Rune: " + (can ? ChatColor.GREEN + "TideWielder" : ChatColor.RED + "No Rune"));
            sender.sendMessage(ChatColor.GRAY + "  Evo: " + ChatColor.LIGHT_PURPLE + lvl
                    + ChatColor.GRAY + " (EvoCore " + (evo != null ? "ON" : "OFF") + ")");
            long cd = Math.max(0, d.getSurgeReadyAt() - System.currentTimeMillis());
            sender.sendMessage(ChatColor.GRAY + "  Surge CD: " + (cd == 0 ? "ready" : (cd / 1000.0) + "s"));
            return true;
        }

        if (args[0].equalsIgnoreCase("rune")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            TideAccessBridge.grant(p);
            manager.armStartWindow(p, 600L);
            hud.refresh(p);
            sender.sendMessage(ChatColor.GREEN + "You are now attuned to the " + ChatColor.AQUA + "TideWielder" + ChatColor.GREEN + " rune.");
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            TideAccessBridge.revoke(p);
            manager.onRuneRevoked(p);
            hud.refresh(p);
            sender.sendMessage(ChatColor.RED + "You let the tides rest. TideWielder rune disabled.");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            hud.refresh(p);
            sender.sendMessage(ChatColor.GREEN + "Tide HUD rebuilt.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /tide for help.");
        return true;
    }
}
