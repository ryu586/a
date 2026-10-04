package com.example.structphysics;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * ブロック接続ネットワークの構造解析。
 *
 * 1. 変更点から連結なブロック群をグラフ化 (ノード=ブロック, エッジ=面で接する接続)
 * 2. 地盤(アンカー)からの最短経路(ダイクストラ)で「荷重の流れ方向」を決定
 * 3. 遠い順に荷重・モーメントを下流へ伝達し、各接続の 軸力/せん断/曲げ を算出
 * 4. 材料強度と比較 → 使用率(=1/安全率) → 損傷を蓄積 → 損傷1.0で結合破断
 * 5. 破断したら 2〜4 を再計算。アンカーに繋がらなくなった部分は崩落
 *
 * 再利用前提のインスタンス (メインスレッド専用)。
 */
final class StructureSolver {

    // 方向: 0:+X 1:-X 2:+Y 3:-Y 4:+Z 5:-Z
    static final int[] DX = {1, -1, 0, 0, 0, 0};
    static final int[] DY = {0, 0, 1, -1, 0, 0};
    static final int[] DZ = {0, 0, 0, 0, 1, -1};
    static final int[] OPP = {1, 0, 3, 2, 5, 4};
    static final String[] DIR = {"+X", "-X", "上", "下", "+Z", "-Z"};
    static final String[] MODE = {"-", "圧縮", "引張", "せん断", "曲げ"};

    private static final int COST_DOWN = 10, COST_SIDE = 15, COST_UP = 30;
    private static final int INF = Integer.MAX_VALUE;
    private static final double G = 9.81;
    private static final byte M_COMP = 1, M_TENS = 2, M_SHEAR = 3, M_BEND = 4;

    // ---- 座標のlongパック (x:26bit, z:26bit, y:12bit) ----
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    static int unpackX(long k) {
        return (int) (k >> 38);
    }

    static int unpackZ(long k) {
        return (int) (k << 26 >> 38);
    }

    static int unpackY(long k) {
        return (int) (k << 52 >> 52);
    }

    /** 解析結果 */
    static final class Result {
        final List<Long> unsupported = new ArrayList<>();   // 支えを失ったブロック
        final Map<Long, Float> cracks = new HashMap<>();    // 損傷の進んだブロック
        boolean stressed;                                   // 次tickも再解析が必要か
        int nodes;
    }

    private final Materials mats;
    private final Settings cfg;
    private final int cap;

    // ノード配列
    private final long[] key;
    private final int[] px, py, pz;
    private final MaterialProps[] prop;
    private final boolean[] anchor;
    private final int[] nbr;           // [i*6+d] = 隣接ノードindex or -1
    private final float[] edgeDmg;     // [i*3+axis] = 正方向エッジの損傷 (1.0で破断)
    private final int[] dist, order;
    private final double[] load, mx, mz;
    // エッジ配列 (荷重を渡す向き: i -> nbr[i*6+d])
    private final double[] eF, eM, eU;
    private final byte[] eMode;
    private final Map<Long, Integer> idx = new HashMap<>();
    private final double[] w = new double[6];

    private World world;
    private int minY, maxY;
    private int n, orderLen;
    private boolean stressed;

    StructureSolver(Materials mats, Settings cfg) {
        this.mats = mats;
        this.cfg = cfg;
        this.cap = cfg.maxNodes;
        key = new long[cap];
        px = new int[cap];
        py = new int[cap];
        pz = new int[cap];
        prop = new MaterialProps[cap];
        anchor = new boolean[cap];
        nbr = new int[cap * 6];
        edgeDmg = new float[cap * 3];
        dist = new int[cap];
        order = new int[cap];
        load = new double[cap];
        mx = new double[cap];
        mz = new double[cap];
        eF = new double[cap * 6];
        eM = new double[cap * 6];
        eU = new double[cap * 6];
        eMode = new byte[cap * 6];
    }

    Set<Long> coveredKeys() {
        return idx.keySet();
    }

