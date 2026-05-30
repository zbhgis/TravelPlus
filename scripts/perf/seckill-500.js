/**
 * 秒杀 500 库存全耗尽压测（600 用户一人一单验证不超卖）
 * 前置：DB+Redis 库存=500，ticket 21 订单清空，Redis order:21 清空，600 个测试用户存在（15300000001~600）
 * 用法：node scripts/perf/seckill-500.js [--concurrency 200] [--duration 6] [--ticket 21]
 * 输出：QPS、成功数、各失败分类、延迟分布；测后核对 DB/Redis 一致性判断是否超卖
 */
const args = process.argv.slice(2);
const getArg = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
};
const CONCURRENCY = parseInt(getArg('concurrency', '200'), 10);
const DURATION_SEC = parseInt(getArg('duration', '6'), 10);
const TICKET_ID = parseInt(getArg('ticket', '21'), 10);
const BASE_URL = String(getArg('url', 'http://localhost:8081'));
const PASSWORD = '123456';

const phoneFor = (n) => '153' + String(2 + n).padStart(8, '0'); // 15300000002..15300000601

async function login(phone) {
  try {
    const res = await fetch(BASE_URL + '/user/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ phone, password: PASSWORD })
    });
    const body = await res.json();
    return body.data;
  } catch (e) { return null; }
}

(async () => {
  // 1. 批量登录 600 用户（并行 50）
  console.log('批量登录 600 用户...');
  const phones = Array.from({ length: 600 }, (_, i) => phoneFor(i));
  const tokens = [];
  const loginQueue = [...phones];
  const loginWorkers = Array.from({ length: 50 }, async () => {
    while (loginQueue.length) {
      const phone = loginQueue.shift();
      const t = await login(phone);
      if (t) tokens.push(t);
    }
  });
  await Promise.all(loginWorkers);
  console.log(`登录成功 ${tokens.length}/600`);
  if (tokens.length < 500) { console.error('可用用户不足 500，无法验证'); process.exit(1); }

  // 2. 压测：200 并发打秒杀，每个请求轮换不同用户
  const stats = { total: 0, ok: 0, insufficient: 0, duplicate: 0, other: 0, errors: 0, latencyMs: [] };
  const deadline = Date.now() + DURATION_SEC * 1000;
  let idx = 0;
  const nextToken = () => tokens[idx++ % tokens.length];

  async function worker() {
    while (Date.now() < deadline) {
      const token = nextToken();
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

  const workers = Array.from({ length: CONCURRENCY }, worker);
  await Promise.all(workers);

  const lat = stats.latencyMs.sort((a, b) => a - b);
  const p50 = lat.length ? lat[Math.floor(0.5 * lat.length)] : 0;
  const p95 = lat.length ? lat[Math.floor(0.95 * lat.length)] : 0;
  const p99 = lat.length ? lat[Math.floor(0.99 * lat.length)] : 0;
  const avg = lat.length ? lat.reduce((a, b) => a + b, 0) / lat.length : 0;
  const max = lat.length ? lat[lat.length - 1] : 0;
  const qps = stats.total / DURATION_SEC;

  console.log(`\n===== 500 库存全耗尽压测 =====`);
  console.log(`  并发=${CONCURRENCY} 时长=${DURATION_SEC}s QPS=${qps.toFixed(1)}`);
  console.log(`  总请求: ${stats.total}`);
  console.log(`  成功: ${stats.ok}  (应 = 500，即库存全部售出)`);
  console.log(`  库存不足: ${stats.insufficient}  重复: ${stats.duplicate}  其他: ${stats.other}  错误: ${stats.errors}`);
  console.log(`  延迟: avg=${avg.toFixed(1)}ms p50=${p50.toFixed(1)} p95=${p95.toFixed(1)} p99=${p99.toFixed(1)} max=${max.toFixed(1)}`);
  console.log(`\n===== 超卖判定指引 =====`);
  console.log(`  查 DB: SELECT COUNT(*) FROM tb_ticket_order WHERE ticket_id=${TICKET_ID}`);
  console.log(`       SELECT stock FROM tb_seckill_ticket WHERE ticket_id=${TICKET_ID}`);
  console.log(`  查 Redis: GET seckill:stock:${TICKET_ID} 与 SCARD seckill:order:${TICKET_ID}`);
  console.log(`  无超卖 = 订单数==500 且 DB库存==0 且 Redis库存==0 且 SCARD==500`);
  console.log(`  超卖 = 订单数 > 500（DB 条件更新已拦）或 三方不一致`);
})();
