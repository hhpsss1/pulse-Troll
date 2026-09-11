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

package cc.aerial.client.features.impl.combat.crystalaura.config;

/**
 * What the damage model assumes about a remote victim's armour when the server hides part of it.
 *
 * <p>A victim's armour reaches the client through two different channels, and a server can obfuscate
 * them independently. The armour POINTS come from the equipment the server sends
 * ({@code ClientboundSetEquipmentPacket}) plus {@code Attributes.ARMOR}, which is syncable. The
 * PROTECTION enchantments are read off those same stacks — so a server that strips enchantment
 * components from the equipment packet leaves the model believing every opponent is unenchanted, and
 * the predicted damage comes out far too high.
 *
 * <p>Every level here SUBSTITUTES the local player's own values for the victim's — it does not take
 * the better of the two, which is where this differs from the assumed-resistance setting.
 *
 * <p>The reason is that the two holes are not the same kind of hole. A Resistance effect the client
 * never receives is genuinely absent from its view, so assuming a minimum can only correct an
 * over-estimate. A rewritten enchantment list is not a missing reading but a false one: the usual
 * obfuscation leaves a decoy enchantment on the stack so the item still glints, and that decoy can be
 * a Protection level the victim does not have. Treated as a lower bound, a fake Protection IV would
 * survive the comparison and talk the aura out of placing at all. So once this is on, the victim's own
 * enchantments stop counting as evidence.
 *
 * <p>The trade-off is the honest one: on a server that does NOT obfuscate, an opponent in genuinely
 * better gear than ours is then under-estimated. Leave it {@link #OFF} there.
 */
public enum ArmorAssumption {
    /** Trust the client's reading, whatever it is. */
    OFF("Off"),

    /**
     * Score the victim with OUR protection enchantments and leave their armour points alone. The right
     * level for the common case: the server rewrites or hides enchantments but the armour pieces
     * themselves are real.
     */
    PROTECTION("Protection"),

    /**
     * Also substitute our armour points and toughness. The right level when the server hides the
     * equipment itself, which leaves {@code getArmorValue()} reading 0.
     */
    FULL("Full");

    private final String label;

    ArmorAssumption(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
