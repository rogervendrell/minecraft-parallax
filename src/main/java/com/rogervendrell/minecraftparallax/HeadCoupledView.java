package com.rogervendrell.minecraftparallax;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Head-coupled perspective ("fish tank VR").
 *
 * <p>The physical screen is treated as a window. Given the viewer's eye position relative to the screen
 * centre (in cm), we move the in-game camera by the matching amount and build an <b>asymmetric</b> view
 * frustum whose edges always pass through the edges of a fixed virtual window. Because the window stays put
 * while the eye moves, objects behind it shift with real motion parallax and the monitor appears to have depth.
 *
 * <p>Coordinates: physical eye {@code (ex, ey, ez)} in cm, +x right, +y up, +z from the screen towards the
 * viewer. Camera-local offsets are in blocks along the camera's right / up / backward axes.
 *
 * <p>Mapping: with the neutral eye at {@code (0, 0, d0)}, the frustum tangents are the physical ones scaled by
 * {@code k = tan(fov/2) * d0 / (screenHeight/2)}, so at the neutral position the picture is identical to
 * vanilla. The camera moves {@code s = D*tan(fov/2)/(screenHeight/2)} blocks per cm sideways and
 * {@code D/d0} blocks per cm in depth, where D is the configured window distance. With those two scales the
 * virtual window always projects exactly onto the monitor's edges.
 *
 * <p>Head rotation (where your face points) additionally turns the camera, amplified so you can look around
 * while your eyes stay on the screen. It is visual only: the player keeps aiming where the mouse points, and
 * the crosshair is re-projected onto that aim point.
 */
public final class HeadCoupledView {
	private static final double MIN_EYE_DISTANCE_CM = 5.0;
	private static final double COLLISION_MARGIN = 0.1;
	private static final double CROSSHAIR_RAY_LENGTH = 256.0;
	/** Minimum tangent on each side of the forward axis for the culling frustum. */
	private static final double CULL_AXIS_MARGIN = 0.15;

	// ---- Results of the latest frame, read by the mixins and the HUD ----
	private static boolean active;
	private static float tanLeft, tanRight, tanBottom, tanTop;
	private static float cullLeft, cullRight, cullBottom, cullTop;
	private static float offsetRight, offsetUp, offsetBack;
	private static float crosshairNdcX, crosshairNdcY;
	private static boolean crosshairVisible = true;
	private static double eyeX, eyeY, eyeZ;
	private static float viewYawDelta, viewPitchDelta;
	private static double headYaw, headPitch;

	// ---- Internal smoothing state ----
	private static double smoothX, smoothY, smoothZ = Double.NaN;
	private static double smoothYaw, smoothPitch;
	private static double weight;
	private static long lastNanos;
	private static boolean wasTracking;

	private HeadCoupledView() {
	}

	/** New camera state for this frame: rotation in Minecraft degrees and a world-space position offset. */
	public record CameraChange(float yRot, float xRot, Vec3 offset) {
	}

