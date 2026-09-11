package cc.aerial.client.features.impl.utility;

import cc.aerial.client.event.impl.game.PreGameTickEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.impl.combat.killaura.KillauraModule;
import cc.aerial.client.property.BoundedNumberProperty;
import cc.aerial.client.utility.HypixelServer;
import cc.aerial.client.utility.InventoryUtility;
import net.hypixel.data.type.GameType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class AutoTotemModule extends Module {
    public static final AutoTotemModule INSTANCE = new AutoTotemModule();

    private final BoundedNumberProperty delay = new BoundedNumberProperty("Delay", 50, 100, 0, 400, 5);
    private final BoundedNumberProperty health = new BoundedNumberProperty("Health", 10, 20, 1, 20, 1);

    private AutoTotemModule() {
        super("Auto Totem", "Automatically equips totems when health is low", ModuleCategory.UTILITY);
        addProperties(delay, health);
    }

    @Subscribe
    public void onPreGameTick(PreGameTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) && !InvMoveModule.INSTANCE.isEnabled()) {
            return;
        }

        if (KillauraModule.INSTANCE.getTargeting().getTarget() != null) {
            return;
        }

        if (HypixelServer.isCurrent()) {
            HypixelServer.ModAPI.Location location = HypixelServer.ModAPI.get().getCurrentLocation();
            if (location != null && (location.isLobby()
                    || !(location.serverType() == GameType.SKYWARS || location.serverType() == GameType.SURVIVAL_GAMES))) {
                return;
            }
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof InventoryMenu)) {
            return;
        }

        float currentHealth = player.getHealth();
        if (currentHealth > health.getRandomValue()) {
            return;
        }

        ItemStack offhandItem = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhandItem.is(Items.TOTEM_OF_UNDYING)) {
            return;
        }

        List<Slot> totemSlots = InventoryUtility.filterSlots(menu, slot -> 
            !slot.getItem().isEmpty() && slot.getItem().is(Items.TOTEM_OF_UNDYING), true);

        if (totemSlots.isEmpty()) {
            return;
        }

        Slot bestTotemSlot = totemSlots.stream()
            .max(Comparator.comparingInt(slot -> slot.getItem().getCount()))
            .orElse(null);

        if (bestTotemSlot == null) {
            return;
        }

        if (!InventoryManagerModule.INSTANCE.canMove((long) delay.getRandomValue())) {
            return;
        }

        InventoryUtility.shiftClick(menu, bestTotemSlot.index);
        InventoryManagerModule.INSTANCE.resetTimer();
    }
}
