#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { performance } from "node:perf_hooks";
import { randomUUID } from "node:crypto";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}
function parseEnv(file) {
  if (!file || !fs.existsSync(file)) return {};
  return Object.fromEntries(fs.readFileSync(file, "utf8").split(/\r?\n/)
    .filter(line => line && !line.trimStart().startsWith("#") && line.includes("="))
    .map(line => {
      const split = line.indexOf("=");
      return [line.slice(0, split).trim(), line.slice(split + 1).trim().replace(/^['"]|['"]$/g, "")];
    }));
}
function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p / 100) - 1)];
}
function round(value) { return Math.round(value * 100) / 100; }

const baseUrl = arg("base", "https://search.5-42-117-227.sslip.io/");
const output = path.resolve(arg("output", "docs/testing/latest/rag-load.json"));
const concurrency = Math.min(2, Math.max(1, Number(arg("concurrency", "1"))));
const iterations = Math.min(20, Math.max(1, Number(arg("iterations", "5"))));
const ephemeral = arg("ephemeral", "false").toLowerCase() === "true";
const inviteFile = arg("invite-file", "");
const cleanupIdentitiesFile = arg("cleanup-identities", "");
const sloProfile = arg("slo-profile", "interactive-default");
const ttftP95LimitMs = Math.max(1, Number(arg("ttft-p95-ms", "5000")));
const totalP95LimitMs = Math.max(1, Number(arg("total-p95-ms", "20000")));
const requestRetries = Math.min(2, Math.max(0, Number(arg("request-retries", "1"))));
const inviteCode = inviteFile && fs.existsSync(path.resolve(inviteFile))
  ? fs.readFileSync(path.resolve(inviteFile), "utf8").trim() : "";
