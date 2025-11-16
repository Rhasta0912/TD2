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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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

public class TideManager implements Listener {

    private final TideWielderPlugin plugin;
    private final IEvoService evo;

    private final Map<UUID, TidePlayerData> data = new HashMap<>();
    private final Map<UUID, FTapState> fTaps = new HashMap<>();
    private final Map<UUID, SneakState> sneaks = new HashMap<>();
    private final Map<UUID, FlowInfo> flows = new HashMap<>();

    private BukkitTask tickTask;

    // Base cooldowns (ms)
    private static final long GRASP_CD   = 11_000L;
    private static final long PRISON_CD  = 14_000L;
    private static final long POOL_CD    = 9_000L;
    private static final long WAVE_CD    = 7_000L;
    private static final long TYPHOON_CD = 60_000L;

    // Evo CDR
    private static final double[] CDR = {1.0, 0.9, 0.8, 0.7};

    // Input timing
    private static final long F_TAP_MS = 260L;
    private static final long F_TAP_TICKS = 6L;
    private static final long SNEAK_TAP_TICKS = 6L;

    // Passive
    private static final double FLOW_GAIN_PER_BLOCK = 0.45;
    private static final double FLOW_DECAY_PER_SEC = 0.35;
    private static final double FLOW_TRIGGER_MIN = 0.45;
    private static final long FLOW_BURST_CD = 1500L;

    public TideManager(TideWielderPlugin plugin, IEvoService evo) {
        this.plugin = plugin;
        this.evo = evo;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        tickTask = new Tick().runTaskTimer(plugin, 1L, 1L);
    }

