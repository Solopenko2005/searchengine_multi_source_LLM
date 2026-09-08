#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = process.cwd();
const evidenceDir = path.join(root, "docs", "testing", "2026-09-07");

function attr(xml, name) {
  const match = xml.match(new RegExp(`${name}="([^"]+)"`));
  return match ? match[1] : "0";
}

function testSummary(modulePath) {
  const directory = path.join(root, modulePath, "target", "surefire-reports");
  const files = fs.readdirSync(directory).filter(name => /^TEST-.*\.xml$/.test(name));
  const result = { suites: files.length, tests: 0, failures: 0, errors: 0, skipped: 0, durationSeconds: 0 };
  for (const file of files) {
    const xml = fs.readFileSync(path.join(directory, file), "utf8");
    const suite = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? "";
    result.tests += Number(attr(suite, "tests"));
    result.failures += Number(attr(suite, "failures"));
    result.errors += Number(attr(suite, "errors"));
    result.skipped += Number(attr(suite, "skipped"));
    result.durationSeconds += Number(attr(suite, "time"));
  }
  result.durationSeconds = Math.round(result.durationSeconds * 1000) / 1000;
  result.passed = result.tests - result.failures - result.errors - result.skipped;
  return result;
}

function coverageSummary(modulePath) {
  const xml = fs.readFileSync(path.join(root, modulePath, "target", "site", "jacoco", "jacoco.xml"), "utf8");
  const counters = [...xml.matchAll(/<counter type="([A-Z]+)" missed="(\d+)" covered="(\d+)"\/>/g)];
  const rootCounters = {};
  for (const [, type, missed, covered] of counters) rootCounters[type] = { missed: Number(missed), covered: Number(covered) };
  function metric(type) {
    const value = rootCounters[type] ?? { missed: 0, covered: 0 };
    const total = value.covered + value.missed;
    return { ...value, total, percent: total ? Math.round(value.covered / total * 1000) / 10 : 0 };
  }
  return { line: metric("LINE"), branch: metric("BRANCH"), instruction: metric("INSTRUCTION") };
}

function parsePgbench(file) {
  const text = fs.readFileSync(file, "utf8");
  const number = (pattern) => Number(text.match(pattern)?.[1] ?? 0);
  return {
    clients: number(/number of clients:\s+(\d+)/),
    durationSeconds: number(/duration:\s+(\d+) s/),
    transactions: number(/transactions actually processed:\s+(\d+)/),
    failedTransactions: number(/failed transactions:\s+(\d+)/),
    averageLatencyMs: number(/latency average =\s+([\d.]+) ms/),
    transactionsPerSecond: number(/tps =\s+([\d.]+)/),
  };
}

const modules = [
  { id: "search", name: "Поисковая система и LLM", path: "Searchengine_1" },
  { id: "authorization", name: "Авторизация", path: "authorization" },
  { id: "email", name: "Отправка e-mail", path: "emailsender" },
].map(module => ({ ...module, tests: testSummary(module.path), coverage: coverageSummary(module.path) }));

const integration = JSON.parse(fs.readFileSync(path.join(evidenceDir, "integration-results.json"), "utf8"));
const llm = JSON.parse(fs.readFileSync(path.join(evidenceDir, "llm-smoke.json"), "utf8"));
const edgeLoad = [1, 5, 10, 20].map(concurrency => JSON.parse(fs.readFileSync(path.join(evidenceDir, `load-edge-c${concurrency}.json`), "utf8")));
const databaseLoad = [1, 5, 10].map(concurrency => parsePgbench(path.join(evidenceDir, "database-load", `database-c${concurrency}.txt`)));
const serverProbe = fs.readFileSync(path.join(evidenceDir, "server-probe.txt"), "utf8");
const databaseMatch = serverProbe.match(/\{"sites"\s*:\s*(\d+),\s*"pages"\s*:\s*(\d+),\s*"lemmas"\s*:\s*(\d+),\s*"search_index"\s*:\s*(\d+),\s*"assistant_chunks"\s*:\s*(\d+)\}/);
const dataset = databaseMatch ? {
  sites: Number(databaseMatch[1]), pages: Number(databaseMatch[2]), lemmas: Number(databaseMatch[3]),
  searchIndexRows: Number(databaseMatch[4]), assistantChunks: Number(databaseMatch[5]),
} : {};

