"""
CATS Controller — Raspberry Pi audio server (two-way audio + control)
------------------------------------------------------------------
Runs alongside your existing camera server (port 5000). Does five things:

    1. HTTP GET /announce?mode=low|normal|sports
       -> speaks the mode name through the USB speaker.

    2. Raw TCP socket on port 6000 ("talk")
       -> receives live 16kHz/mono/16-bit PCM audio from the phone mic
          and pipes it into `aplay` on the USB speaker in real time.

    3. Raw TCP socket on port 6001 ("listen")
       -> captures live 16kHz/mono/16-bit PCM audio from the USB mic
          via `arecord` and streams it out to the phone, so you can
          hear what's happening around the robot.

    4. HTTP GET /health
       -> lightweight reachability check the app polls to know when
          this server (and therefore the Pi) is up and ready.

    5. HTTP GET /shutdown
       -> safely powers off the Raspberry Pi (`sudo shutdown -h now`).
          REQUIRES passwordless sudo for shutdown — see setup note below.

Setup on the Pi:
    sudo apt install espeak-ng alsa-utils
    pip install flask

Find your USB speaker's ALSA device name/index:
    aplay -l
Find your USB mic's ALSA device name/index:
    arecord -l
Then set AUDIO_DEVICE / MIC_INPUT_DEVICE below accordingly.

To allow /shutdown to work without a password prompt, run:
    sudo visudo -f /etc/sudoers.d/cats-shutdown
and add this line (replace `cats` with your actual Pi username):
    cats ALL=(ALL) NOPASSWD: /sbin/shutdown, /usr/sbin/shutdown
Confirm the real path first with: which shutdown

Run:
    python3 audio_server.py
"""

import os
import re
import socket
import subprocess
import threading
import time

from flask import Flask, jsonify, request

# ---- Configuration ---------------------------------------------------
AUDIO_DEVICE = "plughw:3,0"          # USB speaker (card 3) — plays TO the Pi
AUDIO_CARD = "3"                     # must match the card number in AUDIO_DEVICE above
AUDIO_MIXER_CONTROL = "PCM"          # run `amixer -c 3 scontrols` if this name doesn't work
ANNOUNCE_DIR = "/home/pi/cats_announcements"
HTTP_PORT = 5001

MIC_HOST = "0.0.0.0"

# "Talk": phone mic -> Pi speaker
MIC_PORT = 6000
MIC_SAMPLE_RATE = 16000

# "Listen": Pi USB mic -> phone speaker
MIC_INPUT_DEVICE = "plughw:4,0"      # USB mic (card 4) — captures FROM the Pi
LISTEN_PORT = 6001
LISTEN_SAMPLE_RATE = 16000

MODE_PHRASES = {
    "low": "Low gear",
    "normal": "Normal mode",
    "sports": "Sports mode",
}
# ------------------------------------------------------------------------

app = Flask(__name__)


def ensure_announcements():
    """Pre-generate one WAV file per mode so /announce doesn't have to
    call espeak-ng (and hit a startup delay) on every request."""
    os.makedirs(ANNOUNCE_DIR, exist_ok=True)
    for mode, phrase in MODE_PHRASES.items():
        path = os.path.join(ANNOUNCE_DIR, f"{mode}.wav")
        if not os.path.exists(path):
            subprocess.run(["espeak-ng", "-w", path, phrase], check=True)


def play_announcement(mode: str):
    path = os.path.join(ANNOUNCE_DIR, f"{mode}.wav")
    if os.path.exists(path):
        subprocess.run(["aplay", "-D", AUDIO_DEVICE, path])


@app.route("/announce")
def announce():
    mode = request.args.get("mode", "").lower()
    if mode not in MODE_PHRASES:
        return jsonify({"ok": False, "error": f"unknown mode '{mode}'"}), 400
    # play in a background thread so the HTTP response returns immediately
    threading.Thread(target=play_announcement, args=(mode,), daemon=True).start()
    return jsonify({"ok": True, "mode": mode})


@app.route("/health")
def health():
    """Lightweight endpoint the app polls to detect that this server
    (and therefore the Pi) is up and ready — used to drive the app's
    boot-up loading screen."""
    return jsonify({"ok": True})


