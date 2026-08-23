# 이 프로젝트 뜯어보기

직접 분석하기 위한 안내입니다. 어디부터 읽을지, 무엇이 어디로 흐르는지,
그리고 **제 설명을 믿지 말고 직접 확인하는 방법**을 적었습니다.

모바일 자체가 처음이라면 [MOBILE-BASICS.md](MOBILE-BASICS.md) 를 먼저 보세요.

---

## 30초 요약

```
GitHub Pages (웹앱)          ← 화면·규칙 전부. 고치면 즉시 반영
      ↑ 열기
  APK (껍데기 337줄)          ← WebView 띄우기 + OS 알람 걸기. 이것만 재설치 필요
      ↕
Firebase Realtime DB          ← 기기 간 자료 공유. 서버 코드 없음
```

핵심 주장은 하나입니다: **바뀌는 것 대부분이 APK 밖에 있다.**
이게 사실인지 아래에서 직접 확인할 수 있습니다.

---

## 직접 확인해 보기

### 웹과 네이티브의 비중

주석을 뺀 실제 코드 줄 수로 비교합니다. 주석이 꽤 붙어 있어서 그냥 `wc -l`
하면 부풀려 보입니다.

```bash
nocomment() { grep -vE '^[[:space:]]*(//|\*|/\*)' | grep -vE '^[[:space:]]*$'; }

# 네이티브 (재설치 필요)
find app/src -name '*.kt' | xargs cat | nocomment | wc -l

# 웹 (재설치 없이 즉시 반영)
cat web/*.js | nocomment | wc -l
```

지금 기준 대략 **네이티브 337줄 대 웹 1,551줄** 입니다.
다만 비율보다 중요한 건 *무엇이* 어느 쪽에 있느냐입니다. 아래 표를 보세요.

### 껍데기가 제공하는 기능 전부

```bash
grep -A1 '@JavascriptInterface' app/src/main/java/com/henny/checklist/MainActivity.kt \
  | grep -oE 'fun [a-zA-Z]+'
```

이 목록이 곧 "APK 가 할 수 있는 일"입니다. 여기 없는 기능이 필요해지는 순간이
재설치가 필요해지는 순간입니다.

### 실제로 어느 커밋이 재설치를 요구했나

```bash
# WebView 구조로 바꾼 뒤, app/ 을 건드린 커밋 = 재설치가 필요했던 것
git log --oneline fa3565a..HEAD -- app/

# 같은 기간 web/ 만 건드린 커밋 = 재설치 없이 반영된 것
git log --oneline fa3565a..HEAD -- web/
```

### 앱이 여는 주소

```bash
grep APP_URL app/src/main/java/com/henny/checklist/MainActivity.kt
```

이 한 줄이 구조의 핵심입니다. 앱에 화면을 넣는 대신 주소를 넣었기 때문에,
화면을 고치는 일이 APK 와 무관해집니다.

---

## 재설치 경계 — 정확히 어디인가

**재설치가 필요한 것** (APK 안에 있음)

| 무엇 | 어디 |
|---|---|
| 알림 거는 방식, 알림 채널 | `app/src/main/java/.../notify/` |
| 웹 ↔ 네이티브 브리지에 함수 추가 | `MainActivity.Bridge` |
| 권한 추가 | `AndroidManifest.xml` |
| `targetSdk` 변경 | `app/build.gradle.kts` |
| 앱 아이콘, 앱 이름 | `app/src/main/res/` |
| **여는 주소 변경** | `MainActivity.APP_URL` |

**재설치가 필요 없는 것** (웹에 있음)

| 무엇 | 어디 |
|---|---|
| 모든 화면, 모든 버튼 | `web/ui.js` |
| 작업·마일리지·통계 규칙 | `web/core.js` |
| 알림 문구와 시각 계산 | `web/core.js` 의 `computeSchedule` |
| 동기화·병합 방식 | `web/core.js` 의 `Repo`, `mergePlans` |
| 색, 여백, 반응형 | `web/app.css` |

경계선은 `publishSchedule()` 한 곳입니다. 웹이 `[{at, title, body, tag}]` 를
계산해 넘기고, 껍데기는 해석 없이 OS 알람에 겁니다. **이 네 개로 표현되는 한
알림 내용을 아무리 바꿔도 APK 는 그대로입니다.**

---

## 읽는 순서

처음이라면 이 순서를 권합니다.

### 1단계 — 경계부터 (30분)

```
app/src/main/java/com/henny/checklist/MainActivity.kt   껍데기 전부
web/core.js  의 publishSchedule() 과 shell 객체          웹 쪽 경계
```

이 둘만 보면 "무엇이 APK 안이고 무엇이 밖인가"가 잡힙니다.

### 2단계 — 알림이 실제로 어떻게 울리나 (30분)

```
web/core.js   computeSchedule()      3일치 알림 시각 계산
              publishSchedule()      → 네이티브로 넘김
app/.../notify/ScheduleStore.kt      받아서 파일에 저장
app/.../AlarmScheduler.kt            다음 한 개를 OS 에 예약
app/.../AlarmReceiver.kt             울리면 알림 띄우고 다음 것 예약
app/.../BootReceiver.kt              재부팅 뒤 다시 예약
```

여기가 이 앱에서 유일하게 "모바일다운" 부분입니다.

### 3단계 — 자료가 어떻게 흐르나 (1시간)

```
web/core.js   Repo 클래스            읽기/쓰기 진입점
              mutatePlan()           모든 쓰기가 지나는 길목
              stampPlan()            바뀐 항목에만 시각 찍기
              mergePlans()           관리자가 여럿일 때 합치기
              sync() / startLive()   원격과 맞추기
```

