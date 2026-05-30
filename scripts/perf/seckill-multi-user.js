/**
 * 秒杀高并发多用户压测：1000 QPS + 500 库存 超卖验证
 * 每个并发 worker 用不同用户（注册/登录），绕过一人一单限制，模拟真实抢购
 * 用法：node scripts/perf/seckill-multi-user.js [--concurrency 200] [--duration 5] [--ticket 21]
 * 前置：DB+Redis 库存=500，订单清空，Redis order:21 清空
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
const PASSWORD = '123456';

async function login(phone) {
  const res = await fetch(BASE_URL + '/user/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, password: PASSWORD })
  });
  const body = await res.json();
  return body.data;
}

async function runBench() {
  const stats = { total: 0, ok: 0, insufficient: 0, duplicate: 0, other: 0, errors: 0, latencyMs: [] };
  const deadline = Date.now() + DURATION_SEC * 1000;

  // 每个 worker 一个独立用户（从真实用户池取号，循环复用）
  const users = ['13912345678','13686869696','13838411438','13712345678','15812345678','13712340001','13812340002','13912340003','15012340004','15112340005','15212340006','15512340007','15712340008','13456789011','13456789001'];
  const tokens = [];
  for (const u of users) {
    const t = await login(u);
    if (t) tokens.push({ token: t, phone: u });
    await new Promise(r => setTimeout(r, 100)); // 避免登录并发被打
  }
  console.log(`已登录 ${tokens.length} 个用户（${users.length} 个号池，不足时循环复用）`);
  if (tokens.length === 0) { console.error('无可用 token'); process.exit(1); }

  async function worker(idx) {
    const { token } = tokens[idx % tokens.length];
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
        if (body && body.success === true) stats.ok++;
        else if (body && body.errorMsg && body.errorMsg.includes('库存')) stats.insufficient++;
        else if (body && body.errorMsg && body.errorMsg.includes('重复')) stats.duplicate++;
        else stats.other++;
      } catch (e) { stats.errors++; }
    }
  }

  const workers = Array.from({ length: CONCURRENCY }, (_, i) => worker(i));
  await Promise.all(workers);

  const lat = stats.latencyMs.sort((a, b) => a - b);
  const p50 = lat.length ? lat[Math.floor(0.5 * lat.length)] : 0;
  const p95 = lat.length ? lat[Math.floor(0.95 * lat.length)] : 0;
  const p99 = lat.length ? lat[Math.floor(0.99 * lat.length)] : 0;
  const avg = lat.length ? lat.reduce((a, b) => a + b, 0) / lat.length : 0;
  const max = lat.length ? lat[lat.length - 1] : 0;
  const qps = stats.total / DURATION_SEC;

  console.log(`\n===== 多用户秒杀压测 (ticket=${TICKET_ID}, 库存=500) =====`);
  console.log(`  并发=${CONCURRENCY} 时长=${DURATION_SEC}s`);
  console.log(`  总请求: ${stats.total}  (QPS=${qps.toFixed(1)})`);
  console.log(`  成功(拿到orderId): ${stats.ok}`);
  console.log(`  库存不足: ${stats.insufficient}`);
  console.log(`  重复下单(同用户重抢): ${stats.duplicate}`);
  console.log(`  其他失败: ${stats.other}`);
  console.log(`  网络错误: ${stats.errors}`);
  console.log(`  延迟: avg=${avg.toFixed(1)}ms p50=${p50.toFixed(1)} p95=${p95.toFixed(1)} p99=${p99.toFixed(1)} max=${max.toFixed(1)}`);
  return stats;
}

(async () => {
  const stats = await runBench();
  console.log(`\n========== 超卖核对指引 ==========`);
  console.log(`  查: SELECT COUNT(*) FROM tb_ticket_order WHERE ticket_id=${TICKET_ID}`);
  console.log(`      SELECT stock FROM tb_seckill_ticket WHERE ticket_id=${TICKET_ID}`);
  console.log(`      GET seckill:stock:${TICKET_ID} / SCARD seckill:order:${TICKET_ID}`);
  console.log(`  无超卖判定: 订单数 ≤500 且 DB库存+订单数=500 且 Redis库存+订单数=500`);
})();
