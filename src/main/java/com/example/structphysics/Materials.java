package com.example.structphysics;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.EnumMap;
import java.util.Map;

/**
 * 材料定数 (SI単位 / 1ブロック = 1m立方)
 * density: kg/m^3, 他は Pa
 */
record MaterialProps(double density, double youngModulus, double tensile,
                     double compressive, double shear, double bending) {

    /** 1m^3 あたりの質量(kg)。スラブ等は density に体積比を掛けてある */
    double mass() {
        return density;
    }

    MaterialProps scaled(double strength, double volume) {
        return new MaterialProps(density * volume, youngModulus,
                tensile * strength, compressive * strength, shear * strength, bending * strength);
    }
}

final class Materials {
    //                                                      密度   ヤング率  引張    圧縮    せん断  曲げ
    static final MaterialProps STONE    = new MaterialProps(2500,  50e9,   4e6,   80e6,   8e6,   8e6);
    static final MaterialProps BRICK    = new MaterialProps(1900,  20e9,   1.5e6, 30e6,   3e6,   3e6);
    static final MaterialProps CONCRETE = new MaterialProps(2400,  30e9,   3e6,   40e6,   6e6,   5e6);
    static final MaterialProps WOOD     = new MaterialProps( 600,  11e9,  20e6,   20e6,   4e6,  12e6);
    static final MaterialProps METAL    = new MaterialProps(7800, 200e9, 250e6,  250e6, 150e6, 250e6);
    static final MaterialProps GLASS    = new MaterialProps(2500,  70e9,   3e6,  100e6,   5e6,   3e6);
    static final MaterialProps OBSIDIAN = new MaterialProps(2600,  60e9,  10e6,  200e6,  20e6,  15e6);
    static final MaterialProps ICE      = new MaterialProps( 920,   9e9,   1e6,   10e6,   1e6,   1.5e6);
    static final MaterialProps EARTH    = new MaterialProps(1600,  50e6,  20e3,  500e3,  50e3,  60e3);
    static final MaterialProps LOOSE    = new MaterialProps(1700,  30e6,   1e3,  300e3,  20e3,   1e3);
    static final MaterialProps LEAVES   = new MaterialProps( 100,  20e6, 150e3,  150e3, 100e3, 150e3);
    static final MaterialProps SOFT     = new MaterialProps( 300,   5e6, 100e3,  100e3,  50e3, 100e3);

    private final Settings cfg;
    private final Map<Material, MaterialProps> cache = new EnumMap<>(Material.class);

    Materials(Settings cfg) {
        this.cfg = cfg;
    }

    boolean isAnchor(Material m) {
        return cfg.anchors.contains(m);
    }

    /** 構造対象外(空気・液体・非固体)なら null */
    MaterialProps of(Material m) {
        if (cache.containsKey(m)) return cache.get(m);
        MaterialProps p = classify(m);
        cache.put(m, p);
        return p;
    }

    private MaterialProps classify(Material m) {
        if (m.isAir() || !m.isBlock() || !m.isSolid()) return null;
        String n = m.name();
        MaterialProps p;

        if (n.endsWith("_CONCRETE_POWDER") || Tag.SAND.isTagged(m) || n.equals("GRAVEL")) p = LOOSE;
        else if (n.endsWith("_CONCRETE")) p = CONCRETE;
        else if (n.contains("GLASS")) p = GLASS;
        else if (n.contains("OBSIDIAN")) p = OBSIDIAN;
        else if (Tag.LEAVES.isTagged(m)) p = LEAVES;
        else if (Tag.WOOL.isTagged(m)) p = SOFT;
        else if (Tag.ICE.isTagged(m) || n.equals("SNOW_BLOCK")) p = ICE;
        else if (Tag.LOGS.isTagged(m) || Tag.PLANKS.isTagged(m) || Tag.WOODEN_SLABS.isTagged(m)
                || Tag.WOODEN_STAIRS.isTagged(m) || Tag.WOODEN_FENCES.isTagged(m)
                || n.contains("BAMBOO") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")) p = WOOD;
        else if (n.contains("BRICK") || n.contains("TERRACOTTA")) p = BRICK;
        else if (n.equals("DIRT") || n.equals("GRASS_BLOCK") || n.equals("PODZOL") || n.equals("MYCELIUM")
                || n.equals("COARSE_DIRT") || n.equals("ROOTED_DIRT") || n.equals("MUD") || n.equals("CLAY")) p = EARTH;
        else if (!n.endsWith("_ORE") && (n.equals("IRON_BLOCK") || n.equals("GOLD_BLOCK")
                || n.equals("NETHERITE_BLOCK") || n.contains("COPPER"))) p = METAL;
        else p = STONE;

        // 形状による質量補正 (強度は変えない簡易モデル)
        double volume = n.endsWith("_SLAB") ? 0.5
                : n.endsWith("_STAIRS") ? 0.75
                : (n.endsWith("_WALL") || n.endsWith("_FENCE")) ? 0.4
                : 1.0;
        return p.scaled(cfg.strengthScale, volume);
    }
}