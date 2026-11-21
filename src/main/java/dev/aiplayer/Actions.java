package dev.aiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Toutes les actions + le parseur JSON -> Action. */
public final class Actions {
    private Actions() {}

    // ======================= PARSEUR =======================
    /** Liste affichée à l'IA quand elle se trompe de type d'action. */
    public static final String TYPES = "move_to, follow, look_at, sneak, jump, mine, collect, place, use_on, attack, "
            + "interact, equip, use_item, drop, craft, open_inventory, close_screen, click_slot, say, wait";

    /** Les petits modèles inventent des synonymes : on les accepte plutôt que d'échouer. */
    private static String canonicalType(String t) {
        switch (t) {
            case "walk": case "go_to": case "move": case "navigate": case "goto": return "move_to";
            case "dig": case "break": case "break_block": case "mine_block": return "mine";
            case "gather": case "chop": case "harvest": case "collect_block": return "collect";
            case "fight": case "kill": case "hit": return "attack";
            case "eat": case "drink": case "use": return "use_item";
            case "chat": case "talk": case "speak": case "message": return "say";
            case "craft_item": case "crafting": return "craft";
            case "place_block": return "place";
            case "right_click": case "click_block": case "open": return "use_on";
            case "idle": case "sleep": case "stop": return "wait";
            case "hold": case "select": case "equip_item": return "equip";
            case "inventory": return "open_inventory";
            case "close": return "close_screen";
            default: return t;
        }
    }

    /** Accepte "pos":[x,y,z] / "pos":{x,y,z} (et position/target/coords/at) en plus de x,y,z séparés ; "entity_id" -> "id". */
    private static void normalize(JsonObject o) {
        for (String k : new String[]{"pos", "position", "target", "coords", "coordinates", "at", "location"}) {
            if (!o.has(k) || o.has("x")) continue;
            JsonElement e = o.get(k);
            if (e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
                JsonArray a = e.getAsJsonArray();
                o.add("x", a.get(0)); o.add("y", a.get(1)); o.add("z", a.get(2));
            } else if (e.isJsonObject() && e.getAsJsonObject().has("x")) {
                JsonObject q = e.getAsJsonObject();
                o.add("x", q.get("x")); o.add("y", q.get("y")); o.add("z", q.get("z"));
            }
        }
        for (String k : new String[]{"entity_id", "target_id", "entity", "entityId"}) {
            if (!o.has("id") && o.has(k) && o.get(k).isJsonPrimitive()) o.add("id", o.get(k));
        }
    }

    public static Action parse(JsonObject o) {
        String t = canonicalType(str(o, "type", "").toLowerCase(Locale.ROOT).trim());
        normalize(o);
        switch (t) {
            case "move_to": case "goto":
                if (o.has("id"))
                    return new MoveAction(intv(o, "id", -1), 0, 0, 0, num(o, "range", 2.5), bool(o, "sprint", true), 1200, 0);
                return new MoveAction(-1, cx(req(o, "x")), req(o, "y"), cx(req(o, "z")),
                        num(o, "range", 1.0), bool(o, "sprint", true), 1200, 0);
            case "follow":
                return new MoveAction(intv(o, "id", -1), 0, 0, 0, num(o, "range", 3.0), bool(o, "sprint", true),
                        Integer.MAX_VALUE, Math.min(intv(o, "ticks", 200), 2400));
            case "look_at":
                return o.has("id") ? new Look(intv(o, "id", -1), null)
                        : new Look(-1, new Vec3(cx(req(o, "x")), req(o, "y") + 0.5, cx(req(o, "z"))));
            case "sneak": return new Sneak(bool(o, "on", true));
            case "jump": return new Jump();
            case "mine": return new Mine(bp(o));
            case "collect": {
                String b = str(o, "block", str(o, "item", ""));
                if (b.isBlank()) throw new IllegalArgumentException("missing 'block' (e.g. \"oak_log\")");
                return new Collect(b, Math.max(1, Math.min(intv(o, "count", 1), 64)),
                        Math.max(4, Math.min(intv(o, "radius", 24), 32)));
            }
            case "craft": return new Craft(parseGrid(o), Math.max(1, Math.min(intv(o, "count", 1), 16)));
            case "place": return new Place(bp(o), str(o, "item", ""));
            case "use_on": return new UseOn(bp(o), str(o, "item", ""));
            case "attack": return new Attack(intv(o, "id", -1), Math.min(intv(o, "max_ticks", 400), 1200));
            case "interact": return new Interact(intv(o, "id", -1));
            case "equip": return new Equip(str(o, "item", ""), str(o, "slot", "hand").equals("offhand"));
            case "use_item": return new UseItem(str(o, "item", ""), Math.min(intv(o, "ticks", 32), 200));
            case "drop": return new Drop(str(o, "item", ""), bool(o, "all", true));
            case "open_inventory": return new OpenInventory();
            case "close_screen": return new CloseScreen();
            case "click_slot": return new ClickSlot(intv(o, "slot", -1), intv(o, "button", 0), str(o, "mode", "pickup"));
            case "say": return new Say(str(o, "text", ""));
            case "wait": return new Wait(Math.min(intv(o, "ticks", 20), 600));
            default: throw new IllegalArgumentException("unknown action type '" + t + "'");
        }
    }

