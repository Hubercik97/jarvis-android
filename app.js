/*
 * Jarvis — prototyp PWA (etap 1)
 *
 * Co jest prawdziwe w tym etapie:
 *  - rozpoznawanie polskiej mowy (Web Speech API, pl-PL),
 *  - odpowiedzi głosowe (speechSynthesis, polski głos systemu),
 *  - kilka lokalnych poleceń (godzina, data, status, "powiedz …").
 *
 * Czego jeszcze NIE ma:
 *  - połączenia z modelem AI. Aplikacja mówi to wprost, zamiast udawać.
 *    Model AI zostanie podłączony w kolejnym etapie przez własny backend
 *    (klucz API będzie tylko na serwerze, nigdy w tym pliku).
 */
(() => {
  'use strict';

  const APP_VERSION = '0.1.0';
  const LANG = 'pl-PL';

  // Adres przyszłego backendu (etap 2). Gdy jest null, Jarvis nie udaje AI.
  // Uwaga: tutaj nigdy nie wpisujemy klucza API — tylko adres własnego serwera.
  const AI_ENDPOINT = null;

  const $ = (id) => document.getElementById(id);
  const ui = {
    core: $('coreBtn'), stateLabel: $('stateLabel'), interim: $('interim'),
    log: $('log'), clear: $('clearBtn'),
    form: $('textForm'), input: $('textInput'),
    wake: $('wakeToggle'), install: $('installBtn'),
    chipMic: $('chipMic'), chipVoice: $('chipVoice'), chipAi: $('chipAi'), chipNet: $('chipNet'),
    alert: $('alert'), alertTitle: $('alertTitle'), alertText: $('alertText'),
    alertRetry: $('alertRetry'), alertClose: $('alertClose'),
    version: $('version'),
  };

  const SR = window.SpeechRecognition || window.webkitSpeechRecognition || null;
  const TTS = 'speechSynthesis' in window ? window.speechSynthesis : null;

  const app = {
    state: 'idle',
    micPermission: 'unknown', // granted | prompt | denied | unsupported | unknown
    rec: null,               // aktywna sesja rozpoznawania (pojedyncze polecenie)
    plVoice: null,
    lastReply: '',
    currentUtterance: null,  // trzymamy referencję, żeby Chrome nie zgubił zdarzenia onend
  };

  const watch = {
    enabled: false, rec: null, busy: false, restartTimer: null,
    starts: [], wakeLock: null,
  };

  // ------------------------------------------------------------------ UI
  const LABELS = {
    idle: 'Gotowy. Naciśnij i mów.',
    listening: 'Słucham…',
    thinking: 'Przetwarzam…',
    speaking: 'Mówię… (dotknij, aby przerwać)',
    watching: 'Czuwam. Powiedz „Jarvis” i polecenie.',
    disabled: 'Rozpoznawanie mowy niedostępne — użyj pola tekstowego.',
  };

  function setState(state, label) {
    app.state = state;
    ui.core.dataset.state = state;
    ui.stateLabel.textContent = label || LABELS[state] || '';
    ui.core.setAttribute('aria-label',
      state === 'listening' ? 'Zakończ słuchanie' :
      state === 'speaking' ? 'Przerwij odpowiedź' : 'Naciśnij i mów');
  }

  function setChip(chip, state, text) {
    chip.dataset.state = state;
    chip.textContent = text;
  }

  function addMessage(role, text) {
    const li = document.createElement('li');
    li.className = 'msg msg-' + role;
    if (role !== 'system') {
      const who = document.createElement('small');
      who.textContent = role === 'user' ? 'TY' : 'JARVIS';
      li.appendChild(who);
    }
    li.appendChild(document.createTextNode(text));
    ui.log.appendChild(li);
    ui.log.scrollTop = ui.log.scrollHeight;
  }

  function showAlert({ level = 'error', title, text, retry }) {
    ui.alert.dataset.level = level;
    ui.alertTitle.textContent = title;
    ui.alertText.textContent = text;
    ui.alertRetry.hidden = !retry;
    ui.alertRetry.onclick = retry ? () => { hideAlert(); retry(); } : null;
    ui.alert.hidden = false;
  }
  function hideAlert() { ui.alert.hidden = true; }
  ui.alertClose.addEventListener('click', hideAlert);

  const HOW_TO_UNBLOCK =
    'Jak to naprawić na Androidzie:\n' +
    '1) W Chrome dotknij ikony obok adresu strony → Uprawnienia → Mikrofon → Zezwól.\n' +
    '   (W zainstalowanej aplikacji: Chrome → Ustawienia → Ustawienia witryn → Mikrofon.)\n' +
    '2) Sprawdź też uprawnienie systemowe: Ustawienia Androida → Aplikacje → Chrome → Uprawnienia → Mikrofon → Zezwalaj podczas używania.\n' +
    '3) Wróć tutaj i naciśnij „Spróbuj ponownie”.';

  // ------------------------------------------------------------ Mikrofon
  function renderMicChip() {
    const map = {
      granted: ['ok', 'Mikrofon: dostęp przyznany'],
      prompt: ['warn', 'Mikrofon: zapyta o zgodę'],
      denied: ['error', 'Mikrofon: zablokowany'],
      unsupported: ['error', 'Mowa: brak obsługi'],
      insecure: ['error', 'Mikrofon: wymaga HTTPS'],
      unknown: ['warn', 'Mikrofon: nieznany stan'],
    };
    const [s, t] = map[app.micPermission] || map.unknown;
    setChip(ui.chipMic, s, t);
  }

  async function watchMicPermission() {
    if (!navigator.permissions || !navigator.permissions.query) return;
    try {
      const status = await navigator.permissions.query({ name: 'microphone' });
      app.micPermission = status.state;
      renderMicChip();
      status.onchange = () => {
        app.micPermission = status.state;
        renderMicChip();
        if (status.state === 'granted') hideAlert();
      };
    } catch (_) { /* przeglądarka nie zna tego uprawnienia — nic się nie dzieje */ }
  }

  /** Prosi o mikrofon (jeśli trzeba) i zwraca true/false. Wyświetla czytelny błąd. */
  async function ensureMic() {
    if (app.micPermission === 'granted') return true;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      // Starsze przeglądarki: pozwalamy rozpoznawaniu mowy samemu zapytać o zgodę.
      return true;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.getTracks().forEach((t) => t.stop()); // od razu zwalniamy mikrofon dla rozpoznawania mowy
      app.micPermission = 'granted';
      renderMicChip();
      await new Promise((r) => setTimeout(r, 150));
      return true;
    } catch (err) {
      handleMicError(err && err.name);
      return false;
    }
  }

  function handleMicError(name) {
    switch (name) {
      case 'NotAllowedError':
      case 'PermissionDeniedError':
      case 'SecurityError':
        app.micPermission = 'denied';
        renderMicChip();
        showAlert({ title: 'Brak dostępu do mikrofonu', text: 'Jarvis nie może Cię usłyszeć, bo dostęp do mikrofonu został odrzucony.\n\n' + HOW_TO_UNBLOCK, retry: () => startListening() });
        break;
      case 'NotFoundError':
      case 'DevicesNotFoundError':
        showAlert({ title: 'Nie znaleziono mikrofonu', text: 'Telefon nie zgłasza żadnego mikrofonu. Jeśli używasz słuchawek Bluetooth, odłącz je i spróbuj ponownie.', retry: () => startListening() });
        break;
      case 'NotReadableError':
      case 'TrackStartError':
        showAlert({ title: 'Mikrofon jest zajęty', text: 'Inna aplikacja (np. połączenie, dyktafon, Asystent Google) używa teraz mikrofonu. Zamknij ją i spróbuj ponownie.', retry: () => startListening() });
        break;
      default:
        showAlert({ title: 'Nie udało się włączyć mikrofonu', text: 'Błąd: ' + (name || 'nieznany') + '. Spróbuj ponownie lub uruchom stronę ponownie.', retry: () => startListening() });
    }
    setState('idle');
  }

  /** Błędy zwracane przez rozpoznawanie mowy (SpeechRecognitionErrorEvent.error). */
  function handleRecognitionError(code) {
    switch (code) {
      case 'no-speech':
        setState('idle', 'Nic nie usłyszałem. Spróbuj jeszcze raz.');
        return;
      case 'aborted':
        return;
      case 'not-allowed':
        app.micPermission = 'denied';
        renderMicChip();
        showAlert({ title: 'Brak dostępu do mikrofonu', text: 'Rozpoznawanie mowy zostało zablokowane.\n\n' + HOW_TO_UNBLOCK, retry: () => startListening() });
        break;
      case 'service-not-allowed':
        showAlert({ title: 'Usługa rozpoznawania mowy jest niedostępna', text: 'Przeglądarka nie pozwala użyć usługi mowy. Użyj Google Chrome na Androidzie i upewnij się, że aplikacja Google jest włączona (Ustawienia → Aplikacje → Google).' });
        break;
      case 'audio-capture':
        showAlert({ title: 'Nie można przechwycić dźwięku', text: 'Mikrofon nie działa albo używa go inna aplikacja. Zamknij ją i spróbuj ponownie.', retry: () => startListening() });
        break;
      case 'network':
        showAlert({ level: 'warn', title: 'Brak połączenia z usługą mowy', text: 'Rozpoznawanie mowy w Chrome na Androidzie zwykle wymaga internetu. Sprawdź połączenie i spróbuj ponownie.', retry: () => startListening() });
        break;
      case 'language-not-supported':
        showAlert({ title: 'Język polski niedostępny', text: 'Ta przeglądarka nie rozpoznaje mowy po polsku. Użyj Google Chrome.' });
        break;
      default:
        showAlert({ level: 'warn', title: 'Błąd rozpoznawania mowy', text: 'Kod błędu: ' + code + '. Spróbuj ponownie.', retry: () => startListening() });
    }
    setState('idle');
  }

  function createRecognition({ continuous }) {
    const rec = new SR();
    rec.lang = LANG;
    rec.interimResults = true;
    rec.continuous = continuous;
    rec.maxAlternatives = 1;
    return rec;
  }

  // ------------------------------------------- Słuchanie (jedno polecenie)
  async function startListening({ fromWake = false } = {}) {
    if (!SR) { setState('disabled'); return; }
    if (app.state === 'listening' && app.rec) { app.rec.stop(); return; }
    stopSpeaking();
    hideAlert();

    if (watch.enabled) pauseWatch();

    const ok = await ensureMic();
    if (!ok) { finishInteraction(); return; }

    const rec = createRecognition({ continuous: false });
    app.rec = rec;
    let finalText = '';
    let failed = false;

    rec.onstart = () => setState('listening', fromWake ? 'Słucham polecenia…' : LABELS.listening);
    rec.onresult = (e) => {
      let interim = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r.isFinal) finalText += r[0].transcript;
        else interim += r[0].transcript;
      }
      ui.interim.textContent = (finalText + ' ' + interim).trim();
    };
    rec.onerror = (e) => {
      failed = true;
      if (e.error === 'no-speech' && watch.enabled) { setState('watching'); return; }
      handleRecognitionError(e.error);
    };
    rec.onend = () => {
      app.rec = null;
      ui.interim.textContent = '';
      const text = finalText.trim();
      if (text) handleUtterance(text);
      else {
        if (!failed && app.state === 'listening') setState('idle', 'Nic nie usłyszałem. Spróbuj jeszcze raz.');
        finishInteraction();
      }
    };

    try {
      rec.start();
    } catch (err) {
      app.rec = null;
      showAlert({ level: 'warn', title: 'Nie udało się rozpocząć słuchania', text: 'Spróbuj ponownie za chwilę.', retry: () => startListening() });
      finishInteraction();
    }
  }

  /** Po zakończonej wymianie: wróć do czuwania albo do stanu gotowości. */
  function finishInteraction() {
    if (watch.enabled) {
      watch.busy = false;
      if (!document.hidden) {
        setState('watching');
        scheduleWatchRestart(400);
      }
    } else if (app.state !== 'idle') {
      setState('idle');
    }
  }

  async function handleUtterance(text) {
    addMessage('user', text);
    setState('thinking');
    let reply;
    try {
      reply = await respond(text);
    } catch (err) {
      reply = 'Wystąpił błąd podczas przygotowywania odpowiedzi.';
    }
    if (reply) {
      addMessage('jarvis', reply);
      app.lastReply = reply;
      await speak(reply);
    }
    finishInteraction();
  }

  // -------------------------------------------------------------- Mowa
  function pickPolishVoice() {
    if (!TTS) return null;
    const voices = TTS.getVoices();
    const pl = voices.filter((v) => (v.lang || '').toLowerCase().replace('_', '-').startsWith('pl'));
    if (!pl.length) return null;
    return pl.find((v) => /google/i.test(v.name)) || pl.find((v) => v.localService) || pl[0];
  }

  function refreshVoice() {
    if (!TTS) { setChip(ui.chipVoice, 'error', 'Głos: brak obsługi'); return; }
    app.plVoice = pickPolishVoice();
    const any = TTS.getVoices().length > 0;
    if (app.plVoice) setChip(ui.chipVoice, 'ok', 'Głos: polski');
    else if (any) setChip(ui.chipVoice, 'warn', 'Głos: brak polskiego');
    else setChip(ui.chipVoice, 'warn', 'Głos: domyślny systemu');
  }

  function speak(text) {
    return new Promise((resolve) => {
      if (!TTS) { resolve(); return; }
      TTS.cancel();
      const u = new SpeechSynthesisUtterance(text);
      u.lang = LANG;
      if (app.plVoice) u.voice = app.plVoice;
      u.rate = 1.0;
      u.pitch = 0.95;
      app.currentUtterance = u;
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        clearTimeout(guard);
        if (app.currentUtterance === u) app.currentUtterance = null;
        resolve();
      };
      // Zabezpieczenie: niektóre wersje Chrome na Androidzie gubią zdarzenie "end".
      const guard = setTimeout(finish, 4000 + text.length * 90);
      u.onstart = () => setState('speaking');
      u.onend = finish;
      u.onerror = finish;
      TTS.speak(u);
      if (TTS.paused) TTS.resume();
    });
  }

  function stopSpeaking() {
    if (TTS && (TTS.speaking || TTS.pending)) TTS.cancel();
  }

  // -------------------------------------------- Lokalne polecenia (bez AI)
  function norm(s) {
    return Array.from(s).map((ch) => {
      if (ch === 'ł' || ch === 'Ł') return 'l';
      const c = ch.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
      return c.length === 1 ? c : (c[0] || ' ');
    }).join('');
  }

  const fmtTime = () => new Date().toLocaleTimeString(LANG, { hour: '2-digit', minute: '2-digit' });
  const fmtDate = () => new Intl.DateTimeFormat(LANG, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(new Date());

  function greeting() {
    const h = new Date().getHours();
    if (h < 5 || h >= 18) return 'Dobry wieczór.';
    return 'Dzień dobry.';
  }

  function statusReport() {
    const mic = { granted: 'mikrofon działa', prompt: 'mikrofon czeka na zgodę', denied: 'mikrofon jest zablokowany' }[app.micPermission] || 'stan mikrofonu jest nieznany';
    const voice = app.plVoice ? 'mam polski głos' : 'nie mam polskiego głosu w systemie';
    const net = navigator.onLine ? 'internet jest dostępny' : 'brak internetu';
    return `Raport: ${mic}, ${voice}, ${net}. Model AI nie jest jeszcze podłączony.`;
  }

  const NO_AI_REPLY = 'Nie jestem jeszcze połączony z modelem AI, więc nie odpowiem na to pytanie. Powiedz „co potrafisz”, aby usłyszeć dostępne polecenia.';

  async function respond(text) {
    const t = norm(text).replace(/[.,!?;:„”"']/g, ' ').replace(/\s+/g, ' ').trim();

    if (/^(stop|cisza|przestan|wystarczy|anuluj)\b/.test(t)) return '';
    if (/\b(ktora|jaka) (jest )?godzina\b|\bgodzina\b.*\bjest\b|^godzina$/.test(t)) return `Jest ${fmtTime()}.`;
    if (/\b(jaki|ktory) (jest )?(dzis|dzisiaj|dzien)|\bjaka (jest )?(dzis |dzisiaj )?data\b|\bdzisiejsza data\b|^data$/.test(t)) return `Dziś jest ${fmtDate()}.`;
    const m = text.match(/^\s*(?:powiedz|powtórz za mną|przeczytaj)\s+(?:mi\s+)?(.+)$/i);
    if (m) { const s = m[1].trim(); return s.charAt(0).toUpperCase() + s.slice(1); }
    if (/\bpowtorz\b/.test(t)) return app.lastReply || 'Nie mam jeszcze czego powtórzyć.';
    if (/\b(kim jestes|jak sie nazywasz|przedstaw sie)\b/.test(t)) return 'Jestem Jarvis, Twój asystent głosowy. To wczesny prototyp: rozumiem polską mowę i odpowiadam głosem, ale model AI zostanie podłączony w kolejnym etapie.';
    if (/\b(co potrafisz|co umiesz|pomoc|pomocy|jakie (masz )?polecenia)\b/.test(t)) return 'Na razie potrafię: podać godzinę i datę, zrobić raport stanu, powtórzyć ostatnią odpowiedź i przeczytać tekst po słowie „powiedz”. Rozmowa z modelem AI pojawi się w kolejnym etapie.';
    if (/\b(status|raport|diagnostyka|test systemow)\b/.test(t)) return statusReport();
    if (/\b(dziekuje|dzieki)\b/.test(t)) return 'Proszę bardzo.';
    if (/^(czesc|hej|siema|witaj|dzien dobry|dobry wieczor|halo|jarvis)\b/.test(t)) return `${greeting()} W czym mogę pomóc?`;

    if (AI_ENDPOINT) {
      // Etap 2: tutaj trafi zapytanie do własnego backendu (bez kluczy w przeglądarce).
    }
    return NO_AI_REPLY;
  }

  // ---------------------------------------------- Czuwanie na „Jarvis”
  const WAKE_RE = /\b(jarvis|jarvisie|jarwis|jarwisie|dzarwis|dzarvis|dzarwisie|dzarvisie|dzarwiz|dzerwis|dzervis|zarwis|jervis|jarvi)\b/;

  function matchWake(text) {
    const n = norm(text);
    const m = WAKE_RE.exec(n);
    if (!m) return null;
    const rest = Array.from(text).slice(m.index + m[0].length).join('').replace(/^[\s,.:;!?-]+/, '');
    return { rest };
  }

  async function startWatch() {
    if (!SR) {
      ui.wake.checked = false;
      showAlert({ title: 'Czuwanie niedostępne', text: 'Ta przeglądarka nie obsługuje rozpoznawania mowy. Użyj Google Chrome na Androidzie.' });
      return;
    }
    const ok = await ensureMic();
    if (!ok) { ui.wake.checked = false; return; }
    watch.enabled = true;
    watch.busy = false;
    watch.starts = [];
    await acquireWakeLock();
    addMessage('system', 'Czuwanie włączone. Ekran pozostanie włączony, dopóki aplikacja jest otwarta.');
    setState('watching');
    runWatchRec();
  }

  function stopWatch(reason) {
    watch.enabled = false;
    ui.wake.checked = false;
    clearTimeout(watch.restartTimer);
    if (watch.rec) { try { watch.rec.abort(); } catch (_) {} watch.rec = null; }
    releaseWakeLock();
    if (reason) addMessage('system', reason);
    if (app.state === 'watching') setState('idle');
  }

  /** Wstrzymuje nasłuch czuwania na czas jednej rozmowy. */
  function pauseWatch() {
    watch.busy = true;
    clearTimeout(watch.restartTimer);
    if (watch.rec) { try { watch.rec.abort(); } catch (_) {} watch.rec = null; }
  }

  function scheduleWatchRestart(ms) {
    clearTimeout(watch.restartTimer);
    watch.restartTimer = setTimeout(runWatchRec, ms);
  }

  function runWatchRec() {
    if (!watch.enabled || watch.busy || watch.rec || document.hidden) return;

    // Ochrona przed pętlą: jeśli przeglądarka kończy nasłuch natychmiast, wyłączamy czuwanie.
    const now = Date.now();
    watch.starts = watch.starts.filter((t) => now - t < 20000);
    watch.starts.push(now);
    if (watch.starts.length > 8) {
      stopWatch('Czuwanie wyłączone: przeglądarka ciągle przerywa nasłuch. Użyj przycisku mikrofonu.');
      return;
    }

    const rec = createRecognition({ continuous: true });
    watch.rec = rec;

    rec.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        const transcript = r[0].transcript;
        if (!r.isFinal) {
          if (matchWake(transcript)) ui.interim.textContent = transcript;
          continue;
        }
        const hit = matchWake(transcript);
        if (!hit) continue;
        ui.interim.textContent = '';
        watch.starts = [];
        pauseWatch();
        if (hit.rest && hit.rest.length > 1) {
          handleUtterance(hit.rest);
        } else {
          speak('Słucham.').then(() => setTimeout(() => startListening({ fromWake: true }), 250));
        }
        return;
      }
    };

    rec.onerror = (e) => {
      if (e.error === 'no-speech' || e.error === 'aborted') return;
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed' || e.error === 'audio-capture') {
        stopWatch();
        handleRecognitionError(e.error);
      } else if (e.error === 'network') {
        stopWatch('Czuwanie wyłączone: brak połączenia z usługą rozpoznawania mowy.');
      }
    };

    rec.onend = () => {
      if (watch.rec === rec) watch.rec = null;
      if (watch.enabled && !watch.busy && !document.hidden) scheduleWatchRestart(300);
    };

    try { rec.start(); } catch (_) { watch.rec = null; scheduleWatchRestart(800); }
  }

  async function acquireWakeLock() {
    if (!('wakeLock' in navigator)) return;
    try {
      watch.wakeLock = await navigator.wakeLock.request('screen');
      watch.wakeLock.addEventListener('release', () => { watch.wakeLock = null; });
    } catch (_) { /* np. tryb oszczędzania baterii — czuwanie działa dalej, ale ekran może zgasnąć */ }
  }
  function releaseWakeLock() {
    if (watch.wakeLock) { watch.wakeLock.release().catch(() => {}); watch.wakeLock = null; }
  }

  document.addEventListener('visibilitychange', () => {
    if (!watch.enabled) return;
    if (document.hidden) {
      clearTimeout(watch.restartTimer);
      if (watch.rec) { try { watch.rec.abort(); } catch (_) {} watch.rec = null; }
      addMessage('system', 'Czuwanie wstrzymane — aplikacja przeszła w tło.');
    } else {
      acquireWakeLock();
      addMessage('system', 'Czuwanie wznowione.');
      if (!watch.busy) { setState('watching'); scheduleWatchRestart(300); }
    }
  });

  // ------------------------------------------------------ Instalacja PWA
  let deferredPrompt = null;
  const isStandalone = () => window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    if (!isStandalone()) ui.install.hidden = false;
  });
  ui.install.addEventListener('click', async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    try { await deferredPrompt.userChoice; } catch (_) {}
    deferredPrompt = null;
    ui.install.hidden = true;
  });
  window.addEventListener('appinstalled', () => {
    ui.install.hidden = true;
    addMessage('system', 'Jarvis został dodany do ekranu głównego.');
  });

  // ---------------------------------------------------------- Sieć
  function renderNet() {
    if (navigator.onLine) setChip(ui.chipNet, 'ok', 'Sieć: online');
    else setChip(ui.chipNet, 'warn', 'Sieć: offline');
  }
  window.addEventListener('online', renderNet);
  window.addEventListener('offline', renderNet);

  // ------------------------------------------------------- Zdarzenia UI
  ui.core.addEventListener('click', () => {
    if (app.state === 'disabled') { ui.input.focus(); return; }
    if (app.state === 'speaking') { stopSpeaking(); return; }
    if (app.state === 'thinking') return;
    startListening();
  });

  ui.form.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = ui.input.value.trim();
    if (!text) return;
    ui.input.value = '';
    if (app.rec) { try { app.rec.abort(); } catch (_) {} }
    if (watch.enabled) pauseWatch();
    stopSpeaking();
    handleUtterance(text);
  });

  ui.clear.addEventListener('click', () => { ui.log.innerHTML = ''; app.lastReply = ''; });

  ui.wake.addEventListener('change', () => {
    if (ui.wake.checked) startWatch();
    else stopWatch('Czuwanie wyłączone.');
  });

  // --------------------------------------------------------- Start
  function init() {
    ui.version.textContent = `v${APP_VERSION} · etap 1 · AI: niepołączone`;
    renderNet();

    if (!window.isSecureContext) {
      app.micPermission = 'insecure';
      renderMicChip();
      showAlert({ title: 'Wymagane połączenie HTTPS', text: 'Mikrofon działa tylko na stronach HTTPS (np. GitHub Pages) albo na localhost. Otwórz aplikację pod adresem https://…' });
    }

    if (!SR) {
      app.micPermission = 'unsupported';
      renderMicChip();
      setState('disabled');
      ui.wake.disabled = true;
      showAlert({ level: 'warn', title: 'Brak rozpoznawania mowy', text: 'Ta przeglądarka nie obsługuje rozpoznawania mowy. Na Androidzie otwórz Jarvisa w Google Chrome. Możesz też pisać polecenia w polu tekstowym.' });
    } else {
      if (window.isSecureContext) { app.micPermission = 'unknown'; renderMicChip(); }
      watchMicPermission();
      setState('idle');
    }

    if (TTS) {
      refreshVoice();
      TTS.addEventListener ? TTS.addEventListener('voiceschanged', refreshVoice) : (TTS.onvoiceschanged = refreshVoice);
      setTimeout(refreshVoice, 1500);
    } else {
      refreshVoice();
    }

    if ('serviceWorker' in navigator && window.isSecureContext) {
      navigator.serviceWorker.register('sw.js').catch(() => {});
    }
  }

  init();
})();
