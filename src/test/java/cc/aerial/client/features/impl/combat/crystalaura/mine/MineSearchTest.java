package cc.aerial.client.features.impl.combat.crystalaura.mine;

import cc.aerial.client.features.impl.combat.crystalaura.calc.FakeBlockGetter;
import cc.aerial.client.testsupport.MinecraftBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cc.aerial.client.features.impl.combat.crystalaura.mine.MineRules.CLEAR_ABOVE;
import static cc.aerial.client.features.impl.combat.crystalaura.mine.MineRules.CLEAR_BASE;
import static cc.aerial.client.features.impl.combat.crystalaura.mine.MineRules.CLEAR_IMPOSSIBLE;
import static cc.aerial.client.features.impl.combat.crystalaura.mine.MineRules.CLEAR_NONE;
import static cc.aerial.client.features.impl.combat.crystalaura.mine.MineRules.clearMask;
import static cc.aerial.client.features.impl.combat.crystalaura.mine.MineRules.fallsWhenUnsupported;
import static cc.aerial.client.features.impl.combat.crystalaura.mine.MineRules.isDiggable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state rules of {@link MineSearch}, exercised on a {@link FakeBlockGetter} through the pure
 * {@link MineRules} — everything the live search adds on top of them is range, entities and rays.
 *
 * <p>The replaceable predicate stands in for the search's real replace-clicked test, which needs a placement
 * context and hence a player: the vanilla rule is that the state can be replaced and the held item is not that
 * same block, plus block overrides, and with obsidian in hand only the first term can differ for any block used
 * here. The clearable predicate drops the range and the standing-block terms, leaving the diggability rule.
 */
class MineSearchTest {
    private static final int SIZE = 16;

    /** The base cell of every scene; the two cells above it stay inside the region. */
    private static final BlockPos BASE = new BlockPos(8, 8, 8);

    private final BlockState air = Blocks.AIR.defaultBlockState();
    private final BlockState stone = Blocks.STONE.defaultBlockState();
    private final BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** A world that is air everywhere except the cells given as offsets above {@link #BASE}. */
    private static FakeBlockGetter scene(int dy0, BlockState state0, int dy1, BlockState state1) {
        FakeBlockGetter fake = new FakeBlockGetter(SIZE, SIZE, SIZE);
        fake.set(BASE.getX(), BASE.getY() + dy0, BASE.getZ(), state0);
        fake.set(BASE.getX(), BASE.getY() + dy1, BASE.getZ(), state1);
        return fake;
    }

    private static FakeBlockGetter scene(int dy0, BlockState state0, int dy1, BlockState state1,
                                         int dy2, BlockState state2) {
        FakeBlockGetter fake = scene(dy0, state0, dy1, state1);
        fake.set(BASE.getX(), BASE.getY() + dy2, BASE.getZ(), state2);
        return fake;
    }

    private static int mask(FakeBlockGetter fake) {
        return clearMask(fake, BASE, new BlockPos.MutableBlockPos(),
                (pos, state) -> state.canBeReplaced(),
                (pos, state) -> isDiggable(fake, pos, state));
    }

    private static BlockState waterlogged() {
        return Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP)
                .setValue(BlockStateProperties.WATERLOGGED, true);
    }

    /**
     * Row 1 of the cell-count table: the enemy stands on flat ground, the ground cell is solid and the cell
     * above it is already air, so exactly the ground cell goes and the obsidian drops into the hole.
     */
    @Test
    void flatGroundClearsTheBaseCellOnly() {
        int mask = mask(scene(0, stone, 1, air));
        assertEquals(CLEAR_BASE, mask);
        assertEquals(1, Integer.bitCount(mask));
    }

    /** Row 2: underground, both the base cell and the cell above it are solid, so both go. */
    @Test
    void anEnclosedPocketClearsBothCells() {
        int mask = mask(scene(0, stone, 1, stone));
        assertEquals(CLEAR_BASE | CLEAR_ABOVE, mask);
        assertEquals(2, Integer.bitCount(mask));
    }

    /** Row 3: the base cell is already replaceable, only the ceiling above it is in the way. */
    @Test
    void aSolidCeilingOverAFreeBaseClearsTheCeilingOnly() {
        int mask = mask(scene(0, air, 1, stone));
        assertEquals(CLEAR_ABOVE, mask);
        assertEquals(1, Integer.bitCount(mask));
    }

