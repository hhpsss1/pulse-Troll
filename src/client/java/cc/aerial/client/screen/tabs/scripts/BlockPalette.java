package cc.aerial.client.screen.tabs.scripts;

import cc.aerial.client.screen.tabs.scripts.BlockDef.Kind;

import java.util.List;

/**
 * The fixed catalogue of blocks the visual constructor offers. Triggers map to a lifecycle hook;
 * conditions are boolean JS expressions ANDed together; actions are JS statements run in order.
 */
public final class BlockPalette {
    private BlockPalette() {
    }

    public static final List<BlockDef> TRIGGERS = List.of(
            // Default: runs every tick while the module toggle is ON (ScriptModule.onTick gates on isEnabled()).
            new BlockDef("active", Kind.TRIGGER, "While module is active", "setOnTick"),
            new BlockDef("enable", Kind.TRIGGER, "When module is enabled", "setOnEnable"),
            new BlockDef("disable", Kind.TRIGGER, "When module is disabled", "setOnDisable")
    );

    public static final List<BlockDef> CONDITIONS = List.of(
            new BlockDef("onGround", Kind.CONDITION, "Player is on ground",
                    "(game.getPlayer() != null && game.getPlayer().isOnGround())"),
            new BlockDef("healthBelow", Kind.CONDITION, "Health below {0}",
                    "(game.getPlayer() != null && game.getPlayer().getHealth() < {0})",
                    BlockDef.number("hp", "10")),
            new BlockDef("healthAbove", Kind.CONDITION, "Health above {0}",
                    "(game.getPlayer() != null && game.getPlayer().getHealth() > {0})",
                    BlockDef.number("hp", "10")),
            new BlockDef("sprinting", Kind.CONDITION, "Player is sprinting",
                    "(game.getPlayer() != null && game.getPlayer().isSprinting())"),
            new BlockDef("inWater", Kind.CONDITION, "Player is in water",
                    "(game.getPlayer() != null && game.getPlayer().isInWater())"),
            new BlockDef("holding", Kind.CONDITION, "Holding item {0}",
                    "(game.getPlayer() != null && game.getPlayer().getHeldItemName().toLowerCase().indexOf(({0}).toLowerCase()) >= 0)",
                    BlockDef.text("name", "sword")),
            new BlockDef("nearbyPlayer", Kind.CONDITION, "A player within {0} blocks",
                    "(game.getPlayers().filter(function(e){ return e.distanceToPlayer() < {0}; }).length > 0)",
                    BlockDef.number("range", "6")),
            new BlockDef("moduleOn", Kind.CONDITION, "Module {0} is on",
                    "(game.isModuleEnabled({0}))", BlockDef.text("module", "killaura")),
            new BlockDef("chance", Kind.CONDITION, "Random chance {0}%",
                    "(utils.randomInt(0, 100) < {0})", BlockDef.number("percent", "50")),
            new BlockDef("daytime", Kind.CONDITION, "It is daytime",
                    "(game.getWorld() != null && game.getWorld().isDaytime())")
    );

    public static final List<BlockDef> ACTIONS = List.of(
            new BlockDef("chat", Kind.ACTION, "Send chat {0}",
                    "game.sendChatMessage({0});", BlockDef.text("message", "/hello")),
            new BlockDef("clientMsg", Kind.ACTION, "Show client message {0}",
                    "game.addChatMessage({0});", BlockDef.text("message", "hi from aerial")),
            new BlockDef("log", Kind.ACTION, "Log {0}",
                    "utils.log({0});", BlockDef.text("text", "tick")),
            new BlockDef("look", Kind.ACTION, "Look yaw {0} pitch {1}",
                    "if (game.getPlayer() != null) game.getPlayer().setRotation({0}, {1});",
                    BlockDef.number("yaw", "0"), BlockDef.number("pitch", "0")),
            new BlockDef("jump", Kind.ACTION, "Jump",
                    "if (game.getPlayer() != null) game.getPlayer().jump();"),
            new BlockDef("swing", Kind.ACTION, "Swing hand",
                    "if (game.getPlayer() != null) game.getPlayer().swingHand();"),
            new BlockDef("sprintOn", Kind.ACTION, "Start sprinting",
                    "if (game.getPlayer() != null) game.getPlayer().setSprinting(true);"),
            new BlockDef("sprintOff", Kind.ACTION, "Stop sprinting",
                    "if (game.getPlayer() != null) game.getPlayer().setSprinting(false);"),
            new BlockDef("setVelY", Kind.ACTION, "Set upward velocity {0}",
                    "if (game.getPlayer() != null) { var __p = game.getPlayer(); __p.setVelocity(__p.getMotionX(), {0}, __p.getMotionZ()); }",
                    BlockDef.number("y", "0.42")),
            new BlockDef("holdKey", Kind.ACTION, "Hold key {0}",
                    "game.setKey({0}, true);", BlockDef.text("key", "forward")),
            new BlockDef("releaseKey", Kind.ACTION, "Release key {0}",
                    "game.setKey({0}, false);", BlockDef.text("key", "forward")),
            new BlockDef("toggleModule", Kind.ACTION, "Toggle module {0}",
                    "game.toggleModule({0});", BlockDef.text("module", "sprint")),
            new BlockDef("enableModule", Kind.ACTION, "Enable module {0}",
                    "game.setModuleEnabled({0}, true);", BlockDef.text("module", "sprint")),
            new BlockDef("disableModule", Kind.ACTION, "Disable module {0}",
                    "game.setModuleEnabled({0}, false);", BlockDef.text("module", "sprint")),
            new BlockDef("attackNearest", Kind.ACTION, "Attack nearest player",
                    "(function(){ var __b=null,__d=1e9; game.getPlayers().forEach(function(e){ var d=e.distanceToPlayer(); if(d<__d){__d=d;__b=e;} }); if(__b && __d < 6) game.attackEntity(__b); })();"),
            new BlockDef("wait", Kind.ACTION, "Wait {0} ms",
                    "utils.sleep({0});", BlockDef.number("ms", "50"))
    );

    public static BlockDef byId(String id) {
        for (BlockDef d : TRIGGERS) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        for (BlockDef d : CONDITIONS) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        for (BlockDef d : ACTIONS) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return null;
    }
}
