package cc.aerial.client.script;

import java.util.function.Consumer;

/**
 * The {@code events} global: lets scripts hook any client event, including the 2D and 3D render
 * passes used for custom visuals (ESP, HUD, tracers). Callbacks receive a typed context object.
 *
 * Example (JS):
 *   events.onRender2D(function(r) { r.roundedRect(10, 10, 80, 20, 4, utils.color(0,0,0,160)); });
 *   events.onRender3D(function(r) {
 *     game.getEntities().forEach(function(e) { if (e.isPlayer()) r.entityBox(e, utils.rainbow(1,0), false, true); });
 *   });
 */
public final class ScriptEventsAPI {
    private final ScriptEventBus bus = ScriptEventBus.getInstance();

    public void onTick(Runnable callback) {
        bus.addTick(callback);
    }

    public void onRender2D(Consumer<ScriptRender2D> callback) {
        bus.addRender2D(callback);
    }

    public void onRender3D(Consumer<ScriptRender3D> callback) {
        bus.addRender3D(callback);
    }

    public void onChat(Consumer<ScriptEventBus.ChatMsg> callback) {
        bus.addChat(callback);
    }

    public void onKey(Consumer<ScriptEventBus.KeyPress> callback) {
        bus.addKey(callback);
    }

    public void onSendPacket(Consumer<ScriptEventBus.PacketInfo> callback) {
        bus.addSendPacket(callback);
    }

    public void onReceivePacket(Consumer<ScriptEventBus.PacketInfo> callback) {
        bus.addReceivePacket(callback);
    }

    public void onWorldJoin(Runnable callback) {
        bus.addWorldJoin(callback);
    }

    public void onAttack(Consumer<ScriptEntity> callback) {
        bus.addAttack(callback);
    }

    public void onJump(Runnable callback) {
        bus.addJump(callback);
    }
}
