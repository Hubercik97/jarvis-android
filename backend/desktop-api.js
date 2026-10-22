import { reserveModelCall } from './cloud.js';
const REPO = /^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/;
async function audioBody(request) {
  const reader = request.body?.getReader();
  if (!reader) throw new Error('body');
  let text = '', size = 0; const decoder = new TextDecoder();
  for (;;) {
    const {value,done} = await reader.read(); if (done) break;
    size += value.length; if (size > 3_000_000) { await reader.cancel(); throw new Error('large'); }
    text += decoder.decode(value, {stream:true});
  }
  return JSON.parse(text + decoder.decode());
}
export async function handleDesktopApi(request, env, url, json) {
  try {
    if (url.pathname === '/stt' && request.method === 'POST') {
      if (!env.OPENAI_API_KEY) return json({error:'Transkrypcja wymaga klucza OpenAI na serwerze'},503);
      const body = await audioBody(request);
      if (!['audio/webm','audio/wav','audio/mp4'].includes(body.mime) || typeof body.audio !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(body.audio) || body.audio.length > 2_800_000 || body.audio.length % 4 !== 0) return json({error:'Nieprawidłowe nagranie (maks. 2 MB)'},400);
      const bytes = Uint8Array.from(atob(body.audio), c => c.charCodeAt(0));
      if (!bytes.length || bytes.length > 2_000_000) return json({error:'Za duże lub puste nagranie'},413);
      if (!await reserveModelCall(env)) return json({error:'Dzienny limit AI wykorzystany'},429);
      const form = new FormData();
      form.append('file',new Blob([bytes],{type:body.mime}),body.mime === 'audio/mp4' ? 'speech.mp4' : body.mime === 'audio/wav' ? 'speech.wav' : 'speech.webm');
      form.append('model',env.STT_MODEL || 'gpt-4o-mini-transcribe'); form.append('language','pl');
      const response = await fetch('https://api.openai.com/v1/audio/transcriptions',{method:'POST',headers:{authorization:`Bearer ${env.OPENAI_API_KEY}`},body:form,signal:AbortSignal.timeout(30_000)});
      if (!response.ok) return json({error:`Transkrypcja niedostępna (HTTP ${response.status})`},502);
      const data = await response.json();
      return typeof data.text === 'string' ? json({text:data.text.slice(0,12000)}) : json({error:'Nieprawidłowa transkrypcja'},502);
    }
    if (url.pathname === '/weather' && request.method === 'GET') {
      const lat = Number(url.searchParams.get('latitude')), lon = Number(url.searchParams.get('longitude'));
      if (!url.searchParams.has('latitude') || !url.searchParams.has('longitude') || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180) return json({error:'Nieprawidłowe współrzędne'},400);
      const response = await fetch(`https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,weather_code&timezone=auto`,{signal:AbortSignal.timeout(8_000)});
      if (!response.ok) return json({error:'Pogoda jest chwilowo niedostępna'},502);
      const data = await response.json();
      if (typeof data.current?.temperature_2m !== 'number') return json({error:'Nieprawidłowe dane pogody'},502);
      return json({temperature:data.current.temperature_2m,code:data.current.weather_code,time:data.current.time,source:'Open-Meteo'});
    }
    if (url.pathname === '/developer/project' && request.method === 'GET') {
      const repo = url.searchParams.get('repo') || '';
      if (!REPO.test(repo) || repo.split('/').some(p => p==='.' || p==='..')) return json({error:'Nieprawidłowe owner/repository'},400);
      const headers = {'user-agent':'Jarvis-Desktop','accept':'application/vnd.github+json',...(env.GITHUB_READ_TOKEN ? {authorization:`Bearer ${env.GITHUB_READ_TOKEN}`} : {})};
      const response = await fetch(`https://api.github.com/repos/${repo}`,{headers,signal:AbortSignal.timeout(10_000),redirect:'error'});
      if (!response.ok) return json({error:`GitHub niedostępny (HTTP ${response.status})`},502);
      const data = await response.json();
      return json({name:data.full_name,branch:data.default_branch,open_issues:data.open_issues_count,pushed_at:data.pushed_at,url:`https://github.com/${repo}`,mode:'read-only'});
    }
    return json({error:'Nie znaleziono'},404);
  } catch (error) {
    if (error.message === 'large') return json({error:'Za duże nagranie'},413);
    if (error instanceof SyntaxError || error.message === 'body') return json({error:'Nieprawidłowy JSON lub nagranie'},400);
    return json({error:'Usługa niedostępna lub nieprawidłowa konfiguracja'},503);
  }
}
