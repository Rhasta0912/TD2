package com.example.evo.api;

import java.util.UUID;

/**
 * Minimal EvoCore API surface so TideWielder can hook Evo if EvoCore is installed.
 * Mirrors the Winder / EvoCore interface.
 */
public interface IEvoService {

    /**
     * Get a player's Evo level (0–3).
     */
    int getEvoLevel(UUID playerId);

    /**
     * Set a player's Evo level (0–3).
     */
    void setEvoLevel(UUID playerId, int level);

    /**
     * Lookup a scalar multiplier for a given key at a given evo level.
     * Example keys: "pull", "dash", "dive", etc.
     */
    double multiplier(String key, int evoLevel);
}
