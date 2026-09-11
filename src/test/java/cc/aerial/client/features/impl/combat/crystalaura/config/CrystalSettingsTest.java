package cc.aerial.client.features.impl.combat.crystalaura.config;

import cc.aerial.client.property.GroupProperty;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.property.MultipleBooleanProperty;
import cc.aerial.client.property.NumberProperty;
import cc.aerial.client.property.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two ways this setting tree can break silently.
 *
 * <p>{@code ConfigUtility} serialises by display name and stops at the first sibling that matches,
 * so two siblings sharing a name means the second is never loaded — no error, just a value that
 * quietly resets. And {@code NumberProperty.setValue} rounds to a multiple of the increment on every
 * write, the constructor's included, so a default that is not a multiple of its own increment is
 * silently a different default than the one written down.
 */
class CrystalSettingsTest {
    @Test
    void siblingNamesAreUniqueWithinEveryGroup() {
        List<Property<?>> roots = List.of(new CrystalSettings().getProperties());
        assertUniqueSiblings("<root>", roots);
    }

    private static void assertUniqueSiblings(String path, List<Property<?>> siblings) {
        Set<String> seen = new HashSet<>();
        for (Property<?> property : siblings) {
            assertTrue(seen.add(property.getName()),
                    "duplicate sibling name '" + property.getName() + "' under " + path
                            + " — ConfigUtility would only ever load the first of them");
        }
        for (Property<?> property : siblings) {
            List<Property<?>> children = childrenOf(property);
            if (!children.isEmpty()) {
                assertUniqueSiblings(path + "/" + property.getName(), children);
            }
        }
    }

    private static List<Property<?>> childrenOf(Property<?> property) {
        if (property instanceof GroupProperty group) {
            return group.getPropertyList();
        }
        if (property instanceof MultipleBooleanProperty multi) {
            return new ArrayList<>(multi.getValue());
        }
        return List.of();
    }

    @Test
    void everyNumberDefaultSurvivesItsOwnIncrement() {
        CrystalSettings settings = new CrystalSettings();
        assertDefault(settings.place.range, 4.5);
        assertDefault(settings.place.delay, 0);
        assertDefault(settings.place.minDamage, 6);
        assertDefault(settings.place.maxSelfDamage, 6);
        assertDefault(settings.place.assumeResistance, 0);
        assertDefault(settings.place.predictTicks, 2);
        assertDefault(settings.place.swapBack, 0);

        assertDefault(settings.brk.range, 3);
        assertDefault(settings.brk.delay, 0);
        assertDefault(settings.brk.retryTicks, 0);

        assertDefault(settings.rotation.holdTicks, 5);
        assertDefault(settings.rotation.smooth.maxSpeed, 60);
        assertDefault(settings.rotation.smooth.minSpeed, 15);
        assertDefault(settings.rotation.smooth.accel, 0.6);
        assertDefault(settings.rotation.smooth.returnSpeed, 30);
        assertDefault(settings.rotation.smooth.resetThreshold, 1);

        assertDefault(settings.basePlace.minGain, 20);
        assertDefault(settings.basePlace.range, 4.5);
        assertDefault(settings.basePlace.mine.maxBlocks, 2);
        assertDefault(settings.basePlace.mine.minGain, 30);
        assertDefault(settings.basePlace.mine.range, 4.5);
        assertDefault(settings.basePlace.mine.maxTicks, 40);

        assertDefault(settings.target.range, 10);

        assertDefault(settings.render.animationSpeed, 1);
        assertDefault(settings.render.outlineWidth, 0.02);
        assertDefault(settings.render.feather, 0.5);
        assertDefault(settings.render.fresnel, 1.4);
        assertDefault(settings.render.faceMin, 0.15);
        assertDefault(settings.render.gradient, 0.35);
        assertDefault(settings.render.fadeStart, 24);
        assertDefault(settings.render.fadeEnd, 64);
        assertDefault(settings.render.glowRadius, 0.55);
        assertDefault(settings.render.glowStrength, 1.35);
        assertDefault(settings.render.glowFalloff, 1.2);
        assertDefault(settings.render.glowAlpha, 0.85);
        assertDefault(settings.render.glowGain, 3);
        assertDefault(settings.render.domeStrength, 1);
        assertDefault(settings.render.domeAmplitude, 0.026);
        assertDefault(settings.render.domeRipples, 3);
        assertDefault(settings.render.forgeStrength, 1);
        assertDefault(settings.render.forgeRadius, 2.5);
    }

    /**
     * The stored value must be the intended default. A tolerance of a millionth allows the
     * floating-point residue of {@code round(v / inc) * inc} while still catching a default that has
     * actually been snapped to a neighbouring step.
     */
    private static void assertDefault(NumberProperty property, double expected) {
        assertEquals(expected, property.getValue(), 1e-6,
                "'" + property.getName() + "' default was rounded by its own increment of "
                        + property.getIncrement());
    }

    /**
     * Every mode in the tree must be able to name its own options.
     *
     * <p>{@code ConfigUtility} restores a mode by walking {@code getValues()} and matching on
     * {@code name()}, so a mode whose option array is missing does not merely fail to load — it throws,
     * out of the client's initialiser, before the window exists. The array goes missing for an enum whose
     * constants carry their own bodies unless the constants are read off the DECLARING class, which is
     * what makes this worth a test rather than a glance: it is invisible until a config has been saved
     * once and read back, so a first run always looks fine.
     */
    @Test
    void everyModeKnowsItsOwnOptions() {
        assertModesResolvable("<root>", List.of(new CrystalSettings().getProperties()));
    }

    private static void assertModesResolvable(String path, List<Property<?>> siblings) {
        for (Property<?> property : siblings) {
            if (property instanceof ModeProperty<?> mode) {
                Object[] values = mode.getValues();
                assertNotNull(values, "mode '" + path + "/" + mode.getName()
                        + "' has no options — its enum constants were read off an anonymous subclass");
                assertTrue(Arrays.asList(values).contains(mode.getValue()),
                        "mode '" + path + "/" + mode.getName() + "' does not list its own current value");
            }
            List<Property<?>> children = childrenOf(property);
            if (!children.isEmpty()) {
                assertModesResolvable(path + "/" + property.getName(), children);
            }
        }
    }

    @Test
    void theTreeHasTheSevenRootNodes() {
        Property<?>[] roots = new CrystalSettings().getProperties();
        assertEquals(7, roots.length);
        List<String> names = new ArrayList<>();
        for (Property<?> root : roots) {
            names.add(root.getName());
        }
        assertEquals(List.of("Place", "Break", "Rotation", "BasePlace", "Target", "Render", "Debug"), names);
    }
}
