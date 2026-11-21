package dev.aiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Écran de réglages : /ai settings, ou le bouton "AI Player" du menu Échap.
 * Volontairement sans surcharge de render() (signature instable d'une version à l'autre) : que des widgets standards.
 * Les valeurs de départ de "Reset to defaults" sont celles de {@link Config#defaults()} (= le preset du mod).
 */
public class SettingsScreen extends Screen {
    /** Valeurs du réglage "thinking" envoyées à LM Studio ("" = non envoyé). "off" est le défaut du mod. */
    private static final String[] REASONING = {"off", "", "low", "medium", "high", "on"};

    private final Screen parent;

    private EditBox host, port, model, timeout, temperature, maxTokens, scanRadius, history, maxActions, extra;
    private StringWidget statusWidget;

    // morceaux de l'URL qu'on ne modifie pas dans l'écran (http/https et chemin /api/v1/chat)
    private String scheme, path;

    private String reasoning;
    private final boolean[] showThoughts = new boolean[1];
    private final boolean[] verbose = new boolean[1];
    private final boolean[] fallbackWander = new boolean[1];
    private final boolean[] keepRunning = new boolean[1];
    private final boolean[] attackPlayers = new boolean[1];

    // valeurs affichées dans les champs : sert à les garder si la fenêtre est redimensionnée (init() est rappelé)
    private final String[] texts = new String[10];
    private String statusText = "";

    private List<String> models = new ArrayList<>();

    public SettingsScreen(Screen parent) {
        super(Component.literal("AI Player settings"));
        this.parent = parent;
        load(Config.INSTANCE);
    }

    /** Remplit l'état de l'écran à partir d'une config (la config actuelle, ou le preset par défaut). */
    private void load(Config c) {
        String[] u = splitUrl(c.url);
        scheme = u[0];
        path = u[3];
        texts[0] = u[1];                                  // host
        texts[1] = u[2];                                  // port
        texts[2] = c.model;
        texts[3] = String.valueOf(c.requestTimeoutSeconds);
        texts[4] = c.temperature < 0 ? "-1" : String.valueOf(c.temperature);
        texts[5] = String.valueOf(c.maxOutputTokens);
        texts[6] = String.valueOf(c.scanRadius);
        texts[7] = String.valueOf(c.historySize);
        texts[8] = String.valueOf(c.maxActionsPerPlan);
        texts[9] = c.extraSystemPrompt == null ? "" : c.extraSystemPrompt;
        reasoning = c.reasoning == null ? "" : c.reasoning;
        showThoughts[0] = c.showThoughts;
        verbose[0] = c.verbose;
        fallbackWander[0] = c.fallbackWander;
        keepRunning[0] = c.keepRunningInPause;
        attackPlayers[0] = c.allowAttackPlayers;
    }

    /** "http://localhost:1234/api/v1/chat" -> {scheme, host, port, path}. */
    private static String[] splitUrl(String url) {
        try {
            URI u = URI.create(url.trim());
            String scheme = u.getScheme() == null ? "http" : u.getScheme();
            String host = u.getHost() == null ? "localhost" : u.getHost();
            int port = u.getPort() >= 0 ? u.getPort() : "https".equals(scheme) ? 443 : 80;
            String path = (u.getRawPath() == null ? "" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            return new String[]{scheme, host, String.valueOf(port), path};
        } catch (Exception e) {
            return splitUrl(Config.defaults().url);
        }
    }

    /** Relit les champs avant un init() (redimensionnement) pour ne pas perdre ce que l'humain a tapé. */
    private void remember() {
        EditBox[] boxes = {host, port, model, timeout, temperature, maxTokens, scanRadius, history, maxActions, extra};
        for (int i = 0; i < boxes.length; i++) if (boxes[i] != null) texts[i] = boxes[i].getValue();
    }

    @Override
    protected void init() {
        remember();
        int w = 340, left = this.width / 2 - w / 2;
        int fx = left + 110, fw = w - 110;                 // colonne des champs (lignes pleine largeur)
        int rowH = this.height < 330 ? 20 : 22;
        int total = 12 + rowH * 11 + 12;
        int y = Math.max(4, (this.height - total) / 2);

        // titre (le widget fait toute la largeur : le texte est centré par défaut)
        addRenderableWidget(new StringWidget(left, y, w, 9, Component.literal("AI Player settings"), this.font));
        y += 14;

        // host + port
        label(left, y, "Host / Port");
        host = box(fx, y, fw - 66, "Host", texts[0], 100);
        port = box(fx + fw - 60, y, 60, "Port", texts[1], 5);
        y += rowH;

        // modèle : champ texte (colle le nom exact) + boutons < > pour parcourir la liste récupérée
        label(left, y, "AI model name");
        model = box(fx, y, fw - 44, "Model", texts[2], 200);
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleModel(-1))
                .bounds(fx + fw - 42, y - 1, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleModel(1))
                .bounds(fx + fw - 20, y - 1, 20, 20).build());
        y += rowH;

        // timeout : 0 = illimité
        label(left, y, "Timeout (sec)");
        timeout = box(fx, y, 60, "Timeout", texts[3], 5);
        label(fx + 68, y, "0 = unlimited");
        y += rowH;

        // deux colonnes de champs numériques
        int c2 = left + 172;
        label(left, y, "Temp (-1=off)");
        temperature = box(left + 84, y, 80, "Temperature", texts[4], 6);
        label(c2, y, "Max tokens");
        maxTokens = box(c2 + 84, y, 80, "Max tokens", texts[5], 6);
        y += rowH;

        label(left, y, "Scan radius");
        scanRadius = box(left + 84, y, 80, "Scan radius", texts[6], 3);
        label(c2, y, "Chat lines");
        history = box(c2 + 84, y, 80, "Chat lines", texts[7], 3);
        y += rowH;

        label(left, y, "Actions/plan");
        maxActions = box(left + 84, y, 80, "Actions per plan", texts[8], 3);
        addRenderableWidget(Button.builder(Component.literal("Fetch models"), b -> fetchModels())
                .bounds(c2, y - 1, 168, 20).build());
        y += rowH;

        label(left, y, "Extra prompt");
        extra = box(fx, y, fw, "Extra system prompt", texts[9], 500);
        y += rowH;

        // boutons : 2 colonnes
        int bw = w / 2 - 2;
        addRenderableWidget(Button.builder(Component.literal(reasoningLabel()), b -> {
            int i = 0;
            for (int k = 0; k < REASONING.length; k++) if (REASONING[k].equals(reasoning)) i = k;
            reasoning = REASONING[(i + 1) % REASONING.length];
            b.setMessage(Component.literal(reasoningLabel()));
        }).bounds(left, y, bw, 20).build());
        toggle(left + w / 2 + 2, y, bw, "Show thoughts", showThoughts);
        y += rowH;
        toggle(left, y, bw, "Verbose chat", verbose);
        toggle(left + w / 2 + 2, y, bw, "Wander if AI down", fallbackWander);
        y += rowH;
        toggle(left, y, bw, "Run in Esc menu", keepRunning);
        toggle(left + w / 2 + 2, y, bw, "Attack players", attackPlayers);
        y += rowH;

        int b3 = (w - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left, y, b3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> resetDefaults())
                .bounds(left + b3 + 4, y, b3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + 2 * (b3 + 4), y, b3, 20).build());
        y += rowH;

        // ligne d'état (modèles trouvés, erreurs de saisie...)
        statusWidget = new StringWidget(left, y + 2, w, 9, Component.literal(statusText), this.font);
        addRenderableWidget(statusWidget);
    }

    // ---------------------------------------------------------------- aides UI

    /** Étiquette alignée à gauche : le widget a la largeur exacte du texte, donc le centrage ne décale rien. */
    private void label(int x, int y, String text) {
        addRenderableWidget(new StringWidget(x, y + 5, this.font.width(text), 9, Component.literal(text), this.font));
    }

    private EditBox box(int x, int y, int w, String name, String value, int maxLen) {
        EditBox e = new EditBox(this.font, x, y, w, 18, Component.literal(name));
        e.setMaxLength(maxLen);
        e.setValue(value);
        addRenderableWidget(e);
        return e;
    }

    private void toggle(int x, int y, int w, String name, boolean[] ref) {
        addRenderableWidget(Button.builder(Component.literal(toggleLabel(name, ref[0])), b -> {
            ref[0] = !ref[0];
            b.setMessage(Component.literal(toggleLabel(name, ref[0])));
        }).bounds(x, y, w, 20).build());
    }

    private static String toggleLabel(String name, boolean on) {
        return name + ": " + (on ? "ON" : "OFF");
    }

    private String reasoningLabel() {
        return "Thinking: " + (reasoning.isEmpty() ? "default (not sent)" : reasoning.toUpperCase(Locale.ROOT));
    }

    private void setStatus(String s) {
        statusText = s;
        if (statusWidget != null) statusWidget.setMessage(Component.literal(s));
    }

    // ---------------------------------------------------------------- modèles

    private String currentChatUrl() {
        return scheme + "://" + host.getValue().trim() + ":" + port.getValue().trim() + path;
    }

    private void fetchModels() {
        setStatus("Fetching models...");
        try {
            LlmClient.listModels(currentChatUrl()).whenComplete((list, err) -> Minecraft.getInstance().execute(() -> {
                if (err != null) {
                    Throwable t = err.getCause() != null ? err.getCause() : err;
                    setStatus("Error: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage()));
                    return;
                }
                models = list;
                setStatus(list.isEmpty() ? "No model found (is a model loaded?)" : list.size() + " model(s): use < > to choose");
            }));
        } catch (Exception e) {
            setStatus("Bad host/port: " + e.getMessage());
        }
    }

    private void cycleModel(int dir) {
        if (models.isEmpty()) { fetchModels(); return; }
        int i = models.indexOf(model.getValue().trim());
        i = i < 0 ? (dir > 0 ? 0 : models.size() - 1) : Math.floorMod(i + dir, models.size());
        model.setValue(models.get(i));
    }

    // ---------------------------------------------------------------- défauts / sauvegarde

    private void resetDefaults() {
        load(Config.defaults());
        statusText = "Defaults loaded - press Save to keep them.";
        // init() relit les anciens champs (remember) : on les oublie pour qu'il reparte des valeurs par défaut chargées
        host = port = model = timeout = temperature = maxTokens = scanRadius = history = maxActions = extra = null;
        rebuildWidgets(); // reconstruit l'écran à partir de l'état chargé
    }

    private static int parseInt(EditBox e, int def, int min, int max) {
        try {
            int v = Integer.parseInt(e.getValue().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception ex) {
            return def;
        }
    }

    private static double parseDouble(EditBox e, double def) {
        try {
            double v = Double.parseDouble(e.getValue().trim().replace(',', '.'));
            return v < 0 ? -1 : Math.min(v, 2.0);
        } catch (Exception ex) {
            return def;
        }
    }

    private void save() {
        Config c = Config.INSTANCE;

        // adresse du serveur = schéma + host + port + chemin (inchangé)
        String h = host.getValue().trim();
        int p;
        try {
            p = Integer.parseInt(port.getValue().trim());
        } catch (Exception e) {
            p = -1;
        }
        if (h.isEmpty() || h.contains(" ") || p < 1 || p > 65535) {
            setStatus("Invalid host or port (port must be 1-65535).");
            return;
        }
        String newUrl = scheme + "://" + h + ":" + p + path;
        try {
            URI.create(newUrl);
        } catch (Exception e) {
            setStatus("Invalid host: " + e.getMessage());
            return;
        }
        c.url = newUrl;

        String m = model.getValue().trim();
        if (!m.isEmpty()) c.model = m;
        c.temperature = parseDouble(temperature, c.temperature);
        c.requestTimeoutSeconds = parseInt(timeout, c.requestTimeoutSeconds, 0, 86400); // 0 = illimité
        c.maxOutputTokens = parseInt(maxTokens, c.maxOutputTokens, 0, 32768);
        c.scanRadius = parseInt(scanRadius, c.scanRadius, 4, 64);
        c.historySize = parseInt(history, c.historySize, 1, 50);
        c.maxActionsPerPlan = parseInt(maxActions, c.maxActionsPerPlan, 1, 20);
        c.extraSystemPrompt = extra.getValue();
        c.reasoning = reasoning;
        c.showThoughts = showThoughts[0];
        c.verbose = verbose[0];
        c.fallbackWander = fallbackWander[0];
        c.keepRunningInPause = keepRunning[0];
        c.allowAttackPlayers = attackPlayers[0];
        Config.save();
        Controller.INSTANCE.localMsg(String.format(Locale.ROOT,
                "§a[AI] settings saved: %s, model %s, thinking %s, timeout %s",
                c.url, c.model, c.reasoning.isEmpty() ? "default" : c.reasoning,
                c.requestTimeoutSeconds == 0 ? "unlimited" : c.requestTimeoutSeconds + "s"));
        onClose();
    }

    @Override
    public void onClose() {
        Compat.openScreen(Minecraft.getInstance(), parent);
    }
}
