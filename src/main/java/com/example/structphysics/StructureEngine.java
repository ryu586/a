package com.example.structphysics;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.example.structphysics.StructureSolver.DX;
import static com.example.structphysics.StructureSolver.DY;
import static com.example.structphysics.StructureSolver.DZ;
import static com.example.structphysics.StructureSolver.pack;
import static com.example.structphysics.StructureSolver.unpackX;
import static com.example.structphysics.StructureSolver.unpackY;
import static com.example.structphysics.StructureSolver.unpackZ;

/** 毎tick動くメインループ。変更されたブロック周辺だけを解析する。 */
final class StructureEngine {

    private record Pending(long key, Material type) {}

    private static final class WorldState {
        final World world;
        final LinkedHashSet<Long> dirty = new LinkedHashSet<>();    // 変更があり未解析の座標
        final LinkedHashSet<Long> active = new LinkedHashSet<>();   // 応力超過中 → 毎tick再解析
        final Map<Long, float[]> damage = new HashMap<>();          // エッジ損傷 [+X,+Y,+Z]
        final ArrayDeque<Pending> collapse = new ArrayDeque<>();
        final Set<Long> pendingKeys = new HashSet<>();

        WorldState(World world) {
            this.world = world;
        }
    }

    private final Settings cfg;
    private final Materials mats;
    private final StructureSolver solver;
    private final Map<UUID, WorldState> states = new HashMap<>();
    private int tickCounter;

    StructureEngine(Settings cfg) {
        this.cfg = cfg;
        this.mats = new Materials(cfg);
        this.solver = new StructureSolver(mats, cfg);
    }

    private WorldState state(World w) {
        return states.computeIfAbsent(w.getUID(), id -> new WorldState(w));
    }

    void unload(World w) {
        states.remove(w.getUID());
    }

    // =====================================================================
    // イベント側から呼ぶ
    // =====================================================================

