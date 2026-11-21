package dev.aiplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** A* simple : marche, saute 1 bloc, descend jusqu'à 3 blocs, évite lave/feu/cactus. */
public final class Pathfinder {
    private Pathfinder() {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // ---------- tests de blocs ----------
    public static boolean passable(Level l, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = l.getBlockState(p);
        if (!s.getCollisionShape(l, p).isEmpty()) return false;
        if (s.getFluidState().is(FluidTags.LAVA)) return false;
        return !(s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.COBWEB)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE));
    }

    public static boolean solidFloor(Level l, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = l.getBlockState(p);
        if (s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE))
            return false;
        return !s.getCollisionShape(l, p).isEmpty();
    }

    public static boolean water(Level l, int x, int y, int z) {
        return l.getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.WATER);
    }

    public static boolean lava(Level l, int x, int y, int z) {
        return l.getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.LAVA);
    }

    /** Debout sur un sol solide, 2 blocs de hauteur libres. */
    public static boolean standable(Level l, int x, int y, int z) {
        return passable(l, x, y, z) && passable(l, x, y + 1, z) && solidFloor(l, x, y - 1, z);
    }

    public static boolean walkable(Level l, int x, int y, int z) {
        return passable(l, x, y, z) && passable(l, x, y + 1, z)
                && (solidFloor(l, x, y - 1, z) || water(l, x, y - 1, z) || water(l, x, y, z));
    }

    // ---------- A* ----------
    private static final class N {
        final int x, y, z;
        N parent;
        double g, f;
        N(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static double h(int x, int y, int z, BlockPos g) {
        return Math.abs(x - g.getX()) + Math.abs(z - g.getZ()) + Math.abs(y - g.getY());
    }

    private static void relax(PriorityQueue<N> open, Map<Long, Double> bestG, N cur,
                              int x, int y, int z, double cost, BlockPos goal) {
        double g = cur.g + cost;
        long k = key(x, y, z);
        Double old = bestG.get(k);
        if (old != null && old <= g) return;
        bestG.put(k, g);
        N n = new N(x, y, z);
        n.parent = cur;
        n.g = g;
        n.f = g + h(x, y, z, goal);
        open.add(n);
    }

    /** Renvoie la liste de cases (feet) à suivre, ou null si aucun progrès possible. Chemin partiel possible. */
    public static List<BlockPos> find(Level l, BlockPos start, BlockPos goal, double range, int maxNodes) {
        PriorityQueue<N> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Double> bestG = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        N s = new N(start.getX(), start.getY(), start.getZ());
        s.f = h(s.x, s.y, s.z, goal);
        open.add(s);
        bestG.put(key(s.x, s.y, s.z), 0.0);

        N best = s;
        double bestH = s.f;
        double r2 = range * range;
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            N cur = open.poll();
            long ck = key(cur.x, cur.y, cur.z);
            if (!closed.add(ck)) continue;
            expanded++;

            double dx = cur.x - goal.getX(), dz = cur.z - goal.getZ();
            if (dx * dx + dz * dz <= r2 && Math.abs(cur.y - goal.getY()) <= 2) return build(cur);

            double hh = h(cur.x, cur.y, cur.z, goal);
            if (hh < bestH) { bestH = hh; best = cur; }

            for (int[] d : DIRS) {
                int nx = cur.x + d[0], nz = cur.z + d[1], y = cur.y;
                if (walkable(l, nx, y, nz)) {
                    relax(open, bestG, cur, nx, y, nz, 1.0, goal);
                } else if (walkable(l, nx, y + 1, nz) && passable(l, cur.x, y + 2, cur.z)) {
                    relax(open, bestG, cur, nx, y + 1, nz, 2.0, goal); // saut
                } else if (passable(l, nx, y, nz) && passable(l, nx, y + 1, nz)) {
                    for (int drop = 1; drop <= 3; drop++) {   // chute
                        int ny = y - drop;
                        if (!passable(l, nx, ny + 1, nz)) break;
                        if (walkable(l, nx, ny, nz)) {
                            relax(open, bestG, cur, nx, ny, nz, 1.0 + drop, goal);
                            break;
                        }
                    }
                }
            }
        }
        return best == s ? null : build(best);
    }

    private static List<BlockPos> build(N end) {
        ArrayList<BlockPos> out = new ArrayList<>();
        for (N n = end; n != null; n = n.parent) out.add(new BlockPos(n.x, n.y, n.z));
        Collections.reverse(out);
        return out;
    }
}