const env = { ...parseEnv(path.resolve(arg("credentials-file", ".env"))), ...process.env };
let credentials = [];
if (env.TEST_USERS_JSON) {
  try { credentials = JSON.parse(env.TEST_USERS_JSON); } catch { throw new Error("TEST_USERS_JSON is not valid JSON"); }
}
if (!credentials.length && (env.TEST_USERNAME || env.APP_ADMIN_USERNAME)
    && (env.TEST_PASSWORD || env.APP_ADMIN_PASSWORD)) {
  credentials = [{ username: env.TEST_USERNAME || env.APP_ADMIN_USERNAME,
    password: env.TEST_PASSWORD || env.APP_ADMIN_PASSWORD }];
}
const ephemeralUsers = [];
if (!credentials.length && ephemeral) {
  for (let index = 0; index < concurrency; index++) {
    const username = `rag-evidence-${Date.now()}-${index}-${randomUUID().slice(0, 8)}@example.test`;
    const password = `Rag!${randomUUID()}aA1`;
    const registration = await fetch(new URL("/auth-api/api/v1/auth/register", baseUrl), {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        firstName: "RAG",
        lastName: "Evidence",
        email: username,
        password1: password,
        password2: password,
        role: "USER",
      }),
    });
    if (![200, 201, 204].includes(registration.status)) {
      throw new Error(`Ephemeral test user registration failed with HTTP ${registration.status}`);
    }
    credentials.push({ username, password });
    ephemeralUsers.push(username);
  }
  if (cleanupIdentitiesFile) {
    const cleanupPath = path.resolve(cleanupIdentitiesFile);
    fs.mkdirSync(path.dirname(cleanupPath), { recursive: true });
    fs.writeFileSync(cleanupPath, JSON.stringify(ephemeralUsers));
  }
}
if (credentials.length < concurrency || credentials.some(item => !item.username || !item.password)) {
  console.error("Provide one distinct account per concurrent client via TEST_USERS_JSON, or one TEST_USERNAME/TEST_PASSWORD for concurrency=1");
  process.exit(2);
}
function hiddenCsrf(html) {
  return html.match(/<input[^>]+name=["']_csrf["'][^>]+value=["']([^"']+)["']/i)?.[1]
    || html.match(/<input[^>]+value=["']([^"']+)["'][^>]+name=["']_csrf["']/i)?.[1];
}
function metaCsrf(html) {
  return html.match(/<meta[^>]+name=["']_csrf["'][^>]+content=["']([^"']+)["']/i)?.[1];
}

async function createSession(credential) {
  const cookieJar = new Map();
  function storeCookies(headers) {
    const values = typeof headers.getSetCookie === "function"
      ? headers.getSetCookie() : [headers.get("set-cookie")].filter(Boolean);
    for (const combined of values) {
      for (const item of combined.split(/,(?=[^;,]+=)/)) {
        const pair = item.split(";", 1)[0];
        const split = pair.indexOf("=");
        if (split > 0) cookieJar.set(pair.slice(0, split).trim(), pair.slice(split + 1).trim());
      }
    }
  }
  async function sessionFetch(url, options = {}) {
    const headers = new Headers(options.headers || {});
    if (cookieJar.size) headers.set("Cookie", [...cookieJar]
      .map(([name, value]) => `${name}=${value}`).join("; "));
    const response = await fetch(new URL(url, baseUrl), { ...options, headers, redirect: "manual" });
    storeCookies(response.headers);
    return response;
  }
  const loginPage = await sessionFetch("/login");
  const loginHtml = await loginPage.text();
  const loginCsrf = hiddenCsrf(loginHtml);
  if (loginPage.status !== 200 || !loginCsrf) throw new Error("Cannot obtain login CSRF token");
  const login = await sessionFetch("/login", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ username: credential.username, password: credential.password, _csrf: loginCsrf }),
  });
  if (![302, 303].includes(login.status) || /\/login\?error/.test(login.headers.get("location") ?? "")) {
    throw new Error(`Authentication failed with HTTP ${login.status}`);
  }
  const app = await sessionFetch("/app");
  const appHtml = await app.text();
  const csrf = metaCsrf(appHtml) || decodeURIComponent(cookieJar.get("XSRF-TOKEN") || "");
  if (app.status !== 200 || !csrf) throw new Error("Authenticated application CSRF token is unavailable");
  if (inviteCode) {
    const accepted = await sessionFetch(`/api/groups/invitations/${encodeURIComponent(inviteCode)}/accept`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrf },
      body: "{}",
    });
    if (accepted.status !== 200) {
      throw new Error(`Group invitation was not accepted: HTTP ${accepted.status}`);
    }
  }
  return { fetch: sessionFetch, csrf };
}

const sessions = await Promise.all(credentials.slice(0, concurrency).map(createSession));

const questions = [
  "Какие основные научные методы представлены в моих источниках?",
  "Сформулируй три подтвержденных источниками вывода.",
  "Какие направления машинного обучения встречаются в материалах?",
  "Какие практические применения описаны в научных статьях?",
  "Сравни подходы из наиболее релевантных источников.",
];

async function one(index, session) {
  const started = performance.now();
  let ttftMs = 0;
  let answer = "";
  let sourceCount = 0;
  let done = null;
  let error = "";
  try {
    const response = await session.fetch("/api/assistant/chat/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", "X-XSRF-TOKEN": session.csrf },
      body: JSON.stringify({ message: questions[index % questions.length], requestId: `evidence-${Date.now()}-${index}` }),
    });
    if (response.status !== 200 || !response.body) {
      return { status: response.status, success: false, totalMs: round(performance.now() - started), error: "HTTP failure" };
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const chunk = await reader.read();
      buffer += decoder.decode(chunk.value || new Uint8Array(), { stream: !chunk.done });
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || "";
      for (const block of blocks) {
        const event = block.match(/^event:\s*(.+)$/m)?.[1]?.trim() || "message";
        const dataText = block.split(/\r?\n/).filter(line => line.startsWith("data:"))
          .map(line => line.slice(5).trimStart()).join("\n");
        let data = null;
        try { data = JSON.parse(dataText); } catch { /* heartbeat/comments */ }
        if (event === "sources") sourceCount = Array.isArray(data?.items) ? data.items.length : 0;
        if (event === "delta" && data?.text) {
          if (!ttftMs) ttftMs = round(performance.now() - started);
          answer += data.text;
        }
        if (event === "done") done = data;
        if (event === "error") error = data?.message || "SSE error";
      }
      if (chunk.done) break;
    }
    const citations = [...answer.matchAll(/\[(\d+)]/g)].map(match => Number(match[1]));
    const validCitations = citations.filter(value => value >= 1 && value <= sourceCount);
    return {
      status: response.status,
      success: Boolean(done?.usedLlm && !error && answer.trim()),
      usedLlm: Boolean(done?.usedLlm),
      retrievalMode: done?.retrievalMode || "unknown",
      sourceCount,
      citationCount: citations.length,
      validCitationCount: validCitations.length,
      hasValidCitation: validCitations.length > 0,
      ttftMs,
      totalMs: round(performance.now() - started),
      serverRetrievalMs: done?.retrievalMs ?? null,
      serverGenerationMs: done?.generationMs ?? null,
      error,
    };
  } catch (failure) {
    return { status: 0, success: false, ttftMs, totalMs: round(performance.now() - started), error: failure.message };
  }
}

async function oneWithRetry(index, session) {
  let sample;
  for (let attempt = 0; attempt <= requestRetries; attempt++) {
    sample = await one(index, session);
    const transient = sample.status === 0 || sample.status >= 500;
    if (!transient || attempt === requestRetries) {
      return { ...sample, attempts: attempt + 1 };
    }
  }
  return sample;
}

const samples = new Array(iterations);
let next = 0;
async function worker(workerIndex) {
  while (next < iterations) {
    const index = next++;
    samples[index] = await oneWithRetry(index, sessions[workerIndex]);
  }
}
await Promise.all(Array.from({ length: concurrency }, (_, index) => worker(index)));
const ttft = samples.filter(item => item.ttftMs > 0).map(item => item.ttftMs);
const totals = samples.map(item => item.totalMs);
const successful = samples.filter(item => item.success).length;
const citationPass = samples.filter(item => item.hasValidCitation).length;
const report = {
  generatedAt: new Date().toISOString(),
  target: new URL("/api/assistant/chat/stream", baseUrl).toString(),
  profile: "authenticated RAG/SSE, read-only",
  accountMode: ephemeralUsers.length ? "ephemeral visitor" : "configured test account",
  inheritedGroupSources: Boolean(inviteCode),
  sloProfile,
  concurrency,
  iterations,
  requestRetries,
  summary: {
    successful,
    failed: iterations - successful,
    successRate: round(successful / iterations),
    validCitationRate: round(citationPass / iterations),
    ttftP50Ms: round(percentile(ttft, 50)),
    ttftP95Ms: round(percentile(ttft, 95)),
    totalP50Ms: round(percentile(totals, 50)),
    totalP95Ms: round(percentile(totals, 95)),
  },
  thresholds: {
    allResponsesUseLlm: successful === iterations,
    validCitationRateAtLeast80Percent: citationPass / iterations >= 0.8,
    ttftP95LimitMs,
    totalP95LimitMs,
    ttftP95WithinSlo: percentile(ttft, 95) <= ttftP95LimitMs,
    totalP95WithinSlo: percentile(totals, 95) <= totalP95LimitMs,
  },
  samples,
};
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
if (!report.thresholds.allResponsesUseLlm
    || !report.thresholds.validCitationRateAtLeast80Percent
    || !report.thresholds.ttftP95WithinSlo
    || !report.thresholds.totalP95WithinSlo) process.exitCode = 1;
