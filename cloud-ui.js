// Same-origin API only. The personal workspace token stays in memory for this tab.
(() => {
  const $ = id => document.getElementById(id);
  let token = '';
  let enabled = false;
  let history = [];
  let conversation = null;
  let busy = false;
  const status = text => { $('cloudStatus').textContent = text; };
  async function api(path, method = 'GET', body) {
    const response = await fetch(path, {
      method, headers: { 'x-jarvis-token': token, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }), cache: 'no-store',
    });
    let data;
    try { data = await response.json(); } catch { throw new Error('Brak API Jarvisa pod tym adresem. Użyj wersji hostowanej przez Worker.'); }
    if (!response.ok) throw new Error(data.error || `Błąd API ${response.status}`);
    return data;
  }
  function report(error) { status(error.message || 'Błąd połączenia'); }
  function button(label, action) {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'btn-small';
    button.textContent = label; button.addEventListener('click', () => Promise.resolve().then(action).catch(report)); return button;
  }
  async function refreshMemory() {
    const data = await api('/memory');
    $('cloudMemory').replaceChildren();
    for (const fact of data.facts) {
      const item = document.createElement('li'); item.textContent = fact.text + ' ';
      item.append(button('Usuń', async () => { if (confirm('Usunąć ten fakt z pamięci?')) { await api('/memory/' + encodeURIComponent(fact.id), 'DELETE'); await refreshMemory(); } }));
      $('cloudMemory').append(item);
    }
  }
  async function refreshConversations() {
    const data = await api('/conversations');
    $('cloudConversations').replaceChildren();
    for (const entry of data.conversations) {
      const item = document.createElement('li'); item.textContent = entry.title + ' ';
      item.append(button('Odczytaj', async () => {
        const data = await api(`/conversations/${entry.id}/messages`);
        $('cloudTranscript').textContent = data.messages.map(m => `${m.role === 'user' ? 'Ty' : 'Jarvis'}: ${m.content}`).join('\n\n');
      }), button('Usuń', async () => {
        if (!confirm('Trwale usunąć rozmowę?')) return;
        await api('/conversations/' + entry.id, 'DELETE');
        if (conversation === entry.id) { conversation = null; history = []; }
        $('cloudTranscript').textContent = ''; await refreshConversations();
      }));
      $('cloudConversations').append(item);
    }
  }
  async function refreshTasks() {
    const data = await api('/tasks');
    $('cloudTasks').replaceChildren();
    for (const task of data.tasks) {
      const item = document.createElement('li'); item.textContent = `${task.title} — ${task.status} `;
      if (task.status === 'queued') item.append(button('Uruchom', async () => {
        status('Zadanie w toku. Możesz je anulować przyciskiem poniżej.');
        const running = api(`/tasks/${task.id}/run`, 'POST');
        const timer = setInterval(() => refreshTasks().catch(report), 2000);
        try { await running; status('Zadanie zakończone.'); } finally { clearInterval(timer); await refreshTasks(); }
      }));
      if (['queued','running'].includes(task.status)) item.append(button('Anuluj', async () => { await api(`/tasks/${task.id}/cancel`, 'POST'); await refreshTasks(); }));
      if (task.result) {
        const result = document.createElement('p');
        try { const data = JSON.parse(task.result); result.textContent = data.reply || data.error; } catch { result.textContent = 'Nieprawidłowy wynik'; }
        item.append(result);
      }
      $('cloudTasks').append(item);
    }
  }
  $('cloudConnect').addEventListener('submit', async e => {
    e.preventDefault(); token = $('cloudToken').value.trim(); $('cloudToken').value = '';
    try {
      const health = await api('/health');
      if (health.auth !== 'ok' || !health.configured) throw new Error('Sprawdź token oraz konfigurację modelu na serwerze.');
      enabled = true; history = []; conversation = null;
      $('version').textContent = 'v0.2.0 · AI: połączone';
      $('chipAi').textContent = `AI: ${health.provider || 'chmura'}`; $('chipAi').dataset.state = 'ok';
      status('Połączono. Wiadomości będą wysyłane do dostawcy AI. Historia chmurowa jest zapisywana po włączeniu opcji poniżej.');
    } catch (error) { enabled = false; token = ''; report(error); }
  });
  $('cloudDisconnect').addEventListener('click', () => {
    enabled = false; token = ''; history = []; conversation = null;
    $('version').textContent = 'v0.2.0 · AI: niepołączone';
    $('chipAi').textContent = 'AI: niepołączone'; $('chipAi').dataset.state = 'off';
    for (const id of ['cloudMemory','cloudTasks','cloudConversations','cloudTranscript']) $(id).replaceChildren();
    status('Rozłączono. Token usunięty z pamięci karty.');
  });
  $('cloudRefresh').addEventListener('click', async () => {
    const results = await Promise.allSettled([refreshMemory(), refreshConversations(), refreshTasks()]);
    const errors = results.filter(r => r.status === 'rejected');
    status(errors.length ? errors.map(e => e.reason.message).join(' · ') : 'Dane odświeżone.');
  });
  $('cloudFactForm').addEventListener('submit', async e => {
    e.preventDefault();
    try { await api('/memory','POST',{ text: $('cloudFact').value }); $('cloudFact').value = ''; await refreshMemory(); } catch (error) { report(error); }
  });
  $('cloudTaskForm').addEventListener('submit', async e => {
    e.preventDefault();
    try { await api('/tasks','POST',{ title: $('cloudTaskTitle').value, kind: $('cloudTaskKind').value }); $('cloudTaskTitle').value = ''; await refreshTasks(); } catch (error) { report(error); }
  });
  window.jarvisCloud = {
    get enabled() { return enabled; },
    async reply(text) {
      if (busy) return 'Poczekaj na zakończenie poprzedniej odpowiedzi.';
      busy = true;
      history.push({ role: 'user', content: text }); history = history.slice(-20);
      try {
        // Optional persistence: errors must not silently suggest that a transcript was saved.
        if ($('cloudSaveHistory').checked) {
          if (!conversation) conversation = (await api('/conversations','POST',{ title: text.slice(0,120) })).id;
          await api(`/conversations/${conversation}/messages`,'POST',{ role: 'user', content: text });
        }
        const data = await api('/chat','POST',{ messages: history });
        if (!data.reply) throw new Error('Model nie zwrócił odpowiedzi.');
        history.push({ role: 'assistant', content: data.reply });
        if ($('cloudSaveHistory').checked && conversation) await api(`/conversations/${conversation}/messages`,'POST',{ role: 'assistant', content: data.reply });
        return data.reply;
      } catch (error) { report(error); return `Nie udało się zakończyć operacji w chmurze: ${error.message}`; }
      finally { busy = false; }
    },
    clear() { history = []; conversation = null; },
  };
})();
