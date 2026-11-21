package dev.aiplayer;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Attaque utilisée dans les modes de combat (/ai defend, /ai kill ...) à la place de {@link Actions.Attack}.
 * Deux différences : (1) les joueurs sont filtrés par {@link Controller#mayAttackPlayer} (la cible de /ai kill est autorisée
 * même si allowAttackPlayers=false) ; (2) quand max_ticks est atteint l'action est DONE au lieu de FAILED, pour que le
 * reste du plan continue (ex. un "say" entre deux attaques en mode chat).
 */
public class ModeAttack extends Actions.Attack {
    ModeAttack(int id, int maxTicks) { super(id, maxTicks); }

    @Override
    public Status tick(Controller c) {
        LocalPlayer p = c.player();
        Entity e = c.level().getEntity(id);
        if (e == null || !e.isAlive()) return Status.DONE; // mort ou disparu
        if (e instanceof Player pl && !c.mayAttackPlayer(pl)) {
            return fail("attacking this player is not allowed: no player asked you to attack him in the chat");
        }
        if (++ticks > maxTicks) return Status.DONE;
        if (ticks == 1) c.selectBestWeapon();

        Vec3 aim = e.position().add(0, e.getBbHeight() * 0.6, 0);
        double dist = p.getEyePosition().distanceTo(aim);
        if (dist > 2.9) {
            if (mv == null) mv = new MoveAction(id, 0, 0, 0, 1.5, true, Integer.MAX_VALUE, Integer.MAX_VALUE);
            if (mv.tick(c) == Status.FAILED) return fail("cannot reach target: " + mv.failReason);
        } else {
            boolean aligned = c.lookAtPoint(aim, 45f);
            if (aligned && p.getAttackStrengthScale(0.5f) >= 0.95f) Compat.attack(c.mc, p, e);
        }
        return Status.RUNNING;
    }
}