    /** ブロックが置かれた/壊れた/動いた: 周辺の損傷データを捨てて再解析キューへ */
    void markChanged(Block b) {
        markChanged(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    void markChanged(World w, int x, int y, int z) {
        WorldState ws = state(w);
        ws.damage.remove(pack(x, y, z));
        for (int a = 0; a < 3; a++) {   // 負方向の隣が持つ「+方向エッジ」も消す
            long nk = pack(x - (a == 0 ? 1 : 0), y - (a == 1 ? 1 : 0), z - (a == 2 ? 1 : 0));
            float[] arr = ws.damage.get(nk);
            if (arr != null) arr[a] = 0f;
        }
        ws.dirty.add(pack(x, y, z));
        for (int d = 0; d < 6; d++) ws.dirty.add(pack(x + DX[d], y + DY[d], z + DZ[d]));
    }

    /** 損傷を保ったまま再解析だけ依頼 */
    void markDirty(Block b) {
        state(b.getWorld()).dirty.add(pack(b.getX(), b.getY(), b.getZ()));
    }

    List<String> inspect(Block b) {
        WorldState ws = state(b.getWorld());
        long k = pack(b.getX(), b.getY(), b.getZ());
        solver.run(b.getWorld(), k, true, ws.damage);
        return solver.describe(k);
    }

    // =====================================================================
    // 毎tick
    // =====================================================================
    void tick() {
        tickCounter++;
        for (WorldState ws : states.values()) {
            drainCollapse(ws);
            analyse(ws);
        }
    }

    private void analyse(WorldState ws) {
        if (ws.dirty.isEmpty() && ws.active.isEmpty()) return;

        List<Long> seeds = new ArrayList<>(ws.active.size() + ws.dirty.size());
        seeds.addAll(ws.active);
        seeds.addAll(ws.dirty);
        ws.active.clear();
        ws.dirty.clear();

        Set<Long> covered = new HashSet<>();
        long deadline = System.nanoTime() + cfg.budgetNanos;
        int done = 0;
        int i = 0;
        for (; i < seeds.size(); i++) {
            if (done >= cfg.maxAnalysesPerTick || System.nanoTime() > deadline) break;
            long seed = seeds.get(i);
            if (covered.contains(seed)) continue;
            if (!isStructural(ws.world, seed)) continue;
            done++;
            StructureSolver.Result r = solver.run(ws.world, seed, false, ws.damage);
            covered.addAll(solver.coveredKeys());
            apply(ws, seed, r);
        }
        for (; i < seeds.size(); i++) ws.dirty.add(seeds.get(i));   // 予算超過分は次tickへ
    }

    private boolean isStructural(World w, long k) {
        int x = unpackX(k), y = unpackY(k), z = unpackZ(k);
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return false;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
        return mats.of(w.getType(x, y, z)) != null;
    }

    private void apply(WorldState ws, long seed, StructureSolver.Result r) {
        if (!r.unsupported.isEmpty()) {
            r.unsupported.sort(Comparator.comparingInt(StructureSolver::unpackY));
            for (long k : r.unsupported) {
                if (ws.pendingKeys.add(k)) {
                    ws.collapse.add(new Pending(k, ws.world.getType(unpackX(k), unpackY(k), unpackZ(k))));
                }
            }
        }
        if (r.stressed) ws.active.add(seed);
        if (cfg.visualCracks && !r.cracks.isEmpty()) showCracks(ws, r.cracks);
    }

    // =====================================================================
    // 崩落: 支えを失ったブロックを FallingBlock に変える (1tickあたり上限あり)
    // =====================================================================
    private void drainCollapse(WorldState ws) {
        World w = ws.world;
        int budget = cfg.maxCollapsePerTick;
        int spawned = 0;
        Location last = null;
        while (budget > 0 && !ws.collapse.isEmpty()) {
            Pending p = ws.collapse.poll();
            ws.pendingKeys.remove(p.key());
            int x = unpackX(p.key()), y = unpackY(p.key()), z = unpackZ(p.key());
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            if (b.getType() != p.type()) continue;   // その間に変化した
            budget--;

            markChanged(w, x, y, z);                  // 損傷データ破棄 + 周囲を再解析へ
            BlockData data = b.getBlockData();
            Location loc = new Location(w, x + 0.5, y, z + 0.5);
            last = loc;

            if (b.getState(false) instanceof TileState) {   // チェスト等は落下させず通常破壊(中身ドロップ)
                b.breakNaturally();
                continue;
            }
            b.setType(Material.AIR, false);
            FallingBlock fb = w.spawnFallingBlock(loc, data);
            fb.setDropItem(false);
            fb.setHurtEntities(true);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            fb.setVelocity(new Vector(rnd.nextDouble(-0.05, 0.05), 0, rnd.nextDouble(-0.05, 0.05)));
            if (spawned++ % 6 == 0) {
                w.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.5, 0), 8, 0.3, 0.3, 0.3, 0, data);
            }
        }
        if (last != null) {
            w.playSound(last, Sound.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1.5f, 0.6f);
        }
    }

    // =====================================================================
    // 損傷の可視化 (ひび割れ表示 + きしみ音)
    // =====================================================================
    private void showCracks(WorldState ws, Map<Long, Float> cracks) {
        World w = ws.world;
        List<Player> players = w.getPlayers();
        if (players.isEmpty()) return;
        int sounds = 0;
        for (Map.Entry<Long, Float> e : cracks.entrySet()) {
            long k = e.getKey();
            Location loc = new Location(w, unpackX(k), unpackY(k), unpackZ(k));
            float prog = Math.min(1f, e.getValue());
            for (Player pl : players) {
                if (pl.getLocation().distanceSquared(loc) < 64 * 64) pl.sendBlockDamage(loc, prog);
            }
            if (tickCounter % 10 == 0 && prog > 0.3f && sounds++ < 3) {
                w.playSound(loc, Sound.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 0.8f, 0.5f);
            }
        }
    }
}