#!/usr/bin/env python3
"""Retour Son - retour de test sur PC.

S'abonne à un enregistreur (le téléphone), mesure la qualité du flux réseau
et enregistre ce qui est reçu dans un WAV pour vérification.

    python tools/pc_monitor.py 192.168.1.42 --seconds 60 --wav recu.wav
    python tools/pc_monitor.py            # découverte automatique (si le pare-feu laisse passer)
"""
import argparse
import math
import socket
import struct
import sys
import time
import wave

AUDIO_MAGIC = 0x52534131
HELLO_MAGIC = 0x52534831
BEACON_MAGIC = 0x52534231
DISCOVERY_PORT = 47000
AUDIO_PORT = 47001
RATE = 48000
HDR = struct.Struct(">iiBfih")


def discover(timeout):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", DISCOVERY_PORT))
    s.settimeout(0.5)
    end = time.perf_counter() + timeout
    try:
        while time.perf_counter() < end:
            try:
                data, addr = s.recvfrom(512)
            except socket.timeout:
                continue
            if len(data) >= 6 and struct.unpack(">i", data[:4])[0] == BEACON_MAGIC:
                n = data[5]
                return addr[0], data[6:6 + n].decode("utf-8", "replace")
    finally:
        s.close()
    return None


def pct(values, p):
    if not values:
        return 0.0
    v = sorted(values)
    return v[min(len(v) - 1, int(len(v) * p))]


def db(x):
    return 20 * math.log10(max(x, 1e-6))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if sys.platform == "win32":
        import ctypes
        ctypes.windll.winmm.timeBeginPeriod(1)  # minuteur Windows à 1 ms au lieu de 15,6
    ap = argparse.ArgumentParser()
    ap.add_argument("ip", nargs="?", help="IP de l'enregistreur (sinon découverte)")
    ap.add_argument("--seconds", type=float, default=30)
    ap.add_argument("--wav", help="enregistre le flux reçu dans ce fichier")
    args = ap.parse_args()

    ip = args.ip
    if not ip:
        print("Recherche d'un enregistreur (5 s)...")
        found = discover(5)
        if not found:
            sys.exit("Aucun enregistreur trouvé. Donne l'IP du téléphone en argument.")
        ip, name = found
        print(f"Trouvé : {name} ({ip})")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
    sock.bind(("", 0))
    sock.settimeout(0.05)
    hello = struct.pack(">i", HELLO_MAGIC)

    wav = None
    if args.wav:
        wav = wave.open(args.wav, "wb")
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)

    start = time.perf_counter()
    next_hello = 0.0
    next_report = start + 1
    expected = None
    base = None  # (seq0, t0) pour l'horloge idéale
    total = lost = late = 0
    delays = []        # retard relatif de chaque paquet (ms) sur toute la session
    win_delays = []
    win_pkts = win_lost = 0
    win_peak = 0.0
    gaps = []
    last_arrival = None
    rec_state = ""

    print(f"Abonnement à {ip}:{AUDIO_PORT} pour {args.seconds:.0f} s...")
    try:
        while time.perf_counter() - start < args.seconds:
            now = time.perf_counter()
            if now >= next_hello:
                sock.sendto(hello, (ip, AUDIO_PORT))
                next_hello = now + 0.5
            if now >= next_report:
                if win_pkts:
                    jit = pct(win_delays, 0.99) - min(win_delays)
                    print(f"[{now - start:5.1f}s] paquets={win_pkts:4d} perdus={win_lost:3d} "
                          f"gigue p99={jit:6.1f} ms  crête={db(win_peak):6.1f} dBFS  {rec_state}")
                else:
                    print(f"[{now - start:5.1f}s] aucun paquet reçu")
                win_delays, win_pkts, win_lost, win_peak = [], 0, 0, 0.0
                next_report += 1

            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            t = time.perf_counter()
            if len(data) < HDR.size:
                continue
            magic, seq, flags, peak, elapsed, n = HDR.unpack_from(data)
            if magic != AUDIO_MAGIC or len(data) < HDR.size + 2 * n:
                continue

            if expected is not None:
                d = (seq - expected) & 0xFFFFFFFF
                if d >= 0x80000000:
                    if d > 0xFFFFFFFF - 200:
                        late += 1
                        continue
                    print("  (l'enregistreur a redémarré : resynchronisation)")
                    base = None
                    d = 0
                if d:
                    lost += d
                    win_lost += d
                    if wav:
                        wav.writeframes(b"\x00\x00" * (d * n))
            expected = (seq + 1) & 0xFFFFFFFF
            if base is None:
                base = (seq, t)
            ideal = base[1] + ((seq - base[0]) & 0xFFFFFFFF) * n / RATE
            delay = (t - ideal) * 1000
            delays.append(delay)
            win_delays.append(delay)
            if last_arrival is not None:
                gaps.append((t - last_arrival) * 1000)
            last_arrival = t

            total += 1
            win_pkts += 1
            win_peak = max(win_peak, peak)
            rec_state = f"REC {elapsed / 1000:7.1f}s" if flags & 1 else "pas en REC"
            if flags & 2:
                rec_state += "  ⚠ micro USB débranché"
            if flags & 4:
                rec_state += "  ⚠ MICRO COUPÉ (autre appli)"
            if wav:
                pcm = data[HDR.size:HDR.size + 2 * n]
                # big-endian -> little-endian pour le WAV
                wav.writeframes(bytes(b for i in range(0, len(pcm), 2) for b in (pcm[i + 1], pcm[i])))
    except KeyboardInterrupt:
        pass
    finally:
        if wav:
            wav.close()

    print("\n=== Bilan ===")
    if not total:
        print("Aucun paquet reçu. Vérifie l'IP, que le mode Enregistreur est lancé et que PC et téléphone sont sur le même réseau.")
        return
    rel = [d - min(delays) for d in delays]
    print(f"paquets reçus      : {total}  ({total * n / RATE:.1f} s d'audio)")
    print(f"perdus             : {lost} ({100 * lost / (total + lost):.2f} %)   en retard/dupliqués : {late}")
    print(f"écart entre paquets: médiane {pct(gaps, 0.5):.1f} ms, p99 {pct(gaps, 0.99):.1f} ms, max {max(gaps):.1f} ms")
    print(f"gigue (retard relatif) : p50 {pct(rel, 0.5):.1f} ms, p99 {pct(rel, 0.99):.1f} ms, "
          f"p99.9 {pct(rel, 0.999):.1f} ms, max {max(rel):.1f} ms")
    print(f"=> tampon conseillé ≈ {math.ceil(pct(rel, 0.999) / 5) * 5 + 5:.0f} ms")
    if args.wav:
        print(f"flux reçu enregistré dans {args.wav}")


if __name__ == "__main__":
    main()
