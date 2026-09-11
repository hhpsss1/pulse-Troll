package cc.aerial.client.features.impl.other;

import cc.aerial.client.event.impl.game.PreGameTickEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.property.MultipleBooleanProperty;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

public final class FakePlayerModule extends Module {
    public static final FakePlayerModule INSTANCE = new FakePlayerModule();

    private static final UUID FAKE_PLAYER_UUID = UUID.fromString("01880033-0042-0007-1337-000000000000");
    private static final String FAKE_NAME = "oliwierszczepanik";

    private final BooleanProperty copyInventory = new BooleanProperty("Copy Inventory", false);
    private final MultipleBooleanProperty armor = new MultipleBooleanProperty("Armor",
            new BooleanProperty("Helmet", true),
            new BooleanProperty("Chestplate", true),
            new BooleanProperty("Leggings", true),
            new BooleanProperty("Boots", true)
    );
    private final ModeProperty<ArmorType> armorType = new ModeProperty<>("Armor Type", ArmorType.NETHERITE);

    private RemotePlayer fakePlayer;

    private FakePlayerModule() {
        super("Fake Player", "Spawns a fake player for testing", ModuleCategory.UTILITY);
        addProperties(copyInventory, armor, armorType);
    }

    @Override
    protected void onEnable() {
        spawnFakePlayer();
    }

    @Override
    protected void onDisable() {
        removeFakePlayer();
    }

    @Subscribe
    public void onPreGameTick(PreGameTickEvent event) {
        if (!isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            removeFakePlayer();
            return;
        }
        if (fakePlayer == null) {
            spawnFakePlayer();
            return;
        }
        if (fakePlayer.level() != mc.level || fakePlayer.isRemoved()) {
            fakePlayer = null;
            spawnFakePlayer();
            return;
        }
        if (copyInventory.getValue()) {
            copyInventory(fakePlayer);
        }
    }

    public static boolean handleLocalAttack(Entity target) {
        FakePlayerModule module = INSTANCE;
        if (!module.isEnabled()) return false;
        return module.handleLocalAttackInternal(target);
    }

    private boolean handleLocalAttackInternal(Entity target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        if (fakePlayer == null) return false;
        if (target != fakePlayer || fakePlayer.level() != mc.level || fakePlayer.isRemoved()) return false;
        if (fakePlayer.hurtTime > 0) return true;

        float baseDamage = weaponDamage(mc.player.getMainHandItem());
        float cooldown = mc.player.getAttackStrengthScale(0.5f);
        baseDamage *= 0.2f + cooldown * cooldown * 0.8f;

        boolean critical = mc.player.fallDistance > 0.0f
                && !mc.player.onGround()
                && !mc.player.onClimbable()
                && !mc.player.isInWater()
                && !mc.player.hasEffect(MobEffects.BLINDNESS)
                && !mc.player.isPassenger()
                && cooldown > 0.9f;

        if (critical) baseDamage *= 1.5f;

        var source = mc.level.damageSources().playerAttack(mc.player);
        float damage = CombatRules.getDamageAfterAbsorb(fakePlayer, baseDamage, source, armorValue(), armorToughness());
        mc.level.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(), 
                SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0f, 1.0f);

