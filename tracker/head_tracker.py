#!/usr/bin/env python3
"""
Minecraft Parallax head tracker.

Webcam -> MediaPipe Face Landmarker -> position of the point between your eyes, in centimetres,
relative to the centre of the screen -> UDP packets for the Minecraft mod.

Output coordinates (screen space, centimetres):
    +x  to your right as you look at the screen
    +y  up
    +z  distance from the screen plane towards you
Head rotation (degrees, where your face points):
    +yaw    turning your head to your right
    +pitch  looking up
    +roll   tilting your head towards your right shoulder

Packets use OpenTrack's "UDP over network" layout: six little-endian doubles
(x, y, z, yaw, pitch, roll) sent to 127.0.0.1:4242 by default.

Keys in the preview window:
    c  calibrate depth: sit with your eyes exactly --calib-distance cm from the screen, then press c
    r  reset depth calibration
    m  flip the left/right direction (if moving right moves the view the wrong way)
    q / Esc  quit
"""

from __future__ import annotations

import argparse
import json
import math
import os
import socket
import struct
import sys
import time
import urllib.request

import cv2
import numpy as np

try:
    import mediapipe as mp
    from mediapipe.tasks.python import vision
    from mediapipe.tasks.python.core.base_options import BaseOptions
except ImportError:
    sys.exit("MediaPipe is not installed. Run ./run_tracker.sh (it creates a venv and installs it).")

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(HERE, "face_landmarker.task")
MODEL_URL = ("https://storage.googleapis.com/mediapipe-models/face_landmarker/"
             "face_landmarker/float16/latest/face_landmarker.task")
CALIBRATION_PATH = os.path.join(HERE, "calibration.json")

# MediaPipe face mesh indices of the eye corners (subject's right eye: 33 outer, 133 inner;
# subject's left eye: 362 inner, 263 outer). The midpoint of each pair approximates the eyeball centre
# and, unlike the iris, does not move when you look around the screen.
RIGHT_EYE = (33, 133)
LEFT_EYE = (362, 263)


class OneEuroFilter:
    """Casiez et al. 2012: low jitter when still, low lag when moving."""

    def __init__(self, min_cutoff: float, beta: float, d_cutoff: float = 1.0):
        self.min_cutoff = min_cutoff
        self.beta = beta
        self.d_cutoff = d_cutoff
        self.x_prev: float | None = None
        self.dx_prev = 0.0
        self.t_prev = 0.0

    @staticmethod
    def _alpha(cutoff: float, dt: float) -> float:
        tau = 1.0 / (2.0 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / dt)

    def reset(self) -> None:
        self.x_prev = None

    def __call__(self, x: float, t: float) -> float:
        if self.x_prev is None:
            self.x_prev, self.dx_prev, self.t_prev = x, 0.0, t
            return x
        dt = max(t - self.t_prev, 1e-3)
        dx = (x - self.x_prev) / dt
        a_d = self._alpha(self.d_cutoff, dt)
        dx_hat = a_d * dx + (1.0 - a_d) * self.dx_prev
        cutoff = self.min_cutoff + self.beta * abs(dx_hat)
        a = self._alpha(cutoff, dt)
        x_hat = a * x + (1.0 - a) * self.x_prev
        self.x_prev, self.dx_prev, self.t_prev = x_hat, dx_hat, t
        return x_hat


def ensure_model() -> None:
    if os.path.exists(MODEL_PATH):
        return
    print(f"Downloading face landmark model to {MODEL_PATH} ...")
    tmp = MODEL_PATH + ".part"
    urllib.request.urlretrieve(MODEL_URL, tmp)
    os.replace(tmp, MODEL_PATH)


def load_depth_scale() -> float:
    try:
        with open(CALIBRATION_PATH) as f:
            return float(json.load(f)["depth_scale"])
    except (OSError, KeyError, ValueError, TypeError):
        return 1.0


def save_depth_scale(scale: float) -> None:
    with open(CALIBRATION_PATH, "w") as f:
        json.dump({"depth_scale": scale}, f, indent=2)


def open_camera(source: str, width: int, height: int, fps: int) -> cv2.VideoCapture:
    src: int | str = int(source) if source.isdigit() else source
    backend = cv2.CAP_V4L2 if sys.platform.startswith("linux") else cv2.CAP_ANY
    cap = cv2.VideoCapture(src, backend)
    if not cap.isOpened():
        cap = cv2.VideoCapture(src)
    if not cap.isOpened():
        sys.exit(f"Could not open camera {source!r}. Try --camera 1, or check that no other app is using it.")
    cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    cap.set(cv2.CAP_PROP_FPS, fps)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # always process the newest frame
    return cap


