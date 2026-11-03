package com.rogervendrell.minecraftparallax;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

/**
 * Listens for head positions on UDP using OpenTrack's "UDP over network" packet layout:
 * six little-endian doubles {@code x, y, z, yaw, pitch, roll} (48 bytes). Positions are in centimetres.
 *
 * <p>The bundled Python tracker sends the eye midpoint relative to the screen centre:
 * +x = right, +y = up, +z = distance from the screen towards you.
 */
public final class HeadTrackingReceiver {
	public record Sample(double x, double y, double z, double yaw, double pitch, double roll, long nanoTime) {
	}

	private volatile @Nullable Sample latest;
	private volatile @Nullable String error;
	private volatile int packetsPerSecond;
	private @Nullable Thread thread;
	private @Nullable DatagramSocket socket;
	private volatile boolean running;
	private String boundTo = "";

	public synchronized void start(String bindAddress, int port) {
		stop();
		error = null;
		boundTo = bindAddress + ":" + port;
		try {
			DatagramSocket s = new DatagramSocket(null);
			s.setReuseAddress(true);
			s.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
			s.setSoTimeout(250);
			socket = s;
		} catch (IOException e) {
			error = "Cannot listen on UDP " + boundTo + ": " + e.getMessage();
			ParallaxClient.LOGGER.error(error);
			return;
		}

		running = true;
		DatagramSocket s = socket;
		thread = new Thread(() -> run(s), "Minecraft Parallax UDP receiver");
		thread.setDaemon(true);
		thread.start();
		ParallaxClient.LOGGER.info("Listening for head tracking data on UDP {}", boundTo);
	}

	public synchronized void stop() {
		running = false;
		if (socket != null) {
			socket.close();
			socket = null;
		}
		if (thread != null) {
			try {
				thread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			thread = null;
		}
		latest = null;
		packetsPerSecond = 0;
	}

	private void run(DatagramSocket s) {
		byte[] buf = new byte[512];
		DatagramPacket packet = new DatagramPacket(buf, buf.length);
		ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
		int count = 0;
		long windowStart = System.nanoTime();

		while (running) {
			try {
				packet.setLength(buf.length);
				s.receive(packet);
				if (packet.getLength() >= 48) {
					double x = bb.getDouble(0);
					double y = bb.getDouble(8);
					double z = bb.getDouble(16);
					double yaw = bb.getDouble(24);
					double pitch = bb.getDouble(32);
					double roll = bb.getDouble(40);
					if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
						latest = new Sample(x, y, z, yaw, pitch, roll, System.nanoTime());
						count++;
					}
				}
			} catch (SocketTimeoutException ignored) {
				// loop so we notice when running becomes false
			} catch (SocketException e) {
				if (running) {
					error = "UDP socket error: " + e.getMessage();
				}
				break;
			} catch (IOException e) {
				error = "UDP receive error: " + e.getMessage();
			}

			long now = System.nanoTime();
			if (now - windowStart >= 1_000_000_000L) {
				packetsPerSecond = (int) Math.round(count * 1e9 / (now - windowStart));
				count = 0;
				windowStart = now;
			}
		}
	}

	public @Nullable Sample latest() {
		return latest;
	}

	/** The latest sample if it is younger than {@code timeoutSeconds}, otherwise null. */
	public @Nullable Sample fresh(double timeoutSeconds) {
		Sample s = latest;
		if (s == null || System.nanoTime() - s.nanoTime() > (long) (timeoutSeconds * 1e9)) {
			return null;
		}
		return s;
	}

	public int packetsPerSecond() {
		Sample s = latest;
		// The counter only updates while packets arrive, so report 0 once the stream has stopped.
		if (s == null || System.nanoTime() - s.nanoTime() > 1_500_000_000L) {
			return 0;
		}
		return packetsPerSecond;
	}

	public @Nullable String error() {
		return error;
	}

	public String boundTo() {
		return boundTo;
	}
}