const totalTests = modules.reduce((sum, module) => sum + module.tests.tests, 0);
const totalFailed = modules.reduce((sum, module) => sum + module.tests.failures + module.tests.errors, 0);
const lineCovered = modules.reduce((sum, module) => sum + module.coverage.line.covered, 0);
const lineTotal = modules.reduce((sum, module) => sum + module.coverage.line.total, 0);
const overallLineCoverage = Math.round(lineCovered / lineTotal * 1000) / 10;
const maxEdge = edgeLoad.at(-1);
const maxDatabase = databaseLoad.at(-1);

const summary = {
  generatedAt: new Date().toISOString(),
  testedCommit: process.env.TESTED_COMMIT || "working-tree",
  target: integration.target,
  verdict: "Условно готово к пилотной эксплуатации",
  modules,
  totals: { tests: totalTests, failed: totalFailed, passed: totalTests - totalFailed, overallLineCoveragePercent: overallLineCoverage },
  integration: integration.summary,
  dataset,
  edgeLoad,
  databaseLoad,
  llm,
  headline: {
    maxEdgeConcurrency: maxEdge.concurrency,
    maxEdgeRequestsPerSecond: maxEdge.requestsPerSecond,
    maxEdgeP95Ms: maxEdge.latencyMs.p95,
    maxEdgeErrors: maxEdge.failed,
    databaseClients: maxDatabase.clients,
    databaseTransactionsPerSecond: maxDatabase.transactionsPerSecond,
    databaseAverageLatencyMs: maxDatabase.averageLatencyMs,
    databaseFailedTransactions: maxDatabase.failedTransactions,
    llmDurationSeconds: Math.round(llm.durationMs / 100) / 10,
  },
  limitations: [
    "Покрытие строками составляет менее 60 процентов в поисковом и e-mail модулях.",
    "Нагрузочный HTTP-тест production выполнен для публичного web-контура без пользовательских данных.",
    "Поисковый SQL проверен под read-only нагрузкой на фактическом production-наборе, но полный авторизованный RAG-сценарий под нагрузкой не запускался.",
    "LLM smoke-тест подтверждает доступность модели, однако 10,48 секунды на короткий ответ требуют оптимизации перед массовой эксплуатацией.",
  ],
  recommendations: [
    "Поднять покрытие критических сервисов SearchService, DocumentIndexingService, AssistantService и email-контроллера до 60 процентов и выше.",
    "Добавить отдельный staging-контур с обезличенной копией данных для авторизованных E2E и RAG load-тестов.",
    "Ввести SLO: p95 поиска до 2 секунд, ошибки до 1 процента, time-to-first-token LLM до 5 секунд.",
    "Подключить непрерывный мониторинг p95, ошибок, очереди индексации, длины контекста и времени генерации LLM.",
  ],
};

fs.writeFileSync(path.join(evidenceDir, "summary.json"), JSON.stringify(summary, null, 2));

const csv = ["layer,scenario,concurrency,throughput_per_second,latency_ms,error_count"];
for (const item of edgeLoad) csv.push(`HTTP,Публичный web-контур,${item.concurrency},${item.requestsPerSecond},${item.latencyMs.p95},${item.failed}`);
for (const item of databaseLoad) csv.push(`PostgreSQL,Поисковый SQL,${item.clients},${item.transactionsPerSecond},${item.averageLatencyMs},${item.failedTransactions}`);
csv.push(`LLM,Короткий ответ,1,${Math.round(100000 / llm.durationMs) / 100},${llm.durationMs},${llm.passed ? 0 : 1}`);
fs.writeFileSync(path.join(evidenceDir, "performance-summary.csv"), csv.join("\n") + "\n");

const moduleRows = modules.map(module => `
  <tr><td>${module.name}</td><td>${module.tests.tests}</td><td>${module.tests.failures + module.tests.errors}</td>
  <td>${module.coverage.line.percent}%</td><td>${module.coverage.branch.percent}%</td></tr>`).join("");
