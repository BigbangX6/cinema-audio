#!/usr/bin/env python3
"""Retour Son - faux enregistreur sur PC.

Se présente sur le réseau comme un enregistreur et envoie un signal de test
(un bip de 100 ms par seconde, plus fort toutes les 5 s) aux retours qui s'abonnent.
Sert à tester le mode Retour du téléphone sans micro.

    python tools/fake_recorder.py
    python tools/fake_recorder.py --rec     # se déclare "en enregistrement"
    python tools/fake_recorder.py --no-usb  # simule un micro USB débranché
"""
import argparse
import math
import socket
import struct
import sys
import time

AUDIO_MAGIC = 0x52534131
HELLO_MAGIC = 0x52534831
BEACON_MAGIC = 0x52534231
DISCOVERY_PORT = 47000
AUDIO_PORT = 47001
RATE = 48000
N = 240


def tone_block(sample_index):
    out = []
    peak = 0.0
    for i in range(N):
        k = sample_index + i
        pos = k % RATE
        loud = (k // RATE) % 5 == 0
        if pos < RATE // 10:
            amp = 0.9 if loud else 0.3
            v = amp * math.sin(2 * math.pi * 1000 * k / RATE)
        else:
            v = 0.0
        peak = max(peak, abs(v))
        out.append(int(v * 32767))
    return struct.pack(f">{N}h", *out), peak


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if sys.platform == "win32":
        import ctypes
        ctypes.windll.winmm.timeBeginPeriod(1)  # minuteur Windows à 1 ms au lieu de 15,6
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec", action="store_true")
    ap.add_argument("--name", default="PC-test")
    ap.add_argument("--no-usb", action="store_true", help="simule un micro USB débranché")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("", AUDIO_PORT))
    sock.setblocking(False)
    beacon_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    beacon_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    name = args.name.encode()
    flags = (1 if args.rec else 0) | (2 if args.no_usb else 0)
    beacon = struct.pack(">iBB", BEACON_MAGIC, flags, len(name)) + name

    listeners = {}
    seq = 0
    sample = 0
    start = time.perf_counter()
    next_beacon = 0.0
    print(f"Faux enregistreur '{args.name}' actif (Ctrl+C pour arrêter)")
    try:
        while True:
            now = time.perf_counter()
            if now >= next_beacon:
                for dst in ("255.255.255.255",):
                    try:
                        beacon_sock.sendto(beacon, (dst, DISCOVERY_PORT))
                    except OSError:
                        pass
                for a in [a for a, t in listeners.items() if now - t > 3]:
                    print(f"retour déconnecté : {a}")
                    del listeners[a]
                next_beacon = now + 1
            while True:
                try:
                    data, addr = sock.recvfrom(64)
                except (BlockingIOError, OSError):
                    break
                if len(data) >= 4 and struct.unpack(">i", data[:4])[0] == HELLO_MAGIC:
                    if addr not in listeners:
                        print(f"retour connecté : {addr}")
                    listeners[addr] = now
            # Envoie tous les paquets dus depuis le début (rattrape les retards du minuteur Windows).
            while sample + N <= (now - start) * RATE:
                pcm, peak = tone_block(sample)
                elapsed = int(sample * 1000 / RATE) if args.rec else 0
                pkt = struct.pack(">iiBfih", AUDIO_MAGIC, seq, flags, peak, elapsed, N) + pcm
                for a in listeners:
                    sock.sendto(pkt, a)
                seq += 1
                sample += N
            time.sleep(0.001)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
