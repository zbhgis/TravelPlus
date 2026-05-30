/**
 * TravelPlus 限流拦截率压测脚本
 * 目标：实测 @RateLimiter 滑动窗口（5s/5次）在高并发恶意请求下的拦截率
 * 用法：node scripts/perf/rate-limit-bench.js [--concurrency 200] [--duration 30] [--warmup 2]
 * 输出：总请求/通过/拦截/拦截率 + 窗口理论值对比
 */
const args = process.argv.slice(2);
const getArg = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
};
const CONCURRENCY = parseInt(getArg('concurrency', '200'), 10);
const DURATION_SEC = parseInt(getArg('duration', '30'), 10);
const WARMUP_SEC = parseInt(getArg('warmup', '2'), 10);
const BASE_URL = String(getArg('url', 'http://localhost:8081'));

// 限流接口（AttractionController @RateLimiter window=5 limit=5）
const RATE_LIMITED_URL = BASE_URL + '/attraction/of/type?typeId=1&current=1';
// 对照：无限流接口
const NO_LIMIT_URL = BASE_URL + '/map/points';

async function runBench(url, label) {
  const results = { total: 0, passed: 0, blocked: 0, errors: 0, latencyMs: [] };
  const deadline = Date.now() + DURATION_SEC * 1000;

  async function worker() {
    while (Date.now() < deadline) {
      const start = process.hrtime.bigint();
      try {
        const res = await fetch(url);
        const body = await res.json();
        const end = process.hrtime.bigint();
        results.latencyMs.push(Number(end - start) / 1e6);
        results.total++;
        if (body && body.success === true) {
          results.passed++;
        } else if (body && body.success === false) {
          results.blocked++;  // 被限流/业务失败
        } else {
          results.errors++;
        }
      } catch (e) {
        results.errors++;
      }
    }
  }

  // 预热
  const warmDeadline = Date.now() + WARMUP_SEC * 1000;
  async function warmup() {
    while (Date.now() < warmDeadline) {
      try { await fetch(url); } catch (e) {}
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, 20) }, warmup));

  const workers = Array.from({ length: CONCURRENCY }, worker);
  await Promise.all(workers);

  const lat = results.latencyMs.sort((a, b) => a - b);
  const p50 = lat.length ? lat[Math.floor(0.5 * lat.length)] : 0;
  const p95 = lat.length ? lat[Math.floor(0.95 * lat.length)] : 0;
  const p99 = lat.length ? lat[Math.floor(0.99 * lat.length)] : 0;
  const avg = lat.length ? lat.reduce((a, b) => a + b, 0) / lat.length : 0;
  const rate = results.total ? (results.blocked / results.total * 100).toFixed(2) : '0';

  console.log(`\n===== ${label} =====`);
  console.log(`  总请求: ${results.total}`);
  console.log(`  通过(success:true): ${results.passed}`);
  console.log(`  拦截(success:false): ${results.blocked}`);
  console.log(`  网络错误: ${results.errors}`);
  console.log(`  ★ 拦截率: ${rate}%`);
  console.log(`  QPS: ${(results.total / DURATION_SEC).toFixed(1)}`);
  console.log(`  延迟 avg=${avg.toFixed(1)}ms p50=${p50.toFixed(1)} p95=${p95.toFixed(1)} p99=${p99.toFixed(1)}`);
  return results;
}

(async () => {
  console.log(`TravelPlus 限流拦截率压测 | 并发=${CONCURRENCY} 时长=${DURATION_SEC}s 预热=${WARMUP_SEC}s`);
  console.log(`限流接口: ${RATE_LIMITED_URL}`);
  console.log(`(规则: 滑动窗口 5s 内限 5 次 → 30s 理论放行约 ${Math.floor(DURATION_SEC / 5) * 5} 次)`);

  const limited = await runBench(RATE_LIMITED_URL, '限流接口 /attraction/of/type (5s/5次)');
  const noLimit = await runBench(NO_LIMIT_URL, '对照接口 /map/points (无限流)');

  console.log(`\n========== 最终对比 ==========`);
  console.log(`| 接口 | 总请求 | 通过 | 拦截 | 拦截率 | QPS |`);
  console.log(`| 限流(5s/5) | ${limited.total} | ${limited.passed} | ${limited.blocked} | ${(limited.blocked / limited.total * 100).toFixed(2)}% | ${(limited.total / DURATION_SEC).toFixed(1)} |`);
  console.log(`| 无限流对照 | ${noLimit.total} | ${noLimit.passed} | ${noLimit.blocked} | ${(noLimit.blocked / noLimit.total * 100).toFixed(2)}% | ${(noLimit.total / DURATION_SEC).toFixed(1)} |`);
  console.log(`\n窗口理论值: ${Math.floor(DURATION_SEC / 5) * 5} 次（5s 窗口 × ${DURATION_SEC / 5} 个窗口 × 5 次/窗）`);
})();
