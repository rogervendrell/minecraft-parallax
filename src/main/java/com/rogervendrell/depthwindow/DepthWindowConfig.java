package com.rogervendrell.depthwindow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Plain JSON config stored at {@code config/depthwindow.json}.
 * Every public non-static field is an option and can also be changed in-game with
 * {@code /depthwindow set <option> <value>}.
 */
public final class DepthWindowConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("depthwindow.json");

	/** Master switch (also toggled with the keybind). */
	public boolean enabled = true;

	/** UDP port the tracker sends to. 4242 is OpenTrack's default "UDP over network" port. */
	public int udpPort = 4242;
	/** Address to listen on. Use 0.0.0.0 to accept data from another machine. */
	public String udpBindAddress = "127.0.0.1";

	/** Physical size of the visible screen area. Defaults are this laptop's panel (EDID: 309 x 174 mm). */
	public double screenWidthCm = 30.9;
	public double screenHeightCm = 17.4;

	/**
	 * Recenter automatically the first time tracking data arrives after the game starts, so the view begins
	 * centred on however you are sitting. The effect stays off until then.
	 */
	public boolean autoRecenter = true;

	/** Eye-to-screen distance at which the picture matches vanilla exactly. Set by "recenter". */
	public double neutralDistanceCm = 50.0;
	/** Added to the tracker's X/Y so your normal sitting position becomes the centre. Set by "recenter". */
	public double centerOffsetXCm = 0.0;
	public double centerOffsetYCm = 0.0;

	/**
	 * How far (in blocks) in front of the camera the virtual "window pane" sits. Things at this distance
	 * stay still on screen; anything further away appears behind the screen, anything closer pops out.
	 */
	public double windowDistanceBlocks = 1.0;
	/**
	 * Turning your head also turns the view, like TrackIR. Visual only: you keep aiming (and walking) where the
	 * mouse points, and the crosshair stays on that spot.
	 */
	public boolean headRotation = true;
	/** View degrees per degree of head turn. Your eyes have to stay on the screen, so values above 1 help. */
	public double rotationYawGain = 2.0;
	public double rotationPitchGain = 1.5;
	/** Head turns smaller than this (degrees) are ignored, so small natural movements don't wobble the view. */
	public double rotationDeadzoneDegrees = 2.0;
	/** The view never turns further than this from your aim direction (degrees). */
	public double rotationMaxDegrees = 70.0;
	/** Head angles (degrees) that count as looking straight ahead. Set by "recenter". */
	public double neutralYawDegrees = 0.0;
	public double neutralPitchDegrees = 0.0;
	public boolean invertYaw = false;
	public boolean invertPitch = false;

	/** Exaggerates (&gt;1) or tones down (&lt;1) your head movement. 1 = physically correct. */
	public double strength = 1.0;
	/**
	 * false: keep your in-game FOV and scale the window to fit it (recommended).
	 * true: use the real angle the screen covers from your eyes (true-to-life, but very zoomed in).
	 */
	public boolean physicalFov = false;
	/** Extra smoothing time constant in seconds applied on top of the tracker's own filter. */
	public double smoothingSeconds = 0.03;
	/** The camera is never moved further than this from your real eye position. */
	public double maxOffsetBlocks = 1.5;
	/** Keep the camera out of walls when you lean into them. */
	public boolean collision = true;
	/** Move the crosshair so it stays on the block you are actually aiming at. */
	public boolean crosshairFollowsAim = true;
	/** Apply the effect in third-person views as well. */
	public boolean applyInThirdPerson = true;
	/** Seconds without tracker data before the view eases back to normal. */
	public double trackingTimeoutSeconds = 0.5;
	/** Flip an axis if the tracker uses the opposite convention (e.g. some OpenTrack setups). */
	public boolean invertX = false;
	public boolean invertY = false;
	public boolean invertZ = false;
	/** Show the status overlay in the top-left corner. */
	public boolean showStatusHud = false;

	public static DepthWindowConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				DepthWindowConfig config = GSON.fromJson(reader, DepthWindowConfig.class);
				if (config != null) {
					config.save(); // writes any options added since the file was created
					return config;
				}
			} catch (IOException | JsonParseException e) {
				DepthWindowClient.LOGGER.error("Could not read {}, using defaults", PATH, e);
			}
		}

		DepthWindowConfig config = new DepthWindowConfig();
		config.save();
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			DepthWindowClient.LOGGER.error("Could not write {}", PATH, e);
		}
	}

	public static List<String> optionNames() {
		List<String> names = new ArrayList<>();
		for (Field field : DepthWindowConfig.class.getFields()) {
			if (!Modifier.isStatic(field.getModifiers())) {
				names.add(field.getName());
			}
		}
		return names;
	}

	public String get(String name) {
		try {
			return String.valueOf(DepthWindowConfig.class.getField(name).get(this));
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("Unknown option '" + name + "'");
		}
	}

	/** Parses and sets an option by name. Throws IllegalArgumentException with a readable message on bad input. */
	public void set(String name, String value) {
		Field field;
		try {
			field = DepthWindowConfig.class.getField(name);
		} catch (NoSuchFieldException e) {
			throw new IllegalArgumentException("Unknown option '" + name + "'");
		}
		if (Modifier.isStatic(field.getModifiers())) {
			throw new IllegalArgumentException("Unknown option '" + name + "'");
		}

		String v = value.trim();
		try {
			Class<?> type = field.getType();
			if (type == boolean.class) {
				String lower = v.toLowerCase(Locale.ROOT);
				if (!lower.equals("true") && !lower.equals("false")) {
					throw new IllegalArgumentException("'" + name + "' must be true or false");
				}
				field.setBoolean(this, Boolean.parseBoolean(lower));
			} else if (type == int.class) {
				field.setInt(this, Integer.parseInt(v));
			} else if (type == double.class) {
				double d = Double.parseDouble(v);
				if (!Double.isFinite(d)) {
					throw new IllegalArgumentException("'" + name + "' must be a finite number");
				}
				field.setDouble(this, d);
			} else if (type == String.class) {
				field.set(this, v);
			}
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + v + "' is not a valid number for '" + name + "'");
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException("Cannot set '" + name + "'");
		}
	}
}
