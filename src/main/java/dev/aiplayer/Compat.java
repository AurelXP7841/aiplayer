package dev.aiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * TOUS les appels Minecraft les plus fragiles d'une version à l'autre sont ici.
 * Si la compilation casse sur 26.3, c'est (presque) toujours dans ce fichier
 * (ex: ContainerInput a pu être renommé, signatures de useItemOn / interact...).
 */
public final class Compat {
    private Compat() {}

    public static void attack(Minecraft mc, LocalPlayer p, Entity e) {
        mc.gameMode.attack(p, e);
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** Appelé à chaque tick : démarre puis continue la casse du bloc. */
    public static void mine(Minecraft mc, LocalPlayer p, BlockPos pos, Direction face) {
        mc.gameMode.continueDestroyBlock(pos, face);
        p.swing(InteractionHand.MAIN_HAND);
    }

    public static void stopMine(Minecraft mc) {
        mc.gameMode.stopDestroyBlock();
    }

    public static void useOn(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
    }

    public static void interact(Minecraft mc, LocalPlayer p, Entity e) {
        mc.gameMode.interact(p, e, new EntityHitResult(e, e.position().add(0, e.getBbHeight() * 0.6, 0)), InteractionHand.MAIN_HAND);
    }

    public static void click(Minecraft mc, LocalPlayer p, int containerId, int slot, int button, ContainerInput type) {
        mc.gameMode.handleContainerInput(containerId, slot, button, type, p);
    }

    public static void sendChat(LocalPlayer p, String text) {
        p.connection.sendChat(text);
    }

    public static void selectSlot(LocalPlayer p, int i) {
        p.getInventory().setSelectedSlot(i);
    }

    public static int selectedSlot(LocalPlayer p) {
        return p.getInventory().getSelectedSlot();
    }

    public static void openInventory(Minecraft mc, LocalPlayer p) {
        mc.gui.setScreen(new InventoryScreen(p));
    }

    // ---- ajoutés pour le contrôle direct (sans touches clavier) ----

    /** true si le monde est gelé (solo + écran pause). Avec le mixin ça ne doit plus arriver quand l'IA est ON. */
    public static boolean isPaused(Minecraft mc) {
        return mc.isPaused();
    }

    /** Clic droit "direct" avec l'objet en main (manger, arc, bouclier, lancer...). Pas de touche simulée. */
    public static void startUse(Minecraft mc, LocalPlayer p) {
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
    }

    /** Relâche le clic droit (tire l'arc, arrête de manger/bloquer). */
    public static void releaseUse(Minecraft mc, LocalPlayer p) {
        mc.gameMode.releaseUsingItem(p);
    }

    /** true si l'écran ouvert est un inventaire/coffre/table (pas le menu Échap, pas le chat). */
    public static boolean isContainerScreen(Minecraft mc) {
        return mc.gui.screen() instanceof AbstractContainerScreen<?>;
    }

    /** Ferme l'écran seulement si c'est un conteneur : on ne ferme JAMAIS le menu Échap de l'humain. */
    public static void closeContainerScreen(Minecraft mc, LocalPlayer p) {
        if (isContainerScreen(mc)) p.closeContainer();
    }

    public static boolean isFood(ItemStack s) {
        return s.has(DataComponents.FOOD);
    }

    /** Écran actuellement ouvert (null = aucun). En 26.x : mc.gui.screen() (le champ mc.screen n'existe plus). */
    public static Screen screen(Minecraft mc) {
        return mc.gui.screen();
    }
}