        if (critical) {
            mc.level.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.0f);
            new ClientboundEntityEventPacket(fakePlayer, (byte) 36).handle(mc.player.connection);
        } else if (cooldown > 0.9f) {
            mc.level.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                    SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0f, 1.0f);
        } else {
            mc.level.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 1.0f);
        }

        fakePlayer.handleEntityEvent((byte) 2);
        fakePlayer.hurtTime = 10;
        fakePlayer.hurtDuration = 10;
        float nextHealth = fakePlayer.getHealth() - damage;
        if (nextHealth <= 0.0f) {
            fakePlayer.setHealth(20.0f);
            fakePlayer.setAbsorptionAmount(0.0f);
            new ClientboundEntityEventPacket(fakePlayer, (byte) 35).handle(mc.player.connection);
        } else {
            fakePlayer.setHealth(nextHealth);
        }
        return true;
    }

    private void spawnFakePlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        try {
            removeFakePlayer();

            // Not pushable and not collidable: the twin stands where the player is aiming, and a vanilla
            // player shoves other players out of its space, so a solid one would slide the user off their
            // own position for as long as it exists.
            RemotePlayer fake = new RemotePlayer(mc.level, new GameProfile(FAKE_PLAYER_UUID, FAKE_NAME)) {
                @Override
                public boolean isPushable() {
                    return false;
                }

                @Override
                public boolean canBeCollidedWith(net.minecraft.world.entity.Entity entity) {
                    return false;
                }

                /**
                 * Pushing is not symmetric, which is why the two overrides above are not enough on their
                 * own: an entity shoves the OTHERS it overlaps during its own tick, and the test it runs is
                 * on them, not on itself. Refusing to be pushed therefore only stops the player half of it;
                 * the twin would still walk the player out of their own position every tick.
                 */
                @Override
                protected void pushEntities() {
                }

                @Override
                protected void doPush(net.minecraft.world.entity.Entity entity) {
                }
            };
            fake.copyPosition(mc.player);
            fake.setYRot(mc.player.getYRot());
            fake.setXRot(mc.player.getXRot());
            fake.setYHeadRot(mc.player.getYHeadRot());
            fake.setYRot(mc.player.getYRot());
            fake.setHealth(20.0f);
            fake.setAbsorptionAmount(0.0f);
            fake.setCustomName(Component.literal(FAKE_NAME));
            fake.setCustomNameVisible(true);

            if (copyInventory.getValue()) {
                copyInventory(fake);
            }
            equipArmor(fake);

            fake.setId(freeEntityId(mc.level));
            mc.level.addEntity(fake);
            fake.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 9999, 2));
            fake.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 9999, 4));
            fake.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 9999, 1));
            fakePlayer = fake;
        } catch (Throwable throwable) {
            removeFakePlayer();
            throwable.printStackTrace();
        }
    }

    /**
     * A client-side entity id nothing else is using.
     *
     * <p>26.2 stopped handing out ids in the {@code Entity} constructor — a server entity now gets its id
     * from the spawn packet, and {@code getId()} throws until one is set. A purely client-side entity has no
     * packet to take an id from, so it has to claim one, and it counts DOWN from the top of the range because
     * the server counts up from zero: the two allocators can then only meet if a session ever spawns two
     * billion entities.
     */
    private static int freeEntityId(net.minecraft.client.multiplayer.ClientLevel level) {
        for (int id = Integer.MAX_VALUE; id > Integer.MAX_VALUE - 64; id--) {
            if (level.getEntity(id) == null) {
                return id;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void removeFakePlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && fakePlayer != null && fakePlayer.level() == mc.level) {
            fakePlayer.discard();
        }
        fakePlayer = null;
    }

    private void copyInventory(RemotePlayer fake) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        fake.setItemInHand(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().copy());
        fake.setItemInHand(InteractionHand.OFF_HAND, mc.player.getOffhandItem().copy());
    }

    private void equipArmor(RemotePlayer fake) {
        fake.setItemSlot(EquipmentSlot.HEAD, armorStack("Helmet", "helmet"));
        fake.setItemSlot(EquipmentSlot.CHEST, armorStack("Chestplate", "chestplate"));
        fake.setItemSlot(EquipmentSlot.LEGS, armorStack("Leggings", "leggings"));
        fake.setItemSlot(EquipmentSlot.FEET, armorStack("Boots", "boots"));
    }

    private ItemStack armorStack(String settingName, String piece) {
        BooleanProperty prop = armor.getProperty(settingName);
        if (prop != null && prop.getValue()) {
            return new ItemStack(armorItem(armorType.getValue(), piece));
        }
        return ItemStack.EMPTY;
    }

    private Item armorItem(ArmorType type, String piece) {
        if (type == ArmorType.LEATHER) {
            if (piece.equals("helmet")) return Items.LEATHER_HELMET;
            if (piece.equals("chestplate")) return Items.LEATHER_CHESTPLATE;
            if (piece.equals("leggings")) return Items.LEATHER_LEGGINGS;
            return Items.LEATHER_BOOTS;
        }
        if (type == ArmorType.CHAINMAIL) {
            if (piece.equals("helmet")) return Items.CHAINMAIL_HELMET;
            if (piece.equals("chestplate")) return Items.CHAINMAIL_CHESTPLATE;
            if (piece.equals("leggings")) return Items.CHAINMAIL_LEGGINGS;
            return Items.CHAINMAIL_BOOTS;
        }
        if (type == ArmorType.IRON) {
            if (piece.equals("helmet")) return Items.IRON_HELMET;
            if (piece.equals("chestplate")) return Items.IRON_CHESTPLATE;
            if (piece.equals("leggings")) return Items.IRON_LEGGINGS;
            return Items.IRON_BOOTS;
        }
        if (type == ArmorType.GOLDEN) {
            if (piece.equals("helmet")) return Items.GOLDEN_HELMET;
            if (piece.equals("chestplate")) return Items.GOLDEN_CHESTPLATE;
            if (piece.equals("leggings")) return Items.GOLDEN_LEGGINGS;
            return Items.GOLDEN_BOOTS;
        }
        if (type == ArmorType.DIAMOND) {
            if (piece.equals("helmet")) return Items.DIAMOND_HELMET;
            if (piece.equals("chestplate")) return Items.DIAMOND_CHESTPLATE;
            if (piece.equals("leggings")) return Items.DIAMOND_LEGGINGS;
            return Items.DIAMOND_BOOTS;
        }
        if (type == ArmorType.NETHERITE) {
            if (piece.equals("helmet")) return Items.NETHERITE_HELMET;
            if (piece.equals("chestplate")) return Items.NETHERITE_CHESTPLATE;
            if (piece.equals("leggings")) return Items.NETHERITE_LEGGINGS;
            return Items.NETHERITE_BOOTS;
        }
        return Items.AIR;
    }

    private float armorValue() {
        return (float) selectedArmorStats().stream().mapToDouble(pair -> pair.getLeft()).sum();
    }

    private float armorToughness() {
        return (float) selectedArmorStats().stream().mapToDouble(pair -> pair.getRight()).sum();
    }

    private java.util.List<Pair<Float, Float>> selectedArmorStats() {
        ArmorType material = armorType.getValue();
        java.util.List<Pair<Float, Float>> stats = new java.util.ArrayList<>();
        BooleanProperty helmet = armor.getProperty("Helmet");
        BooleanProperty chestplate = armor.getProperty("Chestplate");
        BooleanProperty leggings = armor.getProperty("Leggings");
        BooleanProperty boots = armor.getProperty("Boots");
        if (helmet != null && helmet.getValue()) stats.add(armorStats(material, "helmet"));
        if (chestplate != null && chestplate.getValue()) stats.add(armorStats(material, "chestplate"));
        if (leggings != null && leggings.getValue()) stats.add(armorStats(material, "leggings"));
        if (boots != null && boots.getValue()) stats.add(armorStats(material, "boots"));
        return stats;
    }

    private Pair<Float, Float> armorStats(ArmorType type, String piece) {
        float armorPoints;
        if (type == ArmorType.LEATHER) {
            if (piece.equals("helmet")) armorPoints = 1.0f;
            else if (piece.equals("chestplate")) armorPoints = 3.0f;
            else if (piece.equals("leggings")) armorPoints = 2.0f;
            else armorPoints = 1.0f;
        } else if (type == ArmorType.GOLDEN) {
            if (piece.equals("helmet")) armorPoints = 2.0f;
            else if (piece.equals("chestplate")) armorPoints = 5.0f;
            else if (piece.equals("leggings")) armorPoints = 3.0f;
            else armorPoints = 1.0f;
        } else if (type == ArmorType.CHAINMAIL || type == ArmorType.IRON) {
            if (piece.equals("helmet")) armorPoints = 2.0f;
            else if (piece.equals("chestplate")) armorPoints = 6.0f;
            else if (piece.equals("leggings")) armorPoints = 5.0f;
            else armorPoints = 2.0f;
        } else if (type == ArmorType.DIAMOND || type == ArmorType.NETHERITE) {
            if (piece.equals("helmet")) armorPoints = 3.0f;
            else if (piece.equals("chestplate")) armorPoints = 8.0f;
            else if (piece.equals("leggings")) armorPoints = 6.0f;
            else armorPoints = 3.0f;
        } else {
            armorPoints = 0.0f;
        }
        
        float toughness;
        if (type == ArmorType.DIAMOND) toughness = 2.0f;
        else if (type == ArmorType.NETHERITE) toughness = 3.0f;
        else toughness = 0.0f;
        
        return new Pair<>(armorPoints, toughness);
    }

    private float weaponDamage(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.WOODEN_SWORD || item == Items.GOLDEN_SWORD) return 4.0f;
        if (item == Items.STONE_SWORD) return 5.0f;
        if (item == Items.IRON_SWORD) return 6.0f;
        if (item == Items.DIAMOND_SWORD) return 7.0f;
        if (item == Items.NETHERITE_SWORD) return 8.0f;
        if (item == Items.WOODEN_AXE || item == Items.GOLDEN_AXE) return 7.0f;
        if (item == Items.STONE_AXE || item == Items.IRON_AXE || item == Items.DIAMOND_AXE) return 9.0f;
        if (item == Items.NETHERITE_AXE) return 10.0f;
        if (item == Items.MACE) return 6.0f;
        if (item == Items.TRIDENT) return 9.0f;
        return 1.0f;
    }

    public enum ArmorType {
        LEATHER("Leather"),
        CHAINMAIL("Chainmail"),
        IRON("Iron"),
        GOLDEN("Golden"),
        DIAMOND("Diamond"),
        NETHERITE("Netherite");

        private final String label;

        ArmorType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static class Pair<L, R> {
        private final L left;
        private final R right;

        Pair(L left, R right) {
            this.left = left;
            this.right = right;
        }

        L getLeft() {
            return left;
        }

        R getRight() {
            return right;
        }
    }
}
