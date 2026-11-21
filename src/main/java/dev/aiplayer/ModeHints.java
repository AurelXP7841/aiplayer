package dev.aiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Faits CALCULÉS par le mod pour les modes de combat (section "MISSION HINTS" de l'entrée envoyée à l'IA).
 * Un petit modèle ne doit pas deviner où est sa cible : on lui donne l'id, la distance, la dernière position connue.
 */
public final class ModeHints {
    private ModeHints() {}

    public static String build(Controller c) {
        Minecraft mc = c.mc;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (c.mode == Controller.Mode.NORMAL || p == null || level == null) return "";

        StringBuilder sb = new StringBuilder("=== MISSION HINTS (computed by the game mod, reliable) ===\n");
        List<Entity> ents = new ArrayList<>(level.getEntities(p, p.getBoundingBox().inflate(Config.INSTANCE.scanRadius), e -> true));
        ents.sort(Comparator.comparingDouble(p::distanceToSqr));
        switch (c.mode) {
            case DEFEND -> defend(sb, c, p, ents);
            case KILL -> kill(sb, c, p, level);
            default -> killAll(sb, p, level, ents);
        }
        return sb.toString();
    }

    private static double dist3(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String type(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    private static void defend(StringBuilder sb, Controller c, LocalPlayer p, List<Entity> ents) {
        BlockPos b = c.base != null ? c.base : p.blockPosition();
        int radius = Math.max(4, Config.INSTANCE.defendRadius);
        double bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
        double mine = dist3(p.getX() - bx, p.getY() - b.getY(), p.getZ() - bz);
        sb.append(String.format(Locale.ROOT, "base_center=(%d,%d,%d) defend_radius=%d your_distance_from_base=%.0f%s\n",
                b.getX(), b.getY(), b.getZ(), radius, mine,
                mine > radius ? " -> YOU ARE OUTSIDE THE BASE AREA: go back with move_to base_center" : ""));
        int n = 0;
        for (Entity e : ents) {
            if (!(e instanceof Enemy) || !e.isAlive()) continue;
            double db = dist3(e.getX() - bx, e.getY() - b.getY(), e.getZ() - bz);
            if (db > radius) continue;
            if (n == 0) sb.append("threats (hostile monsters inside the defense radius, nearest to you first):\n");
            if (n++ >= 6) break;
            sb.append(String.format(Locale.ROOT, "id=%d %s dist_to_you=%.1f dist_to_base=%.0f\n",
                    e.getId(), type(e), p.distanceTo(e), db));
        }
        if (n == 0) sb.append("threats: none -> stand guard at the base (wait / short patrol)\n");
    }

    private static void kill(StringBuilder sb, Controller c, LocalPlayer p, ClientLevel level) {
        String name = c.killTarget;
        Player target = null;
        for (Player pl : level.players()) {
            if (pl != p && pl.getName().getString().equalsIgnoreCase(name)) { target = pl; break; }
        }
        if (target != null && target.isAlive()) {
            c.lastTargetPos = target.blockPosition();
            c.lastTargetMs = System.currentTimeMillis();
            sb.append(String.format(Locale.ROOT, "kill target %s: id=%d dist=%.1f pos=(%d,%d,%d) hp=%.0f -> attack id=%d\n",
                    target.getName().getString(), target.getId(), p.distanceTo(target),
                    target.getBlockX(), target.getBlockY(), target.getBlockZ(), target.getHealth(), target.getId()));
        } else if (c.lastTargetPos != null) {
            long sec = (System.currentTimeMillis() - c.lastTargetMs) / 1000;
            sb.append(String.format(Locale.ROOT,
                    "kill target %s: NOT visible now (dead, out of range, or not loaded). Last seen at (%d,%d,%d) about %d s ago -> move_to there (sprint), then explore around.\n",
                    name, c.lastTargetPos.getX(), c.lastTargetPos.getY(), c.lastTargetPos.getZ(), sec));
        } else {
            sb.append("kill target ").append(name).append(": not seen yet and not loaded around you. Explore: move_to about 40 blocks away in one direction (sprint) and check again. He may also be offline.\n");
        }
    }

    private static void killAll(StringBuilder sb, LocalPlayer p, ClientLevel level, List<Entity> ents) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player pl : level.players()) {
            if (pl == p || !pl.isAlive()) continue;
            double d = p.distanceToSqr(pl);
            if (d < best) { best = d; nearest = pl; }
        }
        if (nearest != null) {
            sb.append(String.format(Locale.ROOT, "nearest player %s: id=%d dist=%.1f pos=(%d,%d,%d) hp=%.0f -> attack id=%d\n",
                    nearest.getName().getString(), nearest.getId(), p.distanceTo(nearest),
                    nearest.getBlockX(), nearest.getBlockY(), nearest.getBlockZ(), nearest.getHealth(), nearest.getId()));
        } else {
            sb.append("nearest player: none loaded around you -> explore (move_to about 40 blocks away, sprint)\n");
        }
        for (Entity e : ents) {
            if (e instanceof LivingEntity le && !(e instanceof Player) && le.isAlive()) {
                sb.append(String.format(Locale.ROOT, "nearest other creature: id=%d %s dist=%.1f hp=%.0f\n",
                        e.getId(), type(e), p.distanceTo(e), le.getHealth()));
                break;
            }
        }
    }
}