    // =====================================================================
    // 実行
    // =====================================================================
    Result run(World world, long seed, boolean dryRun, Map<Long, float[]> store) {
        this.world = world;
        this.minY = world.getMinHeight();
        this.maxY = world.getMaxHeight();
        n = 0;
        idx.clear();
        build(seed, store);

        Result r = new Result();
        r.nodes = n;
        if (n == 0) return r;

        boolean broke = false;
        if (dryRun) {
            distances();
            loads();
            evaluate(0);
        } else {
            int iter = 0;
            do {
                distances();                                   // ③ 荷重経路
                loads();                                       // ② 荷重計算
                broke = evaluate(iter == 0 ? 1 : 2);           // ④⑤⑥ 応力→強度比較→破断
            } while (broke && ++iter < cfg.maxIterations);     // ⑦ 破断したら再計算
            if (broke) distances();                            // 最終的な連結状態
            writeBack(store);
        }

        r.stressed = stressed || broke;
        for (int i = 0; i < n; i++) {
            if (!anchor[i] && dist[i] == INF) {                // ⑧ 支えを失った → 崩落
                r.unsupported.add(key[i]);
            } else {
                float c = nodeDamage(i);
                if (c >= 0.1f) r.cracks.put(key[i], c);
            }
        }
        return r;
    }

    // =====================================================================
    // グラフ構築 (BFS)。地盤ブロックはそれ以上広げない。
    // 範囲外・上限超過・未ロードの固体は「さらに外で支えられている」とみなして地盤扱い。
    // =====================================================================
    private void build(long seed, Map<Long, float[]> store) {
        int sx = unpackX(seed), sy = unpackY(seed), sz = unpackZ(seed);
        Material st = world.getType(sx, sy, sz);
        MaterialProps sp = mats.of(st);
        if (sp == null) return;
        idx.put(seed, add(seed, sx, sy, sz, st, sp, store));
        int r = cfg.radius;

        for (int h = 0; h < n; h++) {
            if (anchor[h]) continue;
            for (int d = 0; d < 6; d++) {
                int nx = px[h] + DX[d], ny = py[h] + DY[d], nz = pz[h] + DZ[d];
                if (ny < minY || ny >= maxY) continue;
                if (!world.isChunkLoaded(nx >> 4, nz >> 4)) {
                    anchor[h] = true;
                    continue;
                }
                Material t = world.getType(nx, ny, nz);
                MaterialProps p = mats.of(t);
                if (p == null) continue;
                long k = pack(nx, ny, nz);
                Integer j = idx.get(k);
                if (j == null) {
                    boolean outside = Math.abs(nx - sx) > r || Math.abs(ny - sy) > r || Math.abs(nz - sz) > r;
                    if (outside || n >= cap) {
                        anchor[h] = true;
                        continue;
                    }
                    j = add(k, nx, ny, nz, t, p, store);
                    idx.put(k, j);
                }
                nbr[h * 6 + d] = j;
                nbr[j * 6 + OPP[d]] = h;
            }
        }
    }

    private int add(long k, int x, int y, int z, Material t, MaterialProps p, Map<Long, float[]> store) {
        int i = n++;
        key[i] = k;
        px[i] = x;
        py[i] = y;
        pz[i] = z;
        prop[i] = p;
        anchor[i] = mats.isAnchor(t) || y <= minY;
        Arrays.fill(nbr, i * 6, i * 6 + 6, -1);
        float[] dm = store.get(k);
        edgeDmg[i * 3] = dm == null ? 0f : dm[0];
        edgeDmg[i * 3 + 1] = dm == null ? 0f : dm[1];
        edgeDmg[i * 3 + 2] = dm == null ? 0f : dm[2];
        return i;
    }

    // ---- エッジの損傷スロット (正方向エッジは自ノード、負方向は隣ノードが所有) ----
    private int slot(int i, int d) {
        int o = (d & 1) == 0 ? i : nbr[i * 6 + d];
        return o * 3 + (d >> 1);
    }

    private boolean broken(int i, int d) {
        return edgeDmg[slot(i, d)] >= 1f;
    }

