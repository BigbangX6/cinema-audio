#!/usr/bin/env python3
"""Retour Son - mesure acoustique de latence.

Le PC joue des clics doux sur ses haut-parleurs, posé à côté du micro de l'enregistreur.
On mesure le temps entre la sortie du clic (horloge du convertisseur, via PortAudio)
et l'arrivée sur le PC du paquet réseau qui le contient.
= sortie PC + trajet acoustique + capture Android + paquet + Wi-Fi.

    python tools/latency_test.py 192.168.1.10 [--device 21] [--clicks 10] [--amp 0.25]
"""
import argparse
import socket
import struct
import sys
import threading
import time

import numpy as np
import sounddevice as sd

AUDIO_MAGIC = 0x52534131
HELLO_MAGIC = 0x52534831
AUDIO_PORT = 47001
RATE = 48000
HDR = struct.Struct(">iiBfih")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("ip")
    ap.add_argument("--device", type=int, default=None, help="sortie audio (voir sounddevice.query_devices())")
    ap.add_argument("--clicks", type=int, default=10)
    ap.add_argument("--amp", type=float, default=0.25)
    args = ap.parse_args()

    n = int(RATE * 0.004)
    click = (args.amp * np.sin(2 * np.pi * 2500 * np.arange(n) / RATE) * np.hanning(n)).astype(np.float32)

    lock = threading.Lock()
    want_click = threading.Event()
    dac_times = []       # instants de sortie des clics (horloge perf_counter)
    clock_offsets = []   # perf_counter - horloge PortAudio

    def callback(outdata, frames, t, status):
        outdata.fill(0)
        clock_offsets.append(time.perf_counter() - t.currentTime)
        if want_click.is_set() and frames >= n:
            want_click.clear()
            outdata[:n, :] = click[:, None]
            with lock:
                dac_times.append(t.outputBufferDacTime + clock_offsets[-1])

    channels = min(2, sd.query_devices(args.device, "output")["max_output_channels"])
    stream = sd.OutputStream(device=args.device, samplerate=RATE, channels=channels,
                             dtype="float32", latency="low", callback=callback)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("", 0))
    sock.settimeout(0.02)
    hello = struct.pack(">i", HELLO_MAGIC)
    packets = []  # (arrivée, crête du paquet)

    stream.start()
    start = time.perf_counter()
    next_hello = 0.0
    next_click = start + 2.0
    clicks_done = 0
    end = start + 2.0 + args.clicks * 1.5 + 1.5
    print(f"Sortie : {sd.query_devices(args.device, 'output')['name']} — {args.clicks} clics...")
    while time.perf_counter() < end:
        now = time.perf_counter()
        if now >= next_hello:
            sock.sendto(hello, (args.ip, AUDIO_PORT))
            next_hello = now + 0.5
        if clicks_done < args.clicks and now >= next_click:
            want_click.set()
            clicks_done += 1
            next_click += 1.5
        try:
            data, _ = sock.recvfrom(4096)
        except socket.timeout:
            continue
        t = time.perf_counter()
        if len(data) < HDR.size:
            continue
        magic, seq, flags, peak, elapsed, cnt = HDR.unpack_from(data)
        if magic != AUDIO_MAGIC:
            continue
        pcm = np.frombuffer(data, dtype=">i2", count=cnt, offset=HDR.size).astype(np.float32) / 32768
        packets.append((t, float(np.max(np.abs(pcm)))))
    stream.stop()

    if not packets:
        sys.exit("Aucun paquet reçu de l'enregistreur.")
    arr = np.array(packets)
    off = np.array(clock_offsets)
    print(f"écart horloges PortAudio/perf_counter : {np.median(off) * 1000:.2f} ms (dispersion {np.ptp(off) * 1000:.2f} ms)")
    lat = []
    for i, dac in enumerate(dac_times):
        before = arr[(arr[:, 0] > dac - 0.6) & (arr[:, 0] < dac)]
        noise = np.percentile(before[:, 1], 90) if len(before) else 0.01
        thresh = max(noise * 4, 0.003)
        after = arr[(arr[:, 0] >= dac) & (arr[:, 0] < dac + 1.0)]
        hit = after[after[:, 1] > thresh]
        if len(hit):
            ms = (hit[0, 0] - dac) * 1000
            lat.append(ms)
            print(f"clic {i + 1:2d} : {ms:6.1f} ms   (crête {20 * np.log10(hit[0, 1]):5.1f} dBFS, bruit {20 * np.log10(noise + 1e-9):5.1f})")
        else:
            print(f"clic {i + 1:2d} : non détecté (seuil {20 * np.log10(thresh):.1f} dBFS)")
    if lat:
        print(f"\nlatence clic -> PC : médiane {np.median(lat):.1f} ms, min {min(lat):.1f}, max {max(lat):.1f}")


if __name__ == "__main__":
    main()
