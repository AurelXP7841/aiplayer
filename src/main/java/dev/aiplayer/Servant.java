package dev.aiplayer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mode /ai beafriend : l'IA est un serviteur qui obeit au chat.
 * Ce fichier contient : la base fixe, la lecture du chat (expediteur, messages a soi), la permission d'attaquer un joueur
 * (accordee seulement si un joueur le demande dans le chat) et les faits calcules (MISSION HINTS) envoyes au modele.
 */
public final class Servant {
    private Servant() {}

    /** Base fixe du serviteur. Pour la changer : modifier ces trois nombres. */
    public static final BlockPos BASE = new BlockPos(4287947, 84, -7914829);

    private static final long ATTACK_OK_MS = 10 * 60 * 1000L;
    /** joueurs (en minuscules) qu'un joueur a demande d'attaquer, avec l'heure de la demande. */
    private static final Map<String, Long> attackOk = new HashMap<>();

    private static final Pattern ATTACK = Pattern.compile(
            "(?<!\\p{L})(?:kill|tu(?:e|es|er|ez)|attack\\w*|attaqu\\w*|frapp\\w*|bats|bat|butte\\w*|fight|hit|massacre\\w*"
                    + "|[\u00e9e]limine\\w*|[\u00e9e]clate\\w*|d[\u00e9e]fonce\\w*)(?!\\p{L})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STOP = Pattern.compile(
            "(?<!\\p{L})(?:stop|arr[\u00eae]te\\w*|annule\\w*|cancel|laisse\\w*|[\u00e7c]a suffit|enough|[\u00e9e]pargne\\w*)(?!\\p{L})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static void reset() { attackOk.clear(); }

    // ---------------------------------------------------------------- lecture du chat
    /** Partie avant le premier ':' '>' ou '»' (les 60 premiers caracteres) : "<Steve", "Steve", "[VIP] Steve "... Vide si absente. */
    public static String header(String text) {
        int lim = Math.min(text.length(), 60);
        for (int i = 0; i < lim; i++) {
            char ch = text.charAt(i);
            if (ch == ':' || ch == '>' || ch == '\u00bb' || ch == '\u203a') return text.substring(0, i);
        }
        return "";
    }

    private static String body(String text) {
        String h = header(text);
        return h.isEmpty() ? text : text.substring(h.length() + 1);
    }

    /** Vrai si le message vient de l'IA elle-meme (pour ne pas repondre a ses propres phrases). */
    public static boolean isOwn(String text, String me) {
        String h = header(text);
        return !h.isEmpty() && h.toLowerCase(Locale.ROOT).contains(me.toLowerCase(Locale.ROOT));
    }

    private static String sender(String text) {
        String h = header(text).replaceAll("\\[[^\\]]*\\]", " ").replaceAll("[<\\s]+", " ").trim();
        int sp = h.lastIndexOf(' ');
        return sp >= 0 ? h.substring(sp + 1) : h;
    }

    /** Appele pour chaque message d'un AUTRE joueur : met a jour la liste des joueurs qu'on a le droit d'attaquer. */
    public static void onMessage(Controller c, String text) {
        ClientLevel level = c.level();
        if (level == null) return;
        String body = body(text);
        if (STOP.matcher(body).find()) attackOk.clear();
        if (!ATTACK.matcher(body).find()) return;
        String sender = sender(text);
        LocalPlayer me = c.player();
        for (Player pl : level.players()) {
            if (pl == me) continue;
            String n = pl.getName().getString();
            if (n.equalsIgnoreCase(sender)) continue; // on ne peut pas demander de s'attaquer soi-meme
            if (Pattern.compile("(?<![\\w])" + Pattern.quote(n) + "(?![\\w])", Pattern.CASE_INSENSITIVE).matcher(body).find()) {
                attackOk.put(n.toLowerCase(Locale.ROOT), System.currentTimeMillis());
            }
        }
    }

    /** Filtre appele par Controller.mayAttackPlayer : un joueur ne peut etre frappe que si le chat l'a demande. */
    public static boolean mayAttack(String playerName) {
        Long t = attackOk.get(playerName.toLowerCase(Locale.ROOT));
        return t != null && System.currentTimeMillis() - t < ATTACK_OK_MS;
    }

    // ---------------------------------------------------------------- faits calcules
    private static String type(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /** Section MISSION HINTS du mode beafriend : base, joueurs (ids), creatures proches (ids), permissions d'attaque. */
    public static void hints(StringBuilder sb, Controller c, LocalPlayer p, ClientLevel level, List<Entity> ents) {
        sb.append("your_name=").append(p.getName().getString()).append(" (players call you by this name)\n");
        double bd = Math.sqrt(p.distanceToSqr(BASE.getX() + 0.5, BASE.getY(), BASE.getZ() + 0.5));
        sb.append(String.format(Locale.ROOT,
                "base=(%d,%d,%d) your_distance_to_base=%.0f. Go there ONLY if a player asks you to. Command: {\"type\":\"move_to\",\"x\":%d,\"y\":%d,\"z\":%d,\"range\":2,\"sprint\":true}\n",
                BASE.getX(), BASE.getY(), BASE.getZ(), bd, BASE.getX(), BASE.getY(), BASE.getZ()));

        int np = 0;
        for (Player pl : level.players()) {
            if (pl == p) continue;
            if (np == 0) sb.append("players (use these ids):\n");
            if (np++ >= 10) break;
            sb.append(String.format(Locale.ROOT, "id=%d %s dist_to_you=%.1f pos=(%d,%d,%d)\n",
                    pl.getId(), pl.getName().getString(), p.distanceTo(pl), pl.getBlockX(), pl.getBlockY(), pl.getBlockZ()));
        }
        if (np == 0) sb.append("players: none loaded around you\n");

        int n = 0;
        for (Entity e : ents) {
            if (!(e instanceof LivingEntity) || e instanceof Player || !e.isAlive()) continue;
            if (n == 0) sb.append("creatures nearby (nearest first; near_player = the player closest to it, useful for 'kill THIS horse'):\n");
            if (n++ >= 8) break;
            Player near = null;
            double nd = Double.MAX_VALUE;
            for (Player pl : level.players()) {
                if (pl == p) continue;
                double d = pl.distanceTo(e);
                if (d < nd) { nd = d; near = pl; }
            }
            sb.append(String.format(Locale.ROOT, "id=%d %s%s dist_to_you=%.1f%s\n",
                    e.getId(), type(e), e instanceof Enemy ? " HOSTILE" : "", p.distanceTo(e),
                    near == null ? "" : String.format(Locale.ROOT, " near_player=%s(%.1f)", near.getName().getString(), nd)));
        }
        if (n == 0) sb.append("creatures nearby: none\n");

        long now = System.currentTimeMillis();
        attackOk.entrySet().removeIf(en -> now - en.getValue() > ATTACK_OK_MS);
        StringBuilder ok = new StringBuilder();
        for (String name : attackOk.keySet()) ok.append(ok.length() == 0 ? "" : ", ").append(name);
        sb.append("players you may attack right now (a player asked in chat): ").append(ok.length() == 0 ? "none" : ok.toString()).append('\n');
    }
}
