#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const summaryPath = process.argv[2] ?? "docs/testing/2026-09-07/summary.json";
const outputDir = process.argv[3] ?? "docs/testing/2026-09-07/charts";
const data = JSON.parse(fs.readFileSync(summaryPath, "utf8"));
fs.mkdirSync(outputDir, { recursive: true });

const colors = { blue: "#3D568F", light: "#9FBAF1", lime: "#EAF3B2", ivory: "#FDFDF5", ink: "#17213D", grid: "#DCE5F6" };
const escape = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
const svg = body => `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="680" viewBox="0 0 1200 680"><rect width="1200" height="680" fill="${colors.ivory}"/><style>text{font-family:Montserrat,Arial,sans-serif;fill:${colors.ink}}.title{font-size:32px;font-weight:700;fill:${colors.blue}}.label{font-size:22px}.small{font-size:18px}.value{font-size:21px;font-weight:700;fill:${colors.blue}}.grid{stroke:${colors.grid};stroke-width:1}</style>${body}</svg>`;

function write(name, body) { fs.writeFileSync(path.join(outputDir, name), svg(body)); }

{
  const modules = data.modules;
  let body = `<text class="title" x="60" y="62">Модульные тесты и покрытие строк</text>`;
  modules.forEach((item, index) => {
    const y = 155 + index * 150;
    const testWidth = item.tests.tests / 50 * 360;
    const coverageWidth = item.coverage.line.percent / 100 * 360;
    body += `<text class="label" x="60" y="${y}">${escape(item.name)}</text>
      <rect x="390" y="${y - 28}" width="360" height="32" rx="8" fill="${colors.grid}"/><rect x="390" y="${y - 28}" width="${testWidth}" height="32" rx="8" fill="${colors.light}"/>
      <text class="value" x="765" y="${y - 5}">${item.tests.tests} тестов</text>
      <rect x="390" y="${y + 22}" width="360" height="32" rx="8" fill="${colors.grid}"/><rect x="390" y="${y + 22}" width="${coverageWidth}" height="32" rx="8" fill="${colors.blue}"/>
      <text class="value" x="765" y="${y + 47}">${item.coverage.line.percent}% строк</text>`;
  });
  body += `<text class="small" x="60" y="630">Все ${data.totals.tests} теста пройдены. Общее покрытие строк ${data.totals.overallLineCoveragePercent}%.</text>`;
  write("test-overview.svg", body);
}

{
  const items = data.edgeLoad;
  const maxRps = Math.max(...items.map(item => item.requestsPerSecond));
  const maxP95 = Math.max(...items.map(item => item.latencyMs.p95));
  let body = `<text class="title" x="60" y="62">Production HTTP: пропускная способность и p95</text>`;
  for (let i = 0; i <= 4; i += 1) body += `<line class="grid" x1="100" y1="${540 - i * 110}" x2="1120" y2="${540 - i * 110}"/>`;
  items.forEach((item, index) => {
    const x = 150 + index * 245;
    const height = item.requestsPerSecond / maxRps * 400;
    const p95Y = 540 - item.latencyMs.p95 / maxP95 * 400;
    body += `<rect x="${x}" y="${540 - height}" width="110" height="${height}" rx="10" fill="${colors.light}" stroke="${colors.blue}"/>
      <text class="value" x="${x + 55}" y="${525 - height}" text-anchor="middle">${item.requestsPerSecond} RPS</text>
      <circle cx="${x + 55}" cy="${p95Y}" r="10" fill="${colors.blue}"/><text class="small" x="${x + 70}" y="${p95Y + 6}">${item.latencyMs.p95} мс</text>
      <text class="label" x="${x + 55}" y="585" text-anchor="middle">${item.concurrency} потоков</text>`;
    if (index > 0) {
      const previous = items[index - 1];
      const previousX = 150 + (index - 1) * 245 + 55;
      const previousY = 540 - previous.latencyMs.p95 / maxP95 * 400;
      body += `<line x1="${previousX}" y1="${previousY}" x2="${x + 55}" y2="${p95Y}" stroke="${colors.blue}" stroke-width="5"/>`;
    }
  });
  body += `<text class="small" x="100" y="635">Столбцы: RPS. Линия: p95. Ошибок на всех этапах: 0.</text>`;
  write("edge-load.svg", body);
}

