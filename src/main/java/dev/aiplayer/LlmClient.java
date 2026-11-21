package dev.aiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Appelle l'API native de LM Studio : POST /api/v1/chat {model, system_prompt, input} */
public final class LlmClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private LlmClient() {}

    public static CompletableFuture<String> chat(String system, String input) {
        Config cfg = Config.INSTANCE;
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("system_prompt", system);
        body.addProperty("input", input);
        if (cfg.temperature >= 0) body.addProperty("temperature", cfg.temperature);
        if (cfg.maxOutputTokens > 0) body.addProperty("max_output_tokens", cfg.maxOutputTokens);
        if (cfg.reasoning != null && !cfg.reasoning.isBlank()) body.addProperty("reasoning", cfg.reasoning);

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(cfg.url));
        // requestTimeoutSeconds <= 0 : illimité (on n'appelle pas timeout(), le client HTTP attend indéfiniment)
        if (cfg.requestTimeoutSeconds > 0) rb.timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds));
        HttpRequest req = rb
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() / 100 != 2) {
                String b = r.body();
                throw new RuntimeException("HTTP " + r.statusCode() + ": " + b.substring(0, Math.min(200, b.length())));
            }
            return extractText(r.body());
        });
    }

    /** Liste les modèles chargés/disponibles dans LM Studio (GET /v1/models sur le même hôte que chatUrl). */
    public static CompletableFuture<List<String>> listModels(String chatUrl) {
        URI base = URI.create(chatUrl);
        String root = base.getScheme() + "://" + base.getAuthority();
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + "/v1/models"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + r.statusCode());
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonArray arr = o.has("data") ? o.getAsJsonArray("data")
                    : o.has("models") ? o.getAsJsonArray("models") : new JsonArray();
            List<String> out = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                String id = m.has("id") ? m.get("id").getAsString() : m.has("key") ? m.get("key").getAsString() : null;
                if (id != null && !id.toLowerCase().contains("embed")) out.add(id);
            }
            return out;
        });
    }

    /** Réponse LM Studio : {"output":[{"type":"reasoning",...},{"type":"message","content":"..."}]} */
    static String extractText(String raw) {
        JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
        StringBuilder sb = new StringBuilder();
        if (o.has("output") && o.get("output").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("output")) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                String type = item.has("type") ? item.get("type").getAsString() : "message";
                if (!type.equals("message")) continue; // ignore "reasoning"
                JsonElement c = item.get("content");
                if (c == null) continue;
                if (c.isJsonPrimitive()) sb.append(c.getAsString());
                else if (c.isJsonArray()) for (JsonElement part : (JsonArray) c) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("text"))
                        sb.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
        } else if (o.has("choices")) { // fallback format OpenAI
            sb.append(o.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString());
        }
        return sb.length() > 0 ? sb.toString() : raw;
    }

    /** Extrait le 1er objet JSON d'un texte (gère ```json ... ``` et blabla autour). */
    public static JsonObject extractJson(String text) {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("no JSON in reply: " + text);
        return JsonParser.parseString(text.substring(a, b + 1)).getAsJsonObject();
    }
}
