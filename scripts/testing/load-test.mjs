#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { performance } from "node:perf_hooks";

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

function pct(sorted, p) {
  if (!sorted.length) return 0;
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p / 100) - 1)];
}

const baseUrl = arg("base", "https://search.5-42-117-227.sslip.io/");
const endpoint = arg("endpoint", "/api/search?query=%D0%B8%D1%81%D0%BA%D1%83%D1%81%D1%81%D1%82%D0%B2%D0%B5%D0%BD%D0%BD%D1%8B%D0%B9%20%D0%B8%D0%BD%D1%82%D0%B5%D0%BB%D0%BB%D0%B5%D0%BA%D1%82&limit=10");
const concurrency = Math.min(50, Math.max(1, Number(arg("concurrency", "5"))));
const durationSeconds = Math.min(120, Math.max(1, Number(arg("duration", "20"))));
const output = path.resolve(arg("output", `docs/testing/latest/load-c${concurrency}.json`));
const env = parseEnv(path.resolve(arg("credentials-file", ".env")));
const auth = env.APP_ADMIN_USERNAME && env.APP_ADMIN_PASSWORD
  ? `Basic ${Buffer.from(`${env.APP_ADMIN_USERNAME}:${env.APP_ADMIN_PASSWORD}`).toString("base64")}`
  : "";
const headers = { Accept: "application/json" };
if (auth) headers.Authorization = auth;

const target = new URL(endpoint, baseUrl);
const samples = [];
const statusCounts = {};
let transportErrors = 0;
let assertionErrors = 0;

async function one(record = true) {
  const started = performance.now();
  try {
    const response = await fetch(target, { headers, redirect: "manual" });
    const elapsed = performance.now() - started;
    const text = await response.text();
    statusCounts[response.status] = (statusCounts[response.status] ?? 0) + (record ? 1 : 0);
    if (record) samples.push(elapsed);
    if (record && response.status === 200 && response.headers.get("content-type")?.includes("json")) {
      try {
        const json = JSON.parse(text);
        if (json.result !== true) assertionErrors += 1;
      } catch {
        assertionErrors += 1;
      }
    }
  } catch {
    if (record) {
      transportErrors += 1;
      samples.push(performance.now() - started);
    }
  }
}

for (let i = 0; i < Math.min(5, concurrency); i += 1) await one(false);
const startedAt = performance.now();
const deadline = startedAt + durationSeconds * 1000;
async function worker() {
  while (performance.now() < deadline) await one(true);
}
await Promise.all(Array.from({ length: concurrency }, worker));
const elapsedSeconds = (performance.now() - startedAt) / 1000;
const sorted = [...samples].sort((a, b) => a - b);
const successful = statusCounts[200] ?? 0;
const total = samples.length;
const failed = total - successful + assertionErrors;
const report = {
  generatedAt: new Date().toISOString(),
  target: `${baseUrl.replace(/\/$/, "")}${endpoint}`,
  concurrency,
  configuredDurationSeconds: durationSeconds,
  elapsedSeconds: Math.round(elapsedSeconds * 100) / 100,
  requests: total,
  successful,
  failed,
  transportErrors,
  assertionErrors,
  statusCounts,
  requestsPerSecond: Math.round((total / elapsedSeconds) * 100) / 100,
  latencyMs: {
    min: Math.round((sorted[0] ?? 0) * 100) / 100,
    average: Math.round((sorted.reduce((sum, value) => sum + value, 0) / Math.max(sorted.length, 1)) * 100) / 100,
    p50: Math.round(pct(sorted, 50) * 100) / 100,
    p90: Math.round(pct(sorted, 90) * 100) / 100,
    p95: Math.round(pct(sorted, 95) * 100) / 100,
    p99: Math.round(pct(sorted, 99) * 100) / 100,
    max: Math.round((sorted.at(-1) ?? 0) * 100) / 100,
  },
  thresholds: {
    errorRateBelowOnePercent: total > 0 && failed / total < 0.01,
    p95BelowTwoSeconds: pct(sorted, 95) < 2000,
  },
};

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
if (!successful || failed / Math.max(total, 1) >= 0.01) process.exitCode = 1;
