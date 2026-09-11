package cc.aerial.client.features.impl.world;

import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.GroupProperty;
import cc.aerial.client.property.NumberProperty;
import cc.aerial.client.property.Property;

final class XFarmSettings {
    private final NumberProperty radius;
    private final NumberProperty red, green, blue, alpha;
    
    private final BooleanProperty coal;
    private final BooleanProperty iron;
    private final BooleanProperty gold;
    private final BooleanProperty diamond;
    private final BooleanProperty lapis;
    private final BooleanProperty redstone;
    private final BooleanProperty emerald;
    private final BooleanProperty copper;
    private final BooleanProperty ancientDebris;

    private final Property<?>[] properties;

    XFarmSettings() {
        this.radius = new NumberProperty("Radius", 4, 1, 8, 1);
        
        this.red = new NumberProperty("Red", 1.0, 0.0, 1.0, 0.1);
        this.green = new NumberProperty("Green", 0.0, 0.0, 1.0, 0.1);
        this.blue = new NumberProperty("Blue", 0.0, 0.0, 1.0, 0.1);
        this.alpha = new NumberProperty("Alpha", 1.0, 0.0, 1.0, 0.1);
        
        this.coal = new BooleanProperty("Coal", true);
        this.iron = new BooleanProperty("Iron", true);
        this.gold = new BooleanProperty("Gold", true);
        this.diamond = new BooleanProperty("Diamond", true);
        this.lapis = new BooleanProperty("Lapis", true);
        this.redstone = new BooleanProperty("Redstone", true);
        this.emerald = new BooleanProperty("Emerald", true);
        this.copper = new BooleanProperty("Copper", false);
        this.ancientDebris = new BooleanProperty("Ancient Debris", true);

        this.properties = new Property<?>[]{
                radius,
                new GroupProperty("Color", red, green, blue, alpha),
                new GroupProperty("Ores", coal, iron, gold, diamond, lapis, redstone, emerald, copper, ancientDebris)
        };
    }

    Property<?>[] getProperties() {
        return properties;
    }

    int getRadius() {
        return radius.getValue().intValue();
    }

    float getRed() {
        return red.getValue().floatValue();
    }

    float getGreen() {
        return green.getValue().floatValue();
    }

    float getBlue() {
        return blue.getValue().floatValue();
    }

    float getAlpha() {
        return alpha.getValue().floatValue();
    }

    boolean isCoalEnabled() {
        return coal.getValue();
    }

    boolean isIronEnabled() {
        return iron.getValue();
    }

    boolean isGoldEnabled() {
        return gold.getValue();
    }

    boolean isDiamondEnabled() {
        return diamond.getValue();
    }

    boolean isLapisEnabled() {
        return lapis.getValue();
    }

    boolean isRedstoneEnabled() {
        return redstone.getValue();
    }

    boolean isEmeraldEnabled() {
        return emerald.getValue();
    }

    boolean isCopperEnabled() {
        return copper.getValue();
    }

    boolean isAncientDebrisEnabled() {
        return ancientDebris.getValue();
    }
}
