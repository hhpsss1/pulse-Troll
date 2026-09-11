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

package cc.aerial.client.features.impl.combat.crystalaura.exec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The interaction primitives the aura needs, each a faithful replay of the vanilla call site.
 *
 * <p>Aerial's own placement helper cannot be used here: it hard-calls the scaffold module's swing and would
 * therefore make a crystal placement look like a scaffold block on the wire.
 */
public final class CrystalInteract {
    private CrystalInteract() {
    }

    /**
     * Attacks an entity the way vanilla's attack key does: the game mode's attack — which ensures the carried
     * item was announced, sends the attack packet, runs the client-side prediction and resets the attack
     * strength — followed by the swing.
     */
    public static void attack(Entity target) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null) {
            return;
        }
        gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * The block branch of the vanilla use: the game mode sends the use-on packet whatever the client-side result
     * is, and the swing follows only a client-side success.
     *
     * @return the client-side result, or null when there is no world to act in
     */
    @Nullable
    public static InteractionResult useOn(BlockHitResult hit, InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null) {
            return null;
        }
        InteractionResult result = gameMode.useItemOn(player, hand, hit);
        if (result instanceof InteractionResult.Success) {
            player.swing(hand);
        }
        return result;
    }

    /**
     * The in-air branch of the vanilla use. The rotation of the packet is corrected on its way out by the aim,
     * so it carries what this tick's movement packet carries rather than the camera.
     *
     * @return the client-side result, or null when there is no world to act in
     */
    @Nullable
    public static InteractionResult useItem(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null) {
            return null;
        }
        InteractionResult result = gameMode.useItem(player, hand);
        if (result instanceof InteractionResult.Success) {
            player.swing(hand);
        }
        return result;
    }

    /** A bare swing of one hand, for the cases where the client-side result refused but the server will not. */
    public static void swing(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.swing(hand);
        }
    }
}
