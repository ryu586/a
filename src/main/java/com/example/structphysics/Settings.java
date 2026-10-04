package com.example.structphysics;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.Set;

/** config.yml の値を保持する */
final class Settings {
    final int radius;
    final int maxNodes;
    final int maxIterations;
    final int maxAnalysesPerTick;
    final int maxCollapsePerTick;
    final long budgetNanos;
    final double gravityScale;
    final double strengthScale;
    final double minSafetyFactor;
    final double damageRate;
    final double instantRatio;
    final boolean visualCracks;
    final Set<Material> anchors = EnumSet.noneOf(Material.class);

    Settings(FileConfiguration c) {
        radius = Math.max(2, c.getInt("region-radius", 16));
        maxNodes = Math.max(16, c.getInt("max-nodes", 3000));
        maxIterations = Math.max(1, c.getInt("max-iterations", 6));
        maxAnalysesPerTick = Math.max(1, c.getInt("max-analyses-per-tick", 3));
        maxCollapsePerTick = Math.max(1, c.getInt("max-collapse-per-tick", 48));
        budgetNanos = (long) (c.getDouble("time-budget-ms", 6.0) * 1_000_000);
        gravityScale = c.getDouble("gravity-scale", 1.0);
        strengthScale = c.getDouble("strength-scale", 1.0);
        minSafetyFactor = Math.max(0.01, c.getDouble("min-safety-factor", 1.0));
        damageRate = c.getDouble("damage-rate", 0.05);
        instantRatio = c.getDouble("instant-break-ratio", 3.0);
        visualCracks = c.getBoolean("visual-cracks", true);
        for (String s : c.getStringList("anchor-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) anchors.add(m);
        }
    }
}