package dev.aiplayer;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * CONTRÔLE DIRECT DU JOUEUR (aucune touche clavier simulée).
 *
 * Le jeu lit le mouvement du joueur dans {@code LocalPlayer.input}. Normalement c'est un KeyboardInput
 * (qui lit les touches ZQSD...). On le remplace par celui-ci, qui lit à la place l'état du {@link Controller}
 * (forward / jump / sneak / sprint). Conséquences :
 *  - ça marche même avec un écran ouvert (menu Échap, inventaire, fenêtre sans focus) ;
 *  - le clavier réel de l'humain n'a plus aucun effet sur le déplacement tant que l'IA est ON ;
 *  - quand l'IA est OFF on remet l'input d'origine.
 *
 * FRAGILE (comme Compat.java) : si ça ne compile plus après une mise à jour de Minecraft, c'est ici.
 * Référence 1.21.11 : ClientInput { public Input keyPresses; protected Vec2 moveVector; void tick(); },
 * Input = record(forward, backward, left, right, jump, shift, sprint), Vec2(x = gauche, y = avant).
 */
public final class AiInput extends ClientInput {
    private final ClientInput original;

    private AiInput(ClientInput original) { this.original = original; }

    @Override
    public void tick() {
        Controller c = Controller.INSTANCE;
        this.keyPresses = new Input(c.forward, false, false, false, c.jump, c.sneak, c.sprint);
        this.moveVector = new Vec2(0f, c.forward ? 1f : 0f);
    }

    /** À appeler à chaque tick actif : (ré)installe l'input IA, y compris après un respawn (nouveau LocalPlayer). */
    public static void install(LocalPlayer p) {
        if (!(p.input instanceof AiInput)) p.input = new AiInput(p.input);
    }

    public static void uninstall(LocalPlayer p) {
        if (p.input instanceof AiInput ai) p.input = ai.original;
    }
}
