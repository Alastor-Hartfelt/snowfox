package com.snowfox;

final class SnowFoxSettings {
    private final SnowFoxPlugin plugin;
    SnowFoxSettings(SnowFoxPlugin plugin) { this.plugin = plugin; }
    void loadSettings() {
        plugin.reloadConfig();
        plugin.followStop = bounded("following.stop-distance", 2.0, 0.5, 12.0);
        plugin.followStart = bounded("following.start-distance", 4.0, plugin.followStop + 0.5, 24.0);
        plugin.followSpeed = bounded("following.speed", 1.15, 0.1, 3.0);
        plugin.teleportDistance = bounded("following.teleport-distance", 24.0, plugin.followStart + 1.0, 128.0);
        plugin.followRepathTicks = (int) bounded("following.repath-ticks", 10, 1, 100);
        plugin.stuckCheckTicks = (int) bounded("following.stuck-check-ticks", 40, 5, 200);
        plugin.stuckChecksBeforeTeleport = (int) bounded("following.stuck-checks-before-teleport", 4, 1, 20);
        plugin.snowflakeParticlesEnabled = plugin.getConfig().getBoolean("effects.allow-snowflake-particles", true);
        plugin.wolfStyleCombatEnabled = plugin.getConfig().getBoolean("combat.attacks", false);
        plugin.jumpStyleCombat = plugin.getConfig().getString("combat.attack-style", "jump").equalsIgnoreCase("jump");
        plugin.particleAmount = Math.max(0, Math.min(100, plugin.getConfig().getInt("effects.snowflake-particle-amount", 1)));
        plugin.defaultAging = plugin.getConfig().getBoolean("fox.allow-natural-aging", false);
    }
    double bounded(String key, double fallback, double min, double max) {
        double value = plugin.getConfig().getDouble(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            plugin.getLogger().warning("Invalid config value for " + key + "; using " + fallback);
            return fallback;
        }
        return value;
    }
}
