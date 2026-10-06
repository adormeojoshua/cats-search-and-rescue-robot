from picamera2 import Picamera2
from libcamera import Transform
import cv2, time
import multiprocessing as mp
import numpy as np
import threading
from flask import Flask, Response

MODEL = "s416fp16_ncnn_model"
IMGSZ = 416
CONF  = 0.25
W, H  = 640, 480
BOX_TTL = 1.0        # seconds before stale boxes stop being drawn

app = Flask(__name__)
latest_jpeg = None
jpeg_lock = threading.Lock()


def detector(frame_q, box_q):
    """Runs in its own process — separate interpreter, no shared GIL."""
    from ultralytics import YOLO
    model = YOLO(MODEL)
    print("model loaded in worker")
    while True:
        buf = frame_q.get()
        if buf is None:
            break
        frame = np.frombuffer(buf, dtype=np.uint8).reshape(H, W, 3)
        r = model(frame, imgsz=IMGSZ, conf=CONF, verbose=False)
        out = [
            (int(b.cls), float(b.conf), *map(int, b.xyxy[0]))
            for b in r[0].boxes
        ]
        box_q.put(out)


def gen_frames():
    # Only sends a NEW frame when one is actually available, and caps the
    # loop with a short sleep matching the ~30fps capture rate below.
    # Previously this looped with no sleep at all, re-sending the same
    # jpeg bytes as fast as the CPU allowed (hundreds of times/sec) --
    # each open connection to /video_feed (including the app's old
    # readiness-check polling) spun up its own uncapped, CPU-maxing
    # thread that never got cleanly stopped, causing the camera feed to
    # lag and eventually freeze after repeated connections piled up.
    last_sent = None
    while True:
        with jpeg_lock:
            frame = latest_jpeg
        if frame is not None and frame is not last_sent:
            last_sent = frame
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
        time.sleep(0.03)


@app.route('/video_feed')
def video_feed():
    return Response(gen_frames(),
                     mimetype='multipart/x-mixed-replace; boundary=frame')


# Lightweight route for the Android app's periodic readiness polling.
# Deliberately separate from /video_feed so checking "is the camera up"
# never has to open a second connection into the live MJPEG stream.
@app.route('/camera_health')
def camera_health():
    return "OK", 200


def run_flask():
    app.run(host='0.0.0.0', port=5000, threaded=True)


if __name__ == "__main__":
    mp.set_start_method("spawn")
    frame_q = mp.Queue(maxsize=1)
    box_q   = mp.Queue(maxsize=1)

    p = mp.Process(
        target=detector,
        args=(frame_q, box_q),
        daemon=True
    )
    p.start()

    threading.Thread(target=run_flask, daemon=True).start()

    picam = Picamera2()
    picam.configure(
        picam.create_preview_configuration(
            main={"size": (W, H), "format": "BGR888"},
            transform=Transform(hflip=1, vflip=1)
        )
    )
    picam.start()
    time.sleep(2)

    NAMES  = {0: "human", 1: "animal"}
    COLORS = {0: (0, 255, 0), 1: (255, 128, 0)}

    boxes = []
    waiting = 0
    last_det = 0.0
    cam_n, cam_t0 = 0, time.time()
    det_n, det_t0 = 0, time.time()

    while True:
        frame = picam.capture_array()
        cam_n += 1

        # hand over a frame only every 3rd capture, and only if the worker is idle
        if cam_n % 3 == 0 and waiting == 0 and not frame_q.full():
            try:
                frame_q.put_nowait(frame.tobytes())
                waiting = 1
            except Exception:
                pass

        # pick up results when ready
        if not box_q.empty():
            try:
                boxes = box_q.get_nowait()
                det_n += 1
                waiting = 0
                last_det = time.time()
            except Exception:
                pass

        # draw only if the detection is recent
        if time.time() - last_det < BOX_TTL:
            for cls, conf, x1, y1, x2, y2 in boxes:
                col = COLORS.get(cls, (0, 255, 255))
                cv2.rectangle(
                    frame,
                    (x1, y1),
                    (x2, y2),
                    col,
                    2
                )
                cv2.putText(
                    frame,
                    f"{NAMES.get(cls, cls)} {conf:.2f}",
                    (x1, max(y1 - 6, 12)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.5,
                    col,
                    2
                )

        cam_fps = cam_n / (time.time() - cam_t0)
        det_fps = det_n / (time.time() - det_t0)
        cv2.putText(
            frame,
            f"stream {cam_fps:.1f} | detect {det_fps:.1f}",
            (10, 30),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            (0, 255, 255),
            2
        )

        ok, buf = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 70])
        if ok:
            with jpeg_lock:
                latest_jpeg = buf.tobytes()

        time.sleep(0.03)

    frame_q.put(None)
    picam.stop()
