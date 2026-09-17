package cc.aerial.client.script;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

public final class ScriptWorld {
    private final Level level;
    
    public ScriptWorld(Level level) {
        this.level = level;
    }
    
    public String getDimensionName() {
        return String.valueOf(level.dimension());
    }
    
    public int getBlockId(int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return level.getBlockState(new BlockPos(x, y, z)).getBlock().hashCode();
    }
    
    public String getBlockName(int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return state.getBlock().toString();
    }
    
    public boolean isBlockSolid(int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return state.isSolidRender();
    }

    public long getTime() {
        return level.getGameTime();
    }

    public boolean isDaytime() {
        long dayTime = level.getGameTime() % 24000L;
        return dayTime < 12000L;
    }
    
    public int getPlayerCount() {
        return level.players().size();
    }

    public java.util.List<ScriptEntity> getPlayers() {
        java.util.List<ScriptEntity> result = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : level.players()) {
            result.add(new ScriptEntity(entity));
        }
        return result;
    }

    public Level getHandle() {
        return level;
    }
}
