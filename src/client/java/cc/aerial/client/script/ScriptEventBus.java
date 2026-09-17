package cc.aerial.client.script;

import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.event.impl.game.JoinWorldEvent;
import cc.aerial.client.event.impl.game.PreGameTickEvent;
import cc.aerial.client.event.impl.game.chat.ChatReceivedEvent;
import cc.aerial.client.event.impl.game.packet.ReceivePacketEvent;
import cc.aerial.client.event.impl.game.packet.SendPacketEvent;
import cc.aerial.client.event.impl.game.player.interaction.AttackEvent;
import cc.aerial.client.event.impl.game.player.movement.JumpEvent;
import cc.aerial.client.event.impl.press.KeyPressEvent;
import cc.aerial.client.event.impl.render.Render2DEvent;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.event.subscriber.IEventSubscriber;
import cc.aerial.client.event.subscriber.Subscribe;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Central hub that forwards Minecraft events to script-registered callbacks. Subscribed once to the
 * {@link EventDispatcher}; scripts add callbacks through {@code events.on...}. Cleared on reload.
 */
public final class ScriptEventBus implements IEventSubscriber {
    private static ScriptEventBus INSTANCE;

    private final List<Runnable> tick = new CopyOnWriteArrayList<>();
    private final List<Consumer<ScriptRender2D>> render2d = new CopyOnWriteArrayList<>();
    private final List<Consumer<ScriptRender3D>> render3d = new CopyOnWriteArrayList<>();
    private final List<Consumer<ChatMsg>> chat = new CopyOnWriteArrayList<>();
    private final List<Consumer<KeyPress>> key = new CopyOnWriteArrayList<>();
    private final List<Consumer<PacketInfo>> sendPacket = new CopyOnWriteArrayList<>();
    private final List<Consumer<PacketInfo>> receivePacket = new CopyOnWriteArrayList<>();
    private final List<Runnable> worldJoin = new CopyOnWriteArrayList<>();
    private final List<Consumer<ScriptEntity>> attack = new CopyOnWriteArrayList<>();
    private final List<Runnable> jump = new CopyOnWriteArrayList<>();

    private ScriptEventBus() {
        EventDispatcher.subscribe(this);
    }

