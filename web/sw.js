/* 서비스 워커
 *
 * "빌드하면 바로 반영"이 목표이므로 네트워크를 먼저 본다.
 * 캐시는 오프라인일 때만 쓰는 예비 수단이다.
 */
const VERSION = '__BUILD__';
const CACHE = `henny-${VERSION}`;
const ASSETS = ['./', 'index.html', 'app.css', 'core.js', 'ui.js', 'icon.svg', 'manifest.webmanifest'];

self.addEventListener('install', (e) => {
  // 새 버전을 받으면 기다리지 않고 바로 넘어간다.
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).catch(() => {}));
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter((n) => n !== CACHE).map((n) => caches.delete(n)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  // 팀 저장소 호출은 절대 캐시하지 않는다. 항상 최신이어야 한다.
  if (url.origin !== self.location.origin) return;

  e.respondWith((async () => {
    try {
      const fresh = await fetch(e.request);
      const cache = await caches.open(CACHE);
      cache.put(e.request, fresh.clone());
      return fresh;
    } catch (err) {
      const hit = await caches.match(e.request);
      if (hit) return hit;
      const index = await caches.match('index.html');
      if (index) return index;
      throw err;
    }
  })());
});
