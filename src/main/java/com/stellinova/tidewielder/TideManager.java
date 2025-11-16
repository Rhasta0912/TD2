package com.stellinova.tidewielder;

import com.example.evo.api.IEvoService;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * TideWielder core — Maelstrom / Bubble / Tidepool / Surge / Typhoon + Tide Echo passive.
 *
 * v0.4.1 — Winder-style input (movement + F + sneak only), max FX but optimized, aerial Typhoon.
 *
 * Controls (when attuned to TideWielder):
 *  - Tap F (swap-hand)                    -> Surge
 *  - Double-tap F                         -> Maelstrom
 *  - Sneak + tap F                        -> Bubble
 *  - Quick sneak tap (ground)             -> Tidepool
 *  - Sneak + double-tap F                 -> Typhoon (Evo 3 only, aerial cyclone)
 */
public class TideManager implements Listener {

    private final TideWielderPlugin plugin;
    private final IEvoService evo; // nullable

    private final Map<UUID, TidePlayerData> data = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, LastCooldown> lastCooldown = new HashMap<>();

    // Passive flow tracking (legacy, kept for minimal change)
    private final Map<UUID, FlowState> flows = new HashMap<>();

    // Passive: Tide Echo
    private static final long TIDE_ECHO_DURATION_MS = 4000L;
    private final Map<UUID, EchoState> echoes = new HashMap<>();

    // F-tap state (for Surge / Bubble / Maelstrom / Typhoon)
    private final Map<UUID, FTapState> fTaps = new HashMap<>();

    // Sneak state (for Tidepool quick tap)
    private final Map<UUID, SneakState> sneaks = new HashMap<>();

    private BukkitTask tickTask;

    // Cooldowns (ms)
    private static final long MAELSTROM_CD_BASE = 10_000L;
    private static final long BUBBLE_CD_BASE    = 14_000L;
    private static final long TIDEPOOL_CD_BASE  = 10_000L;
    private static final long SURGE_CD_BASE     = 8_000L;
    private static final long TYPHOON_CD_BASE   = 120_000L;

    // Typhoon timings
    private static final long TYPHOON_DURATION_BASE = 4_000L;
    private static final long TYPHOON_TICK_MS       = 350L;

    // Geometry
    private static final double MAELSTROM_RADIUS = 6.0;
    private static final double SURGE_RADIUS     = 10.0;
    private static final double SURGE_FORCE      = 0.8;

    // Evo CDR factors
    private static final double[] CDR = {1.00, 0.90, 0.75, 0.55};

    // Tidal Momentum tuning (legacy)
    private static final double FLOW_GAIN_PER_BLOCK   = 0.45;
    private static final double FLOW_DECAY_PER_SECOND = 0.35;
    private static final double FLOW_TRIGGER_MIN      = 0.45;
    private static final long   FLOW_BURST_COOLDOWN   = 1500L;

    // F input timing (ms / ticks)
    private static final long F_TAP_WINDOW_MS    = 260L;
    private static final long F_TAP_WINDOW_TICKS = 6L;

    // Sneak tap window (for Tidepool)
    private static final long SNEAK_TAP_TICKS = 6L;

    public TideManager(TideWielderPlugin plugin, IEvoService evo) {
        this.plugin = plugin;
        this.evo = evo;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.tickTask = new TickTask().runTaskTimer(plugin, 1L, 1L);
    }