    // =====================================================================
    // 荷重経路: 地盤からのダイクストラ。下へ流す > 横 > 上(吊り) の順にコストを高くする
    // =====================================================================
    private void distances() {
        Arrays.fill(dist, 0, n, INF);
        orderLen = 0;
        PriorityQueue<Long> pq = new PriorityQueue<>();
        for (int i = 0; i < n; i++) {
            if (anchor[i]) {
                dist[i] = 0;
                pq.add((long) i);
            }
        }
        while (!pq.isEmpty()) {
            long top = pq.poll();
            int i = (int) (top & 0xFFFFFFFFL);
            int di = (int) (top >>> 32);
            if (di != dist[i]) continue;
            order[orderLen++] = i;
            for (int d = 0; d < 6; d++) {
                int j = nbr[i * 6 + d];
                if (j < 0 || broken(i, d)) continue;
                // j から i へ荷重が流れる向きのコスト (j は i の d 方向にいる)
                int cost = DY[d] == 1 ? COST_DOWN : DY[d] == -1 ? COST_UP : COST_SIDE;
                int nd = di + cost;
                if (nd < dist[j]) {
                    dist[j] = nd;
                    pq.add(((long) nd << 32) | j);
                }
            }
        }
    }

    // =====================================================================
    // 荷重の伝達。遠いノードから順に、より地盤に近い隣接ノードへ分配する。
    // 分配比 = 接続の剛性(ヤング率の調和平均) / 経路コスト
    // 同時に「荷重の水平方向の偏り(1次モーメント)」も運んで曲げモーメントを得る。
    // =====================================================================
    private void loads() {
        Arrays.fill(eF, 0, n * 6, 0.0);
        Arrays.fill(eM, 0, n * 6, 0.0);
        Arrays.fill(eU, 0, n * 6, 0.0);
        Arrays.fill(eMode, 0, n * 6, (byte) 0);
        for (int i = 0; i < n; i++) {
            load[i] = prop[i].mass() * G * cfg.gravityScale;
            mx[i] = 0;
            mz[i] = 0;
        }
        for (int o = orderLen - 1; o >= 0; o--) {
            int i = order[o];
            if (anchor[i]) continue;
            double wsum = 0;
            for (int d = 0; d < 6; d++) {
                w[d] = 0;
                int j = nbr[i * 6 + d];
                if (j < 0 || broken(i, d) || dist[j] >= dist[i]) continue;
                double a = prop[i].youngModulus(), b = prop[j].youngModulus();
                double e = 2 * a * b / (a + b);
                double cost = DY[d] == -1 ? COST_DOWN : DY[d] == 1 ? COST_UP : COST_SIDE;
                w[d] = e / cost;
                wsum += w[d];
            }
            if (wsum <= 0) continue;
            for (int d = 0; d < 6; d++) {
                if (w[d] <= 0) continue;
                int j = nbr[i * 6 + d];
                double f = w[d] / wsum;
                double F = f * load[i];
                double dxh = px[i] - px[j], dzh = pz[i] - pz[j];
                // 接続面(中点)まわりのモーメント
                double mxm = f * mx[i] + F * dxh * 0.5;
                double mzm = f * mz[i] + F * dzh * 0.5;
                double M = DY[d] != 0 ? Math.hypot(mxm, mzm)
                        : DX[d] != 0 ? Math.abs(mxm) : Math.abs(mzm);
                eF[i * 6 + d] = F;
                eM[i * 6 + d] = M;
                // 下流ノードへ
                load[j] += F;
                mx[j] += f * mx[i] + F * dxh;
                mz[j] += f * mz[i] + F * dzh;
            }
        }
    }

