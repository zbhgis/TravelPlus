/**
 * 门票列表三级缓存三档压测（MySQL / Redis / Caffeine 二级缓存）
 * 前置：后端 mode 已切换为对应档（mysql / redis / caffeine），各档分别压测
 * 接口：GET /ticket/list/{attractionId}（走 TicketListCacheService）
 * 用法：node scripts/perf/cache-bench.js [--concurrency 100] [--duration 10] [--attraction 1]
 * 输出：QPS、延迟分布（p50/p95/p99）、错误数
 */
const args = process.argv.slice(2);
const getArg = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
};
const CONCURRENCY = parseInt(getArg('concurrency', '100'), 10);
const DURATION_SEC = parseInt(getArg('duration', '10'), 10);
const ATTRACTION_ID = parseInt(getArg('attraction', '1'), 10);
const BASE_URL = String(getArg('url', 'http://localhost:8081'));
const MODE = String(getArg('mode', 'caffeine'));

async function runBench() {
  const stats = { total: 0, ok: 0, errors: 0, latencyMs: [] };
  const deadline = Date.now() + DURATION_SEC * 1000;
  const url = BASE_URL + '/ticket/list/' + ATTRACTION_ID;

  async function worker() {
    while (Date.now() < deadline) {
      const start = process.hrtime.bigint();
      try {
        const res = await fetch(url);
        await res.arrayBuffer();
        const end = process.hrtime.bigint();
        stats.latencyMs.push(Number(end - start) / 1e6);
        stats.total++;
        if (res.ok) stats.ok++; else stats.errors++;
      } catch (e) { stats.errors++; }
    }
  }

  const workers = Array.from({ length: CONCURRENCY }, worker);
  await Promise.all(workers);

  const lat = stats.latencyMs.sort((a, b) => a - b);
  const p50 = lat.length ? lat[Math.floor(0.5 * lat.length)] : 0;
  const p95 = lat.length ? lat[Math.floor(0.95 * lat.length)] : 0;
  const p99 = lat.length ? lat[Math.floor(0.99 * lat.length)] : 0;
  const avg = lat.length ? lat.reduce((a, b) => a + b, 0) / lat.length : 0;
  const qps = stats.total / DURATION_SEC;

  console.log(`[${MODE}] QPS=${qps.toFixed(0)} total=${stats.total} avg=${avg.toFixed(1)}ms p50=${p50.toFixed(1)} p95=${p95.toFixed(1)} p99=${p99.toFixed(1)} errors=${stats.errors}`);
  return { mode: MODE, qps, avg, p50, p95, p99, total: stats.total };
}

runBench().then(r => {
  console.log(`\nRESULT_JSON: ${JSON.stringify(r)}`);
});
