/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.aerial.client.features.impl.combat.crystalaura.calc;

import cc.aerial.client.simulation.PlayerSimulation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the player through Aerial's movement simulation and reports where it lands.
 *
 * <p>Snapshots are taken lazily and kept, so the many queries one tick makes — every placement
 * candidate asks about the same victim — cost one simulation run in total rather than one each.
 * The instance is therefore valid for exactly one tick; build a new one next tick.</p>
 *
 * <p>Predictions are capped at {@link #MAX_TICKS}: past roughly a second and a half the simulation
 * has diverged from anything a real player will do, and the cap keeps a nonsense argument from
 * running the physics thousands of times.</p>
 */
public final class PlayerSimulationExtrapolation implements PositionExtrapolation {
    /** Longest horizon the simulation is allowed to run, in ticks. */
    public static final int MAX_TICKS = 30;

    private final PlayerSimulation simulation;
    private final List<Vec3> snapshots = new ArrayList<>();

    private PlayerSimulationExtrapolation(PlayerSimulation simulation) {
        this.simulation = simulation;
        snapshots.add(simulation.getSimulatedEntity().position());
    }

    /** Null when there is no world to simulate in; the caller falls back to linear extrapolation. */
    @Nullable
    public static PlayerSimulationExtrapolation of(Player player) {
        PlayerSimulation simulation = new PlayerSimulation(player);
        return simulation.getSimulatedEntity() == null ? null : new PlayerSimulationExtrapolation(simulation);
    }

    @Override
    public Vec3 getPositionInTicks(double ticks) {
        int wanted = (int) Math.max(0L, Math.round(Math.min(ticks, MAX_TICKS)));
        while (snapshots.size() <= wanted) {
            simulation.simulateTick();
            snapshots.add(simulation.getSimulatedEntity().position());
        }
        return snapshots.get(wanted);
    }
}
