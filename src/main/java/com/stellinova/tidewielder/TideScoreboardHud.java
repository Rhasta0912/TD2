// v0.1.5 — Tide HUD with Winder-style EVO bonuses (no more +0% at high EVO).
package com.stellinova.tidewielder;

import com.example.evo.api.IEvoService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

public class TideScoreboardHud {

    private final TideWielderPlugin plugin;
    private final TideManager manager;
    @SuppressWarnings("unused")
    private final IEvoService evo; // reserved for direct EvoCore use

    private BukkitTask loop;

    public TideScoreboardHud(TideWielderPlugin plugin, TideManager manager, IEvoService evo) {
        this.plugin = plugin;
        this.manager = manager;
        this.evo = evo;
        startLoop();
    }

    public void shutdown() {
        try {
            if (loop != null) {
                loop.cancel();
                loop = null;
            }
        } catch (Throwable ignored) {}
    }

    private void startLoop() {
        shutdown();
        loop = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        refresh(p);
                    } catch (Throwable ignored) {}
                }
            }
        }.runTaskTimer(plugin, 1L, 10L); // ~0.5s
    }

    public void refresh(Player p) {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;

        // Hide HUD if they don't currently have the rune
        if (!TideAccessBridge.canUseTide(p)) {
            Scoreboard current = p.getScoreboard();
            Objective existing = current.getObjective("tidehud");
            if (existing != null && existing.getDisplaySlot() == DisplaySlot.SIDEBAR) {
                Scoreboard empty = sm.getNewScoreboard();
                p.setScoreboard(empty);
            }
            return;
        }

        Scoreboard sb = sm.getNewScoreboard();
        Objective obj = sb.registerNewObjective("tidehud", Criteria.DUMMY, ChatColor.AQUA + "TideWielder");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        TidePlayerData d = manager.data(p);
        long now = System.currentTimeMillis();
        int evoLvl = Math.max(0, Math.min(3, TideEvoBridge.evo(p)));

        long maelstromLeft = Math.max(0L, d.getMaelstromReadyAt() - now);
        long bubbleLeft    = Math.max(0L, d.getBubbleReadyAt() - now);
        long tidepoolLeft  = Math.max(0L, d.getTidepoolReadyAt() - now);
        long surgeLeft     = Math.max(0L, d.getSurgeReadyAt() - now);
        long typhoonLeft   = Math.max(0L, d.getTyphoonReadyAt() - now);

        int score = 12;

        add(obj, ChatColor.LIGHT_PURPLE + "EVO: " + ChatColor.AQUA + evoLvl, score--);

        // Winder-style: one “big” stat (like jump) + three “normal” stats (like dash/pull/dive)

        // Maelstrom (main control ability — maps to Winder's jump bonus)
        add(obj, abilityHeader("Maelstrom", maelstromLeft), score--);
        add(obj, statLine("Control", percent(abilityBonusPct(evoLvl, "maelstrom"))), score--);

        // Bubble (prison) — normal bonus
        add(obj, abilityHeader("Bubble", bubbleLeft), score--);
        add(obj, statLine("Prison", percent(abilityBonusPct(evoLvl, "bubble"))), score--);

        // Tidepool (zone) — normal bonus
        add(obj, abilityHeader("Tidepool", tidepoolLeft), score--);
        add(obj, statLine("Zone", percent(abilityBonusPct(evoLvl, "tidepool"))), score--);

        // Surge (wave) — normal bonus
        add(obj, abilityHeader("Surge", surgeLeft), score--);
        add(obj, statLine("Wave", percent(abilityBonusPct(evoLvl, "surge"))), score--);

        add(obj, ChatColor.GRAY + "", score--);

        add(obj, typhoonLine(evoLvl, typhoonLeft), score--);

        p.setScoreboard(sb);
    }

    // ---------- helpers ----------

    private static void add(Objective obj, String text, int score) {
        String line = trim(text);
        while (obj.getScoreboard().getEntries().contains(line)) {
            line += ChatColor.RESET;
        }
        obj.getScore(line).setScore(score);
    }

    private static String abilityHeader(String name, long msLeft) {
        if (msLeft <= 0) return ChatColor.AQUA + name;
        int sec = (int) Math.ceil(msLeft / 1000.0);
        return ChatColor.RED + name + ChatColor.GRAY + " (" + ChatColor.YELLOW + sec + "s" + ChatColor.GRAY + ")";
    }

    private static String statLine(String label, String value) {
        return ChatColor.AQUA + "  " + label + ChatColor.WHITE + ": " + value;
    }

    private static String typhoonLine(int evoLvl, long msLeft) {
        if (evoLvl < 3) {
            return ChatColor.AQUA + "Typhoon Ascent" + ChatColor.WHITE + ": " + ChatColor.RED + "Locked";
        }
        if (msLeft <= 0) {
            return ChatColor.AQUA + "Typhoon Ascent" + ChatColor.WHITE + ": " + ChatColor.GREEN + "Ready";
        }
        int sec = (int) Math.ceil(msLeft / 1000.0);
        return ChatColor.RED + "Typhoon Ascent" + ChatColor.WHITE + ": " + ChatColor.YELLOW + sec + "s";
    }

    /** Returns +percent value as text, e.g., +35%. Input expects fractional (0.35). */
    private static String percent(double frac) {
        int pct = (int) Math.round(frac * 100.0);
        return (pct >= 0 ? "+" : "") + pct + "%";
    }

    /** Matches Winder's curve: Evo 0..3 => factors 0..3 with bonuses per key. */
    private static double abilityBonusPct(int evo, String key) {
        int factor = switch (evo) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            default -> 0;
        };

        String k = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);

        double bonus = switch (k) {
            // Main control ability — like Winder "jump": 0.35 * factor
            case "maelstrom" -> 0.35 * factor;

            // Other actives — like Winder "dash/pull/dive": 0.25 * factor
            case "bubble", "tidepool", "surge" -> 0.25 * factor;

            default -> 0.0;
        };

        return bonus; // fractional (e.g., 0.35, 0.75, 1.05)
    }

    private static String trim(String s) {
        if (s == null) return "";
        if (s.length() <= 40) return s;
        return s.substring(0, 37) + "...";
    }
}
