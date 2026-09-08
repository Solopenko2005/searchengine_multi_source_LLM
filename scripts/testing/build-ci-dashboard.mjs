#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const artifactsRoot = path.resolve(process.argv[2] ?? ".ci-artifacts");
const outputRoot = path.resolve(process.argv[3] ?? ".ci-site");
const repository = process.env.GITHUB_REPOSITORY ?? "Solopenko2005/searchengine_multi_source_LLM";
const revision = process.env.GITHUB_SHA ?? "main";
const repositoryUrl = `https://github.com/${repository}`;

const modules = [
  { id: "search", name: "Поисковая система и LLM", source: "Searchengine_1" },
  { id: "authorization", name: "Авторизация", source: "authorization" },
  { id: "email", name: "Отправка e-mail", source: "emailsender" },
];

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function readTests(directory) {
  if (!fs.existsSync(directory)) return { tests: 0, failures: 0, errors: 0, skipped: 0, durationSeconds: 0 };
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0, durationSeconds: 0 };
  for (const file of fs.readdirSync(directory).filter(name => /^TEST-.*\.xml$/.test(name))) {
    const xml = fs.readFileSync(path.join(directory, file), "utf8");
    const suite = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? "";
    const number = name => Number(suite.match(new RegExp(`${name}="([0-9.]+)"`))?.[1] ?? 0);
    totals.tests += number("tests");
    totals.failures += number("failures");
    totals.errors += number("errors");
    totals.skipped += number("skipped");
    totals.durationSeconds += number("time");
  }
  totals.durationSeconds = Math.round(totals.durationSeconds * 1000) / 1000;
  return totals;
}

function readCoverage(jacocoDirectory) {
  const xmlPath = path.join(jacocoDirectory, "jacoco.xml");
  if (fs.existsSync(xmlPath)) {
    const xml = fs.readFileSync(xmlPath, "utf8");
    const counters = {};
    for (const match of xml.matchAll(/<counter type="([A-Z]+)" missed="(\d+)" covered="(\d+)"\s*\/>/g)) {
      counters[match[1]] = { missed: Number(match[2]), covered: Number(match[3]) };
    }
    const line = counters.LINE ?? { missed: 0, covered: 0 };
    const branch = counters.BRANCH ?? { missed: 0, covered: 0 };
    const lineTotal = line.missed + line.covered;
    const branchTotal = branch.missed + branch.covered;
    return {
      linePercent: lineTotal ? Math.round(line.covered * 1000 / lineTotal) / 10 : 0,
      branchPercent: branchTotal ? Math.round(branch.covered * 1000 / branchTotal) / 10 : 0,
      lineCovered: line.covered,
      lineTotal,
    };
  }

  const csvPath = path.join(jacocoDirectory, "jacoco.csv");
  if (!fs.existsSync(csvPath)) return { linePercent: 0, branchPercent: 0, lineCovered: 0, lineTotal: 0 };
  const rows = fs.readFileSync(csvPath, "utf8").trim().split(/\r?\n/);
  const header = rows.shift().split(",");
  const index = Object.fromEntries(header.map((name, position) => [name, position]));
  let lineMissed = 0;
  let lineCovered = 0;
  let branchMissed = 0;
  let branchCovered = 0;
  for (const row of rows) {
    const values = row.split(",");
    lineMissed += Number(values[index.LINE_MISSED] ?? 0);
    lineCovered += Number(values[index.LINE_COVERED] ?? 0);
    branchMissed += Number(values[index.BRANCH_MISSED] ?? 0);
    branchCovered += Number(values[index.BRANCH_COVERED] ?? 0);
  }
  const lineTotal = lineMissed + lineCovered;
  const branchTotal = branchMissed + branchCovered;
  return {
    linePercent: lineTotal ? Math.round(lineCovered * 1000 / lineTotal) / 10 : 0,
    branchPercent: branchTotal ? Math.round(branchCovered * 1000 / branchTotal) / 10 : 0,
    lineCovered,
    lineTotal,
  };
}

fs.rmSync(outputRoot, { recursive: true, force: true });
fs.mkdirSync(outputRoot, { recursive: true });