    /**
     * The replace-in-place case: a one-layer snow under a solid ceiling. The snow is replaceable — its own
     * override answers "one layer only" with obsidian in hand — so the mask leaves the base cell alone and only
     * the ceiling goes, which is exactly the plan whose obsidian has to go in by clicking the snow ITSELF when
     * no neighbour is clickable.
     */
    @Test
    void aSnowLayerUnderASolidCellClearsTheCeilingOnly() {
        BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1);
        int mask = mask(scene(0, snow, 1, stone));
        assertEquals(CLEAR_ABOVE, mask);
        assertEquals(0, mask & CLEAR_BASE, "the base cell must survive, or nothing is left to click");
    }

    /**
     * Nothing to mine: an air cell with air above it is what the base-place search places obsidian into, and an
     * obsidian cell with air above it is what the placement search puts a crystal on. Both need no dig, so the
     * mining search never competes with them.
     */
    @Test
    void aCellThatIsAlreadyUsableNeedsNoDig() {
        assertEquals(CLEAR_NONE, mask(scene(0, air, 1, air)));
        assertEquals(CLEAR_NONE, mask(scene(0, obsidian, 1, air)));
    }

    /**
     * An existing obsidian or bedrock base under a solid cell is the cheapest target there is: one dig, and no
     * obsidian placement at all.
     */
    @Test
    void anExistingBaseUnderASolidCellClearsThatCellOnly() {
        assertEquals(CLEAR_ABOVE, mask(scene(0, obsidian, 1, stone)));
        assertEquals(CLEAR_ABOVE, mask(scene(0, Blocks.BEDROCK.defaultBlockState(), 1, stone)));
    }

    /**
     * Breaking a waterlogged block leaves its fluid's legacy block, i.e. WATER, which is replaceable but not
     * air — the pocket would never satisfy the crystal item's emptiness test, so the whole plan is refused.
     */
    @Test
    void aWaterloggedCellIsNeverMined() {
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0, waterlogged(), 1, air)));
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0, stone, 1, waterlogged())));
    }

    /** Water itself: replaceable as a base (nothing to dig), impossible as the cell above (it is not air). */
    @Test
    void aWaterCellIsReplaceableBelowAndImpossibleAbove() {
        BlockState water = Blocks.WATER.defaultBlockState();
        assertEquals(CLEAR_NONE, mask(scene(0, water, 1, air)));
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0, stone, 1, water)));
    }

    /**
     * A falling block resting on the topmost cell we would clear refills the pocket about two server ticks
     * later, and the client never predicts it, so the plan is refused before it starts.
     */
    @Test
    void aFallingBlockOverThePocketKillsThePlan() {
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0, stone, 1, stone, 2, gravel)));
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0, air, 1, stone, 2, Blocks.SAND.defaultBlockState())));
    }

    /** Suspicious gravel is a brushable block, not a falling block, and does not refill anything. */
    @Test
    void suspiciousGravelOverThePocketIsHarmless() {
        BlockState suspicious = Blocks.SUSPICIOUS_GRAVEL.defaultBlockState();
        assertEquals(CLEAR_BASE | CLEAR_ABOVE, mask(scene(0, stone, 1, stone, 2, suspicious)));
    }

    /**
     * With only the base cell cleared, the cell checked for a falling block is the AIR cell above it — a block
     * one further up keeps its support and is not even neighbour-updated by our break, so it is not our
     * problem.
     */
    @Test
    void aFallingBlockTwoCellsUpIsNotAHazard() {
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        assertEquals(CLEAR_BASE, mask(scene(0, stone, 1, air, 2, gravel)));
    }

    /** Hardness {@code -1} never finishes a dig, so such a cell can be neither the base nor the ceiling. */
    @Test
    void undiggableCellsAreRefusedByClearMask() {
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0, Blocks.BARRIER.defaultBlockState(), 1, air)));
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0, stone, 1, Blocks.BEDROCK.defaultBlockState())));
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0, stone, 1, Blocks.BARRIER.defaultBlockState())));
    }

    /** The undiggable set itself: air, anything carrying a fluid, and every hardness {@code -1} block. */
    @Test
    void isDiggableRefusesAirFluidsAndUnbreakableBlocks() {
        FakeBlockGetter fake = new FakeBlockGetter(SIZE, SIZE, SIZE);
        List<Block> undiggable = List.of(
                Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
                Blocks.WATER, Blocks.LAVA, Blocks.BUBBLE_COLUMN,
                Blocks.BEDROCK, Blocks.BARRIER, Blocks.LIGHT, Blocks.MOVING_PISTON);
        for (Block block : undiggable) {
            assertFalse(isDiggable(fake, BASE, block.defaultBlockState()), block + " must not be diggable");
        }
        assertFalse(isDiggable(fake, BASE, waterlogged()), "a waterlogged block must not be diggable");
    }

    /** The other side of it: ordinary terrain stays diggable, wrong tool or not. */
    @Test
    void isDiggableAcceptsOrdinaryTerrain() {
        FakeBlockGetter fake = new FakeBlockGetter(SIZE, SIZE, SIZE);
        List<Block> diggable = List.of(
                Blocks.STONE, Blocks.DIRT, Blocks.OBSIDIAN, Blocks.GRAVEL, Blocks.SHORT_GRASS, Blocks.OAK_SLAB);
        for (Block block : diggable) {
            assertTrue(isDiggable(fake, BASE, block.defaultBlockState()), block + " must be diggable");
        }
    }

    /** The falling-block set of 26.2, block by block. */
    @Test
    void fallsWhenUnsupportedKnowsTheFallingBlocks() {
        List<Block> falling = List.of(
                Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.DRAGON_EGG,
                Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
                Blocks.CONCRETE_POWDER.white(), Blocks.CONCRETE_POWDER.black());
        for (Block block : falling) {
            assertTrue(fallsWhenUnsupported(block.defaultBlockState()), block + " must fall");
        }
        List<Block> standing = List.of(
                Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL, Blocks.SOUL_SAND,
                Blocks.POINTED_DRIPSTONE, Blocks.STONE, Blocks.OBSIDIAN);
        for (Block block : standing) {
            assertFalse(fallsWhenUnsupported(block.defaultBlockState()), block + " must not fall");
        }
    }

    /**
     * The whole registry: 3 anvils, gravel, sand and red sand, the 16 concrete powders and the dragon egg — 23
     * blocks, and nothing else in 26.2 falls when its support goes.
     */
    @Test
    void exactly23RegisteredBlocksFall() {
        long count = BuiltInRegistries.BLOCK.stream()
                .filter(block -> fallsWhenUnsupported(block.defaultBlockState()))
                .count();
        assertEquals(23L, count);
    }
}
