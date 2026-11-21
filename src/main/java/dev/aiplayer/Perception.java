package dev.aiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Transforme le monde autour du joueur en texte compact pour l'IA. */
public final class Perception {
    private Perception() {}

    private static final Set<String> COMMON = Set.of("stone", "dirt", "grass_block", "deepslate", "netherrack", "sand",
            "gravel", "water", "bedrock", "short_grass", "tall_grass", "andesite", "diorite", "granite", "tuff",
            "sandstone", "snow", "snow_block", "dirt_path", "coarse_dirt", "podzol", "mud", "clay", "red_sand",
            "end_stone", "fern", "seagrass", "tall_seagrass", "kelp", "kelp_plant", "vine", "red_sandstone", "terracotta");

    private static boolean isCommon(String id) { return COMMON.contains(id) || id.endsWith("_leaves"); }

    public static String itemId(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); }
    public static String blockId(BlockState s) { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath(); }

    private static final class Agg {
        int count;
        final List<int[]> near = new ArrayList<>(); // {d2, x, y, z, exposed}
        boolean accepts(int d2, int k) { return near.size() < k || d2 < near.get(near.size() - 1)[0]; }
        void add(int d2, int x, int y, int z, int k, boolean ex) {
            int i = 0;
            while (i < near.size() && near.get(i)[0] <= d2) i++;
            near.add(i, new int[]{d2, x, y, z, ex ? 1 : 0});
            if (near.size() > k) near.remove(near.size() - 1);
        }
    }

    public static String build(Controller c) {
        Minecraft mc = c.mc;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        StringBuilder sb = new StringBuilder(8000);
        BlockPos pos = p.blockPosition();

        // ---------- STATUS ----------
        String dim = level.dimension().toString();
        dim = dim.contains("nether") ? "nether" : dim.contains("end") ? "end" : "overworld";
        sb.append("=== STATUS ===\n");
        sb.append(String.format(Locale.ROOT,
                "name=%s pos=(%.1f, %.1f, %.1f) feet_cell=(%d,%d,%d) facing=%s dimension=%s\n",
                p.getName().getString(), p.getX(), p.getY(), p.getZ(), pos.getX(), pos.getY(), pos.getZ(),
                p.getDirection().getName(), dim));
        sb.append(String.format(Locale.ROOT, "health=%.0f/20 food=%d on_ground=%s in_water=%s sneaking=%s current_action=%s queued=%d\n",
                p.getHealth(), p.getFoodData().getFoodLevel(), p.onGround(), p.isInWater(), c.sneak,
                c.current == null ? "none" : c.current.describe(), c.queue.size()));

        // ---------- INVENTAIRE ----------
        Inventory inv = p.getInventory();
        sb.append("=== INVENTORY === (index: item xcount; 0-8 = hotbar, selected=")
                .append(Compat.selectedSlot(p)).append(")\n");
        boolean any = false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            any = true;
            sb.append(i).append(": ").append(itemId(s)).append(" x").append(s.getCount());
            if (s.isDamageableItem()) sb.append(" dur=").append(s.getMaxDamage() - s.getDamageValue());
            sb.append('\n');
        }
        if (!any) sb.append("(empty)\n");
        sb.append("armor: head=").append(slotName(p.getItemBySlot(EquipmentSlot.HEAD)))
                .append(" chest=").append(slotName(p.getItemBySlot(EquipmentSlot.CHEST)))
                .append(" legs=").append(slotName(p.getItemBySlot(EquipmentSlot.LEGS)))
                .append(" feet=").append(slotName(p.getItemBySlot(EquipmentSlot.FEET)))
                .append(" offhand=").append(slotName(p.getItemBySlot(EquipmentSlot.OFFHAND))).append('\n');

        // ---------- ECRAN OUVERT ----------
        if (Compat.isContainerScreen(mc)) { // seulement inventaire/coffre/table : le menu Échap de l'humain ne compte pas
            sb.append("=== OPEN SCREEN: ").append(mc.screen.getClass().getSimpleName())
                    .append(" / menu ").append(p.containerMenu.getClass().getSimpleName()).append(" === (non-empty menu slots)\n");
            for (Slot sl : p.containerMenu.slots) {
                ItemStack s = sl.getItem();
                if (!s.isEmpty()) sb.append(sl.index).append(": ").append(itemId(s)).append(" x").append(s.getCount()).append('\n');
            }
            sb.append("(total slots: ").append(p.containerMenu.slots.size()).append(")\n");
        }

        // ---------- ENTITES ----------
        int r = Config.INSTANCE.scanRadius;
        List<Entity> ents = new ArrayList<>(level.getEntities(p, p.getBoundingBox().inflate(r), e -> true));
        ents.sort(Comparator.comparingDouble(p::distanceToSqr));
        sb.append("=== NEARBY ENTITIES === (id type distance pos extra)\n");
        int n = 0;
        for (Entity e : ents) {
            if (n++ >= 30) break;
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            StringBuilder extra = new StringBuilder();
            if (e instanceof Player pl) extra.append("PLAYER name=").append(pl.getName().getString());
            if (e instanceof ItemEntity ie) type = "dropped_item:" + itemId(ie.getItem()) + " x" + ie.getItem().getCount();
            if (e instanceof Enemy) extra.append("HOSTILE ");
            if (e instanceof LivingEntity le) extra.append(String.format(Locale.ROOT, "hp=%.0f", le.getHealth()));
            sb.append(String.format(Locale.ROOT, "id=%d %s dist=%.1f pos=(%.0f,%.0f,%.0f) %s\n",
                    e.getId(), type, p.distanceTo(e), e.getX(), e.getY(), e.getZ(), extra));
        }
        if (n == 0) sb.append("(none)\n");

        // ---------- HINTS ----------
        hints(sb, c, ents);

        // ---------- MAP ----------
        map(sb, level, pos);

        // ---------- BLOCS ----------
        blocks(sb, level, pos, r);
        return sb.toString();
    }

    private static int tier(String id) {
        return id.startsWith("netherite") ? 5 : id.startsWith("diamond") ? 4 : id.startsWith("iron") ? 3
                : id.startsWith("stone") ? 2 : id.startsWith("golden") ? 1 : id.startsWith("wooden") ? 0 : -1;
    }

    /** Meilleur outil de ce type dans l'inventaire ("none" sinon). suffix = "_pickaxe", "_axe", "_sword", "_shovel". */
    private static String bestTool(Inventory inv, String suffix) {
        String best = "none"; int bt = -2;
        for (int i = 0; i < 36; i++) {
            String id = itemId(inv.getItem(i));
            if (id.endsWith(suffix) && tier(id) > bt) { bt = tier(id); best = id; }
        }
        return best;
    }

    /**
     * Faits CALCULÉS par le mod (pas par l'IA). Un petit modèle se trompe en déduisant ça des données brutes :
     * on lui donne directement l'état utile (urgences, ennemi le plus proche, outils, nourriture, écran ouvert).
     */
    private static void hints(StringBuilder sb, Controller c, List<Entity> ents) {
        Minecraft mc = c.mc;
        LocalPlayer p = mc.player;
        sb.append("=== HINTS (computed by the game mod, reliable) ===\n");

        if (mc.screen == null) sb.append("ui: none (normal play)\n");
        else if (Compat.isContainerScreen(mc)) sb.append("ui: a container/inventory screen is open -> use click_slot or craft, then close_screen\n");
        else sb.append("ui: ").append(mc.screen.getClass().getSimpleName())
                .append(" is open (probably the human's pause menu). IGNORE it: you keep playing normally.\n");

        List<String> warn = new ArrayList<>();
        int food = p.getFoodData().getFoodLevel();
        if (p.getHealth() <= 8) warn.add("LOW HEALTH (" + (int) p.getHealth() + "/20): eat, flee or avoid fights");
        if (food <= 6) warn.add("VERY HUNGRY (" + food + "/20): eat NOW, you cannot sprint");
        else if (food <= 14) warn.add("hungry (" + food + "/20): eat when safe");
        if (p.isOnFire()) warn.add("ON FIRE: go into water");
        if (p.isInWater()) warn.add("in water");
        sb.append("warnings: ").append(warn.isEmpty() ? "none" : String.join("; ", warn)).append('\n');

        for (Entity e : ents) {
            if (e instanceof Enemy) {
                sb.append(String.format(Locale.ROOT, "nearest hostile: id=%d %s dist=%.1f\n", e.getId(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), p.distanceTo(e)));
                break;
            }
        }

        Inventory inv = p.getInventory();
        sb.append("tools: pickaxe=").append(bestTool(inv, "_pickaxe")).append(" axe=").append(bestTool(inv, "_axe"))
                .append(" sword=").append(bestTool(inv, "_sword")).append(" shovel=").append(bestTool(inv, "_shovel")).append('\n');
        Map<String, Integer> foods = new LinkedHashMap<>();
        int logs = 0, planks = 0, sticks = 0, cobble = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            String id = itemId(st);
            if (Compat.isFood(st)) foods.merge(id, st.getCount(), Integer::sum);
            if (id.endsWith("_log")) logs += st.getCount();
            if (id.endsWith("_planks")) planks += st.getCount();
            if (id.equals("stick")) sticks += st.getCount();
            if (id.equals("cobblestone")) cobble += st.getCount();
        }
        sb.append("food items: ");
        if (foods.isEmpty()) sb.append("none");
        else for (Map.Entry<String, Integer> e : foods.entrySet()) sb.append(e.getKey()).append(" x").append(e.getValue()).append(", ");
        sb.append("\ncrafting stock: logs=").append(logs).append(" planks=").append(planks)
                .append(" sticks=").append(sticks).append(" cobblestone=").append(cobble).append('\n');
    }

    private static String slotName(ItemStack s) { return s.isEmpty() ? "-" : itemId(s); }

    private static void map(StringBuilder sb, Level l, BlockPos c) {
        sb.append("=== MAP 15x15 (north=up(-z), east=right(+x); @=you .=flat floor ^=higher v=lower ~=water L=lava #=blocked/hole) ===\n");
        final int R = 7;
        for (int dz = -R; dz <= R; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -R; dx <= R; dx++) {
                if (dx == 0 && dz == 0) { row.append('@'); continue; }
                int x = c.getX() + dx, z = c.getZ() + dz;
                boolean lava = false;
                for (int y = c.getY() - 3; y <= c.getY() + 1 && !lava; y++) lava = Pathfinder.lava(l, x, y, z);
                char ch = '#';
                if (lava) ch = 'L';
                else {
                    int found = Integer.MIN_VALUE;
                    for (int y = c.getY() + 3; y >= c.getY() - 4; y--) {
                        if (Pathfinder.standable(l, x, y, z)) { found = y; break; }
                    }
                    if (found != Integer.MIN_VALUE) ch = found > c.getY() ? '^' : found < c.getY() ? 'v' : '.';
                    else if (Pathfinder.water(l, x, c.getY() - 1, z) || Pathfinder.water(l, x, c.getY(), z)) ch = '~';
                }
                row.append(ch);
            }
            sb.append(row).append('\n');
        }
    }

    private static void blocks(StringBuilder sb, ClientLevel level, BlockPos pos, int r) {
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        int y0 = Math.max(level.getMinY(), py - r), y1 = Math.min(level.getMaxY(), py + r);
        Map<String, Agg> aggs = new HashMap<>();
        Map<Block, String> ids = new IdentityHashMap<>();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();

        for (int x = px - r; x <= px + r; x++) {
            for (int z = pz - r; z <= pz + r; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockState st = level.getBlockState(mp.set(x, y, z));
                    if (st.isAir()) continue;
                    String id = ids.computeIfAbsent(st.getBlock(), bk -> BuiltInRegistries.BLOCK.getKey(bk).getPath());
                    Agg a = aggs.computeIfAbsent(id, sk -> new Agg());
                    a.count++;
                    boolean common = isCommon(id);
                    int keep = common ? 1 : 5;
                    int d2 = (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz);
                    if (a.accepts(d2, keep)) {
                        boolean ex = false;
                        if (!common) {
                            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                                np.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                                if (level.getBlockState(np).getCollisionShape(level, np).isEmpty()) { ex = true; break; }
                            }
                        }
                        a.add(d2, x, y, z, keep, ex);
                    }
                }
            }
        }

        sb.append("=== BLOCKS within ").append(r).append(" (type xcount: nearest positions (x,y,z), * = exposed to air) ===\n");
        List<Map.Entry<String, Agg>> special = new ArrayList<>(), common = new ArrayList<>();
        for (Map.Entry<String, Agg> e : aggs.entrySet()) (isCommon(e.getKey()) ? common : special).add(e);
        special.sort(Comparator.comparingInt(e -> e.getValue().near.get(0)[0]));
        int shown = 0;
        for (Map.Entry<String, Agg> e : special) {
            if (shown++ >= 45) break;
            sb.append(e.getKey()).append(" x").append(e.getValue().count).append(':');
            for (int[] q : e.getValue().near)
                sb.append(" (").append(q[1]).append(',').append(q[2]).append(',').append(q[3]).append(')').append(q[4] == 1 ? "*" : "");
            sb.append('\n');
        }
        common.sort((a, b) -> b.getValue().count - a.getValue().count);
        sb.append("terrain:");
        for (Map.Entry<String, Agg> e : common) sb.append(' ').append(e.getKey()).append(" x").append(e.getValue().count).append(',');
        sb.append('\n');
    }
}
