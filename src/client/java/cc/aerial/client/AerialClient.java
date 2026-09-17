package cc.aerial.client;

import cc.aerial.client.binding.BindRepository;
import cc.aerial.client.config.ConfigUtility;
import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.features.impl.combat.*;
import cc.aerial.client.features.impl.movement.*;
import cc.aerial.client.features.impl.utility.*;
import cc.aerial.client.features.impl.visual.*;
import cc.aerial.client.features.impl.combat.crystalaura.CrystalAuraModule;
import cc.aerial.client.features.impl.combat.killaura.KillauraModule;
import cc.aerial.client.features.impl.hud.DynamicIsland;
import cc.aerial.client.features.impl.hud.RiseCapsuleModule;
import cc.aerial.client.features.impl.hud.ScaffoldBlockCounter;
import cc.aerial.client.features.impl.hud.VanillaMiningIsland;
import cc.aerial.client.features.impl.other.FakePlayerModule;
import cc.aerial.client.features.impl.other.SpotifyModule;
import cc.aerial.client.overlay.OverlayRenderer;
import cc.aerial.client.features.impl.world.XFarmModule;
import cc.aerial.client.features.impl.world.AntiAfkModule;
import cc.aerial.client.features.impl.world.AntiDebuffModule;
import cc.aerial.client.features.impl.world.BreakerModule;
import cc.aerial.client.features.impl.world.ChestAuraModule;
import cc.aerial.client.features.impl.world.ScaffoldModule;
import cc.aerial.client.features.impl.world.TimerModule;
import cc.aerial.client.features.repository.ModuleRepository;
import cc.aerial.client.hypixel.AerialHypixelTransport;
import cc.aerial.client.packet.LagManager;
import cc.aerial.client.packet.delay.DelayManager;
import cc.aerial.client.script.ScriptSystem;
import cc.aerial.client.utility.GroundTickTracker;
import cc.aerial.client.utility.TeleportTickTracker;
import cc.aerial.client.screen.ClickGuiKeybind;
import cc.aerial.client.screen.MovementPassthrough;
import net.fabricmc.api.ClientModInitializer;

public class AerialClient implements ClientModInitializer {
	private static ModuleRepository moduleRepository;

	public static ModuleRepository getModuleRepository() {
		return moduleRepository;
	}

