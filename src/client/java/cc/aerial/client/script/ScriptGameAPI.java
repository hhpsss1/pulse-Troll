package cc.aerial.client.script;

import cc.aerial.client.AerialClient;
import cc.aerial.client.features.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public final class ScriptGameAPI {
    public ScriptPlayer getPlayer() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return null;
        return new ScriptPlayer(player);
    }
    
    public ScriptWorld getWorld() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return null;
        return new ScriptWorld(level);
    }
    
    public int getFPS() {
        return Minecraft.getInstance().getFps();
    }
    
    public void setClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }
    
    public String getClipboard() {
        return Minecraft.getInstance().keyboardHandler.getClipboard();
    }
    
    public void sendChatMessage(String message) {
        Minecraft.getInstance().player.connection.sendChat(message);
    }
    
    public void addChatMessage(String message) {
        Minecraft.getInstance().player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }

    public boolean isInWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null;
    }

    public boolean isInGui() {
        return Minecraft.getInstance().gui.screen() != null;
    }

    public String getServerName() {
        var server = Minecraft.getInstance().getCurrentServer();
        return server == null ? "singleplayer" : server.ip;
    }

    /** Every entity currently loaded, wrapped for scripting (great for ESP visuals). */
    public List<ScriptEntity> getEntities() {
        List<ScriptEntity> result = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                result.add(new ScriptEntity(entity));
            }
        }
        return result;
    }

    /** Only players (excluding yourself). */
    public List<ScriptEntity> getPlayers() {
        List<ScriptEntity> result = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (Entity entity : mc.level.players()) {
                if (entity != mc.player) {
                    result.add(new ScriptEntity(entity));
                }
            }
        }
        return result;
    }

    // --- interaction ---
    public void attackEntity(ScriptEntity target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameMode != null && target != null) {
            mc.gameMode.attack(mc.player, target.getHandle());
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    public void useItem() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameMode != null) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
    }

    /** Hold or release a movement/action key. name: forward, back, left, right, jump, sneak, sprint, attack, use. */
    public void setKey(String name, boolean pressed) {
        Minecraft mc = Minecraft.getInstance();
        var options = mc.options;
        var mapping = switch (name.toLowerCase()) {
            case "forward" -> options.keyUp;
            case "back", "backward" -> options.keyDown;
            case "left" -> options.keyLeft;
            case "right" -> options.keyRight;
            case "jump" -> options.keyJump;
            case "sneak", "shift" -> options.keyShift;
            case "sprint" -> options.keySprint;
            case "attack" -> options.keyAttack;
            case "use" -> options.keyUse;
            default -> null;
        };
        if (mapping != null) {
            mapping.setDown(pressed);
        }
    }

    // --- module control ---
    private Module findModule(String name) {
        try {
            return AerialClient.getModuleRepository().getModule(name.toLowerCase().replace(' ', '_'));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean toggleModule(String name) {
        Module module = findModule(name);
        if (module != null) {
            module.toggle();
            return true;
        }
        return false;
    }

    public void setModuleEnabled(String name, boolean enabled) {
        Module module = findModule(name);
        if (module != null) {
            module.setEnabled(enabled);
        }
    }

    public boolean isModuleEnabled(String name) {
        Module module = findModule(name);
        return module != null && module.isEnabled();
    }
}
