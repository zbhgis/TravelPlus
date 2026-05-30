#!/usr/bin/env node
/**
 * TravelPlus 搜索接口性能压测脚本（LIKE 基线 / ES 版通用）
 *
 * 用法：
 *   node scripts/perf/bench.js [--keywords 西湖,乌镇,xyz不存在] [--concurrency 20]
 *                              [--duration 10] [--warmup 2] [--url http://localhost:8081]
 *
 * 统计：总请求数、QPS、平均延迟、P50/P95/P99、最小/最大、错误数（网络错误 + 非 2xx）。
 * 输出：每个关键词一行明细 + 末尾对比表。
 */
const args = process.argv.slice(2);
const getArg = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
};

const keywords = String(getArg('keywords', '西湖')).split(',').map(s => s.trim()).filter(Boolean);
const CONCURRENCY = parseInt(getArg('concurrency', '20'), 10);
const DURATION_SEC = parseInt(getArg('duration', '10'), 10);
const WARMUP_SEC = parseInt(getArg('warmup', '2'), 10);
const BASE_URL = String(getArg('url', 'http://localhost:8081'));

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.floor(p * sorted.length));
  return sorted[idx];
}

async function runBench(keyword) {
  const url = `${BASE_URL}/search?keyword=${encodeURIComponent(keyword)}`;
  const latencies = [];
  let ok = 0;
  let errors = 0;
  const statusMap = new Map();
  let stopped = false;

  const deadline = Date.now() + DURATION_SEC * 1000;
  const stopAt = () => deadline;

  async function worker() {
    while (Date.now() < stopAt()) {
      const start = process.hrtime.bigint();
      try {
        const res = await fetch(url);
        // 读取完整响应体，确保测量到整个请求-响应周期
        await res.arrayBuffer();
        const end = process.hrtime.bigint();
        latencies.push(Number(end - start) / 1e6);
        statusMap.set(res.status, (statusMap.get(res.status) || 0) + 1);
        if (res.ok) ok++; else errors++;
      } catch (e) {
        const end = process.hrtime.bigint();
        latencies.push(Number(end - start) / 1e6);
        errors++;
        statusMap.set('ERR', (statusMap.get('ERR') || 0) + 1);
      }
    }
  }

  // 预热：静默跑 WARMUP_SEC 秒（编译/连接池/MySQL 缓存预热），不计入统计
  const warmDeadline = Date.now() + WARMUP_SEC * 1000;
  async function warmupWorker() {
    while (Date.now() < warmDeadline) {
      try { const r = await fetch(url); await r.arrayBuffer(); } catch (_) { /* ignore */ }
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, warmupWorker));

  const realStart = Date.now();
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  const realMs = Date.now() - realStart;

  latencies.sort((a, b) => a - b);
  const total = latencies.length;
  const qps = total / (realMs / 1000);
  const avg = total ? latencies.reduce((s, v) => s + v, 0) / total : 0;
  const p50 = percentile(latencies, 0.50);
  const p95 = percentile(latencies, 0.95);
  const p99 = percentile(latencies, 0.99);
  const min = latencies.length ? latencies[0] : 0;
  const max = latencies.length ? latencies[latencies.length - 1] : 0;
  const statusStr = [...statusMap.entries()].map(([k, v]) => `${k}=${v}`).join(' ');

  return { keyword, total, qps, avg, p50, p95, p99, min, max, ok, errors, statusStr };
}

function fmt(v) {
  return Number(v).toFixed(2);
}

(async () => {
  console.log(`TravelPlus LIKE 基线压测 | 并发=${CONCURRENCY} 时长=${DURATION_SEC}s 预热=${WARMUP_SEC}s 目标=${BASE_URL}`);
  console.log('='.repeat(100));

  const results = [];
  for (const kw of keywords) {
    console.log(`\n>> 正在压测 keyword="${kw}" ...`);
    const r = await runBench(kw);
    results.push(r);
    console.log(
      `   total=${r.total} qps=${fmt(r.qps)} avg=${fmt(r.avg)}ms p50=${fmt(r.p50)}ms ` +
      `p95=${fmt(r.p95)}ms p99=${fmt(r.p99)}ms min=${fmt(r.min)}ms max=${fmt(r.max)}ms ` +
      `errors=${r.errors} ok=${r.ok} [${r.statusStr}]`
    );
  }

  console.log('\n' + '='.repeat(100));
  console.log('对比表 (keyword | QPS | avg ms | P50 ms | P95 ms | P99 ms | errors | total)');
  console.log('-'.repeat(100));
  for (const r of results) {
    console.log(
      `  ${r.keyword.padEnd(12)} | ${fmt(r.qps).padStart(8)} | ${fmt(r.avg).padStart(7)} | ` +
      `${fmt(r.p50).padStart(7)} | ${fmt(r.p95).padStart(7)} | ${fmt(r.p99).padStart(7)} | ` +
      `${String(r.errors).padStart(6)} | ${r.total}`
    );
  }
})();
