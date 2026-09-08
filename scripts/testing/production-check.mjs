#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import tls from "node:tls";
import { performance } from "node:perf_hooks";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

function parseEnv(file) {
  if (!file || !fs.existsSync(file)) return {};
  return Object.fromEntries(fs.readFileSync(file, "utf8")
    .split(/\r?\n/)
    .filter(line => line && !line.trimStart().startsWith("#") && line.includes("="))
    .map(line => {
      const split = line.indexOf("=");
      return [line.slice(0, split).trim(), line.slice(split + 1).trim().replace(/^['"]|['"]$/g, "")];
    }));
}

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

async function request(baseUrl, check, headers = {}) {
  const started = performance.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), check.timeoutMs ?? 30000);
  try {
    const response = await fetch(new URL(check.path, baseUrl), {
      method: check.method ?? "GET",
      headers: { Accept: "application/json, text/html;q=0.9", ...headers, ...(check.headers ?? {}) },
      body: check.body,
      redirect: check.redirect ?? "manual",
      signal: controller.signal,
    });
    const text = await response.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* HTML and empty responses are valid for several checks. */ }
    const expected = Array.isArray(check.expected) ? check.expected : [check.expected];
    const assertionOk = !check.assert || check.assert({ response, text, json });
    return {
      name: check.name,
      method: check.method ?? "GET",
      path: check.path,
      status: response.status,
      expected,
      durationMs: Math.round((performance.now() - started) * 10) / 10,
      passed: expected.includes(response.status) && assertionOk,
      contentType: response.headers.get("content-type") ?? "",
      location: response.headers.get("location") ?? "",
      securityHeaders: Object.fromEntries(["strict-transport-security", "x-content-type-options", "x-frame-options", "referrer-policy"]
        .map(name => [name, response.headers.get(name)])),
      json: check.keepJson ? json : undefined,
      excerpt: check.keepExcerpt ? text.replace(/\s+/g, " ").slice(0, 360) : undefined,
    };
  } catch (error) {
    return {
      name: check.name,
      method: check.method ?? "GET",
      path: check.path,
      status: 0,
      expected: Array.isArray(check.expected) ? check.expected : [check.expected],
      durationMs: Math.round((performance.now() - started) * 10) / 10,
      passed: false,
      error: error.name === "AbortError" ? "timeout" : error.message,
    };
  } finally {
    clearTimeout(timer);
  }
}

function inspectTls(baseUrl) {
  const url = new URL(baseUrl);
  return new Promise(resolve => {
    const socket = tls.connect({ host: url.hostname, port: Number(url.port || 443), servername: url.hostname, timeout: 15000 }, () => {
      const certificate = socket.getPeerCertificate();
      resolve({
        passed: socket.authorized,
        authorized: socket.authorized,
        protocol: socket.getProtocol(),
        subject: certificate.subject?.CN ?? "",
        issuer: certificate.issuer?.O ?? certificate.issuer?.CN ?? "",
        validFrom: certificate.valid_from,
        validTo: certificate.valid_to,
      });
      socket.end();
    });
    socket.once("timeout", () => { socket.destroy(); resolve({ passed: false, error: "timeout" }); });
    socket.once("error", error => resolve({ passed: false, error: error.message }));
  });
}

const baseUrl = arg("base", "https://search.5-42-117-227.sslip.io/");
const output = path.resolve(arg("output", "docs/testing/latest/integration-results.json"));
const env = parseEnv(path.resolve(arg("credentials-file", ".env")));
const credentialsPresent = Boolean(env.APP_ADMIN_USERNAME && env.APP_ADMIN_PASSWORD);
const basic = credentialsPresent
  ? { Authorization: `Basic ${Buffer.from(`${env.APP_ADMIN_USERNAME}:${env.APP_ADMIN_PASSWORD}`).toString("base64")}` }
  : {};