    // =====================================================================
    // 各接続の応力評価。
    //   荷重種別: 下向き=圧縮 / 上向き(吊り)=引張 / 横向き=せん断
    //   曲げ    : 断面 1m x 1m → 曲げ応力 = 6M / (1m^3)
    //   使用率 u = max( 軸応力/強度 + 曲げ応力/曲げ強度 , せん断応力/せん断強度 )   (安全率 = 1/u)
    // mode: 0=読み取りのみ / 1=損傷を蓄積 / 2=即時破断のみ判定
    // =====================================================================
    private boolean evaluate(int mode) {
        boolean broke = false;
        stressed = false;
        double limit = 1.0 / cfg.minSafetyFactor;
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < 6; d++) {
                double F = eF[i * 6 + d];
                if (F <= 0) continue;
                int j = nbr[i * 6 + d];
                MaterialProps a = prop[i], b = prop[j];
                double sc = Math.max(1, Math.min(a.compressive(), b.compressive()));
                double st = Math.max(1, Math.min(a.tensile(), b.tensile()));
                double ss = Math.max(1, Math.min(a.shear(), b.shear()));
                double sb = Math.max(1, Math.min(a.bending(), b.bending()));

                int s = slot(i, d);
                double eff = Math.max(0.02, 1.0 - edgeDmg[s]);   // 損傷で有効強度が低下

                double uC = 0, uT = 0, uS = 0;
                if (DY[d] == -1) uC = F / (sc * eff);            // 下の支持へ → 圧縮
                else if (DY[d] == 1) uT = F / (st * eff);        // 上から吊られている → 引張
                else uS = F / (ss * eff);                        // 横 → せん断
                double uB = 6.0 * eM[i * 6 + d] / (sb * eff);    // 曲げ

                double u = Math.max(uC + uT + uB, uS);
                byte md = M_BEND;
                double top = uB;
                if (uC > top) { top = uC; md = M_COMP; }
                if (uT > top) { top = uT; md = M_TENS; }
                if (uS > top) { md = M_SHEAR; }
                eU[i * 6 + d] = u;
                eMode[i * 6 + d] = md;

                double ratio = u / limit;
                if (ratio > 1.0) {
                    stressed = true;
                    if (mode != 0) {
                        if (ratio >= cfg.instantRatio) {
                            edgeDmg[s] = 1f;                     // 脆性破壊
                            broke = true;
                        } else if (mode == 1) {
                            edgeDmg[s] = (float) Math.min(1.0,
                                    edgeDmg[s] + cfg.damageRate * Math.min(ratio - 1.0, 4.0));
                            if (edgeDmg[s] >= 1f) broke = true;
                        }
                    }
                }
            }
        }
        return broke;
    }

    private float nodeDamage(int i) {
        float m = 0f;
        for (int d = 0; d < 6; d++) {
            if (nbr[i * 6 + d] < 0) continue;
            float v = edgeDmg[slot(i, d)];
            if (v < 1f && v > m) m = v;
        }
        return m;
    }

    private void writeBack(Map<Long, float[]> store) {
        for (int i = 0; i < n; i++) {
            float[] arr = store.get(key[i]);
            for (int a = 0; a < 3; a++) {
                float v = edgeDmg[i * 3 + a];
                if (v > 0f) {
                    if (arr == null) {
                        arr = new float[3];
                        store.put(key[i], arr);
                    }
                    arr[a] = v;
                }
            }
        }
    }

    // =====================================================================
    // /sp inspect 用: 直前の dry-run 結果を人間可読に
    // =====================================================================
    List<String> describe(long k) {
        List<String> out = new ArrayList<>();
        Integer ii = idx.get(k);
        if (ii == null) {
            out.add("§7このブロックは構造解析の対象外です");
            return out;
        }
        int i = ii;
        String state = anchor[i] ? " §a[支持体]" : dist[i] == INF ? " §c[支持なし→崩落]" : "";
        out.add(String.format("§6累積荷重 §f%.1f kN §7(自重 %.1f kN)%s",
                load[i] / 1000, prop[i].mass() * G * cfg.gravityScale / 1000, state));
        boolean any = false;
        for (int d = 0; d < 6; d++) {
            double F = eF[i * 6 + d];
            if (F <= 0) continue;
            any = true;
            double u = eU[i * 6 + d];
            out.add(String.format("§7 → %s 接続: %.1f kN / %s / 使用率 %.0f%% / 安全率 %.2f / 損傷 %.0f%%",
                    DIR[d], F / 1000, MODE[eMode[i * 6 + d]], u * 100, u > 0 ? 1 / u : 999.0,
                    edgeDmg[slot(i, d)] * 100));
        }
        if (!any && !anchor[i]) out.add("§7 荷重を受け渡す接続はありません");
        return out;
    }
}