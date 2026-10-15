# Retour Son

Application Android de tournage : un téléphone **enregistre** le son du micro (Rode VideoMic GO II en USB-C)
et l'envoie **en direct** sur le Wi-Fi local (sans internet) à un ou plusieurs téléphones de **retour** (réalisateur, scripte).

## Principe

- **Enregistreur** : WAV 24 bits / 48 kHz mono dans `Music/RetourSon/` (nom `RS_AAAAMMJJ_HHMMSS.wav`).
  L'en-tête est mis à jour chaque seconde : en cas de plantage, la prise reste lisible.
  Le réseau passe par une file non bloquante : il ne peut jamais perturber l'enregistrement.
  Le micro reste ouvert hors prise, pour que le retour entende aussi les répétitions.
  Démarrer = appui simple, arrêter = appui long.
- **Retour** : trouve l'enregistreur tout seul sur le réseau, joue le son avec
  - un **tampon anti-coupures** réglable (10–200 ms),
  - un **retard ajouté** (0–1000 ms) pour caler le son sur le retour vidéo (ou compenser le Bluetooth),
  - indicateur de niveau, alerte de saturation, état REC et durée de la prise.
- Aucun envoi audio quand personne n'écoute (économie de batterie).

## Réseau

UDP, ports **47000** (découverte, broadcast) et **47001** (audio). Audio PCM 16 bits non compressé,
paquets de 5 ms (~800 kbit/s par auditeur). Si la découverte ne fonctionne pas sur un réseau,
saisir l'IP de l'enregistreur à la main dans l'écran Retour.

## Compiler et installer

Outils installés localement : JDK 17 (`%LOCALAPPDATA%\cinema-audio-tools\jdk`) et SDK Android
(`%LOCALAPPDATA%\Android\Sdk`).

```
set JAVA_HOME=%LOCALAPPDATA%\cinema-audio-tools\jdk\jdk-17.0.20.1+1
gradlew.bat assembleDebug
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## Outils de test (PC)

- `tools/pc_monitor.py IP [--seconds N] [--wav f.wav]` : retour sur PC, mesure pertes et gigue, conseille un tampon.
- `tools/fake_recorder.py [--rec] [--no-usb]` : faux enregistreur (bips 1 kHz), pour tester le mode Retour.
- `tools/adbui.sh` : `source` puis `tap_id btnRec`, `tap_text "ARRÊTER"`, `shot capture.png`.
- Journaux : `adb logcat -s RetourSon` (bilan toutes les 5 s côté enregistreur et retour).

## Code

| Fichier | Rôle |
|---|---|
| `CaptureService` | capture micro, écriture WAV, envoi réseau |
| `WavWriter` | WAV 24 bits résistant aux plantages |
| `AudioStreamer` | balise de découverte + envoi aux auditeurs |
| `MonitorService` | découverte, réception, tampon, lecture |
| `SampleFifo` | file d'échantillons du retour |
| `Protocol` | format des paquets |