    public static ScriptEventBus getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ScriptEventBus();
        }
        return INSTANCE;
    }

    public void addTick(Runnable r) {
        tick.add(r);
        ScriptContext.record(() -> tick.remove(r));
    }

    public void addRender2D(Consumer<ScriptRender2D> r) {
        render2d.add(r);
        ScriptContext.record(() -> render2d.remove(r));
    }

    public void addRender3D(Consumer<ScriptRender3D> r) {
        render3d.add(r);
        ScriptContext.record(() -> render3d.remove(r));
    }

    public void addChat(Consumer<ChatMsg> r) {
        chat.add(r);
        ScriptContext.record(() -> chat.remove(r));
    }

    public void addKey(Consumer<KeyPress> r) {
        key.add(r);
        ScriptContext.record(() -> key.remove(r));
    }

    public void addSendPacket(Consumer<PacketInfo> r) {
        sendPacket.add(r);
        ScriptContext.record(() -> sendPacket.remove(r));
    }

    public void addReceivePacket(Consumer<PacketInfo> r) {
        receivePacket.add(r);
        ScriptContext.record(() -> receivePacket.remove(r));
    }

    public void addWorldJoin(Runnable r) {
        worldJoin.add(r);
        ScriptContext.record(() -> worldJoin.remove(r));
    }

    public void addAttack(Consumer<ScriptEntity> r) {
        attack.add(r);
        ScriptContext.record(() -> attack.remove(r));
    }

    public void addJump(Runnable r) {
        jump.add(r);
        ScriptContext.record(() -> jump.remove(r));
    }

    public void clearAll() {
        tick.clear();
        render2d.clear();
        render3d.clear();
        chat.clear();
        key.clear();
        sendPacket.clear();
        receivePacket.clear();
        worldJoin.clear();
        attack.clear();
        jump.clear();
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            System.err.println("[Script] event handler error: " + t);
        }
    }

    @Subscribe
    public void onTick(PreGameTickEvent event) {
        for (Runnable r : tick) {
            safe(r);
        }
    }

    @Subscribe
    public void onRender2D(Render2DEvent event) {
        if (render2d.isEmpty()) {
            return;
        }
        ScriptRender2D ctx = new ScriptRender2D(event.extractor(), event.partialTick());
        for (Consumer<ScriptRender2D> r : render2d) {
            safe(() -> r.accept(ctx));
        }
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (render3d.isEmpty()) {
            return;
        }
        ScriptRender3D ctx = new ScriptRender3D(event);
        for (Consumer<ScriptRender3D> r : render3d) {
            safe(() -> r.accept(ctx));
        }
    }

    @Subscribe
    public void onChat(ChatReceivedEvent event) {
        if (chat.isEmpty()) {
            return;
        }
        ChatMsg msg = new ChatMsg(event);
        for (Consumer<ChatMsg> r : chat) {
            safe(() -> r.accept(msg));
        }
    }

    @Subscribe
    public void onKey(KeyPressEvent event) {
        if (key.isEmpty()) {
            return;
        }
        KeyPress press = new KeyPress(event);
        for (Consumer<KeyPress> r : key) {
            safe(() -> r.accept(press));
        }
    }

    @Subscribe
    public void onSendPacket(SendPacketEvent event) {
        if (sendPacket.isEmpty()) {
            return;
        }
        PacketInfo info = new PacketInfo(event.getPacket(), event::setCancelled);
        for (Consumer<PacketInfo> r : sendPacket) {
            safe(() -> r.accept(info));
        }
    }

    @Subscribe
    public void onReceivePacket(ReceivePacketEvent event) {
        if (receivePacket.isEmpty()) {
            return;
        }
        PacketInfo info = new PacketInfo(event.getPacket(), event::setCancelled);
        for (Consumer<PacketInfo> r : receivePacket) {
            safe(() -> r.accept(info));
        }
    }

    @Subscribe
    public void onWorldJoin(JoinWorldEvent event) {
        for (Runnable r : worldJoin) {
            safe(r);
        }
    }

    @Subscribe
    public void onAttack(AttackEvent event) {
        if (attack.isEmpty() || event.getTarget() == null) {
            return;
        }
        ScriptEntity entity = new ScriptEntity(event.getTarget());
        for (Consumer<ScriptEntity> r : attack) {
            safe(() -> r.accept(entity));
        }
    }

    @Subscribe
    public void onJump(JumpEvent event) {
        for (Runnable r : jump) {
            safe(r);
        }
    }

    /** Chat message wrapper with cancellation. */
    public static final class ChatMsg {
        private final ChatReceivedEvent event;

        ChatMsg(ChatReceivedEvent event) {
            this.event = event;
        }

        public String getMessage() {
            return event.getText().getString();
        }

        public void cancel() {
            event.setCancelled();
        }
    }

    /** Key press wrapper with cancellation. */
    public static final class KeyPress {
        private final KeyPressEvent event;

        KeyPress(KeyPressEvent event) {
            this.event = event;
        }

        public int getKey() {
            return event.getInteractionCode();
        }

        public boolean isPressed() {
            return event.isPressed();
        }

        public void cancel() {
            event.setCancelled();
        }
    }

    /** Packet wrapper exposing the type name and cancellation. */
    public static final class PacketInfo {
        private final Object packet;
        private final Runnable canceller;

        PacketInfo(Object packet, Runnable canceller) {
            this.packet = packet;
            this.canceller = canceller;
        }

        public String getName() {
            return packet.getClass().getSimpleName();
        }

        public void cancel() {
            canceller.run();
        }
    }
}