    public TidePlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), id -> new TidePlayerData(id));
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        HandlerList.unregisterAll(this);
        data.clear();
        fTaps.clear();
        sneaks.clear();
        flows.clear();
    }

    // ----------------------------------------------------------------
    // Events
    // ----------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        data(p);
        TideAccessBridge.warm(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        fTaps.remove(p.getUniqueId());
        sneaks.remove(p.getUniqueId());
        flows.remove(p.getUniqueId());
    }

    // F: tap / double-tap
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!TideAccessBridge.canUseTide(p)) return;
        e.setCancelled(true);

        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        FTapState st = fTaps.computeIfAbsent(id, k -> new FTapState());

        if (st.pending != null) {
            st.pending.cancel();
            st.pending = null;
        }

        if (st.firstTapAt > 0 && now - st.firstTapAt <= F_TAP_MS) {
            boolean sneakEither = st.firstSneak || p.isSneaking();
            st.firstTapAt = 0;
            st.firstSneak = false;
            st.pending = null;

            if (sneakEither) {
                triggerTyphoon(p);
            } else {
                triggerGrasp(p);
            }
            return;
        }

        st.firstTapAt = now;
        st.firstSneak = p.isSneaking();
        st.pending = new BukkitRunnable() {
            final long tapTime = now;
            final boolean sneak = st.firstSneak;

            @Override
            public void run() {
                if (st.firstTapAt != tapTime) return;
                st.firstTapAt = 0;
                st.firstSneak = false;
                st.pending = null;
                if (sneak) triggerPrison(p);
                else triggerWave(p);
            }
        }.runTaskLater(plugin, F_TAP_TICKS);
    }

    // Sneak: quick tap = pool OR bubble escape
    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        TidePlayerData d = data(p);

        // Bubble escape for ANY imprisoned player
        if (d.isInPrison()) {
            if (e.isSneaking()) {
                adjustPrisonHp(p, d, -1);
            }
            return;
        }

        if (!TideAccessBridge.canUseTide(p)) return;

        UUID id = p.getUniqueId();
        SneakState ss = sneaks.computeIfAbsent(id, k -> new SneakState());
        if (!e.isSneaking()) return; // we only care about press

        if (ss.pending != null) {
            ss.pending.cancel();
            ss.pending = null;
        }
        ss.pending = new BukkitRunnable() {
            @Override
            public void run() {
                ss.pending = null;
                if (!p.isSneaking() && p.isOnGround()) triggerPool(p);
            }
        }.runTaskLater(plugin, SNEAK_TAP_TICKS);
    }

    // Typhoon hits and bubble escape via attacks
    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();
        TidePlayerData d = data(p);
        long now = System.currentTimeMillis();

        // Prison attack escape
        if (d.isInPrison()) {
            adjustPrisonHp(p, d, -2);
            return;
        }

        // Typhoon bolt
        if (d.isInTyphoon() && now <= d.getTyphoonActiveUntil()) {
            LivingEntity target = e.getEntity() instanceof LivingEntity le ? le : null;
            if (target != null) {
                typhoonBolt(p, target);
            }
        }
    }

    // ----------------------------------------------------------------
    // Tick
    // ----------------------------------------------------------------

    private final class Tick extends BukkitRunnable {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                TidePlayerData d = data(p);

                // Tidal Momentum only for rune holders
                if (TideAccessBridge.canUseTide(p)) {
                    updateFlow(p, d, now);
                }

                // Aqua Prison upkeep
                if (d.isInPrison()) {
                    if (now >= d.getPrisonExpiresAt() || d.getPrisonHp() <= 0 || p.isDead()) {
                        clearPrison(p, d);
                    } else {
                        prisonTickFx(p);
                    }
                }

                // Pool duration
                if (d.isInPool() && now >= d.getPoolExpiresAt()) {
                    d.setInPool(false);
                }

                // Pool effects
                if (d.isInPool()) {
                    poolTickEffects(p);
                }

                // Typhoon upkeep
                if (d.isInTyphoon()) {
                    if (now >= d.getTyphoonActiveUntil() || p.isDead()) {
                        d.setInTyphoon(false);
                    } else {
                        typhoonTickFx(p, d, now);
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Ability triggers
    // ----------------------------------------------------------------

    // AB1 - Mariner’s Grasp (double-F)
    private void triggerGrasp(Player p) {
        if (!TideAccessBridge.canUseTide(p)) return;
        TidePlayerData d = data(p);
        long now = System.currentTimeMillis();
        if (!checkCd(p, d.getGraspReadyAt(), "Mariner’s Grasp")) return;

        LivingEntity target = raycastTarget(p, 14);
        if (target == null) {
            sendAB(p, "§cNo target for §bMariner’s Grasp§c.");
            return;
        }

        int evoLvl = getEvo(p);
        double dmgHearts = TideEvoBridge.graspDamage(p);
        double stunSec = TideEvoBridge.graspStunSeconds(p);
        int slowAmp = TideEvoBridge.graspSlowAmp(p);
        double slowMulti = TideEvoBridge.graspSlowDuration(p);

        Location c = target.getLocation().add(0, 0.5, 0);
        World w = c.getWorld();

        // FX swirl
        w.playSound(c, Sound.BLOCK_WATER_AMBIENT, 0.9f, 1.4f);
        w.playSound(c, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.9f, 0.7f);
        int pts = 26;
        for (int y = 0; y < 6; y++) {
            double yy = 0.2 + y * 0.25;
            double r = 1.0 + y * 0.1;
            for (int i = 0; i < pts; i++) {
                double a = 2 * Math.PI * i / pts + y * 0.4;
                double x = Math.cos(a) * r;
                double z = Math.sin(a) * r;
                w.spawnParticle(Particle.SPLASH, c.clone().add(x, yy, z), 1, 0, 0, 0, 0);
            }
        }

        // Slow
        int slowTicks = (int) (30 * slowMulti);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, slowTicks, slowAmp, false, true, true));

        // Stun via strong slowness for short duration
        if (stunSec > 0) {
            int stunTicks = (int) (stunSec * 20);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, stunTicks, 10, false, true, true));
        }

        // Damage
        if (dmgHearts > 0 && target != p) {
            double dmg = dmgHearts * 2.0;
            target.damage(dmg, p);
        }

        long cd = withCdr(p, GRASP_CD);
        d.setGraspReadyAt(now + cd);
        sendAB(p, "§b§lMariner’s Grasp! §fWater surges and binds your target!");
    }

    // AB2 - Aqua Prison (sneak + tap F)
    private void triggerPrison(Player p) {
        if (!TideAccessBridge.canUseTide(p)) return;
        TidePlayerData casterData = data(p);
        long now = System.currentTimeMillis();
        if (!checkCd(p, casterData.getPrisonReadyAt(), "Aqua Prison")) return;

        LivingEntity target = raycastTarget(p, 14);
        if (target == null) {
            sendAB(p, "§cNo target for §3Aqua Prison§c.");
            return;
        }

        TidePlayerData td = (target instanceof Player pl) ? data(pl) : null;

        int hp = TideEvoBridge.prisonHp(p);
        double durSec = TideEvoBridge.prisonDuration(p);
        long expires = now + (long) (durSec * 1000L);

        if (td != null) {
            td.setInPrison(true);
            td.setPrisonHp(hp);
            td.setPrisonExpiresAt(expires);
        }

        // FX bubble
        Location c = target.getLocation().add(0, 1, 0);
        World w = c.getWorld();
        w.playSound(c, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.9f, 1.4f);
        int rings = 3;
        int pts = 20;
        for (int r = 0; r < rings; r++) {
            double rad = 1.0 + r * 0.25;
            double y = 0.3 + r * 0.5;
            for (int i = 0; i < pts; i++) {
                double a = 2 * Math.PI * i / pts;
                double x = Math.cos(a) * rad;
                double z = Math.sin(a) * rad;
                w.spawnParticle(Particle.WATER_BUBBLE, c.clone().add(x, y, z), 1, 0, 0, 0, 0);
            }
        }

        long cd = withCdr(p, PRISON_CD);
        casterData.setPrisonReadyAt(now + cd);
        sendAB(p, "§3§lAqua Prison! §fYour foe is trapped inside a water sphere!");
    }

    // AB3 - Tidebreaker Pool (quick sneak tap)
    private void triggerPool(Player p) {
        if (!TideAccessBridge.canUseTide(p)) return;
        TidePlayerData d = data(p);
        long now = System.currentTimeMillis();
        if (!checkCd(p, d.getPoolReadyAt(), "Tidebreaker Pool")) return;

        d.setInPool(true);
        double durSec = TideEvoBridge.poolDurationSeconds(p);
        d.setPoolExpiresAt(now + (long) (durSec * 1000L));

        Location c = p.getLocation().clone().subtract(0, 1, 0);
        World w = c.getWorld();
        w.playSound(c, Sound.BLOCK_WATER_AMBIENT, 0.9f, 1.1f);

        int pts = 26;
        for (int i = 0; i < pts; i++) {
            double a = 2 * Math.PI * i / pts;
            double x = Math.cos(a) * 2.2;
            double z = Math.sin(a) * 2.2;
            w.spawnParticle(Particle.DRIPPING_WATER, c.clone().add(x, 0.2, z), 1, 0, 0, 0, 0);
        }

        long cd = withCdr(p, POOL_CD);
        d.setPoolReadyAt(now + cd);
        sendAB(p, "§9§lTidebreaker Pool! §fSacred water empowers you!");
    }

    // AB4 - Riptide Wave (tap F)
    private void triggerWave(Player p) {
        if (!TideAccessBridge.canUseTide(p)) return;
        TidePlayerData d = data(p);
        long now = System.currentTimeMillis();
        if (!checkCd(p, d.getWaveReadyAt(), "Riptide Wave")) return;

        int evoLvl = getEvo(p);
        double dmgHearts = TideEvoBridge.waveDamage(p);
        int slowAmp = TideEvoBridge.waveSlowAmp(p);
        double forceMul = TideEvoBridge.waveForce(p);

        Location c = p.getLocation();
        World w = c.getWorld();
        Vector dir = c.getDirection().normalize();

        w.playSound(c, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.9f, 1.2f);

        // FX arc
        int steps = 4;
        int side = 10;
        for (int s = 1; s <= steps; s++) {
            double dist = 1.4 * s;
            Location center = c.clone().add(dir.clone().multiply(dist)).add(0, 0.3, 0);
            for (int i = -side; i <= side; i++) {
                double off = i / (double) side;
                Vector sideV = new Vector(-dir.getZ(), 0, dir.getX()).normalize().multiply(off * 1.3);
                w.spawnParticle(Particle.SPLASH, center.clone().add(sideV), 1, 0.02, 0.02, 0.02, 0);
            }
        }

        double radius = 6.0;
        for (Entity e : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || le == p) continue;
            Vector to = le.getLocation().toVector().subtract(c.toVector());
            double dot = to.normalize().dot(dir);
            boolean front = dot > 0.0;
            Vector push = front ? dir.clone() : dir.clone().multiply(-1);
            push.multiply(0.9 * forceMul);
            push.setY(0.1);
            le.setVelocity(push);

            if (dmgHearts > 0) le.damage(dmgHearts * 2.0, p);
            if (slowAmp > 0) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 40 + evoLvl * 10, slowAmp, false, true, true));
        }

        long cd = withCdr(p, WAVE_CD);
        d.setWaveReadyAt(now + cd);
        sendAB(p, "§b§lRiptide Wave! §fThe tide obeys your command!");
    }
    // Ult - Celestial Typhoon (sneak + double-F)
    private void triggerTyphoon(Player p) {
        if (!TideAccessBridge.canUseTide(p)) return;
        TidePlayerData d = data(p);
        long now = System.currentTimeMillis();
        if (!checkCd(p, d.getTyphoonReadyAt(), "Celestial Typhoon")) return;

        int evoLvl = getEvo(p);
        if (evoLvl < 3) {
            sendAB(p, "§cCelestial Typhoon unlocks at §bEvo III§c.");
            return;
        }

        double durMul = TideEvoBridge.typhoonDuration(p);
        long durMs = (long) (6000L * durMul);

        d.setInTyphoon(true);
        d.setTyphoonActiveUntil(now + durMs);

        Location c = p.getLocation().add(0, 4, 0);
        World w = c.getWorld();
        w.playSound(c, Sound.WEATHER_RAIN_ABOVE, 1.0f, 0.8f);
        w.playSound(c, Sound.BLOCK_WATER_AMBIENT, 0.8f, 1.4f);

        int pts = 24;
        for (int i = 0; i < pts; i++) {
            double a = 2 * Math.PI * i / pts;
            double x = Math.cos(a) * 2.4;
            double z = Math.sin(a) * 2.4;
            w.spawnParticle(Particle.CLOUD, c.clone().add(x, 0, z), 1, 0.02, 0.02, 0.02, 0);
        }

        long cd = withCdr(p, TYPHOON_CD);
        d.setTyphoonReadyAt(now + cd);
        sendAB(p, "§3§l§nCELESTIAL TYPHOON!§r §bThe heavens answer your call!");
    }

    private void typhoonBolt(Player caster, LivingEntity target) {
        double dmgHearts = TideEvoBridge.typhoonDamage(caster);
        int slowAmp = TideEvoBridge.typhoonSlowAmp(caster);

        Location top = target.getLocation().add(0, 6, 0);
        World w = top.getWorld();
        w.playSound(top, Sound.WEATHER_RAIN_ABOVE, 0.6f, 1.5f);

        int pts = 16;
        for (int i = 0; i < pts; i++) {
            double a = 2 * Math.PI * i / pts;
            double x = Math.cos(a) * 0.7;
            double z = Math.sin(a) * 0.7;
            w.spawnParticle(Particle.DRIPPING_WATER, top.clone().add(x, 0, z), 1, 0, 0, 0, 0.02);
        }
        w.spawnParticle(Particle.SPLASH, target.getLocation().add(0, 1, 0), 12, 0.4, 0.3, 0.4, 0.06);

        if (dmgHearts > 0) target.damage(dmgHearts * 2.0, caster);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 16, slowAmp, false, true, true));
    }

    private void typhoonTickFx(Player p, TidePlayerData d, long now) {
        Location c = p.getLocation().add(0, 3.2, 0);
        World w = c.getWorld();
        w.spawnParticle(Particle.DRIPPING_WATER, c, 10, 2.2, 0.8, 2.2, 0.03);
        if (now % 800L < 60L) {
            w.playSound(c, Sound.WEATHER_RAIN_ABOVE, 0.4f, 1.2f);
        }
    }

    // Aqua Prison recurring FX
    private void prisonTickFx(Player prisoner) {
        Location c = prisoner.getLocation().add(0, 1, 0);
        World w = c.getWorld();
        w.spawnParticle(Particle.WATER_BUBBLE, c, 8, 0.6, 0.8, 0.6, 0.02);

        Vector v = prisoner.getVelocity();
        v.setY(Math.max(v.getY(), 0.05));
        v.setX(v.getX() * 0.2);
        v.setZ(v.getZ() * 0.2);
        prisoner.setVelocity(v);

        prisoner.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 5, 3, false, false, false));
    }

    private void clearPrison(Player prisoner, TidePlayerData d) {
        d.setInPrison(false);
        d.setPrisonHp(0);
        d.setPrisonExpiresAt(0L);
        Location c = prisoner.getLocation().add(0, 1, 0);
        World w = c.getWorld();
        w.spawnParticle(Particle.SPLASH, c, 18, 0.6, 0.6, 0.6, 0.08);
        w.playSound(c, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.9f, 1.4f);
    }

    private void adjustPrisonHp(Player prisoner, TidePlayerData d, int delta) {
        if (!d.isInPrison()) return;
        int hp = Math.max(0, d.getPrisonHp() + delta);
        d.setPrisonHp(hp);

        Location c = prisoner.getLocation().add(0, 1, 0);
        World w = c.getWorld();
        w.spawnParticle(Particle.WATER_BUBBLE, c, 4, 0.5, 0.5, 0.5, 0.02);

        if (hp <= 0 || System.currentTimeMillis() >= d.getPrisonExpiresAt()) {
            clearPrison(prisoner, d);
        }
    }

    // Tidebreaker pool logic (buff self, debuff enemies)
    private void poolTickEffects(Player p) {
        TidePlayerData d = data(p);
        Location c = p.getLocation();
        World w = c.getWorld();

        int evoLvl = getEvo(p);
        int slowAmp = TideEvoBridge.poolSlowAmp(p);
        double speedBoost = TideEvoBridge.poolSpeedBoost(p);
        int regenAmp = TideEvoBridge.poolRegenAmp(p);

        double radius = 3.0;
        for (Entity e : w.getNearbyEntities(c, radius, 1.5, radius)) {
            if (!(e instanceof LivingEntity le)) continue;
            boolean self = (le == p);
            if (self) {
                le.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 10, (int) Math.round(speedBoost * 2), false, false, false));
                if (regenAmp > 0) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 10, regenAmp, false, false, false));
                }
            } else {
                le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 10 + evoLvl * 5, slowAmp, false, true, true));
            }
        }

        w.spawnParticle(Particle.DRIPPING_WATER, c.clone().subtract(0, 0.7, 0), 6, 1.8, 0.2, 1.8, 0.03);
    }

    // Tidal Momentum passive
    private void updateFlow(Player p, TidePlayerData d, long now) {
        FlowInfo fi = flows.computeIfAbsent(p.getUniqueId(), k -> new FlowInfo());
        Location loc = p.getLocation();
        if (fi.lastLoc != null) {
            double dt = (now - fi.lastTime) / 1000.0;
            if (dt < 0) dt = 0;

            double dx = loc.getX() - fi.lastLoc.getX();
            double dz = loc.getZ() - fi.lastLoc.getZ();
            double dist = Math.hypot(dx, dz);
            boolean moving = dist > 0.04;

            fi.flow = Math.max(0.0, fi.flow - FLOW_DECAY_PER_SEC * dt);
            if (moving && p.isOnGround() && !p.isSneaking()) {
                fi.flow = Math.min(1.0, fi.flow + dist * FLOW_GAIN_PER_BLOCK);
            }

            if (!moving && fi.flow >= FLOW_TRIGGER_MIN && now - fi.lastBurstAt >= FLOW_BURST_CD) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 0, false, false, true));
                Location c = loc.clone().add(0, 0.2, 0);
                World w = c.getWorld();
                w.spawnParticle(Particle.SPLASH, c, 10, 0.4, 0.2, 0.4, 0.04);
                w.playSound(c, Sound.BLOCK_WATER_AMBIENT, 0.5f, 1.6f);
                fi.lastBurstAt = now;
                fi.flow = 0.0;
            }
        }
        fi.lastLoc = loc.clone();
        fi.lastTime = now;
        d.setFlow(fi.flow);
    }

    // Raycast-ish target finder in front of player
    private LivingEntity raycastTarget(Player p, double maxDist) {
        Location c = p.getLocation();
        Vector dir = c.getDirection().normalize();
        World w = c.getWorld();
        LivingEntity best = null;
        double bestDist = maxDist + 1;

        for (Entity e : w.getNearbyEntities(c, maxDist, maxDist, maxDist)) {
            if (!(e instanceof LivingEntity le) || le == p) continue;
            Vector to = le.getLocation().toVector().subtract(c.toVector());
            double dist = to.length();
            if (dist > maxDist) continue;
            to.normalize();
            double dot = to.dot(dir);
            if (dot < 0.4) continue; // must be in front cone
            if (dist < bestDist) {
                bestDist = dist;
                best = le;
            }
        }
        return best;
    }

    private boolean checkCd(Player p, long readyAt, String name) {
        long now = System.currentTimeMillis();
        if (now >= readyAt) return true;
        long left = readyAt - now;
        double sec = left / 1000.0;
        sendAB(p, "§c" + name + " on cooldown (" + String.format("%.1f", sec) + "s)");
        return false;
    }

    private long withCdr(Player p, long base) {
        int lvl = getEvo(p);
        if (lvl < 0 || lvl > 3) return base;
        return (long) (base * CDR[lvl]);
    }

    private int getEvo(Player p) {
        try {
            if (evo != null) {
                return Math.max(0, Math.min(3, evo.getEvoLevel(p.getUniqueId())));
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private void sendAB(Player p, String msg) {
        try {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
        } catch (Throwable ignored) {
            p.sendMessage(msg);
        }
    }

    // ----------------------------------------------------------------
    // Inner state classes
    // ----------------------------------------------------------------

    private static final class FTapState {
        long firstTapAt = 0L;
        boolean firstSneak = false;
        BukkitTask pending = null;
    }

    private static final class SneakState {
        BukkitTask pending = null;
    }

    private static final class FlowInfo {
        Location lastLoc;
        long lastTime = 0L;
        double flow = 0.0;
        long lastBurstAt = 0L;
    }
}
