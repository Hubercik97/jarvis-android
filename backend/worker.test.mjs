import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import worker, { normalizeMessages, extractText, toModelMessages, safeEqual, cleanSecret, isHeaderSafe, DEFAULT_MODEL } from "./worker.js";

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

const env = { ANTHROPIC_API_KEY: "test-key", APP_TOKEN: "sekret" };

function chatRequest(messages, token = "sekret") {
  return new Request("https://jarvis.test/chat", {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { "x-jarvis-token": token } : {}) },
    body: JSON.stringify({ messages }),
  });
}

test("health reports configuration without exposing the key", async () => {
  const res = await worker.fetch(new Request("https://jarvis.test/health", { headers: { "x-jarvis-token": "zly" } }), env);
  const body = await res.json();
  assert.equal(res.status, 200);
  assert.deepEqual(body, { ok: true, configured: true, key_format_ok: true, model: DEFAULT_MODEL, auth: "invalid" });
  assert.ok(!JSON.stringify(body).includes("test-key"));
});

test("health without key says not configured", async () => {
  const res = await worker.fetch(new Request("https://jarvis.test/health"), {});
  const body = await res.json();
  assert.equal(body.configured, false);
  assert.equal(body.auth, "missing");
});

test("health with correct token reports ok, without token reports invalid", async () => {
  const ok = await worker.fetch(new Request("https://jarvis.test/health", { headers: { "x-jarvis-token": "sekret" } }), env);
  assert.equal((await ok.json()).auth, "ok");
  const none = await worker.fetch(new Request("https://jarvis.test/health"), env);
  assert.equal((await none.json()).auth, "invalid");
});

test("chat is refused when server has no app token configured (fail-closed)", async () => {
  let called = false;
  globalThis.fetch = async () => {
    called = true;
    return new Response("{}", { status: 200 });
  };
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }], "cokolwiek"), { ANTHROPIC_API_KEY: "test-key" });
  assert.equal(res.status, 503);
  assert.match((await res.json()).error, /tokenu aplikacji/);
  assert.equal(called, false);
});

test("chat without token header is rejected", async () => {
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }], null), env);
  assert.equal(res.status, 401);
});

test("responses carry no CORS wildcard and are not cached", async () => {
  const res = await worker.fetch(new Request("https://jarvis.test/health"), env);
  assert.equal(res.headers.get("access-control-allow-origin"), null);
  assert.equal(res.headers.get("cache-control"), "no-store");
});

test("safeEqual compares exactly", () => {
  assert.equal(safeEqual("abc", "abc"), true);
  assert.equal(safeEqual("abc", "abd"), false);
  assert.equal(safeEqual("abc", "abcd"), false);
  assert.equal(safeEqual("", "a"), false);
  assert.equal(safeEqual("zażółć", "zażółć"), true);
});

test("chat rejects wrong app token", async () => {
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }], "zly"), env);
  assert.equal(res.status, 401);
});

test("chat without model key returns 503", async () => {
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }]), { APP_TOKEN: "sekret" });
  assert.equal(res.status, 503);
  assert.match((await res.json()).error, /klucza API/);
});

test("chat forwards to model with key server-side and returns reply", async () => {
  let captured;
  globalThis.fetch = async (url, init) => {
    captured = { url, init };
    return new Response(JSON.stringify({ content: [{ type: "text", text: "Dzień dobry, Hubert." }] }), { status: 200 });
  };
  const res = await worker.fetch(chatRequest([{ role: "user", content: "Cześć Jarvis" }]), env);
  const body = await res.json();
  assert.equal(res.status, 200);
  assert.equal(body.reply, "Dzień dobry, Hubert.");
  assert.equal(captured.init.headers["x-api-key"], "test-key");
  const sent = JSON.parse(captured.init.body);
  assert.equal(sent.model, DEFAULT_MODEL);
  assert.deepEqual(sent.messages, [{ role: "user", content: "Cześć Jarvis" }]);
  assert.match(sent.system, /po polsku/);
});

test("upstream error is reported without leaking provider body", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({ error: { message: "secret detail" } }), { status: 401 });
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }]), env);
  const body = await res.json();
  assert.equal(res.status, 502);
  assert.match(body.error, /401/);
  assert.ok(!body.error.includes("secret detail"));
});

test("network failure to model returns 502", async () => {
  globalThis.fetch = async () => {
    throw new Error("down");
  };
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }]), env);
  assert.equal(res.status, 502);
});

test("invalid json body returns 400", async () => {
  const req = new Request("https://jarvis.test/chat", { method: "POST", headers: { "x-jarvis-token": "sekret" }, body: "{" });
  const res = await worker.fetch(req, env);
  assert.equal(res.status, 400);
});

