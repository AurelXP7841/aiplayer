package dev.aiplayer;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/** Déplacement par pathfinding (coordonnées ou entité), avec saut automatique et détection de blocage. */
public class MoveAction extends Action {
    private double tx, ty, tz;
    private final int entityId;
    private final double range;
    private final boolean sprint;
    private final int maxTicks;
    private final int followTicks; // >0 : on suit l'entité jusqu'à écoulement de ce délai

    private List<BlockPos> path;
    private int idx, replan, failedPlans, ticks, stuck;
    private Vec3 lastPos;

    public MoveAction(int entityId, double x, double y, double z, double range,
                      boolean sprint, int maxTicks, int followTicks) {
        this.entityId = entityId; this.tx = x; this.ty = y; this.tz = z;
        this.range = range; this.sprint = sprint; this.maxTicks = maxTicks; this.followTicks = followTicks;
    }

    @Override
    public Status tick(Controller c) {
        LocalPlayer p = c.player();
        Level l = c.level();
        ticks++;

        if (entityId >= 0) {
            Entity e = l.getEntity(entityId);
            if (e == null || e.isRemoved()) return fail("entity " + entityId + " not found");
            tx = e.getX(); ty = e.getY(); tz = e.getZ();
        }
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double h = Math.hypot(dx, dz);
        boolean arrived = h <= Math.max(range, 0.5) && Math.abs(ty - p.getY()) <= 2.0;

        if (followTicks > 0) {
            if (ticks >= followTicks) return Status.DONE;
            if (arrived) return Status.RUNNING; // on attend près de la cible
        } else if (arrived) {
            return Status.DONE;
        }
        if (ticks > maxTicks) return fail("timeout");

        if (path == null || --replan <= 0) plan(p, l);
        if (path == null) return failedPlans >= 3 ? fail("no path found") : Status.RUNNING;

        // détection de blocage toutes les 20 ticks
        if (ticks % 20 == 0) {
            if (lastPos != null && p.position().distanceTo(lastPos) < 0.2) stuck++; else stuck = 0;
            lastPos = p.position();
            if (stuck >= 1) { c.jump = true; replan = 0; }
            if (stuck >= 4) return fail("stuck (blocked by something)");
        }

        // avance au prochain nœud
        while (idx < path.size()) {
            BlockPos n = path.get(idx);
            double ddx = n.getX() + 0.5 - p.getX(), ddz = n.getZ() + 0.5 - p.getZ();
            if (Math.hypot(ddx, ddz) < 0.55 && Math.abs(p.getY() - n.getY()) < 1.2) idx++; else break;
        }
        if (idx >= path.size()) { replan = 0; return Status.RUNNING; }

        BlockPos n = path.get(idx);
        double ddx = n.getX() + 0.5 - p.getX(), ddz = n.getZ() + 0.5 - p.getZ();
        double hd = Math.hypot(ddx, ddz);
        boolean facing = c.faceYaw(ddx, ddz);
        if (facing) {
            c.forward = true;
            c.sprint = sprint && !c.sneak && p.getFoodData().getFoodLevel() > 6;
        }
        if (p.onGround() && ((n.getY() > p.getBlockY() && hd < 1.6) || p.horizontalCollision)) c.jump = true;
        if (p.isInWater() && n.getY() >= p.getBlockY()) c.jump = true;
        return Status.RUNNING;
    }

    private void plan(LocalPlayer p, Level l) {
        BlockPos start = p.blockPosition();
        BlockPos goal = BlockPos.containing(tx, ty, tz);
        path = Pathfinder.find(l, start, goal, range, 3000);
        idx = 1;
        replan = entityId >= 0 ? 15 : 40;
        if (path == null || path.size() < 2) { path = null; failedPlans++; replan = 10; }
        else failedPlans = 0;
    }

    @Override
    public String describe() {
        return entityId >= 0
                ? (followTicks > 0 ? "follow entity " + entityId : "move_to entity " + entityId)
                : String.format(Locale.ROOT, "move_to (%.0f,%.0f,%.0f)", Math.floor(tx), ty, Math.floor(tz));
    }
}