    public TidePlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new TidePlayerData(p.getUniqueId()));
    }

    public void shutdown() {
        if (tickTask != null) {
            try { tickTask.cancel(); } catch (Throwable ignored) {}
            tickTask = null;
        }
        for (BossBar b : bars.values()) {
            try { b.setVisible(false); b.removeAll(); } catch (Throwable ignored) {}
        }
        bars.clear();
        lastCooldown.clear();
        flows.clear();
        echoes.clear();
        fTaps.clear();
        sneaks.clear();
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------
    // Events
    // ------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        data(p);
        warmAccess(p);
        clearAB(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        clearAB(p);
        flows.remove(p.getUniqueId());
        echoes.remove(p.getUniqueId());
        FTapState ft = fTaps.remove(p.getUniqueId());
        if (ft != null && ft.pending != null) {
            try { ft.pending.cancel(); } catch (Throwable ignored) {}
        }
        SneakState ss = sneaks.remove(p.getUniqueId());
        if (ss != null && ss.pending != null) {
            try { ss.pending.cancel(); } catch (Throwable ignored) {}
        }
    }

    /**
     * F input: tap / double-tap, with sneak as modifier.
     *
     *  - Tap F, not sneaking              -> Surge
     *  - Tap F, sneaking                  -> Bubble
     *  - Double-tap F                     -> Maelstrom
     *  - Sneak + double-tap F             -> Typhoon (Evo 3 only)
     */
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!TideAccessBridge.canUseTide(p)) return;

        // Prevent actual item swap when using Tide
        e.setCancelled(true);

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        FTapState state = fTaps.computeIfAbsent(id, k -> new FTapState());
        // Cancel any pending single-tap cast, we'll reschedule or double-tap
        if (state.pending != null) {
            try { state.pending.cancel(); } catch (Throwable ignored) {}
            state.pending = null;
        }

        if (state.firstTapAt > 0L && (now - state.firstTapAt) <= F_TAP_WINDOW_MS) {
            // Double-tap detected
            boolean sneakEither = p.isSneaking() || state.firstTapSneak;

            if (sneakEither) {
                // Sneak + double-tap -> Typhoon (if Evo 3)
                triggerTyphoon(p);
            } else {
                // Normal double-tap -> Maelstrom
                triggerMaelstrom(p);
            }

            state.firstTapAt = 0L;
            state.firstTapSneak = false;
            state.pending = null;
            return;
        }

        // First tap of a possible single
        state.firstTapAt = now;
        state.firstTapSneak = p.isSneaking();

        // Schedule single-tap resolution after a short window
        state.pending = new BukkitRunnable() {
            @Override
            public void run() {
                // If still same tap (no second tap intervened)
                if (state.firstTapAt == now) {
                    if (state.firstTapSneak) {
                        triggerBubble(p);
                    } else {
                        triggerSurge(p);
                    }
                    state.firstTapAt = 0L;
                    state.firstTapSneak = false;
                    state.pending = null;
                }
            }
        }.runTaskLater(plugin, F_TAP_WINDOW_TICKS);
    }

    /**
     * Sneak quick tap: Tidepool.
     *
     * - If player taps sneak quickly (press + release), we treat it as Tidepool.
     * - If they hold sneak, nothing happens (no accidental spam while crouching).
     */
    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (!TideAccessBridge.canUseTide(p)) return;

        UUID id = p.getUniqueId();
        SneakState ss = sneaks.computeIfAbsent(id, k -> new SneakState());

        if (e.isSneaking()) {
            // Start of a potential quick tap
            if (ss.pending != null) {
                try { ss.pending.cancel(); } catch (Throwable ignored) {}
                ss.pending = null;
            }

            ss.sneakDownAt = System.currentTimeMillis();

            ss.pending = new BukkitRunnable() {
                @Override
                public void run() {
                    ss.pending = null;
                    // Quick tap detection: if player is no longer sneaking, treat it as a tap
                    if (!p.isSneaking()) {
                        // Ground tap only (like Gale Pull)
                        if (p.isOnGround()) {
                            triggerTidepool(p);
                        }
                    }
                }
            }.runTaskLater(plugin, SNEAK_TAP_TICKS);
        } else {
            // Sneak released; quick tap is resolved by pending task
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!TideAccessBridge.canUseTide(p)) return;

        TidePlayerData d = data(p);
        long now = System.currentTimeMillis();
        if (d.isInTyphoon() && now <= d.getTyphoonActiveUntil()) {
            Entity target = e.getEntity();
            Location c = target.getLocation().add(0, 1.0, 0);
            c.getWorld().spawnParticle(Particle.SPLASH, c, 18, 0.4, 0.5, 0.4, 0.02);
            c.getWorld().spawnParticle(Particle.CLOUD, c, 8, 0.3, 0.4, 0.3, 0.01);
            c.getWorld().playSound(c, Sound.WEATHER_RAIN_ABOVE, 0.8f, 1.4f);

            if (target instanceof LivingEntity le) {
                le.damage(0.5, p);
                le.setVelocity(le.getVelocity().add(new Vector(0, 0.15, 0)));
            }
        }
    }

    // ------------------------------------------------------------
    // Command helpers
    // ------------------------------------------------------------

    public void onRuneRevoked(Player p) {
        try {
            BossBar b = bars.remove(p.getUniqueId());
            if (b != null) b.removeAll();
            lastCooldown.remove(p.getUniqueId());
            flows.remove(p.getUniqueId());
            echoes.remove(p.getUniqueId());

            FTapState ft = fTaps.remove(p.getUniqueId());
            if (ft != null && ft.pending != null) {
                try { ft.pending.cancel(); } catch (Throwable ignored) {}
            }
            SneakState ss = sneaks.remove(p.getUniqueId());
            if (ss != null && ss.pending != null) {
                try { ss.pending.cancel(); } catch (Throwable ignored) {}
            }

            clearAB(p);
        } catch (Throwable ignored) {}
    }

    public void armStartWindow(Player p, long windowMs) {
        // Reserved for future gating if needed
    }

    private void warmAccess(Player p) {
        try { TideAccessBridge.warm(p); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------
    // Abilities (with optimized FX) + Tide Echo hooks
    // ------------------------------------------------------------

    private void triggerMaelstrom(Player p) {
        long now = System.currentTimeMillis();
        TidePlayerData d = data(p);
        if (!checkCd(p, d.getMaelstromReadyAt(), "Maelstrom")) return;

        boolean echo = isEchoActive(p, now);
        int evoLvl = evoLevel(p);
        double radius = MAELSTROM_RADIUS * TideEvoBridge.m(p, "maelstrom");

        Location c = p.getLocation();

        // Core sound
        c.getWorld().playSound(c, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.9f, 0.6f);
        c.getWorld().playSound(c, Sound.BLOCK_WATER_AMBIENT, 0.8f, 1.5f);

        // Triple swirling rings around player (optimized points)
        int rings = 3;
        int points = 28;
        for (int r = 0; r < rings; r++) {
            double ringRadius = (radius * 0.4) + (r * radius * 0.2);
            double yOffset = 0.5 + 0.4 * r;
            for (int i = 0; i < points; i++) {
                double angle = (2 * Math.PI * i / points) + (r * 0.7);
                double x = Math.cos(angle) * ringRadius;
                double z = Math.sin(angle) * ringRadius;
                Location pt = c.clone().add(x, yOffset, z);
                c.getWorld().spawnParticle(Particle.SPLASH, pt, 1, 0.02, 0.02, 0.02, 0.0);
                if (evoLvl >= 2 && i % 4 == 0) {
                    c.getWorld().spawnParticle(Particle.CLOUD, pt, 1, 0.01, 0.01, 0.01, 0.0);
                }
            }
        }

        // Vertical vortex column (sparser)
        for (double y = 0; y <= 3.0; y += 0.35) {
            double scale = 0.7 + (y / 3.0);
            double angle = y * 2.5;
            double x = Math.cos(angle) * radius * 0.25 * scale;
            double z = Math.sin(angle) * radius * 0.25 * scale;
            Location pt = c.clone().add(x, y, z);
            c.getWorld().spawnParticle(Particle.DRIPPING_WATER, pt, 1, 0.05, 0.05, 0.05, 0.01);
        }

        // Mechanical effect
        for (Entity e : p.getWorld().getNearbyEntities(c, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || le == p) continue;
            Vector dir = safeUnit(c.toVector().subtract(le.getLocation().toVector()));
            Vector tangent = new Vector(-dir.getZ(), 0, dir.getX());
            Vector swirl = tangent.multiply(0.4).add(dir.multiply(0.15));
            le.setVelocity(safeFinite(le.getVelocity().add(swirl)));
            int amp = (evoLvl >= 2 ? 2 : 1);
            int dur = 40;
            if (echo) {
                amp++;
                dur += 20;
            }
            le.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    dur,
                    amp,
                    false, true, true
            ));
        }

        long cd = cdFromEvo(p, MAELSTROM_CD_BASE);
        d.setMaelstromReadyAt(now + cd);
        showCooldown(p, "Maelstrom", now, now + cd, BarColor.BLUE);
        sendAB(p, ChatColor.AQUA + "Maelstrom" + ChatColor.WHITE + " cast.");
        activateEcho(p, now);
    }

    private void triggerBubble(Player p) {
        long now = System.currentTimeMillis();
        TidePlayerData d = data(p);
        if (!checkCd(p, d.getBubbleReadyAt(), "Bubble")) return;

        boolean echo = isEchoActive(p, now);
        int evoLvl = evoLevel(p);
        double radius = 4.0;
        Location c = p.getLocation().add(p.getLocation().getDirection().normalize().multiply(3));

        // Sounds
        c.getWorld().playSound(c, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.9f, 1.4f);
        c.getWorld().playSound(c, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.7f, 1.2f);

        // Rising spiral of bubbles (optimized steps)
        int steps = 20;
        for (int i = 0; i < steps; i++) {
            double t = i / (double) steps;
            double y = 0.3 + t * 3.0;
            double angle = t * Math.PI * 4;
            double r = 1.2 + (evoLvl * 0.1);
            double x = Math.cos(angle) * r;
            double z = Math.sin(angle) * r;
            Location pt = c.clone().add(x, y, z);
            c.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, pt, 1, 0.04, 0.04, 0.04, 0.0);
            if (i % 3 == 0) {
                c.getWorld().spawnParticle(Particle.SPLASH, pt, 1, 0.03, 0.03, 0.03, 0.0);
            }
        }

        // Bubble "cage"
        int ringPoints = 26;
        for (int i = 0; i < ringPoints; i++) {
            double angle = 2 * Math.PI * i / ringPoints;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            for (double y = 0.5; y <= 3.0; y += 0.75) {
                Location pt = c.clone().add(x, y, z);
                c.getWorld().spawnParticle(Particle.DRIPPING_WATER, pt, 1, 0.02, 0.02, 0.02, 0.01);
            }
        }

        for (Entity e : p.getWorld().getNearbyEntities(c, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || le == p) continue;
            int baseDur = 60 + (evoLvl * 20);
            int dur = baseDur + (echo ? 20 : 0);

            le.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    dur,
                    3,
                    false, true, true
            ));
            le.addPotionEffect(new PotionEffect(
                    PotionEffectType.WEAKNESS,
                    dur,
                    0,
                    false, true, true
            ));
            if (echo) {
                le.addPotionEffect(new PotionEffect(
                        PotionEffectType.BLINDNESS,
                        30,
                        0,
                        false, true, true
                ));
            }
        }

        long dur = 2000L + evoLvl * 1000L;
        d.setInBubble(true);
        d.setBubbleExpiresAt(now + dur);

        long cd = cdFromEvo(p, BUBBLE_CD_BASE);
        d.setBubbleReadyAt(now + cd);
        showCooldown(p, "Bubble", now, now + cd, BarColor.BLUE);
        sendAB(p, ChatColor.AQUA + "Bubble" + ChatColor.WHITE + " cast.");
        activateEcho(p, now);
    }

    private void triggerTidepool(Player p) {
        long now = System.currentTimeMillis();
        TidePlayerData d = data(p);
        if (!checkCd(p, d.getTidepoolReadyAt(), "Tidepool")) return;

        boolean echo = isEchoActive(p, now);
        int evoLvl = evoLevel(p);
        Location base = p.getLocation().clone().subtract(0, 1, 0);
        World w = base.getWorld();

        // Pulsing water sigil: 3 short rings
        double maxR = 3.0;
        int points = 28;
        for (int ring = 0; ring < 3; ring++) {
            double r = maxR * (0.3 + 0.2 * ring);
            double y = 0.05 + 0.02 * ring;
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                double x = Math.cos(angle) * r;
                double z = Math.sin(angle) * r;
                Location pt = base.clone().add(x, y, z);
                w.spawnParticle(Particle.DRIPPING_WATER, pt, 1, 0.01, 0.01, 0.01, 0.0);
                if (ring == 0 && i % 4 == 0) {
                    w.spawnParticle(Particle.SPLASH, pt.clone().add(0, 0.15, 0), 1, 0.01, 0.01, 0.01, 0.0);
                }
            }
        }

        w.spawnParticle(Particle.SPLASH, base.clone().add(0, 1, 0), 20, 1.0, 0.2, 1.0, 0.02);
        w.playSound(base, Sound.BLOCK_WATER_AMBIENT, 0.9f, 1.0f);
        if (evoLvl >= 2) {
            w.playSound(base, Sound.BLOCK_BEACON_POWER_SELECT, 0.5f, 1.5f);
        }

        double radius = 3.0;
        for (Entity e : w.getNearbyEntities(base, radius, 1.5, radius)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le == p) {
                le.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED,
                        60 + evoLvl * 40,
                        1,
                        false, true, true
                ));
                if (evoLvl >= 2) {
                    le.addPotionEffect(new PotionEffect(
                            PotionEffectType.REGENERATION,
                            60 + evoLvl * 40,
                            0,
                            false, true, true
                    ));
                }
            } else {
                le.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        60 + evoLvl * 40,
                        2,
                        false, true, true
                ));
                if (echo) {
                    le.addPotionEffect(new PotionEffect(
                            PotionEffectType.WEAKNESS,
                            40 + evoLvl * 20,
                            0,
                            false, true, true
                    ));
                }
            }
        }

        long cd = cdFromEvo(p, TIDEPOOL_CD_BASE);
        d.setTidepoolReadyAt(now + cd);
        showCooldown(p, "Tidepool", now, now + cd, BarColor.BLUE);
        sendAB(p, ChatColor.AQUA + "Tidepool" + ChatColor.WHITE + " cast.");
        activateEcho(p, now);
    }

    private void triggerSurge(Player p) {
        long now = System.currentTimeMillis();
        TidePlayerData d = data(p);
        if (!checkCd(p, d.getSurgeReadyAt(), "Surge")) return;

        boolean echo = isEchoActive(p, now);
        int evoLvl = evoLevel(p);
        double radius = SURGE_RADIUS * TideEvoBridge.m(p, "surge");
        double baseForce = SURGE_FORCE * TideEvoBridge.m(p, "surge");

        // Tide Echo synergy: stronger wave when chaining abilities
        double force = baseForce * (echo ? 1.25 : 1.0);

        Location c = p.getLocation();
        Vector dir = p.getLocation().getDirection().normalize();

        // Sweeping wave arc in front (optimized density)
        int waveSteps = 4;
        int sideSamples = 10;
        for (int step = 1; step <= waveSteps; step++) {
            double dist = 1.6 * step;
            Location center = c.clone().add(dir.clone().multiply(dist)).add(0, 0.2, 0);
            for (int i = -sideSamples; i <= sideSamples; i++) {
                double offset = i / (double) sideSamples;
                Vector side = new Vector(-dir.getZ(), 0, dir.getX()).normalize().multiply(offset * 1.2 * (1 + 0.08 * evoLvl));
                Location pt = center.clone().add(side);
                c.getWorld().spawnParticle(Particle.SPLASH, pt, 1, 0.04, 0.06, 0.04, 0.0);
                if (step == waveSteps && Math.abs(i) % 3 == 0) {
                    c.getWorld().spawnParticle(Particle.DRIPPING_WATER, pt.clone().add(0, 0.15, 0), 1, 0.01, 0.03, 0.01, 0.0);
                }
            }
        }

        c.getWorld().playSound(c, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.9f, 1.2f);
        c.getWorld().playSound(c, Sound.BLOCK_WATER_AMBIENT, 0.8f, 1.6f);

        for (Entity e : p.getWorld().getNearbyEntities(c, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || le == p) continue;
            Vector to = safeUnit(le.getLocation().toVector().subtract(c.toVector()));
            double dot = to.dot(dir);
            boolean inFront = dot > 0.25;

            Vector push = (inFront ? dir : dir.clone().multiply(-1)).multiply(force);
            push.setY(Math.max(-0.2, Math.min(0.6, push.getY())));
            le.setVelocity(safeFinite(le.getVelocity().add(push)));

            if (evoLvl >= 2) {
                le.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        40,
                        1,
                        false, true, true
                ));
            }
        }

        long cd = cdFromEvo(p, SURGE_CD_BASE);
        d.setSurgeReadyAt(now + cd);
        showCooldown(p, "Surge", now, now + cd, BarColor.BLUE);
        sendAB(p, ChatColor.AQUA + "Surge" + ChatColor.WHITE + " cast.");
        activateEcho(p, now);
    }

    public void triggerTyphoon(Player p) {
        long now = System.currentTimeMillis();
        TidePlayerData d = data(p);
        if (!TideAccessBridge.canUseTide(p)) return;

        boolean echo = isEchoActive(p, now);
        int evoLvl = evoLevel(p);
        // Evo 3 requirement
        if (evoLvl < 3) {
            sendAB(p, ChatColor.RED + "Typhoon unlocks at Evo 3.");
            return;
        }

        if (now < d.getTyphoonReadyAt()) {
            long left = d.getTyphoonReadyAt() - now;
            int sec = (int) Math.ceil(left / 1000.0);
            sendAB(p, ChatColor.RED + "Typhoon on cooldown (" + sec + "s)");
            return;
        }

        long dur = (long) (TYPHOON_DURATION_BASE * TideEvoBridge.m(p, "typhoon") * (echo ? 1.25 : 1.0));
        d.setInTyphoon(true);
        d.setTyphoonActiveUntil(now + dur);
        d.setTyphoonNextBoltAt(now + 350L);

        // Give a slight upward launch and slow-fall vibe
        Vector v = p.getVelocity();
        v.setY(Math.max(v.getY(), 0.7));
        p.setVelocity(safeFinite(v));
        p.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOW_FALLING,
                (int) (dur / 50L) + 10,
                0,
                false, false, true
        ));

        Location c = p.getLocation().add(0, 4, 0);
        World w = c.getWorld();

        // Initial multi-layer storm ring (optimized)
        for (int layer = 0; layer < 3; layer++) {
            double r = 2.0 + layer * 0.8;
            int pts = 28;
            double y = layer * 0.4;
            for (int i = 0; i < pts; i++) {
                double ang = 2 * Math.PI * i / pts;
                double x = Math.cos(ang) * r;
                double z = Math.sin(ang) * r;
                Location pt = c.clone().add(x, y, z);
                w.spawnParticle(Particle.CLOUD, pt, 1, 0.03, 0.03, 0.03, 0.0);
                if (layer == 2 && i % 4 == 0) {
                    w.spawnParticle(Particle.SPLASH, pt.clone().add(0, 0.2, 0), 1, 0.04, 0.04, 0.04, 0.0);
                }
            }
        }

        w.playSound(c, Sound.WEATHER_RAIN_ABOVE, 1.2f, 0.8f);
        w.playSound(c, Sound.ENTITY_DROWNED_HURT_WATER, 0.8f, 1.6f);

        activateEcho(p, now);

        long cd = cdFromEvo(p, TYPHOON_CD_BASE);
        d.setTyphoonReadyAt(now + cd);
        showCooldown(p, "Typhoon", now, now + cd, BarColor.BLUE);
        sendAB(p, ChatColor.AQUA + "Typhoon" + ChatColor.WHITE + " unleashed.");
    }

    // ------------------------------------------------------------
    // Tick — passive upkeep, Typhoon, bossbar, etc.
    // ------------------------------------------------------------

    private final class TickTask extends BukkitRunnable {
        @Override
        public void run() {
            long now = System.currentTimeMillis();

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!TideAccessBridge.canUseTide(p)) continue;

                TidePlayerData d = data(p);

                // Passive: Tide Echo — ambient effect while an echo is active
                tickEchoVisual(p, now);

                // Typhoon upkeep FX
                if (d.isInTyphoon()) {
                    if (now >= d.getTyphoonActiveUntil()) {
                        d.setInTyphoon(false);
                        clearAB(p);

                        // Ending splash shockwave when Typhoon ends
                        Location end = p.getLocation();
                        World w = end.getWorld();
                        w.spawnParticle(Particle.SPLASH, end.clone().add(0, 1, 0),
                                32, 1.5, 0.3, 1.5, 0.08);
                        w.playSound(end, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.0f, 1.0f);

                    } else {
                        double left = (d.getTyphoonActiveUntil() - now) / 1000.0;
                        sendAB(p, ChatColor.AQUA + "Typhoon: " + ChatColor.WHITE +
                                String.format(java.util.Locale.US, "%.1fs", Math.max(0.0, left)));

                        if (now >= d.getTyphoonNextBoltAt()) {
                            d.setTyphoonNextBoltAt(now + TYPHOON_TICK_MS);

                            Location c = p.getLocation().add(0, 4, 0);
                            World w = c.getWorld();

                            // Spinning inner spiral (optimized)
                            int pts = 20;
                            double r = 2.2;
                            for (int i = 0; i < pts; i++) {
                                double t = (now / 220.0) + i * (2 * Math.PI / pts);
                                double x = Math.cos(t) * r;
                                double z = Math.sin(t) * r;
                                Location pt = c.clone().add(x, 0, z);
                                w.spawnParticle(Particle.SPLASH, pt, 1, 0.05, 0.05, 0.05, 0.02);
                            }

                            // Gentle rain around player
                            w.spawnParticle(Particle.DRIPPING_WATER, p.getLocation().add(0, 2.0, 0),
                                    16, 2.3, 1.4, 2.3, 0.03);

                            w.playSound(c, Sound.WEATHER_RAIN_ABOVE, 0.7f, 1.3f);
                        }
                    }
                }

                // Bossbar cooldown progress
                BossBar b = bars.get(p.getUniqueId());
                if (b != null) {
                    LastCooldown lc = lastCooldown.get(p.getUniqueId());
                    if (lc == null) {
                        b.setVisible(false);
                    } else {
                        long start = lc.startMs();
                        long end = lc.endMs();
                        if (now >= end) {
                            b.setVisible(false);
                        } else {
                            double total = Math.max(1.0, (double) (end - start));
                            double left = Math.max(0.0, (double) (end - now));
                            double prog = Math.max(0.0, Math.min(1.0, 1.0 - (left / total)));
                            b.setProgress(prog);
                            String secs = String.format(java.util.Locale.US, "%.1fs", left / 1000.0);
                            b.setTitle(ChatColor.AQUA + lc.label() + ChatColor.GRAY + " cooldown • " + secs);
                            if (!b.isVisible()) b.setVisible(true);
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------
    // Passive flow logic (Tidal Momentum) + Tide Echo helpers
    // ------------------------------------------------------------

    // Passive Tide Echo state
    private static final class EchoState {
        long activeUntil;
    }

    private boolean isEchoActive(Player p, long now) {
        EchoState es = echoes.get(p.getUniqueId());
        return es != null && now <= es.activeUntil;
    }

    private void activateEcho(Player p, long now) {
        EchoState es = echoes.computeIfAbsent(p.getUniqueId(), id -> new EchoState());
        es.activeUntil = now + TIDE_ECHO_DURATION_MS;

        // Small burst when a new echo is set
        Location c = p.getLocation().add(0, 1.1, 0);
        c.getWorld().spawnParticle(Particle.SPLASH, c, 8, 0.3, 0.2, 0.3, 0.02);
        c.getWorld().spawnParticle(Particle.CLOUD, c, 3, 0.2, 0.15, 0.2, 0.01);
    }

    private void tickEchoVisual(Player p, long now) {
        EchoState es = echoes.get(p.getUniqueId());
        if (es == null || now > es.activeUntil) return;

        Location c = p.getLocation().add(0, 0.2, 0);
        c.getWorld().spawnParticle(Particle.DRIPPING_WATER, c, 3, 0.25, 0.05, 0.25, 0.01);
    }

    // Legacy flow state (no longer used, kept for minimal code movement)
    private static final class FlowState {
        double flow = 0.0;
        Location lastLoc = null;
        long lastUpdate = 0L;
        long lastBurstAt = 0L;
    }

    private void updateFlow(Player p, TidePlayerData d, long now) {
        FlowState fs = flows.computeIfAbsent(p.getUniqueId(), id -> new FlowState());
        Location loc = p.getLocation();

        if (fs.lastLoc != null) {
            double dtSec = (now - fs.lastUpdate) / 1000.0;
            if (dtSec < 0) dtSec = 0;

            Vector lastV = fs.lastLoc.toVector();
            Vector curV  = loc.toVector();
            lastV.setY(0); curV.setY(0);
            double dist = curV.distance(lastV);
            boolean moving = dist > 0.04;

            fs.flow = Math.max(0.0, fs.flow - FLOW_DECAY_PER_SECOND * dtSec);

            if (moving && p.isOnGround() && !p.isSneaking()) {
                fs.flow = Math.min(1.0, fs.flow + dist * FLOW_GAIN_PER_BLOCK);
            }

            if (!moving && fs.flow >= FLOW_TRIGGER_MIN && now - fs.lastBurstAt >= FLOW_BURST_COOLDOWN) {
                int evoLvl = evoLevel(p);
                int amp = (evoLvl >= 3 ? 1 : 0);
                int duration = 20;

                p.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED,
                        duration,
                        amp,
                        false, false, true
                ));

                Location c = loc.clone().add(0, 0.15, 0);
                c.getWorld().spawnParticle(Particle.SPLASH, c, 14, 0.25, 0.08, 0.25, 0.04);
                c.getWorld().spawnParticle(Particle.CLOUD, c, 5, 0.18, 0.10, 0.18, 0.01);
                c.getWorld().playSound(c, Sound.BLOCK_WATER_AMBIENT, 0.6f, 1.5f);

                fs.lastBurstAt = now;
                fs.flow = 0.0;
            }
        }

        fs.lastLoc = loc.clone();
        fs.lastUpdate = now;
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private record LastCooldown(String label, long startMs, long endMs) {}

    private static final class FTapState {
        long firstTapAt = 0L;
        boolean firstTapSneak = false;
        BukkitTask pending = null;
    }

    private static final class SneakState {
        long sneakDownAt = 0L;
        BukkitTask pending = null;
    }

    private int evoLevel(Player p) {
        if (evo != null) {
            try {
                return Math.max(0, Math.min(3, evo.getEvoLevel(p.getUniqueId())));
            } catch (Throwable ignored) {}
        }
        return TideEvoBridge.evo(p);
    }

    private boolean checkCd(Player p, long readyAt, String label) {
        long now = System.currentTimeMillis();
        if (now < readyAt) {
            long left = readyAt - now;
            int sec = (int) Math.ceil(left / 1000.0);
            sendAB(p, ChatColor.RED + label + " on cooldown (" + sec + "s)");
            return false;
        }
        return true;
    }

    private long cdFromEvo(Player p, long base) {
        int lvl = evoLevel(p);
        double factor = CDR[Math.max(0, Math.min(3, lvl))];
        return (long) Math.max(0L, Math.floor(base * factor));
    }

    private void showCooldown(Player p, String label, long startMs, long endMs, BarColor color) {
        BossBar bar = bars.computeIfAbsent(p.getUniqueId(), id ->
                Bukkit.createBossBar(ChatColor.AQUA + label, color, BarStyle.SEGMENTED_10));
        bar.setColor(color);
        bar.addPlayer(p);
        bar.setVisible(true);
        lastCooldown.put(p.getUniqueId(), new LastCooldown(label, startMs, endMs));
    }

    private void sendAB(Player p, String msg) {
        try {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
        } catch (Throwable ignored) {
            p.sendMessage(msg);
        }
    }

    private void clearAB(Player p) {
        try {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        } catch (Throwable ignored) {
        }
    }

    private Vector safeUnit(Vector v) {
        if (v == null) return new Vector(0, 1, 0);
        double len = v.length();
        if (!Double.isFinite(len) || len < 1.0e-6) return new Vector(0, 1, 0);
        return v.multiply(1.0 / len);
    }

    private Vector safeFinite(Vector v) {
        double x = v.getX(), y = v.getY(), z = v.getZ();
        if (!Double.isFinite(x)) x = 0.0;
        if (!Double.isFinite(y)) y = 0.0;
        if (!Double.isFinite(z)) z = 0.0;
        return new Vector(x, y, z);
    }
}