test("normalizeMessages merges roles, trims and enforces user start/end", () => {
  const out = normalizeMessages([
    { role: "assistant", content: "start" },
    { role: "user", content: " a " },
    { role: "user", content: "b" },
    { role: "system", content: "hack" },
    { role: "assistant", content: "odp" },
    { role: "user", content: "c" },
    { role: "assistant", content: "koniec" },
  ]);
  assert.deepEqual(out, [
    { role: "user", content: "a\nb" },
    { role: "assistant", content: "odp" },
    { role: "user", content: "c" },
  ]);
  assert.equal(normalizeMessages("x"), null);
  assert.equal(normalizeMessages([{ role: "assistant", content: "x" }]), null);
  assert.equal(normalizeMessages([{ role: "user", content: "x".repeat(20000) }])[0].content.length, 12000);
});

test("extractText joins text blocks only", () => {
  assert.equal(extractText({ content: [{ type: "text", text: "A" }, { type: "tool_use" }, { type: "text", text: "B" }] }), "AB");
  assert.equal(extractText({}), "");
});

test("unknown path returns 404", async () => {
  const res = await worker.fetch(new Request("https://jarvis.test/nope"), env);
  assert.equal(res.status, 404);
});

test("image from user is forwarded as image block", async () => {
  let sent;
  globalThis.fetch = async (url, init) => {
    sent = JSON.parse(init.body);
    return new Response(JSON.stringify({ content: [{ type: "text", text: "Widzę kubek." }] }), { status: 200 });
  };
  const req = chatRequest([{ role: "user", content: "Co to jest?", image: { media_type: "image/jpeg", data: "QUJD" } }]);
  const res = await worker.fetch(req, env);
  assert.equal((await res.json()).reply, "Widzę kubek.");
  assert.equal(sent.messages[0].content[0].type, "image");
  assert.equal(sent.messages[0].content[0].source.data, "QUJD");
  assert.equal(sent.messages[0].content[1].text, "Co to jest?");
});

test("invalid or non-user images are dropped", () => {
  const out = normalizeMessages([
    { role: "user", content: "a", image: { media_type: "text/html", data: "QUJD" } },
    { role: "assistant", content: "b", image: { media_type: "image/jpeg", data: "QUJD" } },
    { role: "user", content: "c", image: { media_type: "image/jpeg", data: "<script>" } },
  ]);
  assert.ok(out.every((m) => !m.image));
  assert.deepEqual(toModelMessages([{ role: "user", content: "x" }]), [{ role: "user", content: "x" }]);
});

test("secrets pasted with invisible characters are cleaned", async () => {
  assert.equal(cleanSecret("  sk-ant-abc\n"), "sk-ant-abc");
  assert.equal(cleanSecret("\u00a0\u200bsk-ant-abc\ufeff\u00a0"), "sk-ant-abc");
  assert.equal(isHeaderSafe("sk-ant-abc"), true);
  assert.equal(isHeaderSafe("sk-ant-ąbc"), false);
  assert.equal(isHeaderSafe("sk ant"), false);

  let sentKey;
  globalThis.fetch = async (url, init) => {
    sentKey = init.headers["x-api-key"];
    return new Response(JSON.stringify({ content: [{ type: "text", text: "OK" }] }), { status: 200 });
  };
  const messyEnv = { ANTHROPIC_API_KEY: "\u00a0test-key\n", APP_TOKEN: "sekret\u200b " };
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }]), messyEnv);
  assert.equal(res.status, 200);
  assert.equal(sentKey, "test-key");
});

test("key with characters invalid in a header gives a clear error, not a crash", async () => {
  let called = false;
  globalThis.fetch = async () => {
    called = true;
    return new Response("{}", { status: 200 });
  };
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }]), { ANTHROPIC_API_KEY: "sk-ant-ąbc", APP_TOKEN: "sekret" });
  assert.equal(res.status, 503);
  assert.match((await res.json()).error, /niedozwolone znaki/);
  assert.equal(called, false);
  const h = await worker.fetch(new Request("https://jarvis.test/health"), { ANTHROPIC_API_KEY: "sk-ant-ąbc", APP_TOKEN: "x" });
  assert.equal((await h.json()).key_format_ok, false);
});

test("connection failure reports only the error type", async () => {
  globalThis.fetch = async () => {
    throw new TypeError("fetch failed: secret sk-ant-xyz");
  };
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }]), env);
  const body = await res.json();
  assert.equal(res.status, 502);
  assert.equal(body.error, "Nie udało się połączyć z modelem AI (TypeError)");
  assert.ok(!body.error.includes("sk-ant"));
});
