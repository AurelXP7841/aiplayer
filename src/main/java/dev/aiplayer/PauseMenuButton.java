package dev.aiplayer;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/**
 * Ajoute un bouton "AI Player" (en haut à gauche) dans le menu Échap pour ouvrir l'écran de réglages.
 * Isolé dans sa propre classe : si l'API d'écrans de Fabric change, c'est le seul fichier à adapter
 * (la commande /ai settings marche sans lui).
 */
public final class PauseMenuButton {
    private PauseMenuButton() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof PauseScreen) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("AI Player"),
                        b -> Compat.openScreen(client, new SettingsScreen(screen)))
                        .bounds(6, 6, 80, 20).build());
            }
        });
    }
}