	/**
	 * Called once per frame from {@code Camera.update}, after vanilla positioned the camera and computed the FOV.
	 *
	 * @return how to change the camera, or null to leave it alone
	 */
	public static @Nullable CameraChange update(
		Vec3 cameraPos, float yRot, float xRot, float fov, boolean detached, boolean panoramic, @Nullable Level level, @Nullable Entity entity
	) {
		ParallaxConfig cfg = ParallaxClient.config();
		Minecraft mc = Minecraft.getInstance();

		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0.0 : Math.min((now - lastNanos) / 1e9, 0.1);
		lastNanos = now;

		if (panoramic || level == null) {
			// Panorama screenshots need the exact vanilla camera.
			weight = 0.0;
			deactivate();
			return null;
		}

		HeadTrackingReceiver.Sample sample = ParallaxClient.receiver().fresh(cfg.trackingTimeoutSeconds);
		boolean tracking = sample != null;
		boolean wanted = cfg.enabled && tracking && ParallaxClient.effectReady() && (cfg.applyInThirdPerson || !detached);

		double d0 = Math.max(cfg.neutralDistanceCm, 10.0);
		if (tracking) {
			double tx = (cfg.invertX ? -sample.x() : sample.x()) + cfg.centerOffsetXCm;
			double ty = (cfg.invertY ? -sample.y() : sample.y()) + cfg.centerOffsetYCm;
			double tz = cfg.invertZ ? -sample.z() : sample.z();
			double tyaw = (cfg.invertYaw ? -sample.yaw() : sample.yaw()) - cfg.neutralYawDegrees;
			double tpitch = (cfg.invertPitch ? -sample.pitch() : sample.pitch()) - cfg.neutralPitchDegrees;
			if (!wasTracking || Double.isNaN(smoothZ) || weight <= 0.0) {
				smoothX = tx;
				smoothY = ty;
				smoothZ = tz;
				smoothYaw = tyaw;
				smoothPitch = tpitch;
			} else {
				double a = cfg.smoothingSeconds <= 0.0 ? 1.0 : 1.0 - Math.exp(-dt / cfg.smoothingSeconds);
				smoothX += (tx - smoothX) * a;
				smoothY += (ty - smoothY) * a;
				smoothZ += (tz - smoothZ) * a;
				smoothYaw += (tyaw - smoothYaw) * a;
				smoothPitch += (tpitch - smoothPitch) * a;
			}
		}
		wasTracking = tracking;

		// Fade the effect in and out instead of snapping (toggle key, tracker lost, ...).
		double target = wanted ? 1.0 : 0.0;
		weight += (target - weight) * (1.0 - Math.exp(-dt / 0.15));
		if (!wanted && weight < 0.002) {
			weight = 0.0;
		}
		if (weight <= 0.0 || Double.isNaN(smoothZ)) {
			deactivate();
			return null;
		}

		// Head rotation turns the view (first person only: in third person it would spin the camera off the player).
		headYaw = smoothYaw;
		headPitch = smoothPitch;
		float yawDelta = 0.0F;
		float pitchDelta = 0.0F;
		if (cfg.viewMode.headLook() && !detached) {
			double maxDeg = Math.max(cfg.rotationMaxDegrees, 0.0);
			yawDelta = (float) (clamp(cfg.rotationYawGain * deadzone(smoothYaw, cfg.rotationDeadzoneDegrees), -maxDeg, maxDeg) * weight);
			pitchDelta = (float) (clamp(cfg.rotationPitchGain * deadzone(smoothPitch, cfg.rotationDeadzoneDegrees), -maxDeg, maxDeg) * weight);
		}
		float newYRot = yRot + yawDelta;               // Minecraft yaw grows when turning right
		float newXRot = (float) clamp(xRot - pitchDelta, -90.0, 90.0); // and pitch grows when looking down
		viewYawDelta = yawDelta;
		viewPitchDelta = newXRot - xRot;
		Quaternionf aimRotation = cameraRotation(yRot, xRot);
		Quaternionf rotation = cameraRotation(newYRot, newXRot);

		// Physical eye position, blended towards neutral by weight and scaled by strength.
		double g = cfg.strength * weight;
		double ex = g * smoothX;
		double ey = g * smoothY;
		double ez = d0 + g * (smoothZ - d0);
		ez = clamp(ez, Math.max(MIN_EYE_DISTANCE_CM, 0.3 * d0), 3.0 * d0);

		// Physical size of the game picture: the window's aspect ratio fitted inside the screen.
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		double screenW = Math.max(cfg.screenWidthCm, 1.0);
		double screenH = Math.max(cfg.screenHeightCm, 1.0);
		double halfW, halfH;
		if (aspect >= screenW / screenH) {
			halfW = screenW / 2.0;
			halfH = halfW / aspect;
		} else {
			halfH = screenH / 2.0;
			halfW = halfH * aspect;
		}

		double windowDist = Math.max(cfg.windowDistanceBlocks, 0.05);
		double tanHalfFov = Math.tan(Math.toRadians(fov) / 2.0);
		double k;      // multiplies physical frustum tangents
		double sLat;   // blocks per cm, sideways
		double sDepth; // blocks per cm, towards/away from the screen
		if (cfg.physicalFov) {
			// True-to-life angles; keep relative zoom effects (sprinting, spyglass) from vanilla.
			double baseFov = mc.options.fov().get().doubleValue();
			k = tanHalfFov / Math.tan(Math.toRadians(baseFov) / 2.0);
			sLat = windowDist / d0;
			sDepth = windowDist / d0;
		} else {
			k = tanHalfFov * d0 / halfH;
			sLat = windowDist * tanHalfFov / halfH;
			sDepth = windowDist / d0;
		}

		// Desired camera offset in camera-local blocks (right, up, back).
		double right = ex * sLat;
		double up = ey * sLat;
		double back = (ez - d0) * sDepth;
		double len = Math.sqrt(right * right + up * up + back * back);
		double maxLen = Math.max(cfg.maxOffsetBlocks, 0.0);
		double scale = len > maxLen && len > 0.0 ? maxLen / len : 1.0;

		Vector3f worldDir = new Vector3f((float) right, (float) up, (float) back).rotate(rotation);
		if (cfg.collision && entity != null && len * scale > 1e-4 && !(entity instanceof Player p && p.isSpectator())) {
			scale *= collisionFraction(level, entity, cameraPos, new Vec3(worldDir).scale(scale));
		}
		right *= scale;
		up *= scale;
		back *= scale;

		// Effective physical eye after clamping/collision, so the window stays exactly in place.
		double effX = right / sLat;
		double effY = up / sLat;
		double effZ = d0 + back / sDepth;

		double l = k * (-halfW - effX) / effZ;
		double r = k * (halfW - effX) / effZ;
		double b = k * (-halfH - effY) / effZ;
		double t = k * (halfH - effY) / effZ;

		tanLeft = (float) l;
		tanRight = (float) r;
		tanBottom = (float) b;
		tanTop = (float) t;

		// Vanilla culls with max(fov, fov setting) so zoom animations don't pop; take the union of both frusta.
		double cullFov = Math.max(fov, mc.options.fov().get().doubleValue());
		double c = Math.tan(Math.toRadians(cullFov) / 2.0) / tanHalfFov;
		double margin = 1.02;
		// The cull frustum must also contain the camera's forward axis. When the eye is past a screen edge (on a
		// laptop your eyes are usually above the top edge, at webcam height) the view frustum points entirely
		// to one side, and vanilla's Frustum.offsetToFullyIncludeCameraCube would step backwards forever
		// trying to fit the camera's cube inside it. Widening only the culling frustum keeps the picture exact.
		cullLeft = (float) Math.min(Math.min(l, l * c) * margin, -CULL_AXIS_MARGIN);
		cullRight = (float) Math.max(Math.max(r, r * c) * margin, CULL_AXIS_MARGIN);
		cullBottom = (float) Math.min(Math.min(b, b * c) * margin, -CULL_AXIS_MARGIN);
		cullTop = (float) Math.max(Math.max(t, t * c) * margin, CULL_AXIS_MARGIN);

		offsetRight = (float) right;
		offsetUp = (float) up;
		offsetBack = (float) back;
		eyeX = effX;
		eyeY = effY;
		eyeZ = effZ;
		active = true;

		Vector3f world = new Vector3f(offsetRight, offsetUp, offsetBack).rotate(rotation);
		Vec3 worldOffset = new Vec3(world.x, world.y, world.z);
		if (cfg.crosshairFollowsAim && !detached) {
			updateCrosshair(mc, level, entity, cameraPos, aimRotation, rotation, worldOffset);
		} else {
			crosshairNdcX = crosshairNdcY = 0.0F;
			crosshairVisible = true;
		}

		return new CameraChange(newYRot, newXRot, worldOffset);
	}

