package com.rogervendrell.minecraftparallax;

/**
 * Implemented by {@link net.minecraft.client.renderer.Projection} through a mixin. When enabled, the
 * perspective matrix is replaced by an asymmetric (off-axis) frustum given as view-space tangents of its
 * left/right/bottom/top edges.
 */
public interface OffAxisProjection {
	void parallax$setOffAxis(boolean enabled, float tanLeft, float tanRight, float tanBottom, float tanTop);
}
