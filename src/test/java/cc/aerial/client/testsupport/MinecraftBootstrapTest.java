package cc.aerial.client.testsupport;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canary for the headless test setup. When a Minecraft or loom bump breaks the bootstrap, this
 * fails on its own instead of taking every damage-maths test down with an unrelated-looking error.
 */
class MinecraftBootstrapTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    void minecraftTypesResolveOnTheTestClasspath() {
        assertEquals(new Vec3(0.5, 0.5, 0.5), new AABB(0, 0, 0, 1, 1, 1).getCenter());
    }

    @Test
    void registriesArePopulated() {
        assertTrue(Blocks.OBSIDIAN.defaultDestroyTime() > 0.0f);
        assertEquals(Identifier.withDefaultNamespace("end_crystal"),
                BuiltInRegistries.ITEM.getKey(Items.END_CRYSTAL));
    }
}