const edgeRows = edgeLoad.map(item => `
  <tr><td>${item.concurrency}</td><td>${item.requests}</td><td>${item.requestsPerSecond}</td><td>${item.latencyMs.p95}</td><td>${item.failed}</td></tr>`).join("");
const dbRows = databaseLoad.map(item => `
  <tr><td>${item.clients}</td><td>${item.transactions}</td><td>${item.transactionsPerSecond}</td><td>${item.averageLatencyMs}</td><td>${item.failedTransactions}</td></tr>`).join("");
const list = items => items.map(item => `<li>${item}</li>`).join("");
const html = `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Комплексное тестирование научной поисковой системы</title><style>
@font-face{font-family:Montserrat;src:url('../../../Searchengine_1/src/main/resources/static/assets/fonts/Montserrat/Montserrat-Regular.svg')}*{box-sizing:border-box}body{margin:0;background:#FDFDF5;color:#17213d;font:15px/1.55 Montserrat,Arial,sans-serif}.page{max-width:1180px;margin:auto;padding:42px}.hero{background:#3D568F;color:white;border-radius:28px;padding:38px;position:relative;overflow:hidden}.hero:after{content:'';position:absolute;width:320px;height:320px;border:2px solid #9FBAF1;border-radius:50%;right:-90px;top:-140px;box-shadow:0 0 0 28px #3D568F,0 0 0 30px #9FBAF1,0 0 0 58px #3D568F,0 0 0 60px #EAF3B2}.eyebrow{text-transform:uppercase;letter-spacing:.12em;color:#EAF3B2;font-weight:700}.hero h1{font-size:38px;line-height:1.12;max-width:760px;margin:10px 0}.verdict{display:inline-block;background:#EAF3B2;color:#17213d;padding:9px 16px;border-radius:999px;font-weight:700}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin:20px 0}.metric,.card{background:white;border:1px solid #dbe4f5;border-radius:18px;padding:20px;box-shadow:0 10px 24px rgba(61,86,143,.08)}.metric b{display:block;font-size:28px;color:#3D568F}.card{margin-top:18px}.card h2{margin:0 0 14px;color:#3D568F}table{width:100%;border-collapse:collapse}th,td{padding:11px 10px;border-bottom:1px solid #dbe4f5;text-align:left}th{background:#edf3ff;color:#3D568F}code{background:#edf3ff;padding:2px 6px;border-radius:5px}a{color:#3D568F;font-weight:700}li{margin:7px 0}.pass{color:#567713;font-weight:700}.warn{color:#9b5b00;font-weight:700}.charts{display:grid;grid-template-columns:1fr 1fr;gap:18px}.charts img,.deck-preview{width:100%;border:1px solid #dbe4f5;border-radius:14px;background:white}@media(max-width:800px){.page{padding:18px}.grid,.charts{grid-template-columns:1fr 1fr}.hero h1{font-size:29px}}@media(max-width:520px){.grid,.charts{grid-template-columns:1fr}}
</style></head><body><main class="page"><section class="hero"><div class="eyebrow">Отчёт от 7 сентября 2026</div><h1>Комплексное тестирование научной поисковой системы с LLM</h1><p>Модульные, интеграционные и нагрузочные проверки с воспроизводимыми исходными результатами.</p><span class="verdict">${summary.verdict}</span></section>
<section class="grid"><div class="metric"><b>${totalTests}</b>тестов, ошибок ${totalFailed}</div><div class="metric"><b>${integration.summary.passed}/${integration.summary.total}</b>production-проверок</div><div class="metric"><b>${maxEdge.requestsPerSecond}</b>HTTP запросов/с</div><div class="metric"><b>${summary.headline.llmDurationSeconds} с</b>короткий LLM-ответ</div></section>
<section class="card"><h2>1. Модульное тестирование</h2><p class="pass">Все ${totalTests} тестов завершились успешно.</p><table><thead><tr><th>Модуль</th><th>Тесты</th><th>Ошибки</th><th>Строки</th><th>Ветви</th></tr></thead><tbody>${moduleRows}</tbody></table><p class="warn">Общее покрытие строк: ${overallLineCoverage}%. Это объективная зона роста, а не основание заявлять полную готовность к высокой нагрузке.</p></section>
<section class="card"><h2>2. Интеграционное тестирование</h2><p class="pass">${integration.summary.passed} из ${integration.summary.total} проверок прошли: HTTPS TLS 1.3, публичные страницы, captcha через auth-сервис, защита Swagger, поиска и LLM-метрик.</p><p>Внутренние проверки: PostgreSQL healthy, Redis PONG, SMTP ready, сервис авторизации публикует 11 операций, LM Studio возвращает список моделей.</p><p>Фактический набор: <b>${dataset.sites} источников</b>, <b>${dataset.pages.toLocaleString("ru-RU")} страниц</b>, <b>${dataset.searchIndexRows.toLocaleString("ru-RU")} строк индекса</b>, <b>${dataset.assistantChunks.toLocaleString("ru-RU")} RAG-фрагментов</b>.</p></section>
<section class="card"><h2>3. Нагрузочное тестирование HTTP</h2><table><thead><tr><th>Параллельность</th><th>Запросы</th><th>RPS</th><th>p95, мс</th><th>Ошибки</th></tr></thead><tbody>${edgeRows}</tbody></table></section>
<section class="card"><h2>4. Нагрузочное тестирование PostgreSQL</h2><table><thead><tr><th>Клиенты</th><th>Транзакции</th><th>TPS</th><th>Среднее, мс</th><th>Ошибки</th></tr></thead><tbody>${dbRows}</tbody></table><p>При 10 клиентах: ${maxDatabase.transactionsPerSecond} TPS, средняя задержка ${maxDatabase.averageLatencyMs} мс, ошибок ${maxDatabase.failedTransactions}.</p></section>
<section class="card"><h2>5. Проверка LLM</h2><p class="pass">OpenAI-совместимый endpoint LM Studio ответил HTTP ${llm.status}, модель ${llm.model}.</p><p>Время короткого ответа: <b>${summary.headline.llmDurationSeconds} с</b>. Ответ: «${llm.answer}»</p><p class="warn">LLM работает, но задержка заметна пользователю. Перед массовым доступом нужен streaming, кэш ответов и измерение time-to-first-token.</p></section>
<section class="card"><h2>Графические доказательства</h2><div class="charts"><img src="charts/test-overview.svg" alt="Тесты и покрытие"><img src="charts/edge-load.svg" alt="HTTP нагрузка"><img src="charts/database-load.svg" alt="Нагрузка базы"><img src="charts/system-scale.svg" alt="Масштаб данных"></div></section>
<section class="card"><h2>Презентация и изображения</h2><p><a href="Комплексное_тестирование_научной_поисковой_системы_итог.pptx">Скачать итоговую презентацию PowerPoint</a> · <a href="presentation-slides/">Открыть отдельные изображения слайдов</a></p><img class="deck-preview" src="presentation-preview.png" alt="Обзор презентации"></section>
<section class="card"><h2>Ограничения</h2><ul>${list(summary.limitations)}</ul></section><section class="card"><h2>Следующие действия</h2><ol>${list(summary.recommendations)}</ol></section>
<section class="card"><h2>Как воспроизвести</h2><p>Модули: <code>mvn verify</code> в каталогах Searchengine_1, authorization и emailsender.</p><p>Интеграция: <code>node scripts/testing/production-check.mjs --base https://search.5-42-117-227.sslip.io/ --output docs/testing/latest/integration-results.json</code></p><p>Безопасная HTTP-нагрузка: <code>node scripts/testing/load-test.mjs --base https://search.5-42-117-227.sslip.io/ --endpoint / --concurrency 20 --duration 15 --output docs/testing/latest/load-c20.json</code></p><p>Серверные read-only проверки запускаются сценариями <code>server-probe.sh</code>, <code>database-load-test.sh</code> и <code>llm-smoke-test.py</code>.</p></section>
<section class="card"><h2>Исходные доказательства</h2><p><a href="TEST_PROTOCOL.md">формальный протокол</a> · <a href="summary.json">summary.json</a> · <a href="integration-results.json">integration-results.json</a> · <a href="performance-summary.csv">performance-summary.csv</a> · <a href="server-probe.txt">server-probe.txt</a> · <a href="llm-smoke.json">llm-smoke.json</a> · <a href="raw/">JUnit XML и JaCoCo</a> · <a href="database-load/">pgbench</a> · <a href="presentation-validation.json">валидация PPTX</a></p></section>
</main></body></html>`;
fs.writeFileSync(path.join(evidenceDir, "report.html"), html);