    private static String str(JsonObject o, String k, String d) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d; }
    private static double num(JsonObject o, String k, double d) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : d; }
    private static int intv(JsonObject o, String k, int d) { return (int) Math.round(num(o, k, d)); }
    private static boolean bool(JsonObject o, String k, boolean d) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : d; }
    private static double req(JsonObject o, String k) {
        if (!o.has(k)) throw new IllegalArgumentException("missing '" + k + "'");
        return o.get(k).getAsDouble();
    }
    /** entier de bloc -> centre du bloc */
    private static double cx(double v) { return v == Math.floor(v) ? v + 0.5 : v; }
    private static BlockPos bp(JsonObject o) {
        return new BlockPos((int) Math.floor(req(o, "x")), (int) Math.floor(req(o, "y")), (int) Math.floor(req(o, "z")));
    }

    // ======================= ACTIONS SIMPLES =======================
    public static class Wait extends Action {
        int left; Wait(int t) { left = t; }
        public Status tick(Controller c) { return --left <= 0 ? Status.DONE : Status.RUNNING; }
        public String describe() { return "wait"; }
    }

    public static class Say extends Action {
        final String text; Say(String t) { text = t; }
        public Status tick(Controller c) {
            String t = text.strip();
            if (t.startsWith("/")) return fail("commands are not allowed");
            if (!t.isEmpty()) Compat.sendChat(c.player(), t.substring(0, Math.min(t.length(), 250)));
            return Status.DONE;
        }
        public String describe() { return "say \"" + text + "\""; }
    }

    public static class Sneak extends Action {
        final boolean on; Sneak(boolean on) { this.on = on; }
        public Status tick(Controller c) { c.sneak = on; return Status.DONE; }
        public String describe() { return "sneak " + on; }
    }

    public static class Jump extends Action {
        public Status tick(Controller c) { c.jump = true; return Status.DONE; }
        public String describe() { return "jump"; }
    }

    public static class Look extends Action {
        final int id; final Vec3 pt; int ticks;
        Look(int id, Vec3 pt) { this.id = id; this.pt = pt; }
        public Status tick(Controller c) {
            Vec3 target = pt;
            if (id >= 0) {
                Entity e = c.level().getEntity(id);
                if (e == null) return fail("entity not found");
                target = e.position().add(0, e.getBbHeight() * 0.8, 0);
            }
            boolean ok = c.lookAtPoint(target, 35f);
            return (ok || ++ticks > 40) ? Status.DONE : Status.RUNNING;
        }
        public String describe() { return id >= 0 ? "look_at entity " + id : "look_at " + pt.x + "," + pt.y + "," + pt.z; }
    }

    // ======================= INVENTAIRE =======================
    public static class Equip extends Action {
        final String item; final boolean offhand;
        Equip(String item, boolean offhand) { this.item = item; this.offhand = offhand; }
        public Status tick(Controller c) {
            int idx = c.findItem(item);
            if (idx < 0) return fail("'" + item + "' not in inventory");
            c.equipIndex(idx, offhand);
            return Status.DONE;
        }
        public String describe() { return "equip " + item; }
    }

    public static class Drop extends Action {
        final String item; final boolean all;
        Drop(String item, boolean all) { this.item = item; this.all = all; }
        public Status tick(Controller c) {
            int idx = c.findItem(item);
            if (idx < 0) return fail("'" + item + "' not in inventory");
            LocalPlayer p = c.player();
            Compat.click(c.mc, p, p.inventoryMenu.containerId, Controller.menuSlot(idx), all ? 1 : 0, ClickType.THROW);
            return Status.DONE;
        }
        public String describe() { return "drop " + item; }
    }

    public static class OpenInventory extends Action {
        public boolean allowsScreen() { return true; }
        public Status tick(Controller c) { Compat.openInventory(c.mc, c.player()); return Status.DONE; }
        public String describe() { return "open_inventory"; }
    }

    public static class CloseScreen extends Action {
        public boolean allowsScreen() { return true; }
        public Status tick(Controller c) { Compat.closeContainerScreen(c.mc, c.player()); return Status.DONE; }
        public String describe() { return "close_screen"; }
    }

    public static class ClickSlot extends Action {
        final int slot, button; final String mode;
        ClickSlot(int slot, int button, String mode) { this.slot = slot; this.button = button; this.mode = mode; }
        public boolean allowsScreen() { return true; }
        public Status tick(Controller c) {
            LocalPlayer p = c.player();
            if (slot < 0 || slot >= p.containerMenu.slots.size()) return fail("invalid slot " + slot);
            ClickType type;
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "quick_move": type = ClickType.QUICK_MOVE; break;
                case "swap": type = ClickType.SWAP; break;
                case "throw": type = ClickType.THROW; break;
                case "pickup_all": type = ClickType.PICKUP_ALL; break;
                case "pickup": type = ClickType.PICKUP; break;
                default: return fail("unknown mode " + mode);
            }
            Compat.click(c.mc, p, p.containerMenu.containerId, slot, button, type);
            return Status.DONE;
        }
        public String describe() { return "click_slot " + slot + " " + mode; }
    }

    /**
     * Clic droit maintenu, en direct (Compat.startUse / releaseUse) :
     * manger/boire (fini tout seul), arc (relâché après "ticks"), bouclier (relâché après "ticks"), objet lancé (instantané).
     */
    public static class UseItem extends Action {
        final String item; int left; boolean equipped, started;
        UseItem(String item, int ticks) { this.item = item; this.left = ticks; }
        @Override public boolean allowsScreen() { return true; }
        public Status tick(Controller c) {
            LocalPlayer p = c.player();
            if (!equipped) {
                equipped = true;
                if (!item.isEmpty()) {
                    int idx = c.findItem(item);
                    if (idx < 0) return fail("'" + item + "' not in inventory");
                    c.equipIndex(idx, false);
                    return Status.RUNNING; // laisse 1 tick au jeu pour changer l'objet en main
                }
            }
            if (!started) {
                started = true;
                Compat.startUse(c.mc, p);
                if (!p.isUsingItem()) return Status.DONE; // objet instantané (perle, boule de neige...) ou non utilisable
                c.use = true;
                return Status.RUNNING;
            }
            if (!p.isUsingItem()) return Status.DONE;   // fini (repas terminé, etc.)
            c.use = true;
            if (--left <= 0) { Compat.releaseUse(c.mc, p); return Status.DONE; } // arc : on tire ; bouclier : on baisse
            return Status.RUNNING;
        }
        @Override public void stop(Controller c) {
            LocalPlayer p = c.player();
            if (started && p != null && p.isUsingItem()) Compat.releaseUse(c.mc, p);
        }
        public String describe() { return "use_item " + item; }
    }

    // ======================= COMBAT =======================
    public static class Attack extends Action {
        final int id, maxTicks; int ticks; MoveAction mv;
        Attack(int id, int maxTicks) { this.id = id; this.maxTicks = maxTicks; }
        public Status tick(Controller c) {
            LocalPlayer p = c.player();
            Entity e = c.level().getEntity(id);
            if (e == null || !e.isAlive()) return Status.DONE; // mort ou disparu
            if (e instanceof Player && !Config.INSTANCE.allowAttackPlayers) return fail("attacking players is disabled in config");
            if (++ticks > maxTicks) return fail("timeout, target still alive");
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
        public String describe() { return "attack entity " + id; }
    }

    public static class Interact extends Action {
        final int id; MoveAction mv; int ticks;
        Interact(int id) { this.id = id; }
        public Status tick(Controller c) {
            LocalPlayer p = c.player();
            Entity e = c.level().getEntity(id);
            if (e == null) return fail("entity not found");
            if (++ticks > 600) return fail("timeout");
            Vec3 pt = e.position().add(0, e.getBbHeight() * 0.6, 0);
            if (p.getEyePosition().distanceTo(pt) > 2.8) {
                if (mv == null) mv = new MoveAction(id, 0, 0, 0, 1.8, false, Integer.MAX_VALUE, Integer.MAX_VALUE);
                if (mv.tick(c) == Status.FAILED) return fail("cannot reach: " + mv.failReason);
                return Status.RUNNING;
            }
            if (!c.lookAtPoint(pt, 45f)) return Status.RUNNING;
            Compat.interact(c.mc, p, e);
            return Status.DONE;
        }
        public String describe() { return "interact entity " + id; }
    }

    // ======================= ACTIONS SUR BLOCS =======================
    /** Base : se rapproche jusqu'à portée d'un bloc. */
    abstract static class ReachAction extends Action {
        protected final BlockPos pos;
        protected int ticks;
        private MoveAction mv; private int tries;
        ReachAction(BlockPos pos) { this.pos = pos; }

        /** null si à portée, sinon RUNNING/FAILED. */
        protected Status approach(Controller c, double reach) {
            Vec3 center = Vec3.atCenterOf(pos);
            if (c.player().getEyePosition().distanceTo(center) <= reach) { mv = null; return null; }
            if (mv == null) {
                if (++tries > 4) return fail("cannot get within reach of the block");
                mv = new MoveAction(-1, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 2.5, false, 300, 0);
            }
            Status s = mv.tick(c);
            if (s == Status.FAILED) return fail("cannot approach: " + mv.failReason);
            if (s == Status.DONE) mv = null;
            return Status.RUNNING;
        }
        protected String where() { return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")"; }
    }

    public static class Mine extends ReachAction {
        boolean toolChosen;
        Mine(BlockPos pos) { super(pos); }
        public Status tick(Controller c) {
            Level l = c.level();
            if (++ticks > 800) return fail("timeout");
            BlockState st = l.getBlockState(pos);
            if (st.isAir()) return Status.DONE;
            if (!st.getFluidState().isEmpty() && st.getCollisionShape(l, pos).isEmpty()) return fail("cannot mine a fluid");
            if (st.getDestroySpeed(l, pos) < 0) return fail("unbreakable block");
            Status a = approach(c, 4.3);
            if (a != null) return a;

            Direction face = exposedFace(c, pos);
            if (face == null) return fail("block is not exposed; mine the blocks around it first");
            if (!toolChosen) { c.selectBestTool(st); toolChosen = true; }
            c.lookAtPoint(Vec3.atCenterOf(pos), 50f);
            Compat.mine(c.mc, c.player(), pos, face);
            return Status.RUNNING;
        }
        @Override public void stop(Controller c) { Compat.stopMine(c.mc); }
        public String describe() { return "mine " + where(); }
    }

    /** Face du bloc non obstruée, la plus proche des yeux du joueur. */
    static Direction exposedFace(Controller c, BlockPos pos) {
        Level l = c.level();
        Vec3 eye = c.player().getEyePosition();
        Direction best = null; double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!l.getBlockState(n).getCollisionShape(l, n).isEmpty()) continue;
            Vec3 f = Vec3.atCenterOf(pos).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            double dist = eye.distanceToSqr(f);
            if (dist < bestD) { bestD = dist; best = d; }
        }
        return best;
    }

    public static class Place extends ReachAction {
        final String item; int step; int wait;
        Place(BlockPos pos, String item) { super(pos); this.item = item; }
        public Status tick(Controller c) {
            Level l = c.level(); LocalPlayer p = c.player();
            if (++ticks > 300) return fail("timeout");
            BlockState cur = l.getBlockState(pos);
            if (step == 2) { // vérification après placement
                if (++wait < 3) return Status.RUNNING;
                return cur.canBeReplaced() ? fail("placement failed") : Status.DONE;
            }
            if (!cur.canBeReplaced()) {
                return Perception.blockId(cur).equals(item) ? Status.DONE : fail("target cell is not empty");
            }
            if (p.getBoundingBox().intersects(new AABB(pos))) return fail("you are standing in the target cell, move away");
            Status a = approach(c, 4.3);
            if (a != null) return a;
            if (step == 0) {
                int idx = c.findItem(item);
                if (idx < 0) return fail("'" + item + "' not in inventory");
                c.equipIndex(idx, false);
                step = 1;
                return Status.RUNNING;
            }
            Direction against = null;
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (!l.getBlockState(n).getCollisionShape(l, n).isEmpty()) { against = d; break; }
            }
            if (against == null) return fail("no solid neighbour block to place against");
            BlockPos n = pos.relative(against);
            Direction face = against.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (!c.lookAtPoint(hitVec, 50f)) return Status.RUNNING;
            Compat.useOn(c.mc, p, new BlockHitResult(hitVec, face, n, false));
            step = 2;
            return Status.RUNNING;
        }
        public String describe() { return "place " + item + " at " + where(); }
    }

    public static class UseOn extends ReachAction {
        final String item; boolean equipped;
        UseOn(BlockPos pos, String item) { super(pos); this.item = item; }
        public Status tick(Controller c) {
            LocalPlayer p = c.player();
            if (++ticks > 300) return fail("timeout");
            Status a = approach(c, 4.3);
            if (a != null) return a;
            if (!equipped && !item.isEmpty()) {
                int idx = c.findItem(item);
                if (idx < 0) return fail("'" + item + "' not in inventory");
                c.equipIndex(idx, false);
                equipped = true;
                return Status.RUNNING;
            }
            Vec3 center = Vec3.atCenterOf(pos);
            Vec3 eye = p.getEyePosition();
            Direction face = Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
            Vec3 hitVec = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (!c.lookAtPoint(hitVec, 50f)) return Status.RUNNING;
            Compat.useOn(c.mc, p, new BlockHitResult(hitVec, face, pos, false));
            return Status.DONE;
        }
        public String describe() { return "use_on " + where(); }
    }

    // ======================= ACTIONS DE HAUT NIVEAU (pour aider un petit modèle) =======================

    /** Tri d'ids : égalité exacte d'abord, puis "planks" -> oak_planks, "log" -> birch_log, "ore" -> iron_ore... */
    static boolean idMatches(String id, String wanted, boolean loose) {
        String w = wanted.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        if (id.equals(w)) return true;
        if (!loose) return false;
        if (w.equals("logs") || w.equals("wood")) w = "log";
        if (w.equals("ores")) w = "ore";
        return id.endsWith("_" + w);
    }

    /**
     * collect : trouve le bloc voulu le plus proche ET accessible, va le casser, ramasse les objets lâchés,
     * recommence jusqu'à "count" blocs cassés. C'est LA façon simple de récolter bois/pierre/minerai.
     */
    public static class Collect extends Action {
        final String block; final int count, radius;
        int broken, ticks, failures, state, wait; // state : 0 chercher, 1 casser, 2 ramasser
        BlockPos target; Mine mine; MoveAction pick; int pickTicks;
        final Set<BlockPos> bad = new HashSet<>();

        Collect(String block, int count, int radius) { this.block = block; this.count = count; this.radius = radius; }

        @Override public Status tick(Controller c) {
            if (++ticks > 6000) return fail("timeout after " + broken + "/" + count);
            if (broken >= count && state == 0) return Status.DONE; // fini, après avoir ramassé les derniers objets
            switch (state) {
                case 0: return search(c);
                case 1: return digging(c);
                default: return picking(c);
            }
        }

        private Status search(Controller c) {
            Level l = c.level(); LocalPlayer p = c.player();
            BlockPos me = p.blockPosition();
            BlockPos best = null; double bestD = Double.MAX_VALUE;
            for (int pass = 0; pass < 2 && best == null; pass++) {          // 0 = id exact, 1 = id "approchant"
                BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
                for (int x = me.getX() - radius; x <= me.getX() + radius; x++)
                    for (int z = me.getZ() - radius; z <= me.getZ() + radius; z++)
                        for (int y = me.getY() - 3; y <= me.getY() + 4; y++) { // seulement ce qu'on peut atteindre à pied
                            mp.set(x, y, z);
                            BlockState st = l.getBlockState(mp);
                            if (st.isAir() || !idMatches(Perception.blockId(st), block, pass == 1)) continue;
                            BlockPos q = mp.immutable();
                            if (bad.contains(q) || exposedFace(c, q) == null) continue;
                            double d = q.distSqr(me);
                            if (d < bestD) { bestD = d; best = q; }
                        }
            }
            if (best == null)
                return broken > 0 ? Status.DONE
                        : fail("no reachable '" + block + "' within " + radius + " blocks (look at BLOCKS, '*' = exposed). Explore with move_to.");
            target = best; mine = new Mine(best); state = 1;
            return Status.RUNNING;
        }

        private Status digging(Controller c) {
            Status s = mine.tick(c);
            if (s == Status.RUNNING) return s;
            mine.stop(c);
            if (s == Status.DONE) { broken++; state = 2; wait = 6; pick = null; pickTicks = 0; }
            else { bad.add(target); if (++failures >= 5) return fail("keeps failing: " + mine.failReason); state = 0; }
            return Status.RUNNING;
        }

        private Status picking(Controller c) {
            if (wait-- > 0) return Status.RUNNING;                 // laisse les objets tomber
            if (++pickTicks > 100) { state = 0; return Status.RUNNING; }
            LocalPlayer p = c.player();
            if (pick == null) {
                ItemEntity best = null; double bestD = Double.MAX_VALUE;
                for (Entity e : c.level().getEntities(p, p.getBoundingBox().inflate(7), x -> x instanceof ItemEntity)) {
                    double d = p.distanceToSqr(e);
                    if (d < bestD) { bestD = d; best = (ItemEntity) e; }
                }
                if (best == null) { state = 0; return Status.RUNNING; }
                pick = new MoveAction(best.getId(), 0, 0, 0, 0.5, false, 120, 0);
            }
            Status s = pick.tick(c);
            if (s != Status.RUNNING) pick = null;                   // objet ramassé (ou inatteignable) : on regarde s'il en reste
            return Status.RUNNING;
        }

        @Override public void stop(Controller c) { if (mine != null) mine.stop(c); }
        public String describe() { return "collect " + block + " (" + broken + "/" + count + ")"; }
    }

    /** "grid" : [["a","b"],["c","d"]] (lignes) ou liste à plat de 4 / 9 cases ; "" ou null = case vide. */
    static String[][] parseGrid(JsonObject o) {
        if (!o.has("grid") || !o.get("grid").isJsonArray()) throw new IllegalArgumentException("missing 'grid'");
        JsonArray g = o.getAsJsonArray("grid");
        if (g.size() == 0) throw new IllegalArgumentException("empty grid");
        List<String[]> rows = new ArrayList<>();
        if (g.get(0).isJsonArray()) {
            for (JsonElement r : g) {
                JsonArray ra = r.getAsJsonArray();
                String[] row = new String[ra.size()];
                for (int i = 0; i < row.length; i++) row[i] = cell(ra.get(i));
                rows.add(row);
            }
        } else {
            int w = g.size() == 4 ? 2 : g.size() == 9 ? 3 : -1;
            if (w < 0) throw new IllegalArgumentException("flat grid must have 4 or 9 entries, or use rows");
            for (int r = 0; r < w; r++) {
                String[] row = new String[w];
                for (int i = 0; i < w; i++) row[i] = cell(g.get(r * w + i));
                rows.add(row);
            }
        }
        if (rows.size() > 3) throw new IllegalArgumentException("grid has more than 3 rows");
        for (String[] r : rows) if (r.length > 3) throw new IllegalArgumentException("grid has more than 3 columns");
        return rows.toArray(new String[0][]);
    }
    private static String cell(JsonElement e) {
        return e == null || e.isJsonNull() ? "" : e.getAsString().replace("minecraft:", "").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * craft : remplit la grille de craft (2x2 de l'inventaire, ou 3x3 si une table de craft est ouverte),
     * prend le résultat. Gère tous les clics lui-même (un clic par tick). En cas d'échec, remet tout dans l'inventaire.
     */
    public static class Craft extends Action {
        final String[][] grid; final int count;
        AbstractContainerMenu menu; int width, invFrom, invTo;
        int r, col, placed, phase, ticks, wait, src = -1;
        // phase : 0 prendre une pile, 1 poser un par un, 2 reposer le reste, 3 attendre le résultat, 4 prendre le résultat, 5 fin

        Craft(String[][] grid, int count) { this.grid = grid; this.count = count; }
        @Override public boolean allowsScreen() { return true; }

        @Override public void start(Controller c) {
            LocalPlayer p = c.player();
            boolean table = p.containerMenu instanceof CraftingMenu;
            menu = table ? p.containerMenu : p.inventoryMenu;   // sans table : grille 2x2 du joueur, aucun écran requis
            width = table ? 3 : 2;
            invFrom = table ? 10 : 9;
            invTo = table ? menu.slots.size() - 1 : 44;
        }

        private int gridSlot() { return 1 + r * width + col; }

        private String cellItem() { return (r < grid.length && col < grid[r].length) ? grid[r][col] : ""; }

        /** Avance (r, col) jusqu'à la prochaine case non vide. false s'il n'y en a plus. */
        private boolean nextCell() {
            while (r < grid.length) {
                while (col < grid[r].length) { if (!cellItem().isEmpty()) return true; col++; }
                r++; col = 0;
            }
            return false;
        }

        private int findSource(String wanted) {
            for (int pass = 0; pass < 2; pass++)
                for (int i = invFrom; i <= invTo; i++) {
                    ItemStack s = menu.getSlot(i).getItem();
                    if (!s.isEmpty() && idMatches(Perception.itemId(s), wanted, pass == 1)) return i;
                }
            return -1;
        }

        private int total(String wanted) {
            int n = 0;
            for (int i = invFrom; i <= invTo; i++) {
                ItemStack s = menu.getSlot(i).getItem();
                if (!s.isEmpty() && idMatches(Perception.itemId(s), wanted, true)) n += s.getCount();
            }
            return n;
        }

        private void click(Controller c, int slot, int button, ClickType t) {
            Compat.click(c.mc, c.player(), menu.containerId, slot, button, t);
        }

        @Override public Status tick(Controller c) {
            if (++ticks > 800) return fail("timeout");
            if (c.player().containerMenu != menu && menu != c.player().inventoryMenu) return fail("crafting screen was closed");
            if (grid.length > width || maxCols() > width)
                return fail(width == 2 ? "this recipe needs a crafting table: place one, use_on it, then craft again"
                                       : "grid too large");
            switch (phase) {
                case 0: {
                    if (!checked) { checked = true; if (!checkIngredients()) return failed; }
                    if (!nextCell()) { phase = 3; wait = 0; return Status.RUNNING; }
                    String want = cellItem();
                    src = findSource(want);
                    if (src < 0) return fail("missing ingredient '" + want + "' in inventory");
                    click(c, src, 0, ClickType.PICKUP);
                    phase = 1;
                    return Status.RUNNING;
                }
                case 1: {
                    ItemStack carried = menu.getCarried();
                    if (placed < count && !carried.isEmpty()) {
                        click(c, gridSlot(), 1, ClickType.PICKUP);   // clic droit = pose UN objet
                        placed++;
                    } else phase = 2;
                    return Status.RUNNING;
                }
                case 2: {
                    if (!menu.getCarried().isEmpty()) click(c, src, 0, ClickType.PICKUP); // on repose le reste
                    if (placed < count) { phase = 0; return Status.RUNNING; } // il en faut encore : autre pile
                    placed = 0; col++; phase = 0;
                    return Status.RUNNING;
                }
                case 3: {
                    if (menu.getSlot(0).hasItem()) { phase = 4; return Status.RUNNING; }
                    if (++wait > 25) return fail("no recipe matches this grid (result slot stayed empty). Check the pattern.");
                    return Status.RUNNING;
                }
                case 4: {
                    click(c, 0, 0, ClickType.QUICK_MOVE);            // shift-clic sur le résultat : fabrique "count" fois
                    phase = 5; wait = 0;
                    return Status.RUNNING;
                }
                default:
                    return ++wait >= 3 ? Status.DONE : Status.RUNNING;
            }
        }

        private Status failed;
        private boolean checked;
        private int maxCols() { int m = 0; for (String[] row : grid) m = Math.max(m, row.length); return m; }

        /** Vérifie qu'on a assez de chaque ingrédient AVANT de toucher quoi que ce soit. */
        private boolean checkIngredients() {
            java.util.Map<String, Integer> need = new java.util.LinkedHashMap<>();
            for (String[] row : grid) for (String it : row) if (it != null && !it.isEmpty()) need.merge(it, count, Integer::sum);
            for (java.util.Map.Entry<String, Integer> e : need.entrySet()) {
                int have = total(e.getKey());
                if (have < e.getValue()) {
                    failed = fail("not enough '" + e.getKey() + "': need " + e.getValue() + ", have " + have);
                    return false;
                }
            }
            return true;
        }

        /** Quoi qu'il arrive : curseur vide et grille vidée (sinon des objets restent coincés dans la grille). */
        @Override public void stop(Controller c) {
            LocalPlayer p = c.player();
            if (menu == null || p == null) return;
            if (!menu.getCarried().isEmpty() && src >= 0) click(c, src, 0, ClickType.PICKUP);
            if (!menu.getCarried().isEmpty()) {           // la case d'origine est prise : n'importe quelle case libre
                for (int i = invFrom; i <= invTo && !menu.getCarried().isEmpty(); i++)
                    if (menu.getSlot(i).getItem().isEmpty()) click(c, i, 0, ClickType.PICKUP);
            }
            int gridEnd = width == 3 ? 9 : 4;
            for (int i = 1; i <= gridEnd; i++)
                if (menu.getSlot(i).hasItem()) click(c, i, 0, ClickType.QUICK_MOVE);
        }

        public String describe() { return "craft " + java.util.Arrays.deepToString(grid) + " x" + count; }
    }
}
