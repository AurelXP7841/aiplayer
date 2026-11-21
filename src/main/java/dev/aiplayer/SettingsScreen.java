package dev.aiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Écran de réglages : /ai settings.
 * Volontairement sans surcharge de render() (signature instable d'une version à l'autre) : que des widgets standards.
 */
public class SettingsScreen extends Screen {
    private static final String[] REASONING = {"", "off", "low", "medium", "high", "on"};

    private final Screen parent;

    private EditBox url, model, temperature, timeout, maxTokens, scanRadius, history, maxActions, extra;
    private Button reasoningBtn;
    private StringWidget statusWidget;

    private String reasoning;
    private final boolean[] showThoughts = new boolean[1];
    private final boolean[] verbose = new boolean[1];
    private final boolean[] fallbackWander = new boolean[1];
    private final boolean[] keepRunning = new boolean[1];
    private final boolean[] attackPlayers = new boolean[1];

    private List<String> models = new ArrayList<>();

    public SettingsScreen(Screen parent) {
        super(Component.literal("AI Player settings"));
        this.parent = parent;
        Config c = Config.INSTANCE;
        reasoning = c.reasoning == null ? "" : c.reasoning;
        showThoughts[0] = c.showThoughts;
        verbose[0] = c.verbose;
        fallbackWander[0] = c.fallbackWander;
        keepRunning[0] = c.keepRunningInPause;
        attackPlayers[0] = c.allowAttackPlayers;
    }

    @Override
    protected void init() {
        Config c = Config.INSTANCE;
        int w = 340, left = this.width / 2 - w / 2, labelW = 110;
        int fx = left + labelW, fw = w - labelW;
        int rowH = this.height < 340 ? 20 : 22;
        int top = Math.max(6, (this.height - rowH * 14) / 2);
        int y = top;

        url = field(left, fx, y, fw, "Server URL", c.url, 200); y += rowH;

        // modèle : champ texte + boutons < > pour parcourir la liste récupérée
        label(left, y, "Model");
        model = new EditBox(this.font, fx, y, fw - 44, 18, Component.literal("Model"));
        model.setMaxLength(200);
        model.setValue(c.model);
        addRenderableWidget(model);
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleModel(-1))
                .bounds(fx + fw - 42, y - 1, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleModel(1))
                .bounds(fx + fw - 20, y - 1, 20, 20).build());
        y += rowH;

        temperature = field(left, fx, y, fw, "Temperature (-1 = off)", c.temperature < 0 ? "-1" : String.valueOf(c.temperature), 8); y += rowH;
        timeout = field(left, fx, y, fw, "Timeout (seconds)", String.valueOf(c.requestTimeoutSeconds), 5); y += rowH;
        maxTokens = field(left, fx, y, fw, "Max output tokens", String.valueOf(c.maxOutputTokens), 6); y += rowH;
        scanRadius = field(left, fx, y, fw, "Scan radius (blocks)", String.valueOf(c.scanRadius), 3); y += rowH;
        history = field(left, fx, y, fw, "Chat history lines", String.valueOf(c.historySize), 3); y += rowH;
        maxActions = field(left, fx, y, fw, "Max actions per plan", String.valueOf(c.maxActionsPerPlan), 3); y += rowH;
        extra = field(left, fx, y, fw, "Extra system prompt", c.extraSystemPrompt == null ? "" : c.extraSystemPrompt, 500); y += rowH;

        // boutons : 2 colonnes
        int bw = w / 2 - 2;
        reasoningBtn = Button.builder(Component.literal(reasoningLabel()), b -> {
            int i = 0;
            for (int k = 0; k < REASONING.length; k++) if (REASONING[k].equals(reasoning)) i = k;
            reasoning = REASONING[(i + 1) % REASONING.length];
            b.setMessage(Component.literal(reasoningLabel()));
        }).bounds(left, y, bw, 20).build();
        addRenderableWidget(reasoningBtn);
        toggle(left + w / 2 + 2, y, bw, "Show thoughts", showThoughts); y += rowH;
        toggle(left, y, bw, "Verbose chat", verbose);
        toggle(left + w / 2 + 2, y, bw, "Wander if LLM down", fallbackWander); y += rowH;
        toggle(left, y, bw, "Run in Esc menu", keepRunning);
        toggle(left + w / 2 + 2, y, bw, "Attack players", attackPlayers); y += rowH;

        // statut + récupération des modèles
        addRenderableWidget(Button.builder(Component.literal("Fetch models"), b -> fetchModels())
                .bounds(left, y, 100, 20).build());
        statusWidget = new StringWidget(left + 108, y + 6, w - 108, 9, Component.literal(""), this.font);
        statusWidget.alignLeft();
        addRenderableWidget(statusWidget);
        y += rowH;

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + w / 2 + 2, y, bw, 20).build());
    }

    // ---------------------------------------------------------------- aides UI

    private void label(int left, int y, String text) {
        StringWidget l = new StringWidget(left, y + 5, 108, 9, Component.literal(text), this.font);
        l.alignLeft();
        addRenderableWidget(l);
    }

    private EditBox field(int left, int fx, int y, int fw, String label, String value, int maxLen) {
        label(left, y, label);
        EditBox e = new EditBox(this.font, fx, y, fw, 18, Component.literal(label));
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
        return "Thinking: " + (reasoning.isEmpty() ? "default (not sent)" : reasoning);
    }

    private void setStatus(String s) {
        if (statusWidget != null) statusWidget.setMessage(Component.literal(s));
    }

    // ---------------------------------------------------------------- modèles

    private void fetchModels() {
        setStatus("Fetching models...");
        final String chatUrl = url.getValue().trim();
        try {
            LlmClient.listModels(chatUrl).whenComplete((list, err) -> Minecraft.getInstance().execute(() -> {
                if (err != null) {
                    Throwable t = err.getCause() != null ? err.getCause() : err;
                    setStatus("Error: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage()));
                    return;
                }
                models = list;
                setStatus(list.isEmpty() ? "No model found (is a model loaded?)" : list.size() + " model(s): use < > to choose");
            }));
        } catch (Exception e) {
            setStatus("Bad URL: " + e.getMessage());
        }
    }

    private void cycleModel(int dir) {
        if (models.isEmpty()) { fetchModels(); return; }
        int i = models.indexOf(model.getValue().trim());
        i = i < 0 ? (dir > 0 ? 0 : models.size() - 1) : Math.floorMod(i + dir, models.size());
        model.setValue(models.get(i));
    }

    // ---------------------------------------------------------------- sauvegarde

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
        String u = url.getValue().trim();
        if (!u.isEmpty()) c.url = u;
        String m = model.getValue().trim();
        if (!m.isEmpty()) c.model = m;
        c.temperature = parseDouble(temperature, c.temperature);
        c.requestTimeoutSeconds = parseInt(timeout, c.requestTimeoutSeconds, 10, 600);
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
        Controller.INSTANCE.localMsg(String.format(Locale.ROOT, "§a[AI] settings saved (model %s, thinking %s)",
                c.model, c.reasoning.isEmpty() ? "default" : c.reasoning));
        onClose();
    }

    @Override
    public void onClose() {
        Compat.openScreen(Minecraft.getInstance(), parent);
    }
}
