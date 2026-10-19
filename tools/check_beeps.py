#!/usr/bin/env python3
"""Retour Son - vérifie la continuité d'une prise enregistrée pendant que le PC bipe toutes les N s.

Repère les bips à 1 kHz (Goertzel sur des tranches de 10 ms) : si des échantillons ont été
perdus, l'écart entre deux bips devient plus court que N secondes. Signale aussi les plages
de zéros absolus (micro coupé).

    python tools/check_beeps.py prise.wav [--every 10]
"""
import argparse
import math
import sys
import wave


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("wav")
    ap.add_argument("--every", type=float, default=10)
    args = ap.parse_args()

    w = wave.open(args.wav)
    rate, width = w.getframerate(), w.getsampwidth()
    raw = w.readframes(w.getnframes())
    n = len(raw) // width
    print(f"{n / rate:.2f} s, {rate} Hz, {width * 8} bits")

    hop = rate // 100
    coef = 2 * math.cos(2 * math.pi * 1000 / rate)
    scale = float(1 << (8 * width - 1))
    energy = []
    zero_run = 0
    zero_runs = []
    for f in range(n // hop):
        s1 = s2 = 0.0
        total = 0.0
        all_zero = True
        for i in range(f * hop, (f + 1) * hop):
            b = raw[i * width:(i + 1) * width]
            x = int.from_bytes(b, "little", signed=True) / scale
            if x != 0:
                all_zero = False
            total += x * x
            s0 = x + coef * s1 - s2
            s2, s1 = s1, s0
        tone = s1 * s1 + s2 * s2 - coef * s1 * s2
        energy.append(tone / (total * hop + 1e-12))
        if all_zero:
            zero_run += 1
        else:
            if zero_run >= 5:
                zero_runs.append((f - zero_run, zero_run))
            zero_run = 0

    # Un bip = tranche où le 1 kHz domine nettement ; on garde le début de chaque bip.
    onsets = []
    for f, e in enumerate(energy):
        if e > 0.3 and (not onsets or f - onsets[-1] > 100):
            onsets.append(f)
    times = [f / 100 for f in onsets]
    gaps = [b - a for a, b in zip(times, times[1:])]
    print(f"{len(times)} bips détectés")
    if gaps:
        print(f"écart entre bips : min {min(gaps):.2f} s, max {max(gaps):.2f} s, moyen {sum(gaps) / len(gaps):.3f} s")
        bad = [(times[i], g) for i, g in enumerate(gaps) if abs(g - args.every) > 0.1]
        for t, g in bad:
            print(f"  ! écart anormal à {t:.1f} s : {g:.2f} s")
        if not bad:
            print("aucun écart anormal : pas d'échantillons perdus")
    for start, length in zero_runs:
        print(f"  ! silence absolu à {start / 100:.1f} s pendant {length * 10} ms")


if __name__ == "__main__":
    main()
