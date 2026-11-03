package com.rogervendrell.minecraftparallax;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

final class ParallaxHud {
	private ParallaxHud() {
	}

	static void register() {
		// Keep the crosshair on the thing you are actually aiming at while the view is shifted.
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, deltaTracker) -> {
			if (!ParallaxClient.config().crosshairFollowsAim || !HeadCoupledView.isActive()) {
				original.extractRenderState(graphics, deltaTracker);
				return;
			}
			if (!HeadCoupledView.crosshairVisible()) {
				return; // the aim point is behind the turned camera
			}
			float dx = HeadCoupledView.crosshairNdcX() * graphics.guiWidth() / 2.0F;
			float dy = -HeadCoupledView.crosshairNdcY() * graphics.guiHeight() / 2.0F;
			graphics.pose().pushMatrix();
			graphics.pose().translate(dx, dy);
			original.extractRenderState(graphics, deltaTracker);
			graphics.pose().popMatrix();
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ParallaxClient.MOD_ID, "status"), (graphics, deltaTracker) -> {
			if (ParallaxClient.config().showStatusHud) {
				drawStatus(graphics);
			}
		});
	}

	static List<String> statusLines() {
		ParallaxConfig cfg = ParallaxClient.config();
		HeadTrackingReceiver receiver = ParallaxClient.receiver();
		HeadTrackingReceiver.Sample fresh = receiver.fresh(cfg.trackingTimeoutSeconds);
		List<String> lines = new ArrayList<>();

		lines.add("Minecraft Parallax: " + (cfg.enabled ? "ON" : "OFF") + "  mode: " + cfg.viewMode.displayName().getString() + (HeadCoupledView.isActive() ? "  (active)" : ""));
		if (receiver.error() != null) {
			lines.add("UDP error: " + receiver.error());
		} else if (fresh == null) {
			lines.add("Waiting for tracker on UDP " + receiver.boundTo());
		} else {
			lines.add(String.format(Locale.ROOT, "Tracker: %d Hz   raw x=%+.1f y=%+.1f z=%.1f cm",
				receiver.packetsPerSecond(), fresh.x(), fresh.y(), fresh.z()));
		}
		if (HeadCoupledView.isActive()) {
			Vec3 eye = HeadCoupledView.effectiveEyeCm();
			Vec3 off = HeadCoupledView.cameraOffset();
			lines.add(String.format(Locale.ROOT, "Eye (calibrated): x=%+.1f y=%+.1f z=%.1f cm", eye.x, eye.y, eye.z));
			lines.add(String.format(Locale.ROOT, "Camera offset: right=%+.2f up=%+.2f back=%+.2f blocks", off.x, off.y, off.z));
			lines.add(String.format(Locale.ROOT, "Head turn: yaw=%+.1f pitch=%+.1f deg -> view yaw=%+.1f pitch=%+.1f deg",
				HeadCoupledView.headYaw(), HeadCoupledView.headPitch(), HeadCoupledView.viewYawDelta(), HeadCoupledView.viewPitchDelta()));
		}
		lines.add(String.format(Locale.ROOT, "Screen %.1fx%.1f cm, neutral %.0f cm, window %.2f blocks, strength %.2f",
			cfg.screenWidthCm, cfg.screenHeightCm, cfg.neutralDistanceCm, cfg.windowDistanceBlocks, cfg.strength));
		return lines;
	}

	private static void drawStatus(GuiGraphicsExtractor graphics) {
		Font font = Minecraft.getInstance().font;
		List<String> lines = statusLines();
		int width = 0;
		for (String line : lines) {
			width = Math.max(width, font.width(line));
		}
		int x = 4;
		int y = 4;
		int lineHeight = font.lineHeight + 2;
		graphics.fill(x - 2, y - 2, x + width + 2, y + lines.size() * lineHeight, 0x90000000);
		for (String line : lines) {
			graphics.text(font, line, x, y, 0xFFFFFFFF, false);
			y += lineHeight;
		}
	}
}
