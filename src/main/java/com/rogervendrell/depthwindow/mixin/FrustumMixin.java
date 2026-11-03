package com.rogervendrell.depthwindow.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Safety net: offsetToFullyIncludeCameraCube steps the frustum backwards until the camera's cube fits inside.
 * That loop never ends if the frustum does not contain its own forward axis. HeadCoupledView already keeps the
 * cull frustum around the axis; this cap makes sure the game can never freeze here regardless. Vanilla needs
 * only a handful of steps (about 30 even at spyglass zoom), so the cap never affects normal rendering.
 */
@Mixin(Frustum.class)
public abstract class FrustumMixin {
	@Unique
	private static final int DEPTHWINDOW$MAX_STEPS = 256;

	@Unique
	private int depthwindow$steps;

	@Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"))
	private void depthwindow$resetSteps(int cubeSize, CallbackInfoReturnable<Frustum> cir) {
		this.depthwindow$steps = 0;
	}

	@WrapOperation(
		method = "offsetToFullyIncludeCameraCube",
		at = @At(value = "INVOKE", target = "Lorg/joml/FrustumIntersection;intersectAab(FFFFFF)I")
	)
	private int depthwindow$capSteps(
		FrustumIntersection intersection, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Operation<Integer> original
	) {
		if (++this.depthwindow$steps > DEPTHWINDOW$MAX_STEPS) {
			return FrustumIntersection.INSIDE;
		}
		return original.call(intersection, minX, minY, minZ, maxX, maxY, maxZ);
	}
}
