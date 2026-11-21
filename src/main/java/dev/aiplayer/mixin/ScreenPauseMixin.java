package dev.aiplayer.mixin;

import dev.aiplayer.Controller;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * En solo, un écran "pause" (Options, Succès, ...) gèle le monde. Tant que l'IA joue, on dit au jeu
 * que ces écrans ne sont PAS des écrans de pause => le monde continue de tourner.
 * (PauseScreen, le menu Échap, redéfinit isPauseScreen : voir PauseScreenMixin.)
 * require = 0 : si la méthode n'existe plus dans une future version, le mixin est ignoré sans crash.
 */
@Mixin(Screen.class)
public abstract class ScreenPauseMixin {
    @Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void aiplayer$keepRunning(CallbackInfoReturnable<Boolean> cir) {
        if (Controller.INSTANCE.shouldKeepWorldRunning()) cir.setReturnValue(false);
    }
}
