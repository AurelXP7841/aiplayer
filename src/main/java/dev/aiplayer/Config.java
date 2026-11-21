package dev.aiplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config dans .minecraft/config/aiplayer.json (modifiable en jeu : /ai settings ou bouton dans le menu Échap).
 * Les valeurs initiales des champs ci-dessous SONT le preset par défaut ("Reset to defaults" de l'écran de réglages).
 */
public class Config {
    /** Version du format du fichier, pour migrer les anciens aiplayer.json. */
    public static final int CURRENT_VERSION = 2;
    public int configVersion = CURRENT_VERSION;

    public String url = "http://localhost:1234/api/v1/chat";
    public String model = "google/gemma-4-12b-qat";
    public int historySize = 15;          // derniers messages de chat envoyés à l'IA
    public int scanRadius = 30;           // rayon de scan des blocs
    public int requestTimeoutSeconds = 180; // attente max de la réponse de l'IA avant "échec". 0 = illimité
    public int maxActionsPerPlan = 8;
    public boolean showThoughts = true;   // affiche la "thought" de l'IA dans ton chat local
    public boolean allowAttackPlayers = false;
    public boolean verbose = true;        // messages de diagnostic gris dans le chat ("thinking...", "reply received..."). Mets false quand tout marche.
    public boolean keepRunningInPause = true; // solo : le menu Échap ne gèle plus le monde tant que l'IA est ON
    public double temperature = -1;       // >= 0 : envoyé à LM Studio (0.2-0.5 conseillé pour un 12B). -1 = non envoyé
    public String extraSystemPrompt = ""; // ex: "Tu t'appelles Bob. Parle français."
    public int maxOutputTokens = 1500;    // limite de génération envoyée à LM Studio. 0 = non envoyé
    public String reasoning = "off";      // thinking du modèle : "off" (défaut) = coupé. "" = non envoyé (LM Studio refuse si le modèle ne gère pas ce réglage)
    public boolean fallbackWander = true; // si le LLM échoue/timeout : le bot se promène au lieu de rester figé
    public boolean compactPrompt = false; // true : prompt système court (~1/3 des tokens) -> traitement plus rapide, mais un petit modèle suit un peu moins bien
    public int maxBlockTypes = 45;        // nb max de types de blocs listés dans BLOCKS (moins = prompt plus court)
    public int maxEntities = 30;          // nb max d'entités listées dans NEARBY ENTITIES

    // ---- modes de combat : /ai defend, /ai kill <joueur>, /ai kill everyone (+ "chat" à la fin de la commande) ----
    // (le nombre de messages de chat donnés à l'IA est historySize plus haut : 1 = juste le dernier, 100 = beaucoup de contexte)
    public int defendRadius = 20;         // /ai defend : rayon (blocs) protégé autour du point où tu tapes la commande
    public String defendPrompt = "";      // consignes du mode defend. "" = texte intégré (ModePrompts). Joker : {RADIUS}
    public String killPrompt = "";        // consignes du mode kill <joueur>. "" = texte intégré. Joker : {TARGET}
    public String killAllPrompt = "";     // consignes du mode kill everyone. "" = texte intégré
    public String chatPrompt = "";        // ajouté quand la commande finit par "chat" (l'IA parle pendant le combat). "" = texte intégré

    public static Config INSTANCE = new Config();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiplayer.json");
    }

    /** Remet TOUS les réglages au preset par défaut (les valeurs des champs ci-dessus). Ne sauvegarde pas. */
    public static Config defaults() {
        return new Config();
    }

    /** Enregistre la config actuelle (utilisé par l'écran de réglages). */
    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(INSTANCE));
        } catch (Exception e) {
            System.err.println("[aiplayer] config save error: " + e);
        }
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                String text = Files.readString(p);
                Config c = GSON.fromJson(text, Config.class);
                if (c != null) {
                    // Gson laisse l'initialiseur du champ quand il est absent du fichier : on lit donc la version dans le JSON brut.
                    JsonObject raw = JsonParser.parseString(text).getAsJsonObject();
                    int fileVersion = raw.has("configVersion") ? raw.get("configVersion").getAsInt() : 1;
                    if (fileVersion < 2) c.reasoning = "off"; // v2 : le thinking est coupé par défaut
                    c.configVersion = CURRENT_VERSION;
                    INSTANCE = c;
                }
            }
            Files.writeString(p, GSON.toJson(INSTANCE)); // réécrit avec les nouveaux champs
        } catch (Exception e) {
            System.err.println("[aiplayer] config error: " + e);
        }
    }
}
