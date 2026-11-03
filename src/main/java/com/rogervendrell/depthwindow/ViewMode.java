package com.rogervendrell.depthwindow;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** What head tracking does to the view. Add new modes here; the settings menu picks them up automatically. */
public enum ViewMode {
	/** Moving your head moves the camera behind a fixed "window" (motion parallax only). */
	WINDOW(false),
	/** Window effect, plus turning your head turns the view (amplified, like TrackIR). */
	WINDOW_AND_HEAD_LOOK(true);

	private final boolean headLook;

	ViewMode(boolean headLook) {
		this.headLook = headLook;
	}

	public boolean headLook() {
		return this.headLook;
	}

	public Component displayName() {
		return Component.translatable("depthwindow.mode." + this.name().toLowerCase(Locale.ROOT));
	}

	public Component description() {
		return Component.translatable("depthwindow.mode." + this.name().toLowerCase(Locale.ROOT) + ".tooltip");
	}
}
