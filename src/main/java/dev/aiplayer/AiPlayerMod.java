package dev.aiplayer;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.Arrays;
import java.util.Set;

public class AiPlayerMod implements ClientModInitializer {
    private static final Set<String> EVERYONE = Set.of("everyone", "everybody", "all", "tous", "tout", "tout le monde", "tous le monde");

    private static int killUsage() {
        Controller.INSTANCE.localMsg("§e[AI] usage: /ai kill <player> [chat]   or   /ai kill everyone [chat]");
        return 0;
    }

    /** /ai kill <joueur|everyone> [chat] : "chat" en dernier mot = l'IA parle pendant le combat. */
    private static int kill(String raw) {
        String[] t = raw.trim().split("\\s+");
        int n = t.length;
        boolean chat = false;
        if (n >= 2 && t[n - 1].equalsIgnoreCase("chat")) { chat = true; n--; }
        String target = String.join(" ", Arrays.copyOfRange(t, 0, n)).trim();
        if (target.isEmpty()) return killUsage();
        if (EVERYONE.contains(target.toLowerCase(java.util.Locale.ROOT))) return startMode(Controller.Mode.KILL_ALL, "", chat);
        return startMode(Controller.Mode.KILL, target, chat);
    }

    private static int startMode(Controller.Mode mode, String target, boolean chat) {
        Controller c = Controller.INSTANCE;
        if (c.player() == null) {
            c.localMsg("§c[AI] join a world first.");
            return 0;
        }
        c.setMode(mode, target, chat);
        String tail = chat ? " §7(chat on)" : "";
        switch (mode) {
            case DEFEND -> c.localMsg("§6[AI] DEFEND mode: guarding this spot, radius " + Config.INSTANCE.defendRadius + "." + tail + " §7/ai normal or /ai off to stop.");
            case KILL -> c.localMsg("§c[AI] KILL mode: hunting " + target + "." + tail + " §7/ai normal or /ai off to stop.");
            default -> c.localMsg("§4[AI] KILL EVERYONE mode." + tail + " §7/ai normal or /ai off to stop.");
        }
        return 1;
    }

    /** /ai settings : l'écran est ouvert au tick suivant, sinon la fermeture du chat l'écraserait aussitôt. */
    private static volatile boolean openSettings;

    @Override
    public void onInitializeClient() {
        Config.load();
        PauseMenuButton.register();

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (openSettings && mc.player != null) {
                openSettings = false;
                Compat.openScreen(mc, new SettingsScreen(Compat.screen(mc)));
            }
            Controller.INSTANCE.tick();
        });

        // messages de joueurs et messages système (affichés dans le chat)
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) ->
                Controller.INSTANCE.onChat(msg.getString(), true));
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) Controller.INSTANCE.onChat(msg.getString(), false);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommands.literal("ai")
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            Controller.INSTANCE.setEnabled(true);
                            Controller.INSTANCE.localMsg("§a[AI] enabled. Don't touch the keyboard, or use /ai off.");
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            Controller.INSTANCE.setEnabled(false);
                            Controller.INSTANCE.localMsg("§e[AI] disabled. You have control again.");
                            return 1;
                        }))
                        .then(ClientCommands.literal("reload").executes(ctx -> {
                            Config.load();
                            Controller.INSTANCE.localMsg("§a[AI] config reloaded (" + Config.INSTANCE.model + ")");
                            return 1;
                        }))
                        .then(ClientCommands.literal("settings").executes(ctx -> {
                            openSettings = true;
                            return 1;
                        }))
                        .then(ClientCommands.literal("config").executes(ctx -> {
                            openSettings = true;
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(ctx -> {
                            Controller.INSTANCE.localMsg("[AI] " + Controller.INSTANCE.status());
                            return 1;
                        }))
                        .then(ClientCommands.literal("ping").executes(ctx -> {
                            Controller.INSTANCE.ping();
                            return 1;
                        }))
                        .then(ClientCommands.literal("defend")
                                .executes(ctx -> startMode(Controller.Mode.DEFEND, "", false))
                                .then(ClientCommands.literal("chat").executes(ctx -> startMode(Controller.Mode.DEFEND, "", true))))
                        .then(ClientCommands.literal("kill")
                                .executes(ctx -> killUsage())
                                .then(ClientCommands.argument("target", StringArgumentType.greedyString())
                                        .executes(ctx -> kill(StringArgumentType.getString(ctx, "target")))))
                        .then(ClientCommands.literal("normal").executes(ctx -> {
                            if (Controller.INSTANCE.mode == Controller.Mode.NORMAL) {
                                Controller.INSTANCE.localMsg("§7[AI] already in normal mode.");
                            } else {
                                Controller.INSTANCE.setMode(Controller.Mode.NORMAL, "", false);
                                Controller.INSTANCE.localMsg("§a[AI] back to normal survival mode.");
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("tell")
                                .then(ClientCommands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                                    Controller.INSTANCE.onOwnerInstruction(StringArgumentType.getString(ctx, "text"));
                                    Controller.INSTANCE.localMsg("§7[AI] instruction sent");
                                    return 1;
                                })))));
    }
}
