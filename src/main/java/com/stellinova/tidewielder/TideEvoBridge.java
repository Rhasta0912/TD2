package com.stellinova.tidewielder;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;

public final class TideEvoBridge {

    private enum Link { SERVICE, PAPI, NONE }

    private static Link linkMode = Link.NONE;
    private static Class<?> svcClass;
    private static Object serviceInstance;
    private static Method mGetEvo;
    private static Method mGetScalar;

    private TideEvoBridge() {}

    public static int evo(Player p) {
        ensureHooked();
        if (p == null) return 0;
        if (linkMode == Link.SERVICE) {
            try {
                Object res = mGetEvo.invoke(serviceInstance, p);
                if (res instanceof Number n) return Math.max(0, Math.min(3, n.intValue()));
            } catch (Throwable ignored) {}
        }
        if (linkMode == Link.PAPI) {
            try {
                String s = PlaceholderAPI.setPlaceholders(p, "%evo_stage%");
                return clampInt(s, 0, 3);
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    public static double m(Player p, String key) {
        ensureHooked();
        if (p != null && linkMode == Link.SERVICE) {
            try {
                Object res = mGetScalar.invoke(serviceInstance, p, key.toLowerCase(Locale.ROOT));
                if (res instanceof Number n) return n.doubleValue();
            } catch (Throwable ignored) {}
        }
        if (p != null && linkMode == Link.PAPI) {
            try {
                String place = switch (key.toLowerCase(Locale.ROOT)) {
                    case "maelstrom" -> "%evo_mult_pull%";
                    case "bubble"   -> "%evo_mult_dive%";
                    case "pool"     -> "%evo_mult_dash%";
                    case "surge"    -> "%evo_mult_dash%";
                    case "typhoon"  -> "%evo_mult_dive%";
                    default -> "%evo_mult_dash%";
                };
                String s = PlaceholderAPI.setPlaceholders(p, place);
                return Double.parseDouble(s);
            } catch (Throwable ignored) {}
        }

        int lvl = (p == null ? 0 : evo(p));
        double bonus = switch (key.toLowerCase(Locale.ROOT)) {
            case "maelstrom" -> 0.25;
            case "bubble"    -> 0.20;
            case "pool"      -> 0.20;
            case "surge"     -> 0.25;
            case "typhoon"   -> 0.30;
            default -> 0.0;
        };
        return 1.0 + bonus * Math.max(0, Math.min(3, lvl));
    }

    private static void ensureHooked() {
        if (linkMode != Link.NONE) return;

        if (tryLoadServiceClass("com.example.evo.IEvoService")) return;
        if (tryLoadServiceClass("com.example.evo.api.IEvoService")) return;

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            linkMode = Link.PAPI;
            return;
        }
        linkMode = Link.NONE;
    }

    private static boolean tryLoadServiceClass(String name) {
        try {
            Class<?> clazz = Class.forName(name);
            Object svc = Bukkit.getServicesManager().load(clazz);
            if (svc == null) return false;

            Method getEvo = findMethod(clazz, "getEvo", org.bukkit.entity.Player.class);
            if (getEvo == null) getEvo = findMethod(clazz, "getEvoLevel", java.util.UUID.class);
            if (getEvo == null) return false;

            Method getScalar = findMethod(clazz, "getScalar", org.bukkit.entity.Player.class, String.class);
            if (getScalar == null) return false;

            svcClass = clazz;
            serviceInstance = svc;
            mGetEvo = getEvo;
            mGetScalar = getScalar;
            linkMode = Link.SERVICE;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int clampInt(String s, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(min, Math.min(max, v));
        } catch (Throwable ignored) {
            return min;
        }
    }
}
