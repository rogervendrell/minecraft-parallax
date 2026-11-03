package com.rogervendrell.depthwindow.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.rogervendrell.depthwindow.HeadCoupledView;
import com.rogervendrell.depthwindow.OffAxisProjection;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.Projection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private Vec3 position;
	@Shadow
	private float fov;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;
	@Shadow
	private boolean detached;
	@Shadow
	private @Nullable Level level;
	@Shadow
	private @Nullable Entity entity;
	@Shadow
	@Final
	private Projection projection;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	public abstract boolean isPanoramicMode();

	/**
	 * Runs inside update() right after vanilla positioned the camera and computed the FOV, and before the
	 * culling frustum is built, so both rendering and culling see the head-shifted, head-turned eye.
	 */
	@Inject(
		method = "update",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;hudFov:F", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER)
	)
	private void depthwindow$applyHeadOffset(DeltaTracker deltaTracker, CallbackInfo ci) {
		HeadCoupledView.CameraChange change = HeadCoupledView.update(
			this.position, this.yRot, this.xRot, this.fov, this.detached, this.isPanoramicMode(), this.level, this.entity
		);
		if (change != null) {
			if (change.yRot() != this.yRot || change.xRot() != this.xRot) {
				this.setRotation(change.yRot(), change.xRot());
			}
			this.setPosition(this.position.add(change.offset()));
		}
	}

	/** The culling frustum must match the off-axis view, otherwise chunks pop at the screen edges. */
	@WrapOperation(
		method = "createProjectionMatrixForCulling",
		at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;perspective(FFFFZ)Lorg/joml/Matrix4f;")
	)
	private Matrix4f depthwindow$offAxisCulling(
		Matrix4f matrix, float fovy, float aspect, float zNear, float zFar, boolean zZeroToOne, Operation<Matrix4f> original
	) {
		if (!HeadCoupledView.isActive()) {
			return original.call(matrix, fovy, aspect, zNear, zFar, zZeroToOne);
		}
		return matrix.frustum(
			HeadCoupledView.cullLeft() * zNear,
			HeadCoupledView.cullRight() * zNear,
			HeadCoupledView.cullBottom() * zNear,
			HeadCoupledView.cullTop() * zNear,
			zNear,
			zFar,
			zZeroToOne
		);
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void depthwindow$updateProjection(DeltaTracker deltaTracker, CallbackInfo ci) {
		boolean on = HeadCoupledView.isActive() && this.level != null;
		((OffAxisProjection) this.projection).depthwindow$setOffAxis(
			on, HeadCoupledView.tanLeft(), HeadCoupledView.tanRight(), HeadCoupledView.tanBottom(), HeadCoupledView.tanTop()
		);
	}
}
