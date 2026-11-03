package com.rogervendrell.minecraftparallax.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.rogervendrell.minecraftparallax.OffAxisProjection;
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
	private boolean parallax$offAxis;
	@Unique
	private float parallax$left, parallax$right, parallax$bottom, parallax$top;

	@Override
	public void parallax$setOffAxis(boolean enabled, float tanLeft, float tanRight, float tanBottom, float tanTop) {
		if (enabled != this.parallax$offAxis
			|| enabled && (tanLeft != this.parallax$left || tanRight != this.parallax$right
				|| tanBottom != this.parallax$bottom || tanTop != this.parallax$top)) {
			this.parallax$offAxis = enabled;
			this.parallax$left = tanLeft;
			this.parallax$right = tanRight;
			this.parallax$bottom = tanBottom;
			this.parallax$top = tanTop;
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
	private Matrix4f parallax$offAxisPerspective(
		Matrix4f matrix, float fovy, float aspect, float zNear, float zFar, boolean zZeroToOne, Operation<Matrix4f> original
	) {
		if (!this.parallax$offAxis) {
			return original.call(matrix, fovy, aspect, zNear, zFar, zZeroToOne);
		}
		return matrix.setFrustum(
			this.parallax$left * zNear,
			this.parallax$right * zNear,
			this.parallax$bottom * zNear,
			this.parallax$top * zNear,
			zNear,
			zFar,
			zZeroToOne
		);
	}
}
