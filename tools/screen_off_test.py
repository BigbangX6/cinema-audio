#!/usr/bin/env python3
"""Retour Son - test écran éteint : enregistrement + retour pendant N minutes, mesure de la batterie.

Démarre l'enregistrement par une commande réseau (comme un retour), bipe sur le PC toutes les
10 s (pour vérifier ensuite la continuité du WAV), relève batterie / processeur chaque minute,
puis arrête l'enregistrement.

    python tools/screen_off_test.py --rec-ip 192.168.1.22 --recorder SERIAL --monitor SERIAL [--minutes 15]
"""
import argparse
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time

COMMAND_MAGIC = 0x52534331
AUDIO_PORT = 47001
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")


def adb(serial, cmd):
    r = subprocess.run([ADB, "-s", serial, "shell", cmd], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout


def command(ip, rec):
    name = b"PC-test"
    pkt = struct.pack(">iiBB", COMMAND_MAGIC, int(time.time() * 1000) & 0x7FFFFFFF, 1 if rec else 0, len(name)) + name
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    for _ in range(10):  # comme le retour : répété, appliqué une seule fois
        s.sendto(pkt, (ip, AUDIO_PORT))
        time.sleep(0.1)


def battery(serial):
    out = adb(serial, "dumpsys battery; cat /sys/class/power_supply/battery/current_now 2>/dev/null")
    level = re.search(r"level: (\d+)", out)
    counter = re.search(r"Charge counter: (\d+)", out)
    cur = re.findall(r"^(-?\d+)\s*$", out, re.M)
    return (int(level.group(1)) if level else -1,
            int(counter.group(1)) // 1000 if counter else -1,
            -int(cur[-1]) // 1000 if cur else None)


def cpu(serial):
    out = adb(serial, "top -b -n 1 -q -o %CPU,NAME | grep -i retourson")
    m = re.search(r"([\d.]+)\s+fr\.cinema", out)
    return float(m.group(1)) if m else -1.0


def beeper(stop, every):
    import winsound
    while not stop.is_set():
        winsound.Beep(1000, 150)
        stop.wait(every - 0.15)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec-ip", required=True)
    ap.add_argument("--recorder", required=True)
    ap.add_argument("--monitor", required=True)
    ap.add_argument("--minutes", type=float, default=15)
    args = ap.parse_args()

    for s in (args.recorder, args.monitor):
        adb(s, "dumpsys batterystats --reset >/dev/null; logcat -c")
    start = {s: battery(s) for s in (args.recorder, args.monitor)}
    for s in (args.recorder, args.monitor):
        adb(s, "input keyevent KEYCODE_SLEEP")
    time.sleep(3)
    t0 = time.time()
    command(args.rec_ip, True)
    print(f"{time.strftime('%H:%M:%S')} REC envoyé, écrans éteints", flush=True)

    stop = threading.Event()
    threading.Thread(target=beeper, args=(stop, 10), daemon=True).start()
    end = t0 + args.minutes * 60
    while time.time() < end:
        time.sleep(min(60, max(0, end - time.time())))
        r, m = battery(args.recorder), battery(args.monitor)
        idle = adb(args.monitor, "dumpsys deviceidle | grep -E 'mState='").strip()
        print(f"{time.strftime('%H:%M:%S')} +{(time.time() - t0) / 60:4.1f} min | "
              f"enregistreur {r[0]}% {r[1]} mAh cpu {cpu(args.recorder):.0f}% | "
              f"retour {m[0]}% {m[1]} mAh courant {m[2]} mA cpu {cpu(args.monitor):.0f}% | {idle}", flush=True)
    stop.set()
    command(args.rec_ip, False)
    dur = (time.time() - t0) / 3600
    print(f"{time.strftime('%H:%M:%S')} STOP envoyé", flush=True)
    time.sleep(2)

    for label, s in (("enregistreur", args.recorder), ("retour", args.monitor)):
        b = battery(s)
        used = start[s][1] - b[1]
        print(f"\n== {label} : {start[s][0]}% -> {b[0]}%, {used} mAh en {dur * 60:.1f} min "
              f"= {used / dur:.0f} mA en moyenne (téléphone entier)")
        stats = adb(s, "dumpsys batterystats")
        uid = re.search(r"(u0a\d+):.*\n?.*fr\.cinema\.retourson", stats)
        for line in stats.splitlines():
            if "Estimated power use" in line or "Capacity:" in line or (uid and line.strip().startswith(f"UID {uid.group(1)}")):
                print("   ", line.strip())
        log = adb(s, "logcat -d -s RetourSon")
        lines = [l for l in log.splitlines() if "capture:" in l or "retour:" in l]
        for l in lines[-2:]:
            print("   ", l[l.find("RetourSon:"):])
        for l in log.splitlines():
            if re.search(r"bloqué|COUPÉ|Erreur|perdus=[1-9]|manque|pleine", l):
                print("   !", l[l.find("RetourSon:"):])


if __name__ == "__main__":
    main()
