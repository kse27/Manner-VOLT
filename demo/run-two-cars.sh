#!/bin/bash
# 매너볼트 두 대 시연 (영상 촬영용)
#  1) Mac 로컬 시연 서버 실행 (127.0.0.1:8787)
#  2) Pleos Connect v2.1.0 에뮬레이터 두 대 실행 (5554 = A 차량, 5556 = B 차량, 두 번째는 -read-only)
#  3) 디버그 APK 빌드 후 두 대에 설치하고 각 역할로 실행
#  4) 감독 패널을 브라우저로 연다
#
# 사용: demo/run-two-cars.sh [--no-build]
# 메모리: 에뮬레이터 하나에 약 4GB를 씁니다. 촬영 중에는 Android Studio를 닫아 두는 것이 좋습니다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMU="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
AVD="${MANNERVOLT_AVD:-Pleos_Connect_v2.1.0}"
PORT="${DEMO_PORT:-8787}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
LOG="${TMPDIR:-/tmp}/mannervolt-demo"
mkdir -p "$LOG"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

# 1) 시연 서버
if ! curl -s -o /dev/null "http://127.0.0.1:$PORT/demo/director-state"; then
  echo "▶ 시연 서버 시작 (로그: $LOG/server.log)"
  DEMO_PORT="$PORT" nohup python3 "$ROOT/demo/demo_server.py" > "$LOG/server.log" 2>&1 &
  sleep 1.5
else
  echo "▶ 시연 서버가 이미 실행 중입니다"
fi

# 2) 에뮬레이터 두 대
# 같은 AVD를 두 번 띄우려면 두 대 모두 -read-only 여야 한다 (시연 중 바뀐 데이터는 끄면 사라짐).
booted() { [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }
if pgrep -f "avd $AVD" > /dev/null && ! pgrep -f "avd $AVD.*-read-only" > /dev/null; then
  echo "▶ 실행 중인 $AVD 를 -read-only 로 다시 시작합니다"
  "$ADB" -s emulator-5554 emu kill > /dev/null 2>&1 || true
  for _ in $(seq 1 30); do pgrep -f "avd $AVD" > /dev/null || break; sleep 1; done
fi
start_emu() { # serial port
  if booted "$1"; then echo "▶ $1 이미 실행 중"; return; fi
  echo "▶ $1 시작 ($AVD, read-only)"
  nohup "$EMU" -avd "$AVD" -port "$2" -read-only -no-snapshot-save > "$LOG/$1.log" 2>&1 &
}
start_emu emulator-5554 5554
sleep 10
start_emu emulator-5556 5556
for s in emulator-5554 emulator-5556; do
  printf "▶ %s 부팅 대기" "$s"
  for _ in $(seq 1 90); do booted "$s" && break; printf "."; sleep 4; done
  booted "$s" || { echo " 실패 (로그: $LOG/$s.log)"; exit 1; }
  echo " 완료"
done

# 3) 빌드, 설치, 실행
if [ "${1:-}" != "--no-build" ] || [ ! -f "$APK" ]; then
  echo "▶ 디버그 APK 빌드"
  (cd "$ROOT" && ./gradlew -q :app:assembleDebug)
fi
launch() { # serial role
  "$ADB" -s "$1" install -r -g "$APK" > /dev/null
  "$ADB" -s "$1" shell am force-stop com.mannervolt
  "$ADB" -s "$1" shell am start -n com.mannervolt/.MainActivity \
    --es demoServer "http://10.0.2.2:$PORT" --es demoRole "$2" > /dev/null
  echo "▶ $1 → 운전자 $2"
}
launch emulator-5554 A
launch emulator-5556 B

# 4) 감독 패널
URL="http://127.0.0.1:$PORT/director"
echo
echo "준비 완료. 감독 패널: $URL"
echo "  - 두 에뮬레이터 창을 나란히 놓고(왼쪽 A, 오른쪽 B), 감독 패널은 녹화 영역 밖에 두세요."
echo "  - 음성 안내까지 녹화하려면 시스템 소리를 담을 수 있는 OBS 등을 쓰세요."
echo "  - 끝내기: demo/stop-two-cars.sh"
open "$URL" 2>/dev/null || true
