package dev.aiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Cerveau côté jeu : file d'actions, état du mouvement voulu, boucle "perception -> LLM -> actions".
 * Le mouvement est appliqué par {@link AiInput} (contrôle direct, pas de touches clavier simulées).
 */
public class Controller {
    public static final Controller INSTANCE = new Controller();

    public final Minecraft mc = Minecraft.getInstance();

    // mouvement VOULU ce tick (lu par AiInput). Remis à zéro à chaque tick, sauf sneak qui persiste.
    // "use" = on maintient le clic droit (manger/arc) : voir applyUse().
    public boolean forward, jump, sprint, use;
    public boolean sneak;

    public final Deque<Action> queue = new ArrayDeque<>();
    public Action current;

    private boolean enabled;
    private final Deque<String> chat = new ArrayDeque<>();
    private final Deque<String> results = new ArrayDeque<>();
    private String memory = "", lastPlan = "";
    private boolean thinking, interrupt;
    private int cooldown, generation, respawnTimer, interruptCooldown;
    private float lastHealth = 20f;
    private boolean useKeyHeld;

    public LocalPlayer player() { return mc.player; }
    public ClientLevel level() { return mc.level; }
    public boolean isEnabled() { return enabled; }

    /** Utilisé par les mixins : l'écran pause/Échap ne doit pas geler le monde pendant que l'IA joue. */
    public boolean shouldKeepWorldRunning() { return enabled && Config.INSTANCE.keepRunningInPause; }

    // ---------------------------------------------------------------- on/off
    public void setEnabled(boolean on) {
        generation++;
        enabled = on;
        thinking = false;
        stopAll();
        releaseKeys();
        sneak = false;
        cooldown = 0;
        interrupt = false;
        if (on) { addResult("AI control enabled."); interrupt = true; }
    }

    private void stopAll() {
        if (current != null) current.stop(this);
        current = null;
        queue.clear();
    }

