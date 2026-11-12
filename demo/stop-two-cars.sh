#!/bin/bash
# 두 대 시연 정리: 앱을 한 대 시연 모드로 되돌리고, 두 번째 에뮬레이터와 시연 서버를 끈다.
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
for s in emulator-5554 emulator-5556; do
  "$ADB" -s "$s" shell am force-stop com.mannervolt > /dev/null 2>&1; "$ADB" -s "$s" shell am start -n com.mannervolt/.MainActivity --es demoServer off > /dev/null 2>&1 && echo "▶ $s 앱을 한 대 시연 모드로 되돌림"
done
"$ADB" -s emulator-5556 emu kill > /dev/null 2>&1 && echo "▶ emulator-5556 종료"
pkill -f "demo/demo_server.py" && echo "▶ 시연 서버 종료"
exit 0
