package cc.aerial.client.features.impl.combat;

import cc.aerial.client.event.impl.game.packet.ReceivePacketEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.utility.ChatUtility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class KillMessageModule extends Module {
    public static final KillMessageModule INSTANCE = new KillMessageModule();

    private final BooleanProperty totemPopEnabled = new BooleanProperty("Totem Pop", true);
    private final BooleanProperty killEnabled = new BooleanProperty("Kill Message", true);
    
    private final ModeProperty<MessageMode> messageMode = new ModeProperty<>("Message Mode", MessageMode.RANDOM);

    private static final long MESSAGE_COOLDOWN_MS = 2000L;
    private long lastMessageTime = 0L;

    private static final Random RANDOM = new Random();

    private static final String[] TOTEM_MESSAGES = {
        "Totem popped {name}!",
        "{name} lost their totem",
        "Bye bye totem {name}",
        "{name}'s totem gone",
        "Pop {name}!",
        "Totem down {name}",
        "{name} no totem lol",
        "Easy totem pop {name}"
    };

    private static final String[] KILL_MESSAGES = {
        "Troll is so good",
        "Ez kill {name}",
        "{name} got rekt",
        "GG {name}",
        "{name} died to Troll",
        "Troll > {name}",
        "{name} is trash",
        "Troll on top",
        "{name} got owned by troll",
        "тебя выебали как шлюху"
    };

    public enum MessageMode {
        RANDOM("Random"),
        SEQUENTIAL("Sequential");

        private final String label;

        MessageMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private int totemMessageIndex = 0;
    private int killMessageIndex = 0;

    private KillMessageModule() {
        super("Kill Message", "Send chat messages on totem pop and kill", ModuleCategory.COMBAT);
        addProperties(totemPopEnabled, killEnabled, messageMode);
    }

    @Subscribe
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!isEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (event.getPacket() instanceof ClientboundEntityEventPacket entityEvent) {
            Entity entity = entityEvent.getEntity(mc.level);
            
            if (entity instanceof LivingEntity living && entity != player) {
                byte eventId = entityEvent.getEventId();
                
                // Totem pop event ID is 35
                if (eventId == 35 && totemPopEnabled.getValue() && canSendMessage()) {
                    String entityName = living.getName().getString();
                    sendTotemMessage(entityName);
                }
            }
        }
    }

    public void onEntityDeath(Entity entity) {
        if (!isEnabled() || !killEnabled.getValue()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || entity == player) {
            return;
        }

        if (entity instanceof LivingEntity living && canSendMessage()) {
            String entityName = living.getName().getString();
            sendKillMessage(entityName);
        }
    }

    private void sendTotemMessage(String targetName) {
        String message;
        
        if (messageMode.getValue() == MessageMode.RANDOM) {
            message = TOTEM_MESSAGES[RANDOM.nextInt(TOTEM_MESSAGES.length)];
        } else {
            message = TOTEM_MESSAGES[totemMessageIndex % TOTEM_MESSAGES.length];
            totemMessageIndex++;
        }
        
        message = message.replace("{name}", targetName);
        ChatUtility.print(message);
        lastMessageTime = System.currentTimeMillis();
    }

    private void sendKillMessage(String targetName) {
        String message;
        
        if (messageMode.getValue() == MessageMode.RANDOM) {
            message = KILL_MESSAGES[RANDOM.nextInt(KILL_MESSAGES.length)];
        } else {
            message = KILL_MESSAGES[killMessageIndex % KILL_MESSAGES.length];
            killMessageIndex++;
        }
        
        message = message.replace("{name}", targetName);
        ChatUtility.print(message);
        lastMessageTime = System.currentTimeMillis();
    }

    private boolean canSendMessage() {
        return System.currentTimeMillis() - lastMessageTime >= MESSAGE_COOLDOWN_MS;
    }

    @Override
    protected void onDisable() {
        lastMessageTime = 0L;
        totemMessageIndex = 0;
        killMessageIndex = 0;
    }
}