@app.route("/volume")
def volume():
    """GET /volume            -> reports the current speaker volume (0-100)
       GET /volume?level=N    -> sets the speaker volume to N (0-100)

    Used by the Settings screen's volume slider in the app."""
    level_param = request.args.get("level")

    if level_param is not None:
        try:
            level = max(0, min(100, int(level_param)))
        except ValueError:
            return jsonify({"ok": False, "error": "level must be an integer 0-100"}), 400
        subprocess.run(
            ["amixer", "-c", AUDIO_CARD, "sset", AUDIO_MIXER_CONTROL, f"{level}%"],
            check=False,
        )
        return jsonify({"ok": True, "level": level})

    # No level given — just report the current volume.
    level = None
    try:
        result = subprocess.run(
            ["amixer", "-c", AUDIO_CARD, "sget", AUDIO_MIXER_CONTROL],
            check=False, capture_output=True, text=True,
        )
        match = re.search(r"\[(\d+)%\]", result.stdout)
        if match:
            level = int(match.group(1))
    except Exception as e:
        print(f"[volume] error reading current level: {e}")

    return jsonify({"ok": True, "level": level})


def do_shutdown():
    # Small delay so the HTTP response below actually reaches the phone
    # before the Pi starts powering down.
    time.sleep(1)
    subprocess.run(["sudo", "shutdown", "-h", "now"])


@app.route("/shutdown")
def shutdown_pi():
    """Safely powers off the Pi. Requires passwordless sudo for shutdown
    (see setup note at the top of this file) — otherwise this silently
    fails to actually shut down, since the process has no way to enter
    a password."""
    threading.Thread(target=do_shutdown, daemon=True).start()
    return jsonify({"ok": True, "message": "Shutting down"})


# ---- "Talk": raw TCP (phone mic) -> aplay (Pi speaker) -----------------
def handle_mic_connection(conn: socket.socket, addr):
    print(f"[mic] connection from {addr}")
    aplay = subprocess.Popen(
        [
            "aplay",
            "-D", AUDIO_DEVICE,
            "-f", "S16_LE",
            "-c", "1",
            "-r", str(MIC_SAMPLE_RATE),
            "-t", "raw",
        ],
        stdin=subprocess.PIPE,
    )
    try:
        while True:
            chunk = conn.recv(4096)
            if not chunk:
                break
            if aplay.stdin:
                aplay.stdin.write(chunk)
                aplay.stdin.flush()
    except (BrokenPipeError, ConnectionResetError) as e:
        print(f"[mic] connection error: {e}")
    finally:
        print(f"[mic] connection from {addr} closed")
        try:
            if aplay.stdin:
                aplay.stdin.close()
        except Exception:
            pass
        aplay.terminate()
        conn.close()


def run_mic_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((MIC_HOST, MIC_PORT))
    srv.listen(1)
    print(f"[mic] listening on {MIC_HOST}:{MIC_PORT}")

    while True:
        conn, addr = srv.accept()
        # Handle one connection at a time — a second connection while one
        # is active will queue until the first drops.
        handle_mic_connection(conn, addr)


# ---- "Listen": arecord (Pi USB mic) -> raw TCP (phone) -----------------
def handle_listen_connection(conn: socket.socket, addr):
    print(f"[listen] connection from {addr}")
    arecord = subprocess.Popen(
        [
            "arecord",
            "-D", MIC_INPUT_DEVICE,
            "-f", "S16_LE",
            "-c", "1",
            "-r", str(LISTEN_SAMPLE_RATE),
            "-t", "raw",
        ],
        stdout=subprocess.PIPE,
    )
    try:
        while True:
            chunk = arecord.stdout.read(4096)
            if not chunk:
                break
            conn.sendall(chunk)
    except (BrokenPipeError, ConnectionResetError) as e:
        print(f"[listen] connection error: {e}")
    finally:
        print(f"[listen] connection from {addr} closed")
        arecord.terminate()
        try:
            arecord.stdout.close()
        except Exception:
            pass
        conn.close()


def run_listen_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((MIC_HOST, LISTEN_PORT))
    srv.listen(1)
    print(f"[listen] listening on {MIC_HOST}:{LISTEN_PORT}")

    while True:
        conn, addr = srv.accept()
        handle_listen_connection(conn, addr)


if __name__ == "__main__":
    ensure_announcements()

    # Run both TCP servers in background threads so Flask can
    # own the main thread (matches how Flask's dev server wants to run).
    mic_thread = threading.Thread(target=run_mic_server, daemon=True)
    mic_thread.start()

    listen_thread = threading.Thread(target=run_listen_server, daemon=True)
    listen_thread.start()

    app.run(host="0.0.0.0", port=HTTP_PORT)
