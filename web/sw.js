/* 서비스 워커
 *
 * "빌드하면 바로 반영"이 목표이므로 네트워크를 먼저 본다.
 * 캐시는 오프라인일 때만 쓰는 예비 수단이다.
 *
 * ---------------------------------------------------------------------------
 * 서비스 워커가 뭔가
 *
 * 페이지와 별개로 도는 스크립트로, 이 오리진에서 나가는 네트워크 요청을 가로챌
 * 수 있다. 브라우저 안에 있는 우리 전용 리버스 프록시라고 보면 가깝다.
 * 페이지가 닫혀도 남아 있고, 한 번 등록되면 다음 방문부터 계속 낀다.
 *
 * 주의할 점: 서비스 워커는 "이전 버전이 계속 살아 있으려는" 성질이 있다.
 * 기본 동작은 열려 있던 탭이 전부 닫힐 때까지 옛 워커가 버티는 것이다.
 * 배포 즉시 반영이 목적인 이 앱과는 정반대라, 아래에서 그 동작을 꺼 둔다.
 *
 * 캐시 우선(cache-first)을 안 쓰는 이유도 같다. 그게 PWA 의 흔한 기본값이지만,
 * 그러면 배포해도 사용자는 옛 화면을 계속 보게 된다.
 */
const VERSION = '__BUILD__';   // 배포 때 실제 값으로 치환된다
const CACHE = `henny-${VERSION}`;
const ASSETS = ['./', 'index.html', 'app.css', 'core.js', 'ui.js', 'icon.svg', 'manifest.webmanifest'];

self.addEventListener('install', (e) => {
  // 새 버전을 받으면 기다리지 않고 바로 넘어간다.
  // 이게 없으면 옛 워커가 탭이 다 닫힐 때까지 버틴다.
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).catch(() => {}));
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    // 캐시 이름에 빌드 버전이 들어 있으므로, 이름이 다른 것은 전부 옛 배포분이다.
    const names = await caches.keys();
    await Promise.all(names.filter((n) => n !== CACHE).map((n) => caches.delete(n)));
    // 이미 열려 있는 화면까지 이 워커가 맡는다. 역시 즉시 반영을 위한 것.
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  // 팀 저장소 호출은 절대 캐시하지 않는다. 항상 최신이어야 한다.
  // (여기서 return 하면 브라우저가 평소대로 처리한다)
  if (url.origin !== self.location.origin) return;

  e.respondWith((async () => {
    try {
      // 네트워크 우선. 성공하면 그 응답을 캐시에 넣어 두기만 한다.
      const fresh = await fetch(e.request);
      const cache = await caches.open(CACHE);
      cache.put(e.request, fresh.clone());   // 응답 본문은 한 번만 읽히므로 복제해서 넣는다
      return fresh;
    } catch (err) {
      // 여기 오는 것은 오프라인일 때다. 그때만 캐시를 꺼내 쓴다.
      const hit = await caches.match(e.request);
      if (hit) return hit;
      const index = await caches.match('index.html');
      if (index) return index;
      throw err;
    }
  })());
});
