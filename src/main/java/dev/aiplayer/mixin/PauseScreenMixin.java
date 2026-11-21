package dev.aiplayer.mixin;

import dev.aiplayer.Controller;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Le menu Échap : en solo il met le monde en pause. Avec l'IA ON, il ne pause plus rien. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void aiplayer$keepRunning(CallbackInfoReturnable<Boolean> cir) {
        if (Controller.INSTANCE.shouldKeepWorldRunning()) cir.setReturnValue(false);
    }
}
