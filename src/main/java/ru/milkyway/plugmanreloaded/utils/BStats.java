package ru.milkyway.plugmanreloaded.utils;

import org.bstats.bukkit.Metrics;
import ru.milkyway.plugmanreloaded.PlugManReloaded;

public class BStats {

    private static final int PLUGIN_ID = 33536;

    public BStats(PlugManReloaded plugin) {
        try {
            new Metrics(plugin, PLUGIN_ID);
        } catch (Exception t) {
            Log.debug("bstats.metrics-start-failed", t);
        }
    }
}

