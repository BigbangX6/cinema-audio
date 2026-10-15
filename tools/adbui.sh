# Aide pour piloter l'appli par adb : source tools/adbui.sh ; tap_id btnRec ; tap_text "Arrêter"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
_center() { sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }
_dump() { $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml | tr '>' '\n'; }
tap_id() { local b; b=$(_dump | grep "id/$1\"" | grep -oE 'bounds="[^"]*"' | head -1); [ -z "$b" ] && { echo "introuvable: $1"; return 1; }; $ADB shell input tap $(echo "$b" | _center); }
tap_text() { local b; b=$(_dump | grep "text=\"$1\"" | grep -oE 'bounds="[^"]*"' | head -1); [ -z "$b" ] && { echo "introuvable: $1"; return 1; }; $ADB shell input tap $(echo "$b" | _center); }
shot() { $ADB exec-out screencap -p > "$1"; }
