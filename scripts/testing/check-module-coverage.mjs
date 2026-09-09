#!/usr/bin/env node

import fs from "node:fs";
import process from "node:process";

const [report, minimumText = "0", label = "module"] = process.argv.slice(2);
if (!report || !fs.existsSync(report)) {
  console.error(`JaCoCo report not found: ${report || "<missing>"}`);
  process.exit(2);
}
const rows = fs.readFileSync(report, "utf8").trim().split(/\r?\n/);
const header = rows.shift().split(",");
const coveredAt = header.indexOf("LINE_COVERED");
const missedAt = header.indexOf("LINE_MISSED");
let covered = 0;
let missed = 0;
for (const line of rows) {
  const values = line.split(",");
  covered += Number(values[coveredAt] || 0);
  missed += Number(values[missedAt] || 0);
}
const percent = covered + missed ? Math.round(covered * 1000 / (covered + missed)) / 10 : 0;
const minimum = Number(minimumText);
const pass = percent >= minimum;
console.log(`${pass ? "PASS" : "FAIL"} ${label}: ${percent}% (${covered}/${covered + missed}), minimum ${minimum}%`);
if (!pass) process.exitCode = 1;
