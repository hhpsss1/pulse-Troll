package cc.aerial.client.features.impl.combat.crystalaura.base;

import cc.aerial.client.features.impl.combat.crystalaura.calc.FakeBlockGetter;
import cc.aerial.client.testsupport.MinecraftBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that admits vanilla's SECOND placement form.
 *
 * <p>The placement context sets its replace-clicked flag from whether the clicked state can be replaced, and
 * then puts the block into the CLICKED cell, ignoring the hit direction. Aiming straight at a snow layer and
 * clicking is therefore how a player puts obsidian there, and it is the only way in for a cell whose every
 * neighbour is itself replaceable.
 *
 * <p>The replaceable argument stands in for the live test, which needs a placement context and hence a level and
 * a player. The values passed here are the ones vanilla computes: the base rule is that the state is replaceable
 * and the held item is not that same block, unless the block overrides it — and a snow layer does, answering
 * "one layer only" with anything but a snow item in hand. The helper below restates that so the expectation is
 * visible rather than assumed.
 */
class ReplaceInPlaceTest {
    private static final int SIZE = 4;
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    private final FakeBlockGetter level = new FakeBlockGetter(SIZE, SIZE, SIZE);

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** A snow layer is replaced by obsidian only at one layer. */
    private static boolean snowRule(int layers) {
        return layers == 1;
    }

    private static BlockState snow(int layers) {
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    private boolean admits(BlockState state, boolean replaceable) {
        return admits(state, replaceable, false);
    }

    private boolean admits(BlockState state, boolean replaceable, boolean sneaking) {
        level.set(POS.getX(), POS.getY(), POS.getZ(), state);
        return BaseRules.isReplaceInPlace(level, POS, state, replaceable, CollisionContext.empty(), sneaking);
    }

    /**
     * A one-layer snow is replaceable by obsidian and has a shape to pick, so the cell may be clicked directly
     * and the obsidian takes its place — no neighbour needed.
     */
    @Test
    void aOneLayerSnowAdmitsTheSelfClick() {
        assertTrue(snowRule(1));
        assertTrue(admits(snow(1), snowRule(1)));
    }

    /**
     * Two layers and deeper are refused, and the refusal is vanilla's own, so the placement would fail and the
     * click would do nothing. This must not change — it is the one snow case the aura is right to skip.
     */
    @Test
    void snowDeeperThanOneLayerIsStillRefused() {
        for (int layers = 2; layers <= 8; layers++) {
            assertFalse(snowRule(layers), "vanilla replaces " + layers + "-layer snow");
            assertFalse(admits(snow(layers), snowRule(layers)), layers + "-layer snow admitted");
        }
    }

    /** Fire is replaceable and carries a 1/16-tall outline, so it is admitted too. */
    @Test
    void fireAdmitsTheSelfClick() {
        assertTrue(admits(Blocks.FIRE.defaultBlockState(), true));
    }

    /**
     * Air is replaceable and is nevertheless refused: the outline of air is empty, so no ray can ever pick the
     * cell. An air base cell keeps using the RELATIVE form, a click on a solid neighbour.
     */
    @Test
    void airIsRefusedAndStaysOnTheNeighbourForm() {
        assertFalse(admits(Blocks.AIR.defaultBlockState(), true));
    }

    /** A block the obsidian would not replace is refused whatever its shape. */
    @Test
    void aNonReplaceableBlockIsRefused() {
        assertFalse(admits(Blocks.STONE.defaultBlockState(), false));
    }

    /**
     * An interactable block consumes the click before the item is used at all unless we sneak — the same term the
     * neighbour form applies, restated for the clicked cell itself. A lever stands in for the interactable list;
     * the blocks this form exists for, snow and fire, are not on it.
     */
    @Test
    void anInteractableBlockNeedsTheSneak() {
        BlockState lever = Blocks.LEVER.defaultBlockState();
        assertFalse(admits(lever, true, false));
        assertTrue(admits(lever, true, true));
    }

    /**
     * The shapes the whole form turns on, straight from the block: a full footprint glued to the BOTTOM of the
     * cell — 2/16 tall for a one-layer snow, 1/16 for a fire. Sampling the whole unit cube for these aims mostly
     * at empty space, which is why the aim for a self-click samples the outline bounds instead.
     */
    @Test
    void theShapesSitOnTheFloorOfTheirCell() {
        AABB snowShape = snow(1).getShape(level, POS, CollisionContext.empty()).bounds();
        assertEquals(0.0, snowShape.minY);
        assertEquals(2.0 / 16.0, snowShape.maxY, 1.0e-9);
        assertEquals(1.0, snowShape.maxX - snowShape.minX, 1.0e-9);

        AABB fireShape = Blocks.FIRE.defaultBlockState().getShape(level, POS, CollisionContext.empty()).bounds();
        assertEquals(0.0, fireShape.minY);
        assertEquals(1.0 / 16.0, fireShape.maxY, 1.0e-9);
        assertEquals(1.0, fireShape.maxZ - fireShape.minZ, 1.0e-9);
    }
}