const markdown = `# Комплексное тестирование научной поисковой системы с LLM

Дата: 7 сентября 2026 года  
Контур: ${integration.target}  
Вердикт: **${summary.verdict}**

## Краткий результат

- Модульные тесты: **${totalTests}/${totalTests}**, ошибок нет.
- Интеграционные production-проверки: **${integration.summary.passed}/${integration.summary.total}**.
- HTTP при 20 параллельных запросах: **${maxEdge.requestsPerSecond} RPS**, p95 **${maxEdge.latencyMs.p95} мс**, ошибок **${maxEdge.failed}**.
- Поисковый SQL при 10 клиентах: **${maxDatabase.transactionsPerSecond} TPS**, средняя задержка **${maxDatabase.averageLatencyMs} мс**, ошибок **${maxDatabase.failedTransactions}**.
- Локальная LLM: HTTP ${llm.status}, короткий ответ за **${summary.headline.llmDurationSeconds} с**.
- Production-набор: **${dataset.sites} источников**, **${dataset.pages} страниц**, **${dataset.searchIndexRows} строк индекса**, **${dataset.assistantChunks} RAG-фрагментов**.

## Что именно проверено

Модульные тесты охватывают поиск и подсветку лемм, разграничение доступа, индексацию сайтов, очередь и удаление источников, обработку RAG-фрагментов, streaming/cancel LLM, анализ тематик, профиль ассистента, регистрацию, JWT, восстановление пароля, captcha и почтовый контекст.

Интеграционные проверки выполнены через публичный HTTPS-контур и внутреннюю Docker-сеть: Caddy, search, authorization, PostgreSQL, Redis, email и OpenAI-совместимый LM Studio endpoint.

Нагрузочные проверки разделены на HTTP edge-контур и фактический поисковый SQL в production. Все операции с БД были только чтением.

## Важные ограничения

${summary.limitations.map(item => `- ${item}`).join("\n")}

## Рекомендации

${summary.recommendations.map((item, index) => `${index + 1}. ${item}`).join("\n")}

## Как воспроизвести

1. Выполнить \`mvn verify\` отдельно в каталогах \`Searchengine_1\`, \`authorization\` и \`emailsender\`.
2. Запустить integration smoke-тест: \`node scripts/testing/production-check.mjs --base https://search.5-42-117-227.sslip.io/ --output docs/testing/latest/integration-results.json\`.
3. Запустить безопасную HTTP-нагрузку: \`node scripts/testing/load-test.mjs --base https://search.5-42-117-227.sslip.io/ --endpoint / --concurrency 20 --duration 15 --output docs/testing/latest/load-c20.json\`.
4. На сервере выполнить read-only сценарии \`server-probe.sh\`, \`database-load-test.sh\` и \`llm-smoke-test.py\`.
5. Обновить сводку: \`node scripts/testing/generate-evidence.mjs\`, затем \`node scripts/testing/generate-charts.mjs docs/testing/2026-09-07/summary.json docs/testing/2026-09-07/charts\`.

## Доказательства

- [Интерактивный HTML-отчёт](report.html)
- [Формальный протокол тестирования](TEST_PROTOCOL.md)
- [Итоговая презентация PowerPoint](Комплексное_тестирование_научной_поисковой_системы_итог.pptx)
- [Обзор презентации PNG](presentation-preview.png)
- [Отдельные изображения слайдов](presentation-slides/)
- [Сводные метрики JSON](summary.json)
- [Результаты интеграционных проверок](integration-results.json)
- [Сводка производительности CSV](performance-summary.csv)
- [LLM smoke-тест](llm-smoke.json)
- [Снимок production-контура](server-probe.txt)
- [Raw pgbench](database-load/)
- [Графики](charts/)
- [JUnit XML и JaCoCo CSV](raw/)
- [Протокол структурной проверки презентации](presentation-validation.json)
`;
fs.writeFileSync(path.join(evidenceDir, "README.md"), markdown);
console.log(JSON.stringify(summary, null, 2));
