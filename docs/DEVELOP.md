# 개발 가이드

## 웹만 고칠 때 (대부분)

안드로이드 SDK 가 없어도 됩니다.

```bash
cd web && python3 -m http.server 8000
```

브라우저로 <http://localhost:8000> 을 엽니다. 껍데기가 없으니 알림만 안 울리고
나머지는 전부 그대로 돕니다.

`web/index.html` 의 `__BUILD__` 는 배포할 때 채워집니다. 로컬에서는 그대로 보입니다.

---

## 검사

```bash
node tools/test_core.mjs       # 판단 규칙 (몇 초)
node tools/test_flow.mjs       # 진짜 브라우저로 화면 흐름
python3 tools/sanity_check.py  # 코틀린 실수 검사
```

**`test_core.mjs`** — `core.js` 를 노드에서 그대로 돌립니다.
날짜 계산, 작업 정렬, 배점 스냅샷, 연속 달성 규칙, 통계, 연결 코드 왕복,
관리자 여럿일 때의 계획 합치기, 예전 자료 이관을 봅니다.

**`test_flow.mjs`** — 크로미움을 띄워 첫 설정부터 실제로 눌러 봅니다.

```
역할 고르기 → 작업자 등록 → 저장소 연결 → 정기 작업 추가 → 연결 코드
→ 작업자 기기에서 코드 입력 → 체크 → 관리자가 되받기
→ 관리자 두 번째 기기 붙이기 → 양쪽에서 더하고 지우기
```

저장소는 요청을 가로채 `Map` 으로 받는 가짜입니다. 진짜 Firebase 를 건드리지 않습니다.

playwright 가 필요합니다.

```bash
npm install --no-save playwright
npx playwright install chromium
node tools/test_flow.mjs
```

이미 크로미움이 깔린 환경이라면 다시 받지 않아도 됩니다.

```bash
HENNY_CHROMIUM=/path/to/chrome node tools/test_flow.mjs
```

**`sanity_check.py`** — 안드로이드 SDK 없이 손볼 때가 있어, 컴파일까지 가지 않고도
잡히는 실수를 미리 검사합니다. 중괄호 짝, 그리고 Compose 시절 세 번 반복해서 겪은
후행 람다 문제입니다.

---

## APK 빌드

Android SDK 가 있고 `local.properties` 에 `sdk.dir` 이 잡혀 있어야 합니다.

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease     # Play 용 .aab
```

서명 키가 없으면 CI 가 일회용 키를 즉석에서 만들어 릴리스 경로를 그대로 검증합니다.
일회용 키로 서명한 AAB 는 내보내지 않습니다 — Play 에 올리면 그 키가 업로드 키로
등록돼 다음부터 못 올리게 되기 때문입니다.

껍데기는 거의 바뀌지 않으므로 APK 를 다시 만들 일이 드뭅니다.
`app/` 이나 알림 동작을 건드렸을 때만 필요합니다.

---

## 배포

```
git push (main)  →  Actions: 검사 → Pages 배포  →  가족이 앱을 여는 순간 최신
```

`main` 에 밀면 자동입니다. 검사가 깨지면 배포도 서지 않습니다.

실험은 다른 브랜치에서 하세요. 거기서도 `Test web app` 워크플로가 같은 검사를 돌리므로,
깨진 것이 `main` 에 가서야 드러나는 일이 없습니다.

| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `web-test.yml` | main 아닌 브랜치에 push | 두 검사 |
| `pages.yml` | main 에 push | 두 검사 → Pages 배포 |
| `android.yml` | push | sanity check → APK/AAB 빌드 |

### 저장소 설정 (처음 한 번)

세 가지가 맞아야 배포가 돕니다. 하나라도 어긋나면 **로그 한 줄 없이 몇 초 만에**
실패하므로 원인을 찾기 어렵습니다.

1. **Settings → Pages → Source** 를 **GitHub Actions** 로
2. **Settings → General → Default branch** 를 **main** 으로
3. **Settings → Environments → github-pages → Deployment branches** 에 **main** 추가

3번이 특히 함정입니다. Pages 를 켤 때 GitHub 이 **그 시점의 기본 브랜치 이름**을
허용 목록에 박아 넣습니다. 나중에 기본 브랜치를 바꿔도 그 목록은 따라 바뀌지 않습니다.

> 워크플로에 `enablement: true` 가 들어 있지만 이걸로 Pages 가 켜지지는 않습니다.
> 워크플로 토큰에는 저장소 설정을 바꿀 권한이 없습니다
> (`Resource not accessible by integration`). 권한이 있는 환경에서는 동작하므로 남겨 뒀습니다.

---

## 서명 키

업로드 키는 **저장소에 두지 않습니다.** GitHub Secrets 에 넣으면 CI 가 꺼내 씁니다.

| 이름 | 값 |
|---|---|
| `KEYSTORE_BASE64` | 키스토어 파일을 base64 로 인코딩한 값 |
| `KEYSTORE_STORE_PASSWORD` | 키스토어 비밀번호 |
| `KEYSTORE_KEY_ALIAS` | 키 별칭 |
| `KEYSTORE_KEY_PASSWORD` | 키 비밀번호 |

키를 만드는 법(컴퓨터 없이 브라우저에서 만드는 방법 포함)은
[PLAY_STORE.md 1단계](../PLAY_STORE.md#1-업로드-키-만들기-필수-제일-먼저)에 있습니다.

### 절대 하면 안 되는 것

- **CI 안에서 업로드 키를 만들지 마세요.** 공개 저장소의 Actions 로그와 아티팩트는
  누구나 볼 수 있습니다.
- 초기 커밋에 들어 있던 `keystore/henny-family.jks`(비밀번호 `hennyfamily`)는 삭제했습니다.
  공개 저장소에 노출됐던 키라 **다시 쓰면 안 됩니다.** git 기록에는 남아 있습니다.

### 같은 비밀번호로 다시 만들면 같은 키가 나오나요

**아닙니다.** `keytool` 은 실행할 때마다 난수로 새 키 쌍을 만듭니다.
비밀번호는 씨앗이 아니라 자물쇠입니다. 같은 명령, 같은 비밀번호라도 결과가 매번 다릅니다.

그래서 **키스토어 파일 자체를 백업해 두어야 합니다.**
다만 Play 에 이미 올린 뒤라면 Play 콘솔에서 업로드 키를 재설정할 수 있습니다.

> `-storepass` 와 `-keypass` 를 다르게 줘도 PKCS12 형식은 둘을 따로 두는 것을
> 지원하지 않아 `keytool` 이 `-keypass` 를 무시합니다. 같은 값을 쓰세요.
