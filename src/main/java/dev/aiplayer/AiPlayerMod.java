package dev.aiplayer;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public class AiPlayerMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Config.load();

        ClientTickEvents.START_CLIENT_TICK.register(mc -> Controller.INSTANCE.tick());

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
                        .then(ClientCommands.literal("status").executes(ctx -> {
                            Controller.INSTANCE.localMsg("[AI] " + Controller.INSTANCE.status());
                            return 1;
                        }))
                        .then(ClientCommands.literal("ping").executes(ctx -> {
                            Controller.INSTANCE.ping();
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