def eye_measurements(landmarks, w: int, h: int) -> tuple[float, float, float]:
    """Returns (u, v, d): pixel position of the point between the eyes and the 3-D eye separation in pixels.

    MediaPipe's z is on roughly the same scale as x, so measuring the separation in 3-D keeps it stable when
    you turn your head (a plain 2-D distance would shrink and make you look further away).
    """
    def centre(pair):
        a, b = landmarks[pair[0]], landmarks[pair[1]]
        return np.array([(a.x + b.x) * 0.5 * w, (a.y + b.y) * 0.5 * h, (a.z + b.z) * 0.5 * w])

    r, l = centre(RIGHT_EYE), centre(LEFT_EYE)
    mid = (r + l) * 0.5
    return float(mid[0]), float(mid[1]), float(np.linalg.norm(r - l))


def head_angles(matrix, mirror: bool) -> tuple[float, float, float, np.ndarray]:
    """Yaw / pitch / roll in degrees (see module docstring) from MediaPipe's facial transformation matrix.

    The matrix maps the canonical face into a camera frame with +x = image right, +y = up, +z = towards the
    camera, and the canonical face looks along +z. Its third column is therefore the "line out of your face".
    The camera faces you, which is a 180 degree turn about the vertical axis from your point of view: yaw
    flips sign, roll does not. A mirrored image is a reflection instead, so then roll flips and yaw does not.
    """
    r = np.asarray(matrix, dtype=float)[:3, :3]
    fwd = r[:, 2]
    side = r[:, 0]
    yaw = math.degrees(math.atan2(fwd[0], fwd[2]))
    pitch = math.degrees(math.atan2(fwd[1], math.hypot(fwd[0], fwd[2])))
    roll = math.degrees(math.atan2(side[1], side[0]))
    if mirror:
        roll = -roll
    else:
        yaw = -yaw
    return yaw, pitch, roll, fwd


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--camera", default="0", help="camera index or device path (default 0 = /dev/video0)")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--hfov", type=float, default=80.0,
                    help="camera horizontal field of view in degrees (HP Wide Vision 720p: ~88 diag = ~80 horiz)")
    ap.add_argument("--ipd", type=float, default=6.3, help="distance between your eye centres in cm")
    ap.add_argument("--cam-offset-x", type=float, default=0.0,
                    help="camera lens position right of the screen centre, cm")
    ap.add_argument("--cam-offset-y", type=float, default=9.7,
                    help="camera lens position above the screen centre, cm (14\" laptop: 17.4/2 + ~1 cm bezel)")
    ap.add_argument("--cam-offset-z", type=float, default=0.0,
                    help="camera lens position in front of the screen plane, cm")
    ap.add_argument("--mirror", action="store_true", help="use if your camera already delivers a mirrored image")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=4242)
    ap.add_argument("--calib-distance", type=float, default=50.0,
                    help="eye-to-screen distance in cm you sit at when pressing 'c' to calibrate")
    ap.add_argument("--min-cutoff", type=float, default=1.0, help="One Euro min cutoff (Hz) for x/y; lower = smoother")
    ap.add_argument("--beta", type=float, default=0.05, help="One Euro speed coefficient; higher = less lag")
    ap.add_argument("--rot-min-cutoff", type=float, default=0.8,
                    help="One Euro min cutoff (Hz) for head rotation; lower = smoother")
    ap.add_argument("--rot-beta", type=float, default=0.02, help="One Euro speed coefficient for head rotation")
    ap.add_argument("--no-preview", action="store_true", help="run without the preview window")
    args = ap.parse_args()

    ensure_model()
    landmarker = vision.FaceLandmarker.create_from_options(vision.FaceLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=MODEL_PATH),
        running_mode=vision.RunningMode.VIDEO,
        num_faces=1,
        min_face_detection_confidence=0.5,
        min_face_presence_confidence=0.5,
        min_tracking_confidence=0.5,
        output_facial_transformation_matrixes=True,
    ))

    cap = open_camera(args.camera, args.width, args.height, args.fps)
    ok, frame = cap.read()
    if not ok:
        sys.exit("Camera opened but returned no frames.")
    h, w = frame.shape[:2]
    focal = (w / 2.0) / math.tan(math.radians(args.hfov) / 2.0)
    print(f"Camera {args.camera}: {w}x{h}, focal length {focal:.0f}px (hfov {args.hfov} deg)")
    print(f"Sending OpenTrack UDP packets to {args.host}:{args.port}")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    target = (args.host, args.port)

    depth_scale = load_depth_scale()
    mirror = args.mirror
    filters = [OneEuroFilter(args.min_cutoff, args.beta),
               OneEuroFilter(args.min_cutoff, args.beta),
               OneEuroFilter(args.min_cutoff * 0.6, args.beta)]  # depth is noisier: smooth a bit more
    rot_filters = [OneEuroFilter(args.rot_min_cutoff, args.rot_beta) for _ in range(3)]
    calib_samples: list[float] = []
    calibrating = False
    last_ts = -1
    fps_t0, fps_n, fps = time.monotonic(), 0, 0.0
    last_print = 0.0
    window = "Minecraft Parallax tracker (q to quit)"

    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                print("Camera stopped delivering frames.")
                break
            now = time.monotonic()
            ts = int(now * 1000)
            if ts <= last_ts:
                ts = last_ts + 1
            last_ts = ts

            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            result = landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), ts)

            eye = None
            angles = (0.0, 0.0, 0.0)
            if result.face_landmarks:
                lm = result.face_landmarks[0]
                u, v, sep = eye_measurements(lm, w, h)
                if sep > 1.0:
                    raw_depth = focal * args.ipd / sep  # pinhole model, cm from the camera along its axis
                    if calibrating:
                        calib_samples.append(raw_depth)
                        if len(calib_samples) >= 30:
                            depth_scale = (args.calib_distance - args.cam_offset_z) / float(np.median(calib_samples))
                            save_depth_scale(depth_scale)
                            calibrating = False
                            print(f"Calibrated: depth scale {depth_scale:.3f} (saved to {CALIBRATION_PATH})")
                    zc = raw_depth * depth_scale
                    xc = (u - w / 2.0) / focal * zc   # camera space: +x = image right
                    yc = (v - h / 2.0) / focal * zc   # camera space: +y = image down
                    # The camera faces you, so image-right is your left: flip x (unless the image is mirrored).
                    sx = (xc if mirror else -xc) + args.cam_offset_x
                    sy = -yc + args.cam_offset_y
                    sz = zc + args.cam_offset_z
                    eye = (filters[0](sx, now), filters[1](sy, now), filters[2](sz, now))
                    fwd = None
                    if result.facial_transformation_matrixes:
                        yaw, pitch, roll, fwd = head_angles(result.facial_transformation_matrixes[0], mirror)
                        angles = (rot_filters[0](yaw, now), rot_filters[1](pitch, now), rot_filters[2](roll, now))
                    sock.sendto(struct.pack("<6d", eye[0], eye[1], eye[2], *angles), target)

                    if not args.no_preview:
                        for pair in (RIGHT_EYE, LEFT_EYE):
                            for i in pair:
                                cv2.circle(frame, (int(lm[i].x * w), int(lm[i].y * h)), 3, (0, 255, 255), -1)
                        cv2.circle(frame, (int(u), int(v)), 5, (0, 0, 255), -1)
                        if fwd is not None:
                            # The "line out of your face", drawn from the nose tip (image y points down).
                            nose = np.array([lm[1].x * w, lm[1].y * h])
                            tip = nose + np.array([fwd[0], -fwd[1]]) * 0.6 * w * 0.25
                            cv2.line(frame, tuple(nose.astype(int)), tuple(tip.astype(int)), (255, 160, 0), 3, cv2.LINE_AA)
            else:
                for f in filters + rot_filters:
                    f.reset()

            fps_n += 1
            if now - fps_t0 >= 1.0:
                fps = fps_n / (now - fps_t0)
                fps_t0, fps_n = now, 0

            if args.no_preview:
                if now - last_print >= 1.0:
                    last_print = now
                    if eye:
                        print(f"\r{fps:4.1f} fps  x={eye[0]:+6.1f}  y={eye[1]:+6.1f}  z={eye[2]:6.1f} cm  "
                              f"yaw={angles[0]:+6.1f}  pitch={angles[1]:+6.1f} deg   ", end="", flush=True)
                    else:
                        print(f"\r{fps:4.1f} fps  no face detected                         ", end="", flush=True)
                continue

            view = cv2.flip(frame, 1)  # selfie view so moving right moves right on screen
            lines = [f"{fps:4.1f} fps -> udp {args.host}:{args.port}"]
            if eye:
                lines.append(f"x {eye[0]:+6.1f} cm  (right +)")
                lines.append(f"y {eye[1]:+6.1f} cm  (up +)")
                lines.append(f"z {eye[2]:6.1f} cm  (distance)")
                lines.append(f"yaw {angles[0]:+6.1f}  pitch {angles[1]:+6.1f}  roll {angles[2]:+6.1f} deg  (right/up +)")
            else:
                lines.append("no face detected")
            if calibrating:
                lines.append(f"CALIBRATING... hold still at {args.calib_distance:.0f} cm")
            lines.append(f"[c] calibrate at {args.calib_distance:.0f}cm  [r] reset  [m] flip x{' (on)' if mirror else ''}  [q] quit")
            for i, text in enumerate(lines):
                y = 28 + i * 26
                cv2.putText(view, text, (12, y), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 0), 4, cv2.LINE_AA)
                cv2.putText(view, text, (12, y), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 1, cv2.LINE_AA)
            cv2.imshow(window, view)

            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), 27):
                break
            if key == ord("c"):
                calib_samples.clear()
                calibrating = True
            elif key == ord("r"):
                depth_scale = 1.0
                save_depth_scale(depth_scale)
                print("Depth calibration reset")
            elif key == ord("m"):
                mirror = not mirror
                print(f"Mirror {'on' if mirror else 'off'}")
    except KeyboardInterrupt:
        pass
    finally:
        print()
        cap.release()
        landmarker.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