분산 시스템 하셨으면 여기가 제일 익숙하실 겁니다. 항목 단위 LWW + 삭제 묘비입니다.

### 4단계 — 화면 (필요할 때)

```
web/ui.js     draw()                 그리기 루프. 입력 유실 대책이 여기
              ACTIONS                이벤트 라우팅 테이블
```

---

## 데이터 흐름

### 관리자가 작업을 추가하면

```
ui.js  ACTIONS['add-routine']
  → core.js  Repo.addRoutine()
      → mutatePlan()          ┐
          → stampPlan()       │ 바뀐 항목에만 updatedAt
          → localStorage 저장  │ 즉시. 오프라인이어도 됨
          → emit()            │ 화면 다시 그리기
          → schedulePush()    ┘ 2초 뒤 원격에 올림 (디바운스)
  → publishSchedule()          알림 일정 다시 계산해 껍데기에 전달
```

### 작업자 기기가 그걸 받는 과정

```
startLive()  15초마다
  → pollForChanges()
      → 원격의 updatedAt 하나만 읽음 (수십 바이트)
      → 내 것보다 크면 → sync() 로 전체 받기
          → mergePlans() 로 합침
          → localStorage 저장 → 화면 갱신
```

폴링 주기가 15초인데도 부담이 없는 이유는 필드 하나만 읽기 때문입니다.
Firebase 가 아닌 백엔드는 그게 안 돼서 60초로 잡습니다.

### 저장 위치 세 곳

| 어디 | 무엇 | 언제 사라지나 |
|---|---|---|
| `localStorage` (WebView) | 설정, 계획, 진행 기록 | 앱 삭제 시 (덮어 설치는 안전) |
| `SharedPreferences` (네이티브) | 알림 일정 사본 | 앱 삭제 시 |
| Firebase | 계획, 진행 기록 | 직접 지울 때까지 |

**실질적인 단일 진실 공급원은 Firebase 입니다.** 앞의 둘은 오프라인용 사본에
가깝습니다. 그래서 기기를 잃어도 연결 코드만 있으면 복구됩니다.

---

## 겪었던 문제와 원인 (분석에 도움이 될 실제 사례)

| 증상 | 진짜 원인 |
|---|---|
| 업데이트하니 자료가 다 없어짐 | 저장 위치가 파일 → `localStorage` 로 바뀌었는데 이관이 없었음 |
| 그전에도 매번 지우고 깔아야 했음 | CI 가 빌드마다 새 키로 서명 → OS 가 다른 앱으로 봄 |
| 화면 맨 윗줄이 시계와 겹침 | `targetSdk` 35 의 edge-to-edge. 코드는 그대로였음 |
| 타이핑하면 글자가 사라짐 | 15초 동기화가 화면 전체를 다시 그림 |
| 키보드가 저장 버튼을 가림 | 안드로이드는 키보드가 떠도 `100vh` 가 안 줄어듦 |
| 빌드가 깨짐 | XML 주석 안에 붙임표 두 개를 연달아 씀 |
| CI 만 실패, 로컬은 통과 | 검사가 "오늘 17시"에 의존. 실행 시각이 그걸 넘김 |

패턴이 보이실 겁니다. **서버에서라면 안 겪을 것들이 대부분이고, 실패가 조용합니다.**
예외도 로그도 없이 그냥 동작을 안 합니다. 그래서 미리 확인하는 코드가 많습니다.

---

## 검사 돌려보기

```bash
# 규칙 검사 (노드만 있으면 됨, 몇 초)
node tools/test_core.mjs

# 화면 흐름 검사 (진짜 크로미움을 띄워 눌러 봄, 1분)
npm install --no-save playwright && npx playwright install --with-deps chromium
node tools/test_flow.mjs

# 컴파일 전에 흔한 실수 걸러내기
python3 tools/sanity_check.py
```

`test_flow.mjs` 가 특히 볼 만합니다. 가짜 Firebase 를 `page.route()` 로 가로채서
기기 두 대(관리자·작업자)가 주고받는 흐름을 실제 브라우저로 확인합니다.

안드로이드 SDK 없이 웹만 보려면:

```bash
cd web && python3 -m http.server 8080
# 브라우저로 localhost:8080. 알림만 빼고 전부 동작합니다.
```

---

## 이 구조의 대가 (장점만 있지는 않습니다)

공정하게 적자면:

- **첫 실행에 인터넷이 필요합니다.** 웹을 받아야 화면이 뜹니다. 두 번째부터는
  서비스 워커가 캐시해 둬서 오프라인도 됩니다.
- **알림 즉시성에 한계가 있습니다.** 관리자가 방금 배정한 임시 작업은 작업자가
  앱을 연 뒤에야 알람에 잡힙니다. 웹이 계산해서 넘겨야 하기 때문입니다.
  (아침 알림이 매일 앱을 열게 하므로 실제 영향은 작습니다)
- **네이티브 성능이 필요한 것은 못 합니다.** 카메라 실시간 처리 같은 것.
  이 앱에는 해당이 없습니다.
- **웹 배포가 깨지면 전원이 즉시 영향을 받습니다.** 스토어 심사 같은 완충이
  없습니다. 그래서 배포 전에 `test_flow.mjs` 를 관문으로 둡니다.

---

## 더 볼 것

- 모바일 자체가 처음이라면 → [MOBILE-BASICS.md](MOBILE-BASICS.md)
- 설계 판단의 배경 → [ARCHITECTURE.md](ARCHITECTURE.md)
- 빌드·배포·서명키 → [DEVELOP.md](DEVELOP.md)
