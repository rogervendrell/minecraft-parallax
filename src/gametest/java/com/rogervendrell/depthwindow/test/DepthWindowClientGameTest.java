package com.rogervendrell.depthwindow.test;

import com.rogervendrell.depthwindow.DepthWindowClient;
import com.rogervendrell.depthwindow.HeadCoupledView;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a little depth test scene, streams fake head positions over UDP exactly like the Python tracker,
 * and takes a screenshot for each head pose.
 */
@SuppressWarnings("UnstableApiUsage")
public class DepthWindowClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("DepthWindowTest");
	private static volatile double[] head;

	@Override
	public void runTest(ClientGameTestContext context) {
		Thread sender = new Thread(DepthWindowClientGameTest::sendLoop, "fake head tracker");
		sender.setDaemon(true);
		sender.start();

		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			var server = sp.getServer();
			server.runCommand("time set 6000");
			server.runCommand("gamerule advance_time false");
			server.runCommand("weather clear");
			server.runCommand("fill -12 -61 -60 12 -61 4 minecraft:grass_block");
			// Near red pillar (left), target block straight ahead, mid lime pillar (right), far blue wall, very far yellow
			server.runCommand("fill -1 -60 -3 -1 -57 -3 minecraft:red_wool");
			server.runCommand("fill 0 -59 -6 0 -58 -6 minecraft:stone");
			server.runCommand("fill 2 -60 -9 2 -56 -9 minecraft:lime_wool");
			server.runCommand("fill -3 -60 -20 3 -54 -20 minecraft:blue_wool");
			server.runCommand("fill -8 -60 -50 -8 -50 -50 minecraft:yellow_wool");
			server.runCommand("fill 8 -60 -50 8 -50 -50 minecraft:yellow_wool");
			server.runCommand("tp @p 0.5 -60 0.5 180 0");
			context.waitTicks(40);
			try {
				context.waitFor(mc -> mc.levelRenderer.hasRenderedAllSections(), 600);
			} catch (Throwable t) {
				LOGGER.warn("Chunks did not finish rendering in time, continuing anyway");
			}
			context.waitTicks(40);

			shot(context, "01_vanilla_no_tracking", null);

			// Real laptop situation: eyes at webcam height, above the top edge of the screen. This used to freeze
			// the game (vanilla culling loop). Auto-recenter should centre on it and the view should look vanilla.
			shot(context, "02a_autocentered_laptop_pose", new double[]{0, 12, 55});
			String centered = context.computeOnClient(mc -> "centerOffsetY=" + DepthWindowClient.config().centerOffsetYCm
				+ " neutralDistance=" + DepthWindowClient.config().neutralDistanceCm);
			LOGGER.info("[DepthWindowTest] after auto-recenter: {}", centered);
			// Back to an uncentred screen-relative setup for the geometry checks below.
			context.runOnClient(mc -> {
				DepthWindowClient.config().centerOffsetXCm = 0;
				DepthWindowClient.config().centerOffsetYCm = 0;
				DepthWindowClient.config().neutralDistanceCm = 50;
			});
			shot(context, "02_head_centered", new double[]{0, 0, 50});
			shot(context, "03_head_right_12cm", new double[]{12, 0, 50});
			shot(context, "04_head_left_12cm", new double[]{-12, 0, 50});
			shot(context, "05_head_up_8cm", new double[]{0, 8, 50});
			shot(context, "06_head_closer_30cm", new double[]{0, 0, 30});
			shot(context, "07_head_further_80cm", new double[]{0, 0, 80});

			context.runOnClient(mc -> DepthWindowClient.config().showStatusHud = true);
			shot(context, "08_status_hud_head_right", new double[]{12, 4, 45});
			context.runOnClient(mc -> DepthWindowClient.config().showStatusHud = false);

			context.runOnClient(mc -> DepthWindowClient.config().enabled = false);
			shot(context, "09_disabled_head_right", new double[]{12, 0, 50});
			context.runOnClient(mc -> DepthWindowClient.config().enabled = true);

			context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
			shot(context, "09b_third_person_head_left", new double[]{-12, 0, 50});
			context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));

			// Head rotation: x, y, z, yaw (+right), pitch (+up). The view turns, the crosshair stays on the aimed block.
			shot(context, "15_turn_head_right_15deg", new double[]{0, 0, 50, 15, 0});
			shot(context, "16_turn_head_left_15deg", new double[]{0, 0, 50, -15, 0});
			shot(context, "17_look_up_10deg", new double[]{0, 0, 50, 0, 10});
			shot(context, "18_turn_and_lean_right", new double[]{8, 0, 50, 12, 0});
			context.runOnClient(mc -> DepthWindowClient.config().headRotation = false);
			shot(context, "19_rotation_off_head_turned", new double[]{0, 0, 50, 15, 0});
			context.runOnClient(mc -> DepthWindowClient.config().headRotation = true);

			// Eye positions outside the screen rectangle (these froze the game before the fix).
			shot(context, "11_eyes_at_webcam_height", new double[]{0, 9.7, 50});
			shot(context, "12_eyes_above_screen_15cm", new double[]{0, 15, 50});
			shot(context, "13_beyond_side_edge", new double[]{25, 0, 50});
			shot(context, "14_extreme_corner_close", new double[]{40, 30, 25});

			// Lean into a wall: the camera must stop before entering the block.
			server.runCommand("fill 1 -60 -1 1 -58 1 minecraft:stone_bricks");
			context.waitTicks(20);
			shot(context, "10_lean_into_wall_right", new double[]{25, 0, 50});
		} finally {
			head = null;
		}
	}

	private static void shot(ClientGameTestContext context, String name, double[] pose) {
		head = pose;
		context.waitTicks(30); // let smoothing and the fade-in settle
		String state = context.computeOnClient(mc -> String.format(
			"active=%s view=(%.1f, %.1f) offset=%s eye=%s crosshairNdc=(%.3f, %.3f) tan=[%.3f %.3f %.3f %.3f]",
			HeadCoupledView.isActive(), HeadCoupledView.viewYawDelta(), HeadCoupledView.viewPitchDelta(),
			HeadCoupledView.cameraOffset(), HeadCoupledView.effectiveEyeCm(),
			HeadCoupledView.crosshairNdcX(), HeadCoupledView.crosshairNdcY(),
			HeadCoupledView.tanLeft(), HeadCoupledView.tanRight(), HeadCoupledView.tanBottom(), HeadCoupledView.tanTop()));
		Path path = context.takeScreenshot(name);
		LOGGER.info("[DepthWindowTest] {} -> {} | {}", name, path, state);
	}

	private static void sendLoop() {
		try (DatagramSocket socket = new DatagramSocket()) {
			InetAddress local = InetAddress.getLoopbackAddress();
			ByteBuffer buf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
			while (true) {
				double[] h = head;
				if (h != null) {
					buf.clear();
					buf.putDouble(h[0]).putDouble(h[1]).putDouble(h[2]);
					for (int i = 3; i < 6; i++) {
						buf.putDouble(i < h.length ? h[i] : 0.0); // yaw, pitch, roll in degrees
					}
					socket.send(new DatagramPacket(buf.array(), 48, local, DepthWindowClient.config().udpPort));
				}
				Thread.sleep(16);
			}
		} catch (Exception e) {
			LOGGER.error("fake tracker stopped", e);
		}
	}
}
