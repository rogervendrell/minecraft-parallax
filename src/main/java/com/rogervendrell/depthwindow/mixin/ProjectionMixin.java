package com.rogervendrell.depthwindow.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.rogervendrell.depthwindow.OffAxisProjection;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Projection.class)
public abstract class ProjectionMixin implements OffAxisProjection {
	@Shadow
	private boolean isMatrixDirty;

	@Unique
	private boolean depthwindow$offAxis;
	@Unique
	private float depthwindow$left, depthwindow$right, depthwindow$bottom, depthwindow$top;

	@Override
	public void depthwindow$setOffAxis(boolean enabled, float tanLeft, float tanRight, float tanBottom, float tanTop) {
		if (enabled != this.depthwindow$offAxis
			|| enabled && (tanLeft != this.depthwindow$left || tanRight != this.depthwindow$right
				|| tanBottom != this.depthwindow$bottom || tanTop != this.depthwindow$top)) {
			this.depthwindow$offAxis = enabled;
			this.depthwindow$left = tanLeft;
			this.depthwindow$right = tanRight;
			this.depthwindow$bottom = tanBottom;
			this.depthwindow$top = tanTop;
			// Bumps the matrix version so the GPU buffer and cached view-projection get refreshed.
			this.isMatrixDirty = true;
		}
	}

	/**
	 * Vanilla passes zNear/zFar swapped (reverse-Z). setFrustum takes the frustum edges at the distance given
	 * as its zNear argument, so scaling the tangents by that same value gives an exactly matching matrix
	 * (a symmetric frustum built this way equals vanilla's setPerspective).
	 */
	@WrapOperation(
		method = "getMatrix",
		at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setPerspective(FFFFZ)Lorg/joml/Matrix4f;")
	)
	private Matrix4f depthwindow$offAxisPerspective(
		Matrix4f matrix, float fovy, float aspect, float zNear, float zFar, boolean zZeroToOne, Operation<Matrix4f> original
	) {
		if (!this.depthwindow$offAxis) {
			return original.call(matrix, fovy, aspect, zNear, zFar, zZeroToOne);
		}
		return matrix.setFrustum(
			this.depthwindow$left * zNear,
			this.depthwindow$right * zNear,
			this.depthwindow$bottom * zNear,
			this.depthwindow$top * zNear,
			zNear,
			zFar,
			zZeroToOne
		);
	}
}