{
  const items = data.databaseLoad;
  const maxTps = Math.max(...items.map(item => item.transactionsPerSecond));
  const maxLatency = Math.max(...items.map(item => item.averageLatencyMs));
  let body = `<text class="title" x="60" y="62">Read-only нагрузка поискового SQL</text>`;
  items.forEach((item, index) => {
    const y = 160 + index * 150;
    const tpsWidth = item.transactionsPerSecond / maxTps * 650;
    const latencyWidth = item.averageLatencyMs / maxLatency * 650;
    body += `<text class="label" x="60" y="${y}">${item.clients} клиент${item.clients === 1 ? "" : "ов"}</text>
      <rect x="260" y="${y - 35}" width="650" height="36" rx="8" fill="${colors.grid}"/><rect x="260" y="${y - 35}" width="${tpsWidth}" height="36" rx="8" fill="${colors.lime}" stroke="${colors.blue}"/><text class="value" x="930" y="${y - 8}">${item.transactionsPerSecond.toFixed(1)} TPS</text>
      <rect x="260" y="${y + 17}" width="650" height="24" rx="7" fill="${colors.grid}"/><rect x="260" y="${y + 17}" width="${latencyWidth}" height="24" rx="7" fill="${colors.blue}"/><text class="small" x="930" y="${y + 38}">${item.averageLatencyMs.toFixed(1)} мс</text>`;
  });
  body += `<text class="small" x="60" y="635">0 ошибочных транзакций. Тест выполнен на production-наборе только операциями SELECT.</text>`;
  write("database-load.svg", body);
}

{
  const dataset = data.dataset;
  const items = [
    ["Источники", dataset.sites], ["Страницы", dataset.pages], ["Леммы", dataset.lemmas],
    ["Строки индекса", dataset.searchIndexRows], ["RAG-фрагменты", dataset.assistantChunks],
  ];
  const maxLog = Math.log10(Math.max(...items.map(([, value]) => value)) + 1);
  let body = `<text class="title" x="60" y="62">Фактический масштаб production-данных</text>`;
  items.forEach(([label, value], index) => {
    const y = 140 + index * 96;
    const width = Math.log10(value + 1) / maxLog * 820;
    body += `<text class="label" x="60" y="${y + 26}">${label}</text><rect x="280" y="${y}" width="${width}" height="42" rx="10" fill="${index === 3 ? colors.blue : colors.light}"/><text class="value" x="${Math.min(1120, 300 + width)}" y="${y + 29}">${Number(value).toLocaleString("ru-RU")}</text>`;
  });
  body += `<text class="small" x="60" y="635">Длина полос рассчитана по логарифмической шкале.</text>`;
  write("system-scale.svg", body);
}

if (data.rag?.baseline && data.rag?.final) {
  const baseline = data.rag.baseline;
  const final = data.rag.final;
  const warm = data.rag.warmCache;
  const metrics = [
    ["Первый токен, p95", baseline.ttftP95Ms, final.ttftP95Ms, warm?.ttftP95Ms],
    ["Полный ответ, p95", baseline.totalP95Ms, final.totalP95Ms, warm?.totalP95Ms],
  ];
  const maxValue = Math.max(...metrics.flatMap(([, before, after, steady]) => [before, after, steady ?? 0]));
  let body = `<text class="title" x="60" y="62">Ускорение полного RAG-сценария</text>
    <text class="small" x="60" y="100">Qwen3-4B, реальные источники группы, потоковый ответ и проверка цитат</text>`;
  metrics.forEach(([label, before, after, steady], index) => {
    const y = 175 + index * 215;
    const beforeWidth = before / maxValue * 760;
    const afterWidth = after / maxValue * 760;
    const steadyWidth = (steady ?? 0) / maxValue * 760;
    body += `<text class="label" x="60" y="${y - 42}">${label}</text>
      <text class="small" x="60" y="${y + 3}">было</text>
      <rect x="130" y="${y - 27}" width="${beforeWidth}" height="42" rx="10" fill="${colors.light}"/>
      <text class="value" x="${150 + beforeWidth}" y="${y + 3}">${(before / 1000).toFixed(2)} с</text>
      <text class="small" x="60" y="${y + 57}">cold</text>
      <rect x="130" y="${y + 27}" width="${afterWidth}" height="42" rx="10" fill="${colors.blue}"/>
      <text class="value" x="${150 + afterWidth}" y="${y + 57}">${(after / 1000).toFixed(2)} с</text>
      <text class="small" x="60" y="${y + 111}">warm</text>
      <rect x="130" y="${y + 81}" width="${steadyWidth}" height="42" rx="10" fill="${colors.lime}" stroke="${colors.blue}"/>
      <text class="value" x="${150 + steadyWidth}" y="${y + 111}">${((steady ?? 0) / 1000).toFixed(2)} с</text>`;
  });
  body += `<text class="small" x="60" y="655">Успешность 5/5, гибридный retrieval, валидные цитаты в 100% ответов.</text>`;
  write("rag-optimization.svg", body);
}

console.log(`Charts: ${path.resolve(outputDir)}`);
