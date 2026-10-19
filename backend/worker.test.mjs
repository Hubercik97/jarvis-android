import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import worker, { normalizeMessages, extractText, toModelMessages, DEFAULT_MODEL } from "./worker.js";

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
  assert.deepEqual(body, { ok: true, configured: true, model: DEFAULT_MODEL, auth: "invalid" });
  assert.ok(!JSON.stringify(body).includes("test-key"));
});

test("health without key says not configured", async () => {
  const res = await worker.fetch(new Request("https://jarvis.test/health"), {});
  const body = await res.json();
  assert.equal(body.configured, false);
  assert.equal(body.auth, "none");
});

test("chat rejects wrong app token", async () => {
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }], "zly"), env);
  assert.equal(res.status, 401);
});

test("chat without key returns 503", async () => {
  const res = await worker.fetch(chatRequest([{ role: "user", content: "hej" }], null), {});
  assert.equal(res.status, 503);
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
