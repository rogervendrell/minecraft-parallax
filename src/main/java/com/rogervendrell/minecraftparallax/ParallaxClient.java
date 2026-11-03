package com.rogervendrell.minecraftparallax;

import com.mojang.blaze3d.platform.InputConstants;
import com.rogervendrell.minecraftparallax.gui.ParallaxScreen;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ParallaxClient implements ClientModInitializer {
	public static final String MOD_ID = "minecraft_parallax";
	public static final Logger LOGGER = LoggerFactory.getLogger("Minecraft Parallax");

	private static ParallaxConfig config = new ParallaxConfig();
	private static final HeadTrackingReceiver RECEIVER = new HeadTrackingReceiver();

	private static KeyMapping toggleKey;
	private static KeyMapping recenterKey;
	private static KeyMapping statusKey;
	private static KeyMapping menuKey;
	/** Set by "/parallax menu"; the screen opens on the next tick, after the chat screen has closed. */
	private static boolean openMenuNextTick;
	/** Whether the head position has been centred since the game started (manually or automatically). */
	private static boolean centeredThisSession;
	private static int freshTicks;

	@Override
	public void onInitializeClient() {
		config = ParallaxConfig.load();
		restartReceiver();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minecraft_parallax.toggle", InputConstants.KEY_F7, category));
		recenterKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minecraft_parallax.recenter", InputConstants.KEY_F8, category));
		statusKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minecraft_parallax.status", InputConstants.KEY_F9, category));
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minecraft_parallax.menu", InputConstants.KEY_F6, category));

		ClientTickEvents.END_CLIENT_TICK.register(ParallaxClient::onEndTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RECEIVER.stop());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> ParallaxCommands.register(dispatcher));
		ParallaxHud.register();

		// A "Minecraft Parallax..." button in the bottom-left corner of the pause menu.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("minecraft_parallax.pause_button"),
						button -> client.gui.setScreen(new ParallaxScreen(screen)))
					.bounds(4, height - 24, 130, 20)
					.tooltip(Tooltip.create(Component.translatable("minecraft_parallax.pause_button.tooltip")))
					.build());
			}
		});
	}

	private static void onEndTick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			toggle();
		}
		while (recenterKey.consumeClick()) {
			notify(recenter());
		}
		while (statusKey.consumeClick()) {
			config.showStatusHud = !config.showStatusHud;
			config.save();
		}
		while (menuKey.consumeClick()) {
			openMenuNextTick = true;
		}
		if (openMenuNextTick && mc.gui.screen() == null) {
			openMenuNextTick = false;
			mc.gui.setScreen(new ParallaxScreen(null));
		}

		// First connection this session: centre on the current head position after a second of stable data.
		if (mc.player != null && RECEIVER.fresh(config.trackingTimeoutSeconds) != null) {
			if (!centeredThisSession && ++freshTicks >= 20) {
				Component keyName = recenterKey.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.YELLOW);
				if (config.autoRecenter) {
					recenter();
					notify(Component.literal("Minecraft Parallax: head tracking connected and centred. Press ").append(keyName).append(" to recenter."));
				} else {
					centeredThisSession = true;
					notify(Component.literal("Minecraft Parallax: head tracking connected. Sit normally and press ").append(keyName).append(" to recenter."));
				}
			}
		} else {
			freshTicks = 0;
		}
	}

	/** The effect waits for the first (auto) recenter so it never starts from an uncentred, tilted view. */
	public static boolean effectReady() {
		return centeredThisSession || !config.autoRecenter;
	}

	public static void toggle() {
		config.enabled = !config.enabled;
		config.save();
		notify(Component.literal("Minecraft Parallax: ")
			.append(config.enabled ? Component.literal("ON").withStyle(ChatFormatting.GREEN) : Component.literal("OFF").withStyle(ChatFormatting.RED)));
	}

	/** Makes the current head position the neutral one (screen centre, current distance). */
	public static Component recenter() {
		HeadTrackingReceiver.Sample s = RECEIVER.fresh(Math.max(config.trackingTimeoutSeconds, 1.0));
		if (s == null) {
			return Component.literal("Minecraft Parallax: no tracking data to recenter on. Is the tracker running?").withStyle(ChatFormatting.RED);
		}
		double x = config.invertX ? -s.x() : s.x();
		double y = config.invertY ? -s.y() : s.y();
		double z = config.invertZ ? -s.z() : s.z();
		config.centerOffsetXCm = -x;
		config.centerOffsetYCm = -y;
		config.neutralDistanceCm = Math.max(15.0, Math.min(200.0, z));
		config.neutralYawDegrees = config.invertYaw ? -s.yaw() : s.yaw();
		config.neutralPitchDegrees = config.invertPitch ? -s.pitch() : s.pitch();
		config.save();
		centeredThisSession = true;
		HeadCoupledView.resetSmoothing();
		return Component.literal(String.format(Locale.ROOT, "Minecraft Parallax: recentered (neutral distance %.0f cm)", config.neutralDistanceCm));
	}

	public static void restartReceiver() {
		RECEIVER.start(config.udpBindAddress, config.udpPort);
	}

	public static void reloadConfig() {
		config = ParallaxConfig.load();
		restartReceiver();
	}

	public static void resetConfig() {
		config = new ParallaxConfig();
		config.save();
		restartReceiver();
	}

	private static void notify(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(message);
		}
	}

	/** Opens the settings menu once the current screen (e.g. chat) has closed. */
	public static void openMenuSoon() {
		openMenuNextTick = true;
	}

	public static KeyMapping toggleKey() {
		return toggleKey;
	}

	public static KeyMapping recenterKey() {
		return recenterKey;
	}

	public static KeyMapping statusKey() {
		return statusKey;
	}

	public static KeyMapping menuKey() {
		return menuKey;
	}

	public static ParallaxConfig config() {
		return config;
	}

	public static HeadTrackingReceiver receiver() {
		return RECEIVER;
	}
}