const results = modules.map(module => {
  const root = path.join(artifactsRoot, module.id);
  const tests = readTests(path.join(root, "surefire"));
  const jacocoDirectory = fs.existsSync(path.join(root, "jacoco")) ? path.join(root, "jacoco") : root;
  const coverage = readCoverage(jacocoDirectory);
  if (fs.existsSync(path.join(jacocoDirectory, "index.html"))) {
    fs.cpSync(jacocoDirectory, path.join(outputRoot, "coverage", module.id), { recursive: true });
  }
  return { ...module, tests, coverage, passed: tests.tests > 0 && tests.failures + tests.errors === 0 };
});

const totals = results.reduce((value, module) => {
  value.tests += module.tests.tests;
  value.failures += module.tests.failures + module.tests.errors;
  value.lineCovered += module.coverage.lineCovered;
  value.lineTotal += module.coverage.lineTotal;
  return value;
}, { tests: 0, failures: 0, lineCovered: 0, lineTotal: 0 });
totals.linePercent = totals.lineTotal ? Math.round(totals.lineCovered * 1000 / totals.lineTotal) / 10 : 0;

const evidenceSource = path.resolve("docs", "testing", "2026-09-07");
if (fs.existsSync(evidenceSource)) {
  fs.cpSync(evidenceSource, path.join(outputRoot, "evidence", "2026-09-07"), {
    recursive: true,
    filter: source => !source.endsWith("_проверено.pptx"),
  });
}

const historicalSummaryPath = path.join(evidenceSource, "summary.json");
const historical = fs.existsSync(historicalSummaryPath)
  ? JSON.parse(fs.readFileSync(historicalSummaryPath, "utf8"))
  : null;

const moduleCards = results.map(module => `
  <article class="module ${module.passed ? "pass" : "fail"}">
    <div class="module-head"><h2>${escapeHtml(module.name)}</h2><span>${module.passed ? "PASS" : "FAIL"}</span></div>
    <div class="numbers"><strong>${module.coverage.linePercent}%</strong><small>покрытие строк</small></div>
    <dl><div><dt>Тесты</dt><dd>${module.tests.tests}</dd></div><div><dt>Ошибки</dt><dd>${module.tests.failures + module.tests.errors}</dd></div><div><dt>Ветви</dt><dd>${module.coverage.branchPercent}%</dd></div></dl>
    <div class="bar"><i style="width:${Math.min(100, module.coverage.linePercent)}%"></i></div>
    <p><a href="coverage/${module.id}/index.html">Открыть детальный JaCoCo</a> · <a href="${repositoryUrl}/tree/${revision}/${module.source}/src/test">Код тестов</a></p>
  </article>`).join("");

const loadBlock = historical ? `
  <section class="panel"><h2>Зафиксированная производительность production</h2>
    <div class="metrics">
      <div><b>${historical.headline.maxEdgeRequestsPerSecond}</b><span>HTTP RPS, 20 клиентов</span></div>
      <div><b>${historical.headline.maxEdgeP95Ms} мс</b><span>HTTP p95</span></div>
      <div><b>${historical.headline.databaseTransactionsPerSecond.toFixed(2)}</b><span>поисковый SQL TPS</span></div>
      <div><b>${historical.headline.llmDurationSeconds} с</b><span>короткий LLM-ответ</span></div>
    </div>
    <p><a href="evidence/2026-09-07/report.html">Полный доказательный отчёт</a> · <a href="${repositoryUrl}/blob/${revision}/docs/testing/2026-09-07/TEST_PROTOCOL.md">Протокол</a> · <a href="${repositoryUrl}/blob/${revision}/docs/testing/2026-09-07/database/DATASET_CARD.md">Карточка БД</a></p>
  </section>
  <section class="panel"><h2>База нагрузочного тестирования</h2>
    <div class="metrics">
      <div><b>${historical.dataset.sites}</b><span>источников</span></div>
      <div><b>${historical.dataset.pages.toLocaleString("ru-RU")}</b><span>страниц</span></div>
      <div><b>${historical.dataset.searchIndexRows.toLocaleString("ru-RU")}</b><span>строк обратного индекса</span></div>
      <div><b>${historical.dataset.assistantChunks.toLocaleString("ru-RU")}</b><span>RAG-фрагментов</span></div>
    </div>
    <p>Полный дамп не публикуется из-за пользовательских документов и данных авторизации. Доступны схема Liquibase, SQL-сценарий, агрегаты и raw-вывод pgbench.</p>
  </section>` : "";