	/** Same orientation Camera.setRotation builds from Minecraft yaw/pitch. */
	private static Quaternionf cameraRotation(float yRot, float xRot) {
		return new Quaternionf().rotationYXZ((float) Math.PI - yRot * (float) (Math.PI / 180.0), -xRot * (float) (Math.PI / 180.0), 0.0F);
	}

	/** Continuous deadzone: zero inside +-dz, then grows from zero. */
	private static double deadzone(double v, double dz) {
		double d = Math.max(dz, 0.0);
		return v > d ? v - d : (v < -d ? v + d : 0.0);
	}

	/**
	 * Where the surface you are aiming at lands on screen (NDC), so the crosshair can stay on it. Uses a long
	 * raycast rather than only vanilla's hit result, which is limited to reach distance.
	 *
	 * @param eyePos      the player's real eye (where aiming happens)
	 * @param aimRotation the player's view direction
	 * @param camRotation the rendered camera's direction (aim direction plus head rotation)
	 * @param camOffset   the rendered camera's world offset from eyePos
	 */
	private static void updateCrosshair(
		Minecraft mc, Level level, @Nullable Entity entity, Vec3 eyePos, Quaternionf aimRotation, Quaternionf camRotation, Vec3 camOffset
	) {
		Vector3f aim = new Vector3f(0.0F, 0.0F, -1.0F).rotate(aimRotation);
		Vec3 end = eyePos.add(aim.x * CROSSHAIR_RAY_LENGTH, aim.y * CROSSHAIR_RAY_LENGTH, aim.z * CROSSHAIR_RAY_LENGTH);
		double dist = Double.POSITIVE_INFINITY;
		if (entity != null) {
			HitResult ray = level.clip(new ClipContext(eyePos, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
			if (ray.getType() != HitResult.Type.MISS) {
				dist = ray.getLocation().distanceTo(eyePos);
			}
		}
		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
			dist = Math.min(dist, hit.getLocation().distanceTo(eyePos));
		}

		// Aim point relative to the rendered camera, in the camera's own axes (x right, y up, -z forward).
		Vector3f rel = Double.isFinite(dist)
			? new Vector3f((float) (aim.x * dist - camOffset.x), (float) (aim.y * dist - camOffset.y), (float) (aim.z * dist - camOffset.z))
			: new Vector3f(aim); // infinitely far: only the direction matters
		rel.rotate(new Quaternionf(camRotation).conjugate());
		double depth = -rel.z;
		if (depth < 1e-3) {
			crosshairVisible = false;
			return;
		}
		double tx = rel.x / depth;
		double ty = rel.y / depth;
		crosshairNdcX = (float) (2.0 * (tx - tanLeft) / (tanRight - tanLeft) - 1.0);
		crosshairNdcY = (float) (2.0 * (ty - tanBottom) / (tanTop - tanBottom) - 1.0);
		crosshairVisible = true;
	}

	/** Fraction (0..1) of the offset the camera can move before a slightly padded box around it hits a block. */
	private static double collisionFraction(Level level, Entity entity, Vec3 from, Vec3 offset) {
		double len = offset.length();
		double fraction = 1.0;
		for (int i = 0; i < 8; i++) {
			double ox = ((i & 1) * 2 - 1) * COLLISION_MARGIN;
			double oy = ((i >> 1 & 1) * 2 - 1) * COLLISION_MARGIN;
			double oz = ((i >> 2 & 1) * 2 - 1) * COLLISION_MARGIN;
			Vec3 start = from.add(ox, oy, oz);
			Vec3 end = start.add(offset);
			HitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
			if (hit.getType() != HitResult.Type.MISS) {
				fraction = Math.min(fraction, hit.getLocation().distanceTo(start) / len);
			}
		}
		return clamp(fraction, 0.0, 1.0);
	}

	private static void deactivate() {
		active = false;
		offsetRight = offsetUp = offsetBack = 0.0F;
		crosshairNdcX = crosshairNdcY = 0.0F;
		crosshairVisible = true;
		viewYawDelta = viewPitchDelta = 0.0F;
	}

	/** Forget smoothing state, e.g. after recentering, so the view does not sweep. */
	public static void resetSmoothing() {
		smoothZ = Double.NaN;
		wasTracking = false;
	}

	private static double clamp(double v, double min, double max) {
		return v < min ? min : (v > max ? max : v);
	}

	public static boolean isActive() {
		return active;
	}

	public static float tanLeft() {
		return tanLeft;
	}

	public static float tanRight() {
		return tanRight;
	}

	public static float tanBottom() {
		return tanBottom;
	}

	public static float tanTop() {
		return tanTop;
	}

	public static float cullLeft() {
		return cullLeft;
	}

	public static float cullRight() {
		return cullRight;
	}

	public static float cullBottom() {
		return cullBottom;
	}

	public static float cullTop() {
		return cullTop;
	}

	public static float crosshairNdcX() {
		return crosshairNdcX;
	}

	public static float crosshairNdcY() {
		return crosshairNdcY;
	}

	public static boolean crosshairVisible() {
		return crosshairVisible;
	}

	/** Smoothed head yaw / pitch relative to neutral, in degrees. */
	public static double headYaw() {
		return headYaw;
	}

	public static double headPitch() {
		return headPitch;
	}

	/** How far the rendered view is turned from the aim direction, in degrees (+yaw right, +pitch up). */
	public static float viewYawDelta() {
		return viewYawDelta;
	}

	public static float viewPitchDelta() {
		return -viewPitchDelta;
	}

	public static Vec3 cameraOffset() {
		return new Vec3(offsetRight, offsetUp, offsetBack);
	}

	public static Vec3 effectiveEyeCm() {
		return new Vec3(eyeX, eyeY, eyeZ);
	}

	public static double weight() {
		return weight;
	}
}
