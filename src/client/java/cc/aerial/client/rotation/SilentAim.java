package cc.aerial.client.rotation;

import cc.aerial.client.event.impl.game.packet.ReceivePacketEvent;
import cc.aerial.client.event.impl.game.packet.SendPacketEvent;
import cc.aerial.client.event.impl.game.player.movement.PreMovementPacketEvent;
import cc.aerial.client.event.subscriber.IEventSubscriber;
import cc.aerial.client.event.subscriber.Subscribe;
import net.minecraft.client.Minecraft;
import cc.aerial.client.utility.PacketUtility;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec2;

/**
 * Server-side-only aim: a rotation is stamped onto the outgoing movement packet and the player's
 * own camera never moves.
 *
 * <p>This is the counterpart to {@link cc.aerial.client.rotation.handler.RotationMouseHandler},
 * which does the opposite — it turns the real camera with synthetic mouse deltas. Modules that need
 * the server to believe they are looking somewhere, without the view moving, submit here.</p>
 *
 * <p>Arbitration is per tick and mirrors {@link ServerRotation}: the highest priority submitted
 * during a tick wins, ties go to the last submitter. A single listener on
 * {@link PreMovementPacketEvent} applies the winner, so competing modules never race each other's
 * writes — they race the priority number instead.</p>
 *
 * <h2>outgoing vs wire</h2>
 * <p>{@link #outgoing()} is what we decided this tick. {@link #wire()} is what the server was last
 * actually told. They differ: {@code LocalPlayer.sendPosition} omits the rotation entirely when it
 * has not changed, so a module that reasons about what the server believes — raytracing from the
 * server's viewpoint, for instance — must read {@link #wire()}, not {@link #outgoing()}.</p>
 */
public final class SilentAim implements IEventSubscriber {
    public static final SilentAim INSTANCE = new SilentAim();

    /** Applied last so that it wins over any module writing the movement packet directly. */
    private static final int APPLY_PRIORITY = -100;

    /**
     * How far a submission goes beyond the movement packet.
     *
     * <p>A rotation that only reaches the packet is not enough on a server that predicts movement: the client
     * still walks by its camera, the server predicts by the yaw it was sent, and the two positions drift
     * apart every tick that the player moves. {@link #STRICT} therefore also runs the LOCAL physics on the
     * sent yaw, which is what makes the prediction agree; {@link #REDIRECT} does that and additionally
     * counter-rotates the movement input, so the player still walks where they were pointing.
     */
    public enum Movement {
        /** Packet only. Feels best, drifts from any server that predicts. */
        NONE,
        /** Physics runs on the sent yaw: the walk direction turns with the aim. */
        STRICT,
        /** Physics runs on the sent yaw, input counter-rotated so the walk direction is unchanged. */
        REDIRECT
    }

    /** Whether a submitted rotation is still waiting for the movement packet that will carry it. */
    private boolean pending;

    private Movement movement = Movement.NONE;

    /** How many movement packets this aim has stamped; diagnostics only. */
    private int applied;

    private float yaw;
    private float pitch;
    private int priority = Integer.MIN_VALUE;
    private Object owner;
    private int submittedTick = -1;

    private float wireYaw;
    private float wirePitch;
    private boolean wireValid;

    private SilentAim() {
    }

    /**
     * Requests that this tick's movement packet carry {@code yaw}/{@code pitch}.
     *
     * <p>Pitch is clamped to the legal range; yaw is passed through unwrapped so callers keep
     * control over which way a turn goes.</p>
     *
     * @return true when the caller currently holds the tick
     */
    public boolean submit(float yaw, float pitch, int priority, Object owner) {
        return submit(yaw, pitch, priority, owner, Movement.NONE);
    }

    /** As {@link #submit(float, float, int, Object)}, choosing how far the correction reaches. */
    public boolean submit(float yaw, float pitch, int priority, Object owner, Movement movement) {
        int tick = currentTick();
        if (tick == Integer.MIN_VALUE) {
            return false;
        }
        if (tick != submittedTick) {
            submittedTick = tick;
            this.priority = Integer.MIN_VALUE;
            this.owner = null;
        }
        if (priority < this.priority) {
            return false;
        }
        this.priority = priority;
        this.owner = owner;
        this.yaw = yaw;
        this.pitch = Math.max(-90.0f, Math.min(90.0f, pitch));
        this.pending = true;
        this.movement = movement;
        return true;
    }

    /** How far this tick's submission corrects movement; {@link Movement#NONE} when nothing is submitted. */
    public Movement movement() {
        return isActive() ? movement : Movement.NONE;
    }

    /**
     * The yaw the local physics should run on, or {@code fallback} when nothing is correcting movement.
     *
     * <p>Read from {@code Entity.moveRelative}, the one place where a yaw becomes a direction of travel.
     */
    public float movementYaw(float fallback) {
        return movement() == Movement.NONE ? fallback : yaw;
    }

