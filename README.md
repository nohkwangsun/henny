# 헨니 체크 (Henny Check)

떨어져 있어도 **오늘 할 일을 챙기게 하는** 체크리스트 앱입니다.

작업자는 앱을 열고 항목을 누르면 끝입니다. 관리자는 누가 무엇을 했는지 한눈에 봅니다.
알림이 매일 앱을 열게 만들고, 마일리지가 계속하게 만듭니다.

| 역할 | 보는 화면 |
|---|---|
| **작업자** | 오늘 목록 하나. 켜기 → 누르기 → 끝. |
| **관리자** | 오늘 현황 / 통계 / 작업 관리 / 설정 |

관리자는 여러 명이어도 됩니다. 부모 두 사람이 같이 관리하는 식입니다.

---

## 빠른 시작

### 1. 앱 설치

[Actions → Build APK](https://github.com/nohkwangsun/henny/actions/workflows/android.yml)
에서 가장 최근 성공한 실행을 열고 **Artifacts → henny-apk** 를 받아 설치합니다.
자세한 방법은 **[INSTALL.md](INSTALL.md)**.

> 앱은 **한 번만** 설치하면 됩니다. 화면과 기능은 웹에 있어서, 고치면 앱을 여는 순간
> 최신이 됩니다. 다시 받을 일이 없습니다.

### 2. 관리자 기기 설정 (5분)

1. 앱 실행 → **팀을 새로 만들래요**
2. 작업자를 한 명씩 추가
3. 저장할 곳 연결 — 화면에 나오는 차례대로 따라가면 됩니다
   ([자세히](docs/SETUP.md))
4. **설정 탭 → 작업자 → 연결 코드** 를 눌러 작업자에게 코드를 보냅니다

### 3. 작업자 기기 설정 (1분)

1. 앱 실행 → **코드를 받았어요**
2. 받은 코드를 붙여넣고 **연결하기**
3. 알림 권한 허용 → 끝

### 4. 관리자를 더 넣고 싶다면

관리자 기기 **설정 탭 → 관리자 코드** 를 상대에게 보냅니다.
상대는 **코드를 받았어요** 에 넣으면 같은 팀의 관리자가 됩니다.

---

## 아키텍처

```
   개발자                      GitHub                        가족 기기
 ─────────                  ──────────                    ────────────
 git push  ──▶  Actions: 검사 ──▶ Pages 배포  ──▶  앱을 열면 최신 화면
                                                          │
                                                          ▼
                                          WebView 안의 웹앱 (화면·규칙)
                                                          │
                                          ┌───────────────┴───────────────┐
                                          ▼                               ▼
                                   네이티브 껍데기                  Firebase (저장)
                                   알림만 담당                    기기끼리 주고받기
```

**세 조각으로 나뉩니다.**

**웹앱 (`web/`)** — 화면과 판단 규칙이 전부 여기 있습니다. GitHub Pages 에 올라가므로,
고쳐서 `main` 에 밀면 몇 십 초 뒤 가족 전원이 최신 상태가 됩니다. 스토어 심사도,
각자 다시 설치하는 수고도 없습니다.

**네이티브 껍데기 (`app/`)** — 하는 일이 둘뿐입니다. 웹앱을 띄우고, 웹이 넘겨준 일정대로
알림을 겁니다. 알림만 네이티브인 이유는 브라우저가 **정해진 시각에 스스로 울리는 것**을
못 하기 때문입니다. 껍데기는 거의 바뀌지 않으므로 앱을 다시 설치할 일이 없습니다.

**저장소 (Firebase Realtime Database)** — 서버를 두지 않습니다. 작은 JSON 문서 몇 개를
주고받습니다.

- **계획 문서 1개** — 관리자가 쓰고 작업자가 읽습니다 (작업자 목록, 정기 작업, 임시 작업, 알림 시각)
- **기록 문서 (작업자당 1개)** — 그 작업자가 쓰고 관리자가 읽습니다 (날짜별 체크 기록)

기록 문서는 쓰는 사람이 한 명뿐이라 부딪힐 일이 없습니다. 계획 문서는 관리자가 여럿일 수
있어 **항목 단위로 합칩니다** — 자세한 규칙은 [ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## 개발

```bash
node tools/test_core.mjs     # 판단 규칙 검사 (몇 초)
node tools/test_flow.mjs     # 진짜 브라우저로 화면 흐름 검사 (playwright 필요)
python3 tools/sanity_check.py  # 코틀린 쪽 실수 검사 (안드로이드 SDK 없이)
./gradlew assembleRelease    # APK 빌드 (Android SDK 필요)
```

웹만 고칠 거라면 안드로이드 SDK 없이도 됩니다. `web/` 을 아무 정적 서버로 열면 그대로 돕니다.

```bash
cd web && python3 -m http.server 8000
```

배포는 `main` 에 밀면 자동입니다. 실험은 다른 브랜치에서 하세요 — 거기서도 검사는 돕니다.
자세한 절차와 저장소 설정은 **[DEVELOP.md](docs/DEVELOP.md)**.

---

## 파일 구조

```
web/                     실제 앱. 여기를 고치면 곧바로 모두에게 반영된다.
  index.html             껍데기 문서
  core.js                자료 구조, 통계, 동기화, 연결 코드, 알람 일정 계산
  ui.js                  작업자 화면 / 관리자 4개 탭 / 첫 설정 / 설정
  app.css                화면 서식
  sw.js                  서비스 워커. 네트워크를 먼저 보고 캐시는 예비용
app/src/main/java/com/henny/checklist/
  MainActivity.kt        WebView 를 띄우고 알림 다리(HennyShell)를 연다
  notify/                알람 예약과 알림 표시
tools/
  test_core.mjs          core.js 판단 규칙 검사
  test_flow.mjs          브라우저로 화면 흐름 검사
  sanity_check.py        코틀린 실수 검사
docs/                    아래 문서들
```

---

## 문서

| 문서 | 내용 |
|---|---|
| [INSTALL.md](INSTALL.md) | APK 설치, 사이드로딩 권한, 스토어와의 차이 |
| [docs/SETUP.md](docs/SETUP.md) | 저장할 곳(Firebase) 만들기, 다른 선택지 |
| [docs/USAGE.md](docs/USAGE.md) | 매일 쓰는 법 — 작업, 마일리지, 통계, 관리자 여러 명 |
| [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md) | 알림 종류와 안 울릴 때 (삼성·샤오미) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 구조, 자료 모델, 동기화 규칙, 설계 판단 |
| [docs/DEVELOP.md](docs/DEVELOP.md) | 빌드, 검사, 배포 파이프라인, 서명키 |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 자료가 사라졌을 때, 복구, 업데이트 |
| [PLAY_STORE.md](PLAY_STORE.md) | 플레이스토어 출시 절차 |
