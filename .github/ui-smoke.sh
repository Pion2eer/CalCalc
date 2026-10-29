#!/bin/bash
# Real-device check on an emulator: open the app, go to «Сегодня», type into the input with the real keyboard
# and take screenshots. The pictures land in shots-device/ and are published with the others.
set -x
out=shots-device; mkdir -p $out
tap_text() {  # tap the first element whose text or hint contains $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml ui.xml >/dev/null
  b=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
t = sys.argv[1]
for n in ET.parse('ui.xml').iter('node'):
    if t in (n.get('text') or '') or t in (n.get('hint') or '') or t in (n.get('content-desc') or ''):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  [ -n "$b" ] && adb shell input tap $b
}
swipe_left_text() {  # drag the row that shows $1 from right to left
  tap_text_xy=$(adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml ui.xml >/dev/null; python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse('ui.xml').iter('node'):
    if sys.argv[1] in (n.get('text') or ''):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((y1 + y2) // 2); break
PY
)
  [ -n "$tap_text_xy" ] && adb shell input swipe 1000 $tap_text_xy 150 $tap_text_xy 400
}
shot() { adb exec-out screencap -p > $out/$1.png; }

adb install -r CalCalc-debug.apk
adb shell pm grant ltd.pion.calcalc android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n ltd.pion.calcalc/.ui.MainActivity
sleep 6
shot d1_first_start
# a slow emulator sometimes shows «Pixel Launcher isn't responding» on top: let it wait
tap_text "Wait"; sleep 1
tap_text "Сегодня"; sleep 2
shot d2_today
tap_text "Сказать голосом"; sleep 3
shot d2b_voice
adb shell input keyevent 4; sleep 2
tap_text "Сфотографировать еду"; sleep 4
shot d2c_camera
# take the picture with the emulator's camera and accept it: the entry with its thumbnail should appear
adb shell input tap 540 2180; sleep 4
shot d2d_camera_taken
tap_text "Done" || tap_text "OK"; sleep 4
shot d2e_photo_entry
tap_text "Что съели"; sleep 3
adb shell input text "grechka%s300%sg"; sleep 2
shot d3_typing_keyboard
adb shell input keyevent 66; sleep 3
adb shell input keyevent 4; sleep 2
shot d4_after_send
tap_text "grechka"; sleep 2
shot d4b_edit_dialog
tap_text "grechka"; sleep 1
adb shell input keyevent 123; adb shell input text "%si%skotleta"; sleep 2
shot d4c_edit_rewritten
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 2
# the two backs can leave the app when the keyboard was already hidden: bring it to the front again
adb shell am start -W -n ltd.pion.calcalc/.ui.MainActivity; sleep 2
tap_text "Сегодня"; sleep 2
# a slow swipe, caught halfway: the day should follow the finger
adb shell input swipe 150 1000 650 1000 2500 & sleep 1.6; shot d4c2_mid_swipe; wait; sleep 2
adb shell input swipe 650 1000 150 1000 300; sleep 2
adb shell input swipe 200 1000 900 1000 250; sleep 2
shot d4d_swiped_to_yesterday
adb shell input swipe 900 1000 200 1000 250; sleep 2
shot d4e_swiped_back_today
# swipe an entry away, the snackbar offers to bring it back
swipe_left_text "grechka"; sleep 1
shot d4f_swipe_deleted
tap_text "Отменить"; sleep 2
shot d4g_restored
tap_text "Дневник"; sleep 2
shot d5_diary
tap_text "Профиль"; sleep 2
shot d6_profile
adb shell input swipe 500 1600 500 500 300; sleep 1
shot d7_profile_steps
tap_text "Подключить шаги"; sleep 4
shot d8_health_connect_permission
adb shell input keyevent 4; sleep 2
ls -la $out