    /**
     * True while a submitted rotation is still waiting for its movement packet.
     *
     * <p>Deliberately NOT "submitted during the current tick". Everything that submits does so from
     * {@code Minecraft.tick} — the pre-tick handlers and the keybind handling — and all of that runs BEFORE
     * {@code level.tickEntities()}, where {@code LocalPlayer.tick} first increments {@code tickCount} through
     * {@code baseTick} and only then sends the movement packet. A submission made in tick N is therefore read
     * back while the counter already says N+1, and an equality test would discard every rotation ever
     * submitted while leaving the clicks that depend on it untouched — the module would act without aiming.
     *
     * <p>So the window is the packet cycle, not the tick: a submission stays live until the movement packet
     * consumes it, and expires if one does not arrive within a tick of the submission.
     */
    /** Movement packets stamped so far, for diagnostics. */
    public int appliedCount() {
        return applied;
    }

    public boolean isActive() {
        return pending && submittedTick != Integer.MIN_VALUE && currentTick() - submittedTick <= 1;
    }

    public Object getOwner() {
        return isActive() ? owner : null;
    }

    /** The rotation this tick's movement packet will carry; the player's own rotation if none. */
    public Vec2 outgoing() {
        if (isActive()) {
            return new Vec2(yaw, pitch);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Vec2.ZERO : new Vec2(player.getYRot(), player.getXRot());
    }

    /** The last rotation actually put on the wire; falls back to {@link #outgoing()}. */
    public Vec2 wire() {
        return wireValid ? new Vec2(wireYaw, wirePitch) : outgoing();
    }

    /**
     * The rotation quantisation step the vanilla mouse pipeline snaps to, in degrees.
     *
     * <p>Same derivation as {@link RotationUtility#patchConstantRotation}: sensitivity is remapped
     * to {@code s = sens * 0.6 + 0.2}, cubed, scaled by 8 for the cursor delta, then by 0.15 to get
     * back to degrees. Any rotation that is not a multiple of this away from the previous one is
     * unreachable with a real mouse.</p>
     */
    public static float gcd() {
        Minecraft minecraft = Minecraft.getInstance();
        double sensitivity = minecraft.options.sensitivity().get() * 0.6d + 0.2d;
        return (float) (sensitivity * sensitivity * sensitivity * 8.0d * 0.15d);
    }

    @Subscribe(priority = APPLY_PRIORITY)
    public void onPreMovementPacket(PreMovementPacketEvent event) {
        if (!isActive()) {
            return;
        }
        event.setYaw(yaw);
        event.setPitch(pitch);
        applied++;
        // The packet is the consumer: one submission serves exactly one movement packet, and the next tick
        // has to ask again. Without this a rotation would keep being stamped on after its owner stopped.
        pending = false;
    }

    @Subscribe
    public void onSendPacket(SendPacketEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof ServerboundMovePlayerPacket move
                && (packet instanceof ServerboundMovePlayerPacket.PosRot
                    || packet instanceof ServerboundMovePlayerPacket.Rot)) {
            wireYaw = move.getYRot(wireYaw);
            wirePitch = move.getXRot(wirePitch);
            wireValid = true;
            return;
        }
        if (packet instanceof ServerboundUseItemPacket use) {
            correctUseItemRotation(event, use);
        }
    }

    /**
     * Rewrites the rotation of an outgoing use-item packet to the one this tick is aiming with.
     *
     * <p>{@code ServerboundUseItemPacket} carries its own yaw and pitch, and the server SNAPS the player to
     * them. Vanilla fills them from the camera, which is right for a real click and wrong for a silent one: the
     * server would be told to look at the camera while this tick's movement packet tells it to look somewhere
     * else, and the two would fight. Nothing else in the packet changes.
     *
     * <p>The replacement goes out through the event-free send, so it does not re-enter this handler; the wire
     * tracking above only follows movement packets, so nothing drifts from skipping the event.
     */
    private void correctUseItemRotation(SendPacketEvent event, ServerboundUseItemPacket use) {
        if (!isActive() || (use.getYRot() == yaw && use.getXRot() == pitch)) {
            return;
        }
        event.setCancelled();
        PacketUtility.sendNoEvent(
                new ServerboundUseItemPacket(use.getHand(), use.getSequence(), yaw, pitch));
    }

    @Subscribe
    public void onReceivePacket(ReceivePacketEvent event) {
        // A server teleport rewrites the server's idea of our rotation, and the relative-flag maths
        // is not worth replicating here: drop the cache and let the next movement packet re-establish
        // the truth. Until then wire() falls back to outgoing(), which is the honest answer.
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            wireValid = false;
        }
    }

    /** Clears per-world state. Call on world change and disconnect. */
    public void reset() {
        pending = false;
        submittedTick = -1;
        priority = Integer.MIN_VALUE;
        owner = null;
        wireValid = false;
    }

    private static int currentTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Integer.MIN_VALUE : player.tickCount;
    }
}
