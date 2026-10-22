/*
 * Service worker Jarvisa.
 * Strategia: najpierw sieć (zawsze świeża wersja po publikacji na GitHub Pages),
 * a bez internetu — wersja z pamięci podręcznej. Ścieżki są względne, więc działa
 * zarówno pod https://<login>.github.io/jarvis-android/, jak i na localhost.
 */
const CACHE = 'jarvis-v0.2.0-cloud';
const SHELL = [
  './',
  './index.html',
  './styles.css',
  './app.js',
  './cloud-ui.js',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './icons/apple-touch-icon.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('jarvis-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  // Authenticated records and tokens must never be stored in the offline cache.
  if (/^\/(health|chat|tts|memory|tasks|conversations)(\/|$)/.test(new URL(req.url).pathname)) return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // obce adresy (np. przyszłe API) bez cache

  event.respondWith(
    fetch(req)
      .then((res) => {
        if (res.ok && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy));
        }
        return res;
      })
      .catch(() =>
        caches.match(req, { ignoreSearch: true }).then((hit) =>
          hit || (req.mode === 'navigate' ? caches.match('./index.html') : Response.error())
        )
      )
  );
});