    public void localMsg(String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(s), false);
    }

    // ---------------------------------------------------------------- chat
    public void onChat(String text, boolean playerChat) {
        chat.addLast(text);
        while (chat.size() > Math.max(1, Config.INSTANCE.historySize)) chat.removeFirst();
        if (!enabled || mc.player == null) return;
        String me = mc.player.getName().getString();
        if (playerChat && !text.contains("<" + me + ">") && !text.startsWith(me + ":")) interrupt = true;
    }

    public void onOwnerInstruction(String text) {
        chat.addLast("[OWNER INSTRUCTION] " + text);
        while (chat.size() > Math.max(1, Config.INSTANCE.historySize)) chat.removeFirst();
        interrupt = true;
    }

    private void addResult(String s) {
        results.addLast(s);
        while (results.size() > 12) results.removeFirst();
    }

    public String status() {
        return "enabled=" + enabled + " thinking=" + thinking
                + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName())
                + " paused=" + Compat.isPaused(mc) + " current=" + (current == null ? "none" : current.describe())
                + " queued=" + queue.size() + " memory=\"" + memory + "\"";
    }

    // ---------------------------------------------------------------- tick
    public void tick() {
        try {
            doTick();
        } catch (Exception e) {
            e.printStackTrace();
            addResult("internal error: " + e);
            stopAll();
        }
    }

    private boolean wasActive;

    private void doTick() {
        LocalPlayer p = mc.player;
        boolean active = enabled && p != null && mc.level != null;
        if (!active) {
            if (wasActive) releaseKeys();
            wasActive = false;
            return;
        }
        wasActive = true;

        if (p.isDeadOrDying() || mc.screen instanceof DeathScreen) {
            if (respawnTimer-- <= 0) {
                p.respawn();
                respawnTimer = 40;
                stopAll();
                addResult("You died and respawned. Your items may be at your death location.");
                interrupt = true;
            }
            releaseKeys();
            return;
        }

        AiInput.install(p); // (ré)installe le contrôle direct, aussi après un respawn

        forward = jump = sprint = use = false;

        // Monde gelé (solo + écran pause alors que le mixin n'a pas pu s'appliquer) : on n'avance pas les actions,
        // sinon elles expireraient (timeouts) pendant que rien ne bouge.
        if (Compat.isPaused(mc)) return;

        float hp = p.getHealth();
        if (hp < lastHealth - 0.01f) {
            interrupt = true;
            addResult(String.format(Locale.ROOT, "You took damage (health %.0f).", hp));
        }
        lastHealth = hp;

        runActions(p);
        applyUse();
        maybeThink();
    }

    private void runActions(LocalPlayer p) {
        if (current == null) {
            current = queue.poll();
            if (current != null) {
                // on ferme un inventaire/coffre resté ouvert, mais jamais le menu Échap de l'humain
                if (!current.allowsScreen()) Compat.closeContainerScreen(mc, p);
                current.start(this);
            }
        }
        if (current == null) return;
        Action a = current;
        Action.Status s;
        try {
            s = a.tick(this);
        } catch (Exception ex) {
            ex.printStackTrace();
            a.failReason = "exception " + ex;
            s = Action.Status.FAILED;
        }
        if (s != Action.Status.RUNNING) {
            addResult(a.describe() + " -> " + (s == Action.Status.DONE ? "done" : "FAILED: " + a.failReason));
            a.stop(this);
            current = null;
            if (s == Action.Status.FAILED) queue.clear();
        }
    }

    /**
     * Seule "touche" encore touchée : le clic droit maintenu, et seulement quand aucun écran n'est ouvert.
     * Pourquoi : sans écran, le jeu relâche l'objet en cours d'utilisation dès que la touche n'est pas enfoncée
     * (le repas serait annulé). Avec un écran ouvert le jeu ne relâche rien, donc rien à faire.
     * Le début/la fin de l'utilisation sont faits en direct (Compat.startUse / releaseUse).
     */
    private void applyUse() {
        boolean want = use && mc.screen == null;
        if (want != useKeyHeld) {
            mc.options.keyUse.setDown(want);
            useKeyHeld = want;
        }
    }

    /** Rend le contrôle à l'humain : enlève AiInput et relâche le clic droit si on le tenait. */
    private void releaseKeys() {
        if (mc.player != null) AiInput.uninstall(mc.player);
        if (useKeyHeld) { mc.options.keyUse.setDown(false); useKeyHeld = false; }
    }

    // ---------------------------------------------------------------- LLM
    private void maybeThink() {
        if (thinking) return;
        if (cooldown > 0) { cooldown--; return; }
        if (interruptCooldown > 0) interruptCooldown--;
        boolean idle = current == null && queue.isEmpty();
        boolean urgent = interrupt && interruptCooldown <= 0;
        if (idle || urgent) {
            interrupt = false;
            interruptCooldown = 40;
            think();
        }
    }

    private void think() {
        String input = buildInput();
        String sys = Prompt.SYSTEM + (Config.INSTANCE.extraSystemPrompt.isBlank() ? "" : "\n" + Config.INSTANCE.extraSystemPrompt);
        results.clear();
        thinking = true;
        final int gen = generation;
        LlmClient.chat(sys, input).whenComplete((reply, err) -> mc.execute(() -> {
            if (gen != generation || !enabled) return;
            thinking = false;
            onResponse(reply, err);
        }));
    }

    private String buildInput() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CHAT HISTORY (last ").append(chat.size()).append(") ===\n");
        if (chat.isEmpty()) sb.append("(no messages)\n");
        for (String m : chat) sb.append(m).append('\n');
        sb.append("=== MEMORY ===\n").append(memory.isEmpty() ? "(empty)" : memory).append('\n');
        sb.append("=== YOUR LAST PLAN ===\n").append(lastPlan.isEmpty() ? "(none)" : lastPlan).append('\n');
        sb.append("=== RESULTS OF YOUR LAST ACTIONS ===\n");
        if (results.isEmpty()) sb.append("(none)\n");
        for (String r : results) sb.append(r).append('\n');
        sb.append(Perception.build(this));
        sb.append("\nReply with the JSON object only.");
        return sb.toString();
    }

    private void onResponse(String reply, Throwable err) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (err != null) {
            Throwable t = err.getCause() != null ? err.getCause() : err;
            localMsg("§c[AI] LLM error: " + t.getMessage());
            cooldown = 100;
            return;
        }
        try {
            JsonObject o = LlmClient.extractJson(reply);
            String thought = o.has("thought") ? o.get("thought").getAsString() : "";
            String say = o.has("say") && !o.get("say").isJsonNull() ? o.get("say").getAsString() : "";
            if (o.has("memory") && !o.get("memory").isJsonNull()) {
                String m = o.get("memory").getAsString().strip();
                if (!m.isEmpty()) memory = m.substring(0, Math.min(600, m.length())); // vide = on garde les anciennes notes
            }

            stopAll();
            StringBuilder plan = new StringBuilder(thought).append(" | ");
            if (!say.isBlank()) {
                queue.add(new Actions.Say(say));
                plan.append("say; ");
            }
            if (o.has("actions") && o.get("actions").isJsonArray()) {
                JsonArray arr = o.getAsJsonArray("actions");
                int n = 0;
                for (JsonElement el : arr) {
                    if (n++ >= Config.INSTANCE.maxActionsPerPlan) break;
                    try {
                        Action a = Actions.parse(el.getAsJsonObject());
                        queue.add(a);
                        plan.append(a.describe()).append("; ");
                    } catch (Exception ex) {
                        addResult("invalid action " + el + " -> " + ex.getMessage() + " (valid types: " + Actions.TYPES + ")");
                    }
                }
            }
            lastPlan = plan.toString();
            if (Config.INSTANCE.showThoughts && !thought.isBlank()) localMsg("§7[AI] " + thought);
            if (queue.isEmpty()) cooldown = 40;
        } catch (Exception ex) {
            addResult("your last reply was not valid JSON (" + ex.getMessage() + "). Reply with ONE JSON object only.");
            localMsg("§c[AI] unparsable reply: " + ex.getMessage());
            cooldown = 20;
        }
    }

    // ---------------------------------------------------------------- aides pour les actions
    public boolean lookAtPoint(Vec3 pt, float maxStep) {
        Vec3 eye = player().getEyePosition();
        double dx = pt.x - eye.x, dy = pt.y - eye.y, dz = pt.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        return rotate(yaw, pitch, maxStep);
    }

    public boolean rotate(float yaw, float pitch, float maxStep) {
        LocalPlayer p = player();
        float dyaw = Mth.wrapDegrees(yaw - p.getYRot());
        float dpitch = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dyaw, -maxStep, maxStep));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dpitch, -maxStep, maxStep), -90f, 90f));
        return Math.abs(dyaw) < 4f && Math.abs(dpitch) < 4f;
    }

    /** Tourne vers (dx,dz) en marchant. Renvoie true si on regarde assez dans la bonne direction pour avancer. */
    public boolean faceYaw(double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float diff = Math.abs(Mth.wrapDegrees(yaw - player().getYRot()));
        rotate(yaw, 10f, 28f);
        return diff < 45f;
    }

    public static int menuSlot(int invIndex) { return invIndex < 9 ? 36 + invIndex : invIndex; }

    public int findItem(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        Inventory inv = player().getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && Perception.itemId(s).equals(n)) return i;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && !n.isEmpty() && Perception.itemId(s).contains(n)) return i;
        }
        return -1;
    }

    private static boolean isArmor(String id) {
        return id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings")
                || id.endsWith("_boots") || id.equals("elytra");
    }

    /** Met l'objet à l'index d'inventaire (0-35) en main / à porter / en main secondaire. */
    public void equipIndex(int idx, boolean offhand) {
        LocalPlayer p = player();
        String id = Perception.itemId(p.getInventory().getItem(idx));
        int cid = p.inventoryMenu.containerId;
        if (isArmor(id)) Compat.click(mc, p, cid, menuSlot(idx), 0, ClickType.QUICK_MOVE);
        else if (offhand) Compat.click(mc, p, cid, menuSlot(idx), 40, ClickType.SWAP);
        else if (idx < 9) Compat.selectSlot(p, idx);
        else Compat.click(mc, p, cid, menuSlot(idx), Compat.selectedSlot(p), ClickType.SWAP);
    }

    public void selectBestTool(BlockState state) {
        LocalPlayer p = player();
        Inventory inv = p.getInventory();
        int sel = Compat.selectedSlot(p);
        float bestSpeed = inv.getItem(sel).getDestroySpeed(state);
        int best = -1;
        for (int i = 0; i < 36; i++) {
            float sp = inv.getItem(i).getDestroySpeed(state);
            if (sp > bestSpeed + 0.01f) { bestSpeed = sp; best = i; }
        }
        if (best >= 0) equipIndex(best, false);
    }

    public void selectBestWeapon() {
        LocalPlayer p = player();
        Inventory inv = p.getInventory();
        int bestScore = weaponScore(Perception.itemId(inv.getItem(Compat.selectedSlot(p)))), best = -1;
        for (int i = 0; i < 36; i++) {
            int sc = weaponScore(Perception.itemId(inv.getItem(i)));
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        if (best >= 0) equipIndex(best, false);
    }

    private static int weaponScore(String id) {
        int tier = id.startsWith("netherite") ? 5 : id.startsWith("diamond") ? 4 : id.startsWith("iron") ? 3
                : id.startsWith("stone") ? 2 : id.startsWith("golden") ? 1 : 0;
        if (id.endsWith("_sword")) return 20 + tier;
        if (id.endsWith("_axe")) return 10 + tier;
        if (id.equals("trident") || id.equals("mace")) return 15;
        return 0;
    }
}
