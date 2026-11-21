package dev.aiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private int llmFailures; // échecs LLM consécutifs (pour le backoff)
    private float lastHealth = 20f;
    private boolean useKeyHeld;

    // ---------------------------------------------------------------- modes de combat
    /** NORMAL = survie (prompt habituel). Les autres REMPLACENT le prompt : voir {@link ModePrompts}. BEAFRIEND = serviteur (voir {@link Servant}). */
    public enum Mode { NORMAL, DEFEND, KILL, KILL_ALL, BEAFRIEND }
    public Mode mode = Mode.NORMAL;
    public String killTarget = "";   // nom du joueur visé (mode KILL)
    public boolean modeChat;         // "chat" à la fin de la commande : l'IA parle pendant le combat
    public BlockPos base;            // centre de la base (mode DEFEND : là où on tape la commande ; mode BEAFRIEND : base fixe)
    public BlockPos lastTargetPos;   // dernière position connue de la cible (mode KILL)
    public long lastTargetMs;
    private String savedMemory = ""; // notes du mode normal, restaurées par /ai normal
    private final Deque<String> unread = new ArrayDeque<>(); // BEAFRIEND : messages du chat pas encore montrés à l'IA

    public LocalPlayer player() { return mc.player; }
    public ClientLevel level() { return mc.level; }
    public boolean isEnabled() { return enabled; }

    /** Utilisé par les mixins : l'écran pause/Échap ne doit pas geler le monde pendant que l'IA joue. */
    public boolean shouldKeepWorldRunning() { return enabled && Config.INSTANCE.keepRunningInPause; }

    // ---------------------------------------------------------------- on/off
    public void setEnabled(boolean on) {
        generation++;
        enabled = on;
        if (!on) leaveMode();
        thinking = false;
        llmFailures = 0;
        stopAll();
        releaseKeys();
        sneak = false;
        cooldown = 0;
        interrupt = false;
        if (on) { addResult("AI control enabled."); interrupt = true; }
    }

    private void leaveMode() {
        if (mode == Mode.NORMAL) return;
        Servant.reset();
        unread.clear();
        mode = Mode.NORMAL;
        memory = savedMemory;
        killTarget = "";
        modeChat = false;
        base = null;
        lastTargetPos = null;
    }

    /** /ai defend, /ai kill <joueur|everyone>, /ai beafriend, /ai normal. Active l'IA si besoin et change toute sa mission. */
    public void setMode(Mode m, String target, boolean chatOn) {
        Servant.reset();
        unread.clear();
        if (!enabled) setEnabled(true);
        if (m == Mode.NORMAL) {
            leaveMode();
        } else {
            if (mode == Mode.NORMAL) savedMemory = memory; // on garde les notes de survie pour plus tard
            memory = "";
            mode = m;
            killTarget = target == null ? "" : target.strip();
            modeChat = chatOn;
            base = m == Mode.DEFEND && mc.player != null ? mc.player.blockPosition()
                    : m == Mode.BEAFRIEND ? Servant.BASE : null;
            lastTargetPos = null;
        }
        generation++;      // une requête partie avec l'ancien prompt sera ignorée à son retour
        thinking = false;
        stopAll();
        lastPlan = "";
        results.clear();
        addResult(m == Mode.NORMAL ? "Back to normal survival mode." : "NEW MISSION: " + missionLine());
        cooldown = 0;
        interrupt = true;
        interruptCooldown = 0;
    }

    /** Les attaques contre un joueur passent par ce filtre (voir Actions.Attack). */
    public boolean mayAttackPlayer(Player target) {
        if (mode == Mode.BEAFRIEND) return Servant.mayAttack(target.getName().getString()); // seulement si le chat l'a demandé
        if (Config.INSTANCE.allowAttackPlayers) return true;
        return mode == Mode.KILL_ALL
                || (mode == Mode.KILL && target.getName().getString().equalsIgnoreCase(killTarget));
    }

    /** Résumé d'une ligne de la mission en cours (envoyé à l'IA dans CURRENT MISSION). */
    public String missionLine() {
        String s;
        switch (mode) {
            case DEFEND -> s = "DEFEND THE BASE" + (base != null ? " at (" + base.getX() + "," + base.getY() + "," + base.getZ() + ")" : "")
                    + ". Stay there, kill hostile monsters that come close, never mine or explore.";
            case KILL -> s = "KILL THE PLAYER " + killTarget + ". Nothing else matters.";
            case BEAFRIEND -> s = "BE A FRIEND: you are the servant of the players. Obey their orders from the chat. Stay where you are unless told. "
                    + "Attack, mine and drop items ONLY when a player asks in the chat. Never craft or build.";
            case KILL_ALL -> s = "KILL EVERYONE. Nothing else matters.";
            default -> s = "";
        }
        if (mode != Mode.NORMAL && modeChat) s += " CHAT MODE: talk in the chat while you fight.";
        return s;
    }

    private void stopAll() {
        if (current != null) current.stop(this);
        current = null;
        queue.clear();
    }

    public void localMsg(String s) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(s));
    }

    // ---------------------------------------------------------------- chat
    public void onChat(String text, boolean playerChat) {
        chat.addLast(text);
        while (chat.size() > Math.max(1, Config.INSTANCE.historySize)) chat.removeFirst();
        if (!enabled || mc.player == null) return;
        String me = mc.player.getName().getString();
        if (mode == Mode.BEAFRIEND) {
            // beaucoup de serveurs envoient le chat comme message "système" (playerChat=false) : on le lit quand même
            boolean looksLikeChat = playerChat || !Servant.header(text).isEmpty();
            if (looksLikeChat && !Servant.isOwn(text, me)) {
                Servant.onMessage(this, text);
                unread.addLast(text);
                while (unread.size() > 10) unread.removeFirst();
                interrupt = true;
                interruptCooldown = 0; // on réagit tout de suite, pas dans 2 secondes
            }
            return;
        }
        if (playerChat && !text.contains("<" + me + ">") && !text.startsWith(me + ":")) interrupt = true;
    }

    public void onOwnerInstruction(String text) {
        chat.addLast("[OWNER INSTRUCTION] " + text);
        while (chat.size() > Math.max(1, Config.INSTANCE.historySize)) chat.removeFirst();
        if (mode == Mode.BEAFRIEND) unread.addLast("[OWNER INSTRUCTION] " + text);
        interrupt = true;
    }

    private void addResult(String s) {
        results.addLast(s);
        while (results.size() > 12) results.removeFirst();
    }

    public String status() {
        return "enabled=" + enabled + " mode=" + mode + (modeChat ? "+chat" : "") + " thinking=" + thinking
                + " screen=" + (Compat.screen(mc) == null ? "none" : Compat.screen(mc).getClass().getSimpleName())
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
            thinking = false;      // ne jamais rester bloqué en "réfléchit"
            cooldown = Math.max(cooldown, 100); // et ne pas réessayer 20 fois par seconde
            localMsg("§c[AI] internal error: " + e + " (details: logs/latest.log)");
        }
    }

    /** /ai ping : teste LM Studio sans rien jouer. Affiche la réponse brute ou l'erreur. */
    public void ping() {
        localMsg("§7[AI] ping " + Config.INSTANCE.url + " model=" + Config.INSTANCE.model + " ...");
        long t0 = System.currentTimeMillis();
        try {
            LlmClient.chat("You answer with a JSON object only.", "Reply with exactly {\"ok\":true}").whenComplete((reply, err) -> mc.execute(() -> {
                double sec = (System.currentTimeMillis() - t0) / 1000.0;
                if (err != null) {
                    Throwable t = err.getCause() != null ? err.getCause() : err;
                    localMsg("§c[AI] ping FAILED: " + t);
                } else {
                    String r = reply.replace('\n', ' ');
                    localMsg(String.format(Locale.ROOT, "§a[AI] ping OK in %.1fs: ", sec) + r.substring(0, Math.min(200, r.length())));
                }
            }));
        } catch (Exception e) {
            localMsg("§c[AI] ping could not start: " + e);
        }
    }

    /** Message de diagnostic dans le chat local (désactivable : verbose=false). */
    private void info(String s) {
        if (Config.INSTANCE.verbose) localMsg("§8[AI] " + s);
    }

    /** Écrit la dernière requête/réponse dans .minecraft/aiplayer-debug/ pour pouvoir les relire/coller. */
    private void dump(String name, String text) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("aiplayer-debug");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), text);
        } catch (Exception ignored) {}
    }

    private boolean wasActive, warnedPaused;
    private long requestStartMs;

    private void doTick() {
        LocalPlayer p = mc.player;
        boolean active = enabled && p != null && mc.level != null;
        if (!active) {
            if (wasActive) releaseKeys();
            wasActive = false;
            return;
        }
        wasActive = true;

        if (p.isDeadOrDying() || Compat.screen(mc) instanceof DeathScreen) {
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

        // Monde gelé (solo + écran pause alors que le mixin n'a pas pu s'appliquer) : on n'avance pas les actions
        // (elles expireraient), mais l'IA peut quand même réfléchir : son plan sera prêt à la reprise.
        if (Compat.isPaused(mc)) {
            if (!warnedPaused) { warnedPaused = true; info("game is paused (menu open in singleplayer): actions wait, press Esc to resume"); }
            maybeThink();
            return;
        }
        warnedPaused = false;

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
        boolean want = use && Compat.screen(mc) == null;
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
        // Tout est protégé : une exception ici ne doit JAMAIS laisser "thinking = true" ni rester silencieuse.
        try {
            String input = buildInput();
            String basePrompt = mode != Mode.NORMAL ? ModePrompts.build(mode, killTarget, modeChat)
                    : Config.INSTANCE.compactPrompt ? Prompt.COMPACT : Prompt.SYSTEM;
            String sys = basePrompt + (Config.INSTANCE.extraSystemPrompt.isBlank() ? "" : "\n" + Config.INSTANCE.extraSystemPrompt);
            results.clear();
            final int gen = generation;
            dump("last_request.txt", "=== SYSTEM ===\n" + sys + "\n\n=== INPUT ===\n" + input);
            info("thinking... (request sent to " + Config.INSTANCE.model + ", " + (sys.length() + input.length()) + " chars"
                    + (mode != Mode.NORMAL ? ", mode " + mode + (modeChat ? "+chat" : "") : Config.INSTANCE.compactPrompt ? ", compact prompt" : "") + ")");
            requestStartMs = System.currentTimeMillis();
            thinking = true;
            LlmClient.chat(sys, input).whenComplete((reply, err) -> mc.execute(() -> {
                if (gen != generation || !enabled) return;
                thinking = false;
                try {
                    onResponse(reply, err);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    localMsg("§c[AI] error while handling the reply: " + ex);
                    cooldown = 100;
                }
            }));
        } catch (Exception ex) {
            thinking = false;
            ex.printStackTrace();
            cooldown = 100;
            localMsg("§c[AI] could not send the request: " + ex);
        }
    }

    private String buildInput() {
        StringBuilder sb = new StringBuilder();
        int keep = Math.max(1, Config.INSTANCE.historySize);   // combien de messages on donne à l'IA (config : historySize)
        int skip = Math.max(0, chat.size() - keep);
        sb.append("=== CHAT HISTORY (last ").append(chat.size() - skip).append(") ===\n");
        if (chat.isEmpty()) sb.append("(no messages)\n");
        int idx = 0;
        for (String m : chat) if (idx++ >= skip) sb.append(m).append('\n');
        if (mode == Mode.BEAFRIEND) {
            sb.append("=== NEW CHAT MESSAGES (unread: read them FIRST, they may contain orders) ===\n");
            if (unread.isEmpty()) sb.append("(none)\n");
            for (String um : unread) sb.append(um).append('\n');
            unread.clear();
        }
        if (mode != Mode.NORMAL) sb.append("=== CURRENT MISSION ===\n").append(missionLine()).append('\n');
        sb.append("=== MEMORY ===\n").append(memory.isEmpty() ? "(empty)" : memory).append('\n');
        sb.append("=== YOUR LAST PLAN ===\n").append(lastPlan.isEmpty() ? "(none)" : lastPlan).append('\n');
        sb.append("=== RESULTS OF YOUR LAST ACTIONS ===\n");
        if (results.isEmpty()) sb.append("(none)\n");
        for (String r : results) sb.append(r).append('\n');
        sb.append(Perception.build(this));
        sb.append(ModeHints.build(this));
        sb.append("\nReply with the JSON object only.");
        return sb.toString();
    }

    private void onResponse(String reply, Throwable err) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        long ms = System.currentTimeMillis() - requestStartMs;
        dump("last_reply.txt", err != null ? "ERROR: " + err : reply);
        if (err == null) info(String.format(Locale.ROOT, "reply received in %.1fs (%d chars)", ms / 1000.0, reply.length()));
        if (err != null) {
            Throwable t = err.getCause() != null ? err.getCause() : err;
            llmFailures++;
            String why = t instanceof java.net.http.HttpTimeoutException
                    ? "no answer within " + Config.INSTANCE.requestTimeoutSeconds + "s (timeout). Raise it in /ai settings, or set 0 for unlimited."
                    : t.toString();
            localMsg(String.format(Locale.ROOT, "§c[AI] LLM error after %.0fs: ", ms / 1000.0) + why);
            // backoff : 5s, 10s, 20s, 40s, puis 60s max (évite d'empiler les requêtes sur un LM Studio saturé)
            cooldown = Math.min(100 << Math.min(llmFailures - 1, 4), 1200);
            if (Config.INSTANCE.fallbackWander && mode != Mode.DEFEND && mode != Mode.BEAFRIEND) wander();
            return;
        }
        llmFailures = 0;
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
                        if (mode != Mode.NORMAL && a instanceof Actions.Attack at) a = new ModeAttack(at.id, at.maxTicks);
                        queue.add(a);
                        plan.append(a.describe()).append("; ");
                    } catch (Exception ex) {
                        addResult("invalid action " + el + " -> " + ex.getMessage() + " (valid types: " + Actions.TYPES + ")");
                    }
                }
            }
            lastPlan = plan.toString();
            if (Config.INSTANCE.showThoughts && !thought.isBlank()) localMsg("§7[AI] " + thought);
            if (queue.isEmpty()) { cooldown = 40; info("the AI sent no action (it will think again shortly)"); }
            else info(queue.size() + " action(s): " + plan);
        } catch (Exception ex) {
            addResult("your last reply was not valid JSON (" + ex.getMessage() + "). Reply with ONE JSON object only.");
            localMsg("§c[AI] unparsable reply: " + ex.getMessage());
            cooldown = 20;
        }
    }

    /** Plan B quand le LLM ne répond pas : marche vers un point aléatoire proche pour ne pas rester figé. */
    private void wander() {
        LocalPlayer p = mc.player;
        if (p == null || current != null || !queue.isEmpty()) return;
        double ang = Math.random() * Math.PI * 2;
        int d = 6 + (int) (Math.random() * 8);
        JsonObject o = new JsonObject();
        o.addProperty("type", "move_to");
        o.addProperty("x", p.getBlockX() + (int) Math.round(Math.cos(ang) * d));
        o.addProperty("y", p.getBlockY());
        o.addProperty("z", p.getBlockZ() + (int) Math.round(Math.sin(ang) * d));
        o.addProperty("range", 2.0);
        try {
            queue.add(Actions.parse(o));
            info("LLM unavailable: wandering");
        } catch (Exception ignored) {}
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
        if (isArmor(id)) Compat.click(mc, p, cid, menuSlot(idx), 0, ContainerInput.QUICK_MOVE);
        else if (offhand) Compat.click(mc, p, cid, menuSlot(idx), 40, ContainerInput.SWAP);
        else if (idx < 9) Compat.selectSlot(p, idx);
        else Compat.click(mc, p, cid, menuSlot(idx), Compat.selectedSlot(p), ContainerInput.SWAP);
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