const publicChecks = [
  { name: "Главная страница", path: "/", expected: 200, keepExcerpt: true, assert: ({ text }) => /научн|search/i.test(text) },
  { name: "Политика конфиденциальности", path: "/privacy", expected: 200, assert: ({ text }) => /политик|privacy/i.test(text) },
  { name: "Страница входа", path: "/login", expected: 200, assert: ({ text }) => /войти|sign in/i.test(text) },
  { name: "Captcha через сервис авторизации", path: "/auth-api/api/v1/auth/captcha", expected: 200, keepJson: true,
    assert: ({ json }) => Boolean(json?.secret && String(json?.image ?? "").startsWith("data:image/png;base64,")) },
  { name: "Swagger закрыт для анонимного пользователя", path: "/api-docs", expected: [302, 401],
    assert: ({ response }) => response.status === 401 || /\/login/.test(response.headers.get("location") ?? "") },
  { name: "Сырой OpenAPI авторизации не опубликован", path: "/auth-api/v3/api-docs", expected: 404 },
  { name: "API поиска закрыт для анонимного пользователя", path: "/api/search?query=test", expected: [302, 401] },
  { name: "Метрики LLM закрыты для анонимного пользователя", path: "/api/assistant/metrics", expected: [302, 401] },
];

const results = [];
for (const check of publicChecks) results.push(await request(baseUrl, check));

const authenticated = { available: credentialsPresent, checks: [] };
if (credentialsPresent) {
  const checks = [
    { name: "Поиск по научным источникам", path: "/api/search?query=%D0%B8%D1%81%D0%BA%D1%83%D1%81%D1%81%D1%82%D0%B2%D0%B5%D0%BD%D0%BD%D1%8B%D0%B9%20%D0%B8%D0%BD%D1%82%D0%B5%D0%BB%D0%BB%D0%B5%D0%BA%D1%82&limit=10", expected: 200, keepJson: true,
      assert: ({ json }) => json?.result === true && Number.isInteger(json?.count) },
    { name: "Статус LLM", path: "/api/assistant/status", expected: 200, keepJson: true,
      assert: ({ json }) => json?.result === true && typeof json?.llmConfigured === "boolean" },
    { name: "Тематики источников", path: "/api/assistant/topics", expected: 200, keepJson: true,
      assert: ({ json }) => Array.isArray(json?.topics) },
    { name: "Метрики ассистента", path: "/api/assistant/metrics", expected: 200, keepJson: true,
      assert: ({ json }) => json?.result === true },
    { name: "Статистика", path: "/api/statistics", expected: 200, keepJson: true,
      assert: ({ json }) => json?.result === true },
  ];
  for (const check of checks) authenticated.checks.push(await request(baseUrl, check, basic));
}

const tlsResult = await inspectTls(baseUrl);
const allChecks = [...results, ...authenticated.checks];
const durations = allChecks.map(item => item.durationMs).filter(Number.isFinite);
const report = {
  generatedAt: new Date().toISOString(),
  target: baseUrl,
  environment: "production, read-only checks",
  tls: tlsResult,
  publicChecks: results,
  authenticated,
  summary: {
    passed: allChecks.filter(item => item.passed).length + (tlsResult.passed ? 1 : 0),
    failed: allChecks.filter(item => !item.passed).length + (tlsResult.passed ? 0 : 1),
    total: allChecks.length + 1,
    medianDurationMs: Math.round(percentile(durations, 50) * 10) / 10,
    p95DurationMs: Math.round(percentile(durations, 95) * 10) / 10,
  },
};

for (const item of report.publicChecks) {
  if (item.name === "Captcha через сервис авторизации" && item.json) {
    item.json = {
      secretLength: String(item.json.secret ?? "").length,
      imageFormat: String(item.json.image ?? "").startsWith("data:image/png;base64,") ? "PNG base64" : "unexpected",
    };
  }
}

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(report, null, 2));
for (const item of allChecks) {
  console.log(`${item.passed ? "PASS" : "FAIL"} ${String(item.status).padStart(3)} ${String(item.durationMs).padStart(8)} ms  ${item.name}`);
}
console.log(`${tlsResult.passed ? "PASS" : "FAIL"} TLS ${tlsResult.protocol ?? "n/a"}  сертификат ${tlsResult.subject ?? "n/a"}`);
console.log(`Итого: ${report.summary.passed}/${report.summary.total}; p95=${report.summary.p95DurationMs} ms`);
console.log(`JSON: ${output}`);
process.exitCode = report.summary.failed ? 1 : 0;