const html = `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CI/CD: качество научной поисковой системы</title><style>
*{box-sizing:border-box}body{margin:0;background:#FDFDF5;color:#17223B;font:16px/1.5 Montserrat,Arial,sans-serif}.wrap{max-width:1180px;margin:auto;padding:34px}.hero{background:#3D568F;color:white;border-radius:26px;padding:34px}.hero small{color:#EAF3B2;text-transform:uppercase;letter-spacing:.1em;font-weight:700}.hero h1{font-size:38px;line-height:1.12;margin:10px 0}.hero a{color:#EAF3B2}.summary,.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-top:18px}.summary div,.metrics div{background:white;border:1px solid #d4e0f3;border-radius:16px;padding:18px}.summary b,.metrics b{display:block;color:#3D568F;font-size:28px}.summary span,.metrics span{color:#65708A;font-size:13px}.modules{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-top:20px}.module,.panel{background:white;border:1px solid #d4e0f3;border-radius:18px;padding:20px;margin-top:20px}.module{margin-top:0}.module-head{display:flex;gap:12px;justify-content:space-between}.module-head h2{font-size:19px;margin:0}.module-head span{background:#EAF3B2;color:#58751F;border-radius:999px;padding:4px 9px;font-weight:700;font-size:12px}.module.fail .module-head span{background:#fee;color:#a33}.numbers strong{display:block;color:#3D568F;font-size:34px;margin-top:18px}.numbers small{color:#65708A}.module dl{display:flex;gap:16px}.module dl div{flex:1}.module dt{color:#65708A;font-size:12px}.module dd{margin:0;font-weight:700}.bar{height:10px;background:#e9eef7;border-radius:9px;overflow:hidden}.bar i{display:block;height:100%;background:#9FBAF1}.panel h2{color:#3D568F}.links{display:flex;flex-wrap:wrap;gap:10px}.links a,a{color:#3D568F;font-weight:700}.links a{background:#E7EFFC;border-radius:10px;padding:10px 13px;text-decoration:none}footer{color:#65708A;padding:28px 0}@media(max-width:850px){.modules,.summary,.metrics{grid-template-columns:1fr 1fr}}@media(max-width:540px){.wrap{padding:16px}.modules,.summary,.metrics{grid-template-columns:1fr}.hero h1{font-size:28px}}
</style></head><body><main class="wrap"><section class="hero"><small>GitHub Actions evidence</small><h1>Качество научной поисковой системы с LLM</h1><p>Автоматический отчёт для ревизии <code>${escapeHtml(revision.slice(0, 12))}</code>. Каждый push и pull request повторно запускает JUnit и JaCoCo.</p><a href="${repositoryUrl}/actions/workflows/ci.yml">Открыть журнал CI/CD</a></section>
<section class="summary"><div><b>${totals.tests}</b><span>модульных тестов</span></div><div><b>${totals.failures}</b><span>ошибок</span></div><div><b>${totals.linePercent}%</b><span>общее покрытие строк</span></div><div><b>${results.filter(item => item.passed).length}/${results.length}</b><span>модулей PASS</span></div></section>
<section class="modules">${moduleCards}</section>${loadBlock}
<section class="panel"><h2>Воспроизводимые доказательства</h2><div class="links"><a href="${repositoryUrl}/actions/workflows/ci.yml">CI и покрытие</a><a href="${repositoryUrl}/actions/workflows/integration-evidence.yml">Интеграции</a><a href="${repositoryUrl}/actions/workflows/load-evidence.yml">Нагрузочный запуск</a><a href="${repositoryUrl}/tree/${revision}/scripts/testing">Код тестирования</a><a href="${repositoryUrl}/blob/${revision}/docs/testing/2026-09-07/database/DATASET_CARD.md">База тестирования</a><a href="evidence/2026-09-07/presentation-preview.png">Презентация</a></div></section>
<footer>Отчёт сформирован автоматически GitHub Actions. Production-данные не публикуются, доступна только обезличенная карточка набора и схема миграций.</footer></main></body></html>`;

fs.writeFileSync(path.join(outputRoot, "index.html"), html);
fs.writeFileSync(path.join(outputRoot, "quality-summary.json"), JSON.stringify({ repository, revision, totals, modules: results }, null, 2));
fs.writeFileSync(path.join(outputRoot, ".nojekyll"), "");
console.log(JSON.stringify({ outputRoot, totals, modules: results }, null, 2));
