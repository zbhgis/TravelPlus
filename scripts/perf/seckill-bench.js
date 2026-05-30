/**
 * 秒杀高并发压测：验证 1000 QPS + 500 库存下的超卖与响应时间
 * 前置：DB+Redis 库存均设为 500，tb_ticket_order 该票订单清空，Redis seckill:order:21 清空
 * 用法：node scripts/perf/seckill-bench.js [--concurrency 200] [--duration 5] [--ticket 21]
 * 输出：总请求/成功/库存不足/重复下单/错误 + 延迟分布 + 超卖核对指引
 */
const args = process.argv.slice(2);
const getArg = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
};
const CONCURRENCY = parseInt(getArg('concurrency', '200'), 10);
const DURATION_SEC = parseInt(getArg('duration', '5'), 10);
const TICKET_ID = parseInt(getArg('ticket', '21'), 10);
const BASE_URL = String(getArg('url', 'http://localhost:8081'));
const PHONE = String(getArg('phone', '13912345678'));
const PASSWORD = String(getArg('password', '123456'));

async function login() {
  const res = await fetch(BASE_URL + '/user/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: PHONE, password: PASSWORD })
  });
  const body = await res.json();
  return body.data; // token
}

async function runBench(token) {
  const stats = { total: 0, ok: 0, insufficient: 0, duplicate: 0, other: 0, errors: 0, latencyMs: [] };
  const deadline = Date.now() + DURATION_SEC * 1000;

  async function worker() {
    while (Date.now() < deadline) {
      const start = process.hrtime.bigint();
      try {
        const res = await fetch(BASE_URL + '/ticket-order/seckill/' + TICKET_ID, {
          method: 'POST',
          headers: { 'authorization': token }
        });
        const body = await res.json();
        const end = process.hrtime.bigint();
        stats.latencyMs.push(Number(end - start) / 1e6);
        stats.total++;
        if (body && body.success === true) {
          stats.ok++;
        } else if (body && body.errorMsg && (body.errorMsg.includes('库存不足') || body.errorMsg.includes('库存'))) {
          stats.insufficient++;
        } else if (body && body.errorMsg && body.errorMsg.includes('重复')) {
          stats.duplicate++;
        } else {
          stats.other++;
        }
      } catch (e) {
        stats.errors++;
      }
    }
  }

  const workers = Array.from({ length: CONCURRENCY }, worker);
  await Promise.all(workers);

  const lat = stats.latencyMs.sort((a, b) => a - b);
  const p50 = lat.length ? lat[Math.floor(0.5 * lat.length)] : 0;
  const p95 = lat.length ? lat[Math.floor(0.95 * lat.length)] : 0;
  const p99 = lat.length ? lat[Math.floor(0.99 * lat.length)] : 0;
  const avg = lat.length ? lat.reduce((a, b) => a + b, 0) / lat.length : 0;
  const max = lat.length ? lat[lat.length - 1] : 0;
  const qps = stats.total / DURATION_SEC;

  console.log(`\n===== 秒杀压测 (ticket=${TICKET_ID}, 库存=500) =====`);
  console.log(`  并发=${CONCURRENCY} 时长=${DURATION_SEC}s`);
  console.log(`  总请求: ${stats.total}  (QPS=${qps.toFixed(1)})`);
  console.log(`  成功(拿到orderId): ${stats.ok}`);
  console.log(`  库存不足: ${stats.insufficient}`);
  console.log(`  重复下单: ${stats.duplicate}`);
  console.log(`  其他失败: ${stats.other}`);
  console.log(`  网络错误: ${stats.errors}`);
  console.log(`  延迟: avg=${avg.toFixed(1)}ms p50=${p50.toFixed(1)} p95=${p95.toFixed(1)} p99=${p99.toFixed(1)} max=${max.toFixed(1)}`);
  return stats;
}

(async () => {
  console.log('登录获取 token...');
  const token = await login();
  if (!token) { console.error('登录失败'); process.exit(1); }
  console.log('token 获取成功, 开始压测');
  const stats = await runBench(token);
  console.log(`\n========== 超卖核对指引 ==========`);
  console.log(`  压测后应查: SELECT COUNT(*) FROM tb_ticket_order WHERE ticket_id=${TICKET_ID}`);
  console.log(`             SELECT stock FROM tb_seckill_ticket WHERE ticket_id=${TICKET_ID}`);
  console.log(`             GET seckill:stock:${TICKET_ID} / SCARD seckill:order:${TICKET_ID}`);
  console.log(`  判断: 订单数=成功数(ok) 且 ≤500; DB库存=500-订单数; Redis库存=500-订单数; SCARD=订单数 => 无超卖`);
})();
