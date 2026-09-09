#!/usr/bin/env node

import fs from "node:fs";
import process from "node:process";

const report = process.argv[2] ?? "Searchengine_1/target/site/jacoco/jacoco.csv";
if (!fs.existsSync(report)) {
  console.error(`JaCoCo report not found: ${report}`);
  process.exit(2);
}
const rows = fs.readFileSync(report, "utf8").trim().split(/\r?\n/);
const header = rows.shift().split(",");
const at = name => header.indexOf(name);
const allData = rows.map(line => line.split(","));
const data = allData.filter(row => row[1] === "searchengine.services.assistant");

function coverage(selected) {
  const total = selected.reduce((sum, row) => sum + Number(row[at("LINE_MISSED")])
    + Number(row[at("LINE_COVERED")]), 0);
  const covered = selected.reduce((sum, row) => sum + Number(row[at("LINE_COVERED")]), 0);
  return { covered, total, percent: total ? Math.round(covered * 1000 / total) / 10 : 0 };
}

const gates = [
  { name: "assistant package", minimum: 60, rows: data },
  { name: "AssistantService", minimum: 55, rows: data.filter(row => row[2] === "AssistantService") },
  { name: "LlmClient", minimum: 70, rows: data.filter(row => row[2] === "LlmClient") },
  { name: "EmbeddingClient", minimum: 75, rows: data.filter(row => row[2] === "EmbeddingClient") },
  { name: "LlmTopicAnalysisService", minimum: 85, rows: data.filter(row => row[2] === "LlmTopicAnalysisService") },
  { name: "AssistantMetricsService", minimum: 90, rows: data.filter(row => row[2] === "AssistantMetricsService") },
  { name: "DocumentIndexingService", minimum: 60, rows: allData.filter(row => row[1] === "searchengine.services"
    && row[2] === "DocumentIndexingService") },
];

let failed = false;
for (const gate of gates) {
  const result = coverage(gate.rows);
  const pass = gate.rows.length > 0 && result.percent >= gate.minimum;
  console.log(`${pass ? "PASS" : "FAIL"} ${gate.name}: ${result.percent}% `
    + `(${result.covered}/${result.total}), minimum ${gate.minimum}%`);
  if (!pass) failed = true;
}
if (failed) process.exitCode = 1;
