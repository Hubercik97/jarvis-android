import json
import re
import uuid
from pathlib import Path
from playwright.sync_api import sync_playwright

secret_file = (Path(__file__).resolve().parents[1] / 'backend' / '.dev.vars').read_text()
token = re.search(r'^APP_TOKEN=(.+)$', secret_file, re.M).group(1)
with sync_playwright() as p:
    browser = p.chromium.launch(executable_path='/usr/bin/chromium', headless=True, args=['--no-sandbox'])
    context = browser.new_context(locale='pl-PL')
    page = context.new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto('http://127.0.0.1:8787/', wait_until='networkidle')
    page.evaluate('navigator.serviceWorker.ready.then(() => true)')
    page.reload(wait_until='networkidle')
    assert page.evaluate('!!navigator.serviceWorker.controller')
    page.locator('summary').click()
    # Model routes are explicitly mocked: real D1/KV endpoints remain exercised.
    page.route('**/health', lambda route: route.fulfill(json={'ok': True,'configured':True,'auth':'ok','provider':'openai'}))
    page.route('**/chat', lambda route: route.fulfill(json={'reply':'Testowa odpowiedź modelu.'}))
    page.locator('#cloudToken').fill(token)
    page.locator('#cloudConnect button').click()
    page.wait_for_function("document.querySelector('#chipAi').textContent === 'AI: openai'")
    assert page.locator('#cloudToken').input_value() == ''
    page.locator('#cloudSaveHistory').check()
    page.locator('#textInput').fill('Przygotuj plan projektu')
    page.locator('#textForm').evaluate('(form) => form.requestSubmit()')
    page.wait_for_function("document.querySelector('.msg-jarvis')?.textContent.includes('Testowa odpowiedź')")
    page.locator('#cloudRefresh').click()
    page.wait_for_function("document.querySelector('#cloudConversations').textContent.includes('Przygotuj plan projektu')")
    conversation = page.locator('#cloudConversations li').filter(has_text='Przygotuj plan projektu').first
    conversation.get_by_role('button',name='Odczytaj').click()
    page.wait_for_function("document.querySelector('#cloudTranscript').textContent.includes('Testowa odpowiedź')")
    page.on('dialog', lambda dialog: dialog.accept())
    conversation.get_by_role('button',name='Usuń').click()
    page.wait_for_function("!document.querySelector('#cloudConversations').textContent.includes('Przygotuj plan projektu')")
    page.locator('#cloudFact').fill('<img src=x onerror=alert(1)>')
    page.locator('#cloudFactForm button').click()
    page.wait_for_function("document.querySelector('#cloudMemory').textContent.includes('<img')")
    assert page.locator('#cloudMemory img').count() == 0
    page.locator('#cloudMemory li').filter(has_text='<img').get_by_role('button',name='Usuń').click()
    page.wait_for_function("!document.querySelector('#cloudMemory').textContent.includes('<img')")
    task_name = 'Test zadania ' + uuid.uuid4().hex
    page.locator('#cloudTaskTitle').fill(task_name)
    page.locator('#cloudTaskForm button').click()
    page.wait_for_function("name => document.querySelector('#cloudTasks').textContent.includes(name)", arg=task_name)
    task = page.locator('#cloudTasks li').filter(has_text=task_name).last
    task.get_by_role('button',name='Anuluj').click()
    page.wait_for_function("name => Array.from(document.querySelectorAll('#cloudTasks li')).some(e => e.textContent.includes(name + ' — cancelled'))", arg=task_name)
    cached = page.evaluate("""async () => (await Promise.all((await caches.keys()).map(async k => (await (await caches.open(k)).keys()).map(r => new URL(r.url).pathname)))).flat()""")
    assert not any(re.match(r'^/(health|memory|tasks|conversations)(/|$)', path) for path in cached)
    page.locator('#cloudDisconnect').click()
    assert page.evaluate('localStorage.length') == 0
    context.set_offline(True)
    page.reload(wait_until='load')
    page.wait_for_selector('#version')
    assert page.locator('#chipNet').inner_text() == 'Sieć: offline'
    page.locator('#textInput').fill('Powiedz offline działa')
    page.locator('#textForm').evaluate('(form) => form.requestSubmit()')
    page.wait_for_function("document.querySelector('.msg-jarvis')?.textContent.includes('Offline działa')")
    assert not errors, errors
    print('PASS: browser cloud chat (mock AI), real D1 history and KV, deletion, XSS text rendering, task cancel, no API caching, offline shell')
    browser.close()
