package dev.aiplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Config dans .minecraft/config/aiplayer.json */
public class Config {
    public String url = "http://localhost:1234/api/v1/chat";
    public String model = "google/gemma-4-12b-qat";
    public int historySize = 15;          // derniers messages de chat envoyés à l'IA
    public int scanRadius = 30;           // rayon de scan des blocs
    public int requestTimeoutSeconds = 180;
    public int maxActionsPerPlan = 8;
    public boolean showThoughts = true;   // affiche la "thought" de l'IA dans ton chat local
    public boolean allowAttackPlayers = false;
    public boolean keepRunningInPause = true; // solo : le menu Échap ne gèle plus le monde tant que l'IA est ON
    public double temperature = -1;       // >= 0 : envoyé à LM Studio (0.2-0.5 conseillé pour un 12B). -1 = non envoyé
    public String extraSystemPrompt = ""; // ex: "Tu t'appelles Bob. Parle français."

    public static Config INSTANCE = new Config();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiplayer.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) INSTANCE = c;
            }
            Files.writeString(p, GSON.toJson(INSTANCE)); // réécrit avec les nouveaux champs
        } catch (Exception e) {
            System.err.println("[aiplayer] config error: " + e);
        }
    }
}
