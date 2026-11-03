package com.rogervendrell.depthwindow.gui;

import com.mojang.serialization.Codec;
import com.rogervendrell.depthwindow.DepthWindowClient;
import com.rogervendrell.depthwindow.DepthWindowConfig;
import com.rogervendrell.depthwindow.HeadTrackingReceiver;
import com.rogervendrell.depthwindow.ViewMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * In-game settings for Depth Window: tracker status, key reminders, mode selection and sliders.
 * Built from vanilla option widgets, so it looks and behaves like Minecraft's own settings screens.
 * Changes apply live (you can see them in the world behind the menu) and are saved when the screen closes.
 *
 * <p>To add a setting: create an {@link OptionInstance} with {@link #toggle} or {@link #slider} and add it to the
 * list in {@link #addOptions()}.
 */
public class DepthWindowScreen extends OptionsSubScreen {
	private static final String PREFIX = "depthwindow.options.";
	private static final Codec<ViewMode> MODE_CODEC = Codec.STRING.xmap(ViewMode::valueOf, ViewMode::name);
	private static final int ROW_WIDTH = 310;

	private final DepthWindowConfig cfg = DepthWindowClient.config();
	private final List<OptionInstance<?>> headLookOptions = new ArrayList<>();
	private @Nullable StringWidget trackerStatus;
	private @Nullable Button recenterButton;
	private @Nullable Component feedback;
	private int feedbackTicks;

	public DepthWindowScreen(@Nullable Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "title"));
	}

	@Override
	protected void addOptions() {
		// ---- Status and keys ----
		this.list.addHeader(text("section.status"));
		this.trackerStatus = new StringWidget(ROW_WIDTH, 20, this.trackerStatusText(), this.font);
		this.list.addBig(this.trackerStatus);
		this.list.addBig(this.keyHint(DepthWindowClient.recenterKey(), "recenter"));
		this.list.addBig(this.keyHint(DepthWindowClient.toggleKey(), "toggle"));
		this.list.addBig(this.keyHint(DepthWindowClient.statusKey(), "status"));
		this.list.addBig(this.keyHint(DepthWindowClient.menuKey(), "menu"));
		this.recenterButton = Button.builder(text("recenter_now"), button -> {
				this.feedback = DepthWindowClient.recenter();
				this.feedbackTicks = 60;
			})
			.tooltip(Tooltip.create(text("recenter_now.tooltip")))
			.build();
		Button keysButton = Button.builder(text("controls"), button -> this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.options)))
			.tooltip(Tooltip.create(text("controls.tooltip")))
			.build();
		this.list.addSmall(this.recenterButton, keysButton);

		// ---- Mode ----
		this.list.addHeader(text("section.mode"));
		OptionInstance<ViewMode> mode = new OptionInstance<>(
			PREFIX + "mode",
			value -> Tooltip.create(value.description()),
			(caption, value) -> value.displayName(), // the cycle button adds "Mode: " itself
			new OptionInstance.Enum<>(List.of(ViewMode.values()), MODE_CODEC),
			this.cfg.viewMode,
			value -> {
				this.cfg.viewMode = value;
				this.updateActiveStates();
			}
		);
		OptionInstance<Boolean> enabled = toggle("enabled", this.cfg.enabled, v -> this.cfg.enabled = v);
		this.list.addSmall(mode, enabled);

		// ---- Head look (only used by the head-look mode) ----
		this.list.addHeader(text("section.look"));
		OptionInstance<Integer> yawGain = slider("yaw_gain", 5, 50, 10, this.cfg.rotationYawGain, "×%.1f", v -> this.cfg.rotationYawGain = v);
		OptionInstance<Integer> pitchGain = slider("pitch_gain", 5, 50, 10, this.cfg.rotationPitchGain, "×%.1f", v -> this.cfg.rotationPitchGain = v);
		OptionInstance<Integer> deadzone = slider("deadzone", 0, 20, 2, this.cfg.rotationDeadzoneDegrees, "%.1f°", v -> this.cfg.rotationDeadzoneDegrees = v);
		OptionInstance<Integer> maxTurn = slider("max_turn", 10, 90, 1, this.cfg.rotationMaxDegrees, "%.0f°", v -> this.cfg.rotationMaxDegrees = v);
		this.headLookOptions.addAll(List.of(yawGain, pitchGain, deadzone, maxTurn));
		this.list.addSmall(yawGain, pitchGain, deadzone, maxTurn);

		// ---- Depth (window) effect ----
		this.list.addHeader(text("section.depth"));
		this.list.addSmall(
			slider("strength", 0, 30, 10, this.cfg.strength, "×%.1f", v -> this.cfg.strength = v),
			slider("window_distance", 1, 40, 10, this.cfg.windowDistanceBlocks, "%.1f", v -> this.cfg.windowDistanceBlocks = v),
			sliderMs("smoothing", 0, 20, this.cfg.smoothingSeconds, v -> this.cfg.smoothingSeconds = v),
			toggle("crosshair", this.cfg.crosshairFollowsAim, v -> this.cfg.crosshairFollowsAim = v)
		);

		// ---- Other ----
		this.list.addHeader(text("section.other"));
		this.list.addSmall(
			toggle("third_person", this.cfg.applyInThirdPerson, v -> this.cfg.applyInThirdPerson = v),
			toggle("auto_recenter", this.cfg.autoRecenter, v -> this.cfg.autoRecenter = v),
			toggle("status_hud", this.cfg.showStatusHud, v -> this.cfg.showStatusHud = v)
		);
		this.list.addBig(Button.builder(text("reset"), button -> {
				this.cfg.resetPreferences();
				this.cfg.save();
				this.minecraft.gui.setScreen(new DepthWindowScreen(this.lastScreen));
			})
			.tooltip(Tooltip.create(text("reset.tooltip")))
			.build());

		this.updateActiveStates();
	}

	/** Head-look sliders are greyed out in modes that don't use them. */
	private void updateActiveStates() {
		boolean headLook = this.cfg.viewMode.headLook();
		for (OptionInstance<?> option : this.headLookOptions) {
			AbstractWidget widget = this.list.findOption(option);
			if (widget != null) {
				widget.active = headLook;
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (this.feedbackTicks > 0) {
			this.feedbackTicks--;
		}
		if (this.trackerStatus != null) {
			this.trackerStatus.setMessage(this.feedbackTicks > 0 && this.feedback != null ? this.feedback : this.trackerStatusText());
		}
		if (this.recenterButton != null) {
			this.recenterButton.active = DepthWindowClient.receiver().fresh(1.0) != null;
		}
	}

	@Override
	public void removed() {
		super.removed();
		this.cfg.save();
	}

	private Component trackerStatusText() {
		HeadTrackingReceiver receiver = DepthWindowClient.receiver();
		if (receiver.error() != null) {
			return Component.translatable(PREFIX + "tracker.error", receiver.error()).withStyle(ChatFormatting.RED);
		}
		if (receiver.fresh(this.cfg.trackingTimeoutSeconds) != null) {
			return Component.translatable(PREFIX + "tracker.connected", receiver.packetsPerSecond()).withStyle(ChatFormatting.GREEN);
		}
		return Component.translatable(PREFIX + "tracker.waiting").withStyle(ChatFormatting.GOLD);
	}

	private StringWidget keyHint(KeyMapping key, String id) {
		Component keyName = Component.literal("[").append(key.getTranslatedKeyMessage()).append("]").withStyle(ChatFormatting.YELLOW);
		return new StringWidget(ROW_WIDTH, 20, Component.translatable(PREFIX + "key_hint." + id, keyName), this.font);
	}

	private static Component text(String id) {
		return Component.translatable(PREFIX + id);
	}

	private static OptionInstance<Boolean> toggle(String id, boolean value, Consumer<Boolean> setter) {
		return OptionInstance.createBoolean(PREFIX + id, OptionInstance.cachedConstantTooltip(text(id + ".tooltip")), value, setter::accept);
	}

	/**
	 * A slider over integer steps; the stored value is {@code step / scale}. For example min 5, max 50, scale 10
	 * gives 0.5 to 5.0 in steps of 0.1.
	 */
	private static OptionInstance<Integer> slider(String id, int min, int max, double scale, double current, String format, DoubleConsumer setter) {
		int initial = (int) Math.max(min, Math.min(max, Math.round(current * scale)));
		return new OptionInstance<>(
			PREFIX + id,
			OptionInstance.cachedConstantTooltip(text(id + ".tooltip")),
			(caption, step) -> Options.genericValueLabel(caption, Component.literal(String.format(Locale.ROOT, format, step / scale))),
			new OptionInstance.IntRange(min, max),
			initial,
			step -> setter.accept(step / scale)
		);
	}

	/** Slider stored in seconds, shown in milliseconds, in 10 ms steps. */
	private static OptionInstance<Integer> sliderMs(String id, int min, int max, double currentSeconds, DoubleConsumer setter) {
		int initial = (int) Math.max(min, Math.min(max, Math.round(currentSeconds * 100.0)));
		return new OptionInstance<>(
			PREFIX + id,
			OptionInstance.cachedConstantTooltip(text(id + ".tooltip")),
			(caption, step) -> Options.genericValueLabel(caption, Component.literal(step * 10 + " ms")),
			new OptionInstance.IntRange(min, max),
			initial,
			step -> setter.accept(step / 100.0)
		);
	}
}
