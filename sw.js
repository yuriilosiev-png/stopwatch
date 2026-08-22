// Версию повышай при каждом релизе: v2 -> v3 -> v4 ...
const CACHE = 'stopwatch-847b5374';
const ASSETS = [
  './',
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './icon-192-maskable.png',
  './icon-512-maskable.png',
  './favicon.png'
];

self.addEventListener('install', (e) => {
  // НЕ вызываем skipWaiting автоматически — ждём нажатия "Обновить"
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// Кнопка "Обновить" присылает это сообщение -> новый воркер активируется
self.addEventListener('message', (e) => {
  if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const req = e.request;
  const isHTML = req.mode === 'navigate' ||
                 (req.headers.get('accept') || '').includes('text/html');

  if (isHTML) {
    // HTML: сеть впереди (всегда свежая версия), кэш — офлайн-резерв
    e.respondWith(
      fetch(req).then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy));
        return res;
      }).catch(() => caches.match(req).then((c) => c || caches.match('./index.html')))
    );
  } else {
    // Остальное (иконки, манифест): кэш впереди
    e.respondWith(
      caches.match(req).then((cached) => cached || fetch(req))
    );
  }
});
