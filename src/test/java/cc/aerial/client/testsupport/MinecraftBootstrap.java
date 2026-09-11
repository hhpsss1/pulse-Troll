package cc.aerial.client.testsupport;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Brings Minecraft's registries up inside a headless JVM.
 *
 * <p>Anything that touches {@code Blocks}, {@code Items}, {@code DamageTypes}, block states or
 * voxel shapes needs this first; without it the registry holders are null and the failure surfaces
 * far from its cause. Idempotent, so every test class can call it from {@code @BeforeAll} without
 * coordinating.</p>
 *
 * <p>What this does <em>not</em> give you: translations (no language file is loaded, so
 * {@code getHoverName()} throws), a {@code Level}, or anything client-side. Tests identify content
 * by registry key and stub the world with a fake {@code BlockGetter}.</p>
 */
public final class MinecraftBootstrap {
    private static boolean done;

    private MinecraftBootstrap() {
    }

    public static synchronized void ensure() {
        if (done) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        done = true;
    }
}
