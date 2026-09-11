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

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * A consuming interaction result together with the hand it came from and which call produced it.
 *
 * <p>The hand matters because the vanilla hand loop runs the MAIN hand first and can end there even when the
 * item the aura cares about sits in the offhand: swinging the wrong hand would put a swing packet on the wire
 * that no client would send.
 */
public record InteractionOutcome(InteractionHand hand, Source source, InteractionResult result) {
    public enum Source {
        /** The result came from using the item on a block. */
        USE_ITEM_ON,
        /** The result came from using the item in the air. */
        USE_ITEM
    }
}