	@Override
	public void onInitializeClient() {
		cc.aerial.client.screen.title.SplashCard.requestFont();

		moduleRepository = ModuleRepository.fromModules(
				AttackDelayModule.INSTANCE,
				AutoClickerModule.INSTANCE,
				WTapModule.INSTANCE,
				HitSelectModule.INSTANCE,
				KeepSprintModule.INSTANCE,
				VelocityModule.INSTANCE,
				AntiBotModule.INSTANCE,
				AntiFireballModule.INSTANCE,
				ReachModule.INSTANCE,
				CriticalsModule.INSTANCE,
				KillauraModule.INSTANCE,
				PiercingModule.INSTANCE,
				AutoBlockModule.INSTANCE,
				CrystalAuraModule.INSTANCE,
				AutoPotModule.INSTANCE,
				SprintModule.INSTANCE,
				SpiderModule.INSTANCE,
				JesusModule.INSTANCE,
				StepModule.INSTANCE,
				PhaseModule.INSTANCE,
				SpeedModule.INSTANCE,
				NullMoveModule.INSTANCE,
				StasisModule.INSTANCE,
				VClipModule.INSTANCE,
				MovementFixModule.INSTANCE,
				NoJumpDelayModule.INSTANCE,
				NoSlowModule.INSTANCE,
				NoPushModule.INSTANCE,
				ClickGuiModule.INSTANCE,
				JoinClaimModule.INSTANCE,
				PingSpoofModule.INSTANCE,
				TeleportAuraModule.INSTANCE,
				RegenModule.INSTANCE,
				ResourcePackSpoofModule.INSTANCE,
				LadderClutchModule.INSTANCE,
		        EagleModule.INSTANCE,
				FlightModule.INSTANCE,
				LongJumpModule.INSTANCE,
				AutoToolModule.INSTANCE,
				FastPlaceModule.INSTANCE,
				FastBreakModule.INSTANCE,
				InvMoveModule.INSTANCE,
				NoFallModule.INSTANCE,
				ChatBypassModule.INSTANCE,
				OverlayModule.INSTANCE,
				NoRotateModule.INSTANCE,
				AntiVoidModule.INSTANCE,
				DisablerModule.INSTANCE,
				AutoHypixelModule.INSTANCE,
				AutoArmorModule.INSTANCE,
				AutoTotemModule.INSTANCE,
				InventoryManagerModule.INSTANCE,
				ChestStealerModule.INSTANCE,
				AutoChestModule.INSTANCE,
				BlinkModule.INSTANCE,
				MaxOptimizationModule.INSTANCE,
				AnimationsModule.INSTANCE,
				CapeModule.INSTANCE,
				AttackEffectsModule.INSTANCE,
				NoHurtCameraModule.INSTANCE,
				FullBrightModule.INSTANCE,
				AmbienceModule.INSTANCE,
				ESPModule.INSTANCE,
				InterfaceModule.INSTANCE,
				TargetESPModule.INSTANCE,
				TargetHudModule.INSTANCE,
				VanillaFixModule.INSTANCE,
				TimerModule.INSTANCE,
				ScaffoldModule.INSTANCE,
				BreakerModule.INSTANCE,
				AntiAfkModule.INSTANCE,
				AntiDebuffModule.INSTANCE,
				ViewClipModule.INSTANCE,
				MurderMysteryModule.INSTANCE,
				KillEffectModule.INSTANCE,
				FireflyModule.INSTANCE,
				JumpCircleModule.INSTANCE,
				BedPlatesModule.INSTANCE,
				XFarmModule.INSTANCE,
				BedwarsUtilModule.INSTANCE,
				ChestAuraModule.INSTANCE,
				DisplaceModule.INSTANCE,
				BacktrackModule.INSTANCE,
				LagRangeModule.INSTANCE,
				RodAimbotModule.INSTANCE,
				ScoreboardModule.INSTANCE,
				ChatModule.INSTANCE,
				PotionEffectsModule.INSTANCE,
				StreamerModule.INSTANCE,
				FreeLookModule.INSTANCE,
				SmoothCameraModule.INSTANCE,
				PostProcessingModule.INSTANCE,
				FakePlayerModule.INSTANCE,
				SpotifyModule.INSTANCE,
				TargetStrafeModule.INSTANCE,
				KillMessageModule.INSTANCE
		);

		// Initialize scripting system after the module repository is ready
		// so script-created modules can register themselves as dynamic modules.
		ScriptSystem.getInstance().initialize();

		AerialHypixelTransport.INSTANCE.register();

		BindRepository.INSTANCE.getBindingService();

		ConfigUtility.load();

		Runtime.getRuntime().addShutdownHook(new Thread(ConfigUtility::save));

		LagManager.INSTANCE.setDelay(0);

		OverlayRenderer.INSTANCE.hashCode();

		EventDispatcher.subscribe(GroundTickTracker.INSTANCE);
		EventDispatcher.subscribe(TeleportTickTracker.INSTANCE);
		EventDispatcher.subscribe(cc.aerial.client.packet.PacketRateDebug.INSTANCE);

		DelayManager.INSTANCE.getDelayModule();

		EventDispatcher.subscribe(new ClickGuiKeybind());
		EventDispatcher.subscribe(new MovementPassthrough());
		EventDispatcher.subscribe(VanillaMiningIsland.INSTANCE);

		EventDispatcher.subscribe(ScaffoldBlockCounter.INSTANCE);

		EventDispatcher.subscribe(cc.aerial.client.rotation.SilentAim.INSTANCE);
	}
}
