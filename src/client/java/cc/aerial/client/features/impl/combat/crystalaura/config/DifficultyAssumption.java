package cc.aerial.client.features.impl.combat.crystalaura.config;

import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

/**
 * Which difficulty the damage model should scale player damage by.
 *
 * <h2>Why this is a setting at all</h2>
 *
 * <p>Vanilla scales damage to a PLAYER by the difficulty and zeroes it outright on peaceful
 * ({@code Player.hurtServer}). That step runs on the server, with the SERVER's difficulty, and the client only
 * knows what it was told: the difficulty arrives in a packet, and a proxy, a protocol translation or a server
 * that simply never sends one leaves the client's copy at its default. A client that has never been told reads
 * peaceful, the model then answers zero damage for every placement, and the whole module goes quiet with no
 * error anywhere — which is exactly the failure this enum exists to prevent.
 *
 * <p>{@link #AUTO} therefore does NOT trust a peaceful reading on a server. Nobody runs player-versus-player on
 * a peaceful server, so peaceful there is far more likely to mean "never synced" than "really peaceful", and
 * the two possible mistakes are not equal: assuming normal on a genuinely peaceful server wastes a few
 * placements the server ignores, while assuming peaceful on a normal server disables the module completely.
 * In singleplayer the client owns the difficulty and it is always believed.
 */
public enum DifficultyAssumption {
    /** The level's own difficulty, except a peaceful reading on a server, which is read as normal. */
    AUTO("Auto"),
    /** Believe the level even when it says peaceful. */
    STRICT("Strict"),
    EASY("Easy"),
    NORMAL("Normal"),
    HARD("Hard");

    private final String label;

    DifficultyAssumption(String label) {
        this.label = label;
    }

    /** The difficulty the damage model should use for {@code level}. */
    public Difficulty resolve(Level level) {
        Difficulty actual = level.getDifficulty();
        return switch (this) {
            case STRICT -> actual;
            case EASY -> Difficulty.EASY;
            case NORMAL -> Difficulty.NORMAL;
            case HARD -> Difficulty.HARD;
            case AUTO -> actual == Difficulty.PEACEFUL && !Minecraft.getInstance().hasSingleplayerServer()
                    ? Difficulty.NORMAL
                    : actual;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
