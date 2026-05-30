/**
 * 【订单详情的实现】#/order/:id 订单详情与支付页面渲染模块
 * 职责：展示订单完整信息（票名/金额/状态/下单时间/支付时间等）、未支付订单显示支付入口（当前余额+积分抵扣+立即支付按钮）
 * 依赖：api.js (get/post)、ui.js (fenToYuan/formatDate/toast/confirmDialog)、store.js (isLoggedIn)
 * 核心链路：
 *   renderOrderDetail(container, params)
 *     → 登录保护（未登录跳 #/login?redirect=order/:id）
 *     → page-header（返回+标题"订单详情"）
 *     → GET /ticket-order/{id} → 详情渲染
 *     → 状态横幅（pending/done/cancel 三色）
 *     → 信息卡片（票名/金额/时间/支付方式）
 *     → 未支付（status=1）显示支付区：
 *       → GET /user/wallet → 获取当前余额 + 积分
 *       → 积分抵扣控件：显示可用积分 + 快捷选择(10/50/100/200) + 实时显示应付金额
 *       → "立即支付"按钮 → confirmDialog（含抵扣信息） → POST /ticket-order/pay/{id} {usePoints}
 *       → 成功 toast + 重新加载详情；失败 api.js 已 toast（含"余额不足"）
 *     → 非本人/不存在 → "订单不存在或已删除"空态
 *     → 返回按钮：history.back()
 *
 * 【订单支付的实现】支付流程细节
 * 实现细节：
 * - 按钮加载态：disabled + "支付中…"文字，防重复点击（paying 标记）
 * - 积分抵扣：任意正整数均可抵扣，1 积分抵 0.01 元（usePoints/100）；快捷 10/50/100/200 或输入框
 * - 实时显示应付金额：原价 - 抵扣金额（最小 0.01）
 * - 确认弹窗：confirmDialog 含抵扣信息（"使用 X 积分抵扣 ¥X，实付 ¥Y 吗？"）
 * - 支付成功：toast("支付成功") + loadDetail() 重新加载；若返回数据含剩余余额/积分，延迟 500ms 二次 toast
 * - 支付失败：api.js 已 toast errorMsg，恢复按钮状态
 * - 钱包余额加载失败：显示"当前余额 ¥--"，不阻断支付流程
 */
import { get, post } from '../api.js';
import { fenToYuan, formatDate, toast, confirmDialog } from '../ui.js';
import { isLoggedIn } from '../store.js';

export async function renderOrderDetail(container, params) {
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('order/' + params.id);
    return;
  }

  const orderId = params.id;

  container.innerHTML = `
    <div id="order-detail-root">
      <div class="page-header">
        <button class="header-back" id="od-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>订单详情</h2>
      </div>
      <div id="order-detail-content">
        <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
      </div>
    </div>
  `;

  const root = container.querySelector('#order-detail-root');
  root.querySelector('#od-back').addEventListener('click', () => {
    window.history.back();
  });

  const contentEl = root.querySelector('#order-detail-content');

  /** 【订单详情/支付共用】状态 CSS 类名映射
   * - 复用 orders.js 同逻辑：1 未支付→pending；2 已支付·3 已核销→done；4 已取消·5 退款中·6 已退款→cancel
   */
  function statusClass(status) {
    const s = Number(status);
    if (s === 2 || s === 3) return 'done';
    if (s === 1) return 'pending';
    if (s === 4 || s === 5 || s === 6) return 'cancel';
    return '';
  }

  /** 【订单详情的实现】加载并渲染订单详情
   * 实现细节：
   * - GET /ticket-order/{id} → 渲染详情卡片（票名/金额/下单时间/支付时间/核销时间/退款时间/支付方式）
   * - 状态横幅根据 status 切换 pending/done/cancel 三色背景 + SVG 图标
   * - 非本人/不存在：api.js 已 toast fail 原因，页面显示"订单不存在或已删除"空态
   * - 未支付订单渲染后调用 initPaySection 初始化支付区
   */
  async function loadDetail() {
    try {
      const item = await get('/ticket-order/' + orderId);
      const s = Number(item.status);

      let bannerClass = '';
      if (s === 1) bannerClass = 'pending';
      else if (s === 2 || s === 3) bannerClass = 'done';
      else bannerClass = 'cancel';

      let bannerIcon = '';
      if (s === 1) {
        // 时钟图标：未支付
        bannerIcon = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>';
      } else if (s === 2 || s === 3) {
        // 对勾图标：已支付/已核销
        bannerIcon = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>';
      } else {
        // 叉号图标：已取消/退款
        bannerIcon = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>';
      }

      let html = `
        <div class="order-detail-banner ${bannerClass}">
          ${bannerIcon}
          <span>${item.statusText || ''}</span>
        </div>
        <div class="order-detail-card">
          <div class="order-detail-row">
            <span class="order-detail-label">门票名称</span>
            <span class="order-detail-value">${item.ticketName || '门票'}</span>
          </div>
          <div class="order-detail-row">
            <span class="order-detail-label">金额</span>
            <span class="order-detail-price">&yen;${fenToYuan(item.ticketPrice)}</span>
          </div>
          <div class="order-detail-row">
            <span class="order-detail-label">下单时间</span>
            <span class="order-detail-value">${formatDate(item.createTime)}</span>
          </div>
          ${item.payTime ? `<div class="order-detail-row"><span class="order-detail-label">支付时间</span><span class="order-detail-value">${formatDate(item.payTime)}</span></div>` : ''}
          ${item.useTime ? `<div class="order-detail-row"><span class="order-detail-label">核销时间</span><span class="order-detail-value">${formatDate(item.useTime)}</span></div>` : ''}
          ${item.refundTime ? `<div class="order-detail-row"><span class="order-detail-label">退款时间</span><span class="order-detail-value">${formatDate(item.refundTime)}</span></div>` : ''}
          ${item.payType ? `<div class="order-detail-row"><span class="order-detail-label">支付方式</span><span class="order-detail-value">${item.payType}</span></div>` : ''}
        </div>
      `;

      if (s === 1) {
        html += `
          <div class="order-pay-section" id="order-pay-section">
            <div class="order-pay-balance" id="order-pay-balance">加载余额中...</div>
            <div class="order-points-deduct" id="order-points-deduct" style="display:none">
              <div class="order-points-deduct-label">积分抵扣（1 积分抵 0.01 元，可任意数量抵扣）</div>
              <div class="order-points-deduct-row">
                <input type="number" class="order-points-input" id="order-points-input" placeholder="输入积分" min="0" step="1">
                <span class="order-points-available" id="order-points-available"></span>
              </div>
              <div class="order-points-quick">
                <button class="order-points-quick-btn" data-points="10">10</button>
                <button class="order-points-quick-btn" data-points="50">50</button>
                <button class="order-points-quick-btn" data-points="100">100</button>
                <button class="order-points-quick-btn" data-points="200">200</button>
              </div>
              <div class="order-points-result" id="order-points-result" style="display:none">抵扣后应付：<span class="order-points-final" id="order-points-final"></span></div>
            </div>
            <button class="btn btn-primary btn-block" id="order-pay-btn">立即支付</button>
            <div class="order-pay-tip">请在 15 分钟内完成付款，超时订单自动取消并回补库存</div>
          </div>`;
      }

      contentEl.innerHTML = html;

      if (s === 1) {
        await initPaySection(item);
      }
    } catch {
      contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>订单不存在或已删除</p></div>';
    }
  }

  /** 【订单支付的实现】初始化支付区—加载余额+积分 + 绑定支付按钮与积分抵扣
   * 实现细节：
   * - GET /user/wallet → 获取当前余额与积分并显示
   * - 钱包查询失败不阻断支付，显示"当前余额 ¥--"
   * - 积分抵扣：任意正整数均可抵扣，1 积分抵 0.01 元；显示可用积分 + 快捷 10/50/100/200 按钮 + 输入框
   * - 实时计算应付金额：原价 - 抵扣（最小 0.01）
   * - 支付按钮使用 paying 标记防重复点击 + disabled + "支付中…"文字三重保护
   * - 确认弹窗：confirmDialog 含抵扣信息
   * - 支付成功：toast + loadDetail 重新加载
   * - 支付失败：api.js 已 toast errorMsg，恢复按钮状态
   */
  async function initPaySection(item) {
    let walletBalance = 0;
    let walletPoints = 0;
    const originalPrice = item.ticketPrice; // 分
    const originalYuan = originalPrice / 100;

    try {
      const wallet = await get('/user/wallet');
      walletBalance = wallet && wallet.balance != null ? Number(wallet.balance) : 0;
      walletPoints = wallet && wallet.points != null ? Number(wallet.points) : 0;
      const balanceEl = document.getElementById('order-pay-balance');
      if (balanceEl) {
        balanceEl.textContent = '当前余额 ¥' + walletBalance.toFixed(2);
      }
      // 显示积分抵扣区
      const deductEl = document.getElementById('order-points-deduct');
      if (deductEl) deductEl.style.display = 'block';
      const availableEl = document.getElementById('order-points-available');
      if (availableEl) availableEl.textContent = '可用 ' + walletPoints + ' 积分';
    } catch {
      const balanceEl = document.getElementById('order-pay-balance');
      if (balanceEl) {
        balanceEl.textContent = '当前余额 ¥--';
      }
      const deductEl = document.getElementById('order-points-deduct');
      if (deductEl) deductEl.style.display = 'none';
    }

    // 积分抵扣逻辑
    let usePoints = 0;

    function updatePriceDisplay() {
      const pointsInput = document.getElementById('order-points-input');
      const resultEl = document.getElementById('order-points-result');
      const finalEl = document.getElementById('order-points-final');
      if (!pointsInput || !resultEl || !finalEl) return;
      let pts = parseInt(pointsInput.value) || 0;
      if (pts < 0) pts = 0;
      if (pts > walletPoints) {
        if (pts > 0) toast('积分不足，最多可用 ' + walletPoints + ' 积分');
        pts = walletPoints;
      }
      usePoints = pts;
      const deductYuan = pts / 100;
      const finalYuan = Math.max(originalYuan - deductYuan, 0.01);
      if (pts > 0) {
        resultEl.style.display = 'block';
        finalEl.textContent = '¥' + finalYuan.toFixed(2);
      } else {
        resultEl.style.display = 'none';
      }
    }

    // 积分输入框
    const pointsInput = document.getElementById('order-points-input');
    if (pointsInput) {
      pointsInput.addEventListener('input', updatePriceDisplay);
    }

    // 快捷积分按钮
    document.querySelectorAll('.order-points-quick-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const pts = parseInt(btn.dataset.points) || 0;
        if (pts <= walletPoints && pointsInput) {
          pointsInput.value = pts;
          updatePriceDisplay();
        } else if (pointsInput) {
          pointsInput.value = walletPoints;
          updatePriceDisplay();
        }
      });
    });

    const payBtn = document.getElementById('order-pay-btn');
    if (!payBtn) return;

    let paying = false;
    payBtn.addEventListener('click', async () => {
      if (paying) return;

      const deductYuan = usePoints / 100;
      const finalYuan = Math.max(originalYuan - deductYuan, 0.01);
      let confirmMsg = '确认使用余额支付 ¥' + finalYuan.toFixed(2) + ' 吗？';
      if (usePoints > 0) {
        confirmMsg = '使用 ' + usePoints + ' 积分抵扣 ¥' + deductYuan.toFixed(2) + '，实付 ¥' + finalYuan.toFixed(2) + ' 吗？';
      }

      const ok = await confirmDialog('确认支付', confirmMsg);
      if (!ok) return;

      paying = true;
      payBtn.disabled = true;
      payBtn.textContent = '支付中…';

      try {
        const result = await post('/ticket-order/pay/' + orderId, { usePoints: usePoints });
        toast('支付成功');
        await loadDetail();
        if (result && result.balance != null) {
          setTimeout(() => {
            toast('剩余余额 ¥' + Number(result.balance).toFixed(2));
          }, 500);
        }
      } catch {
        // api.js already toasted the error message; restore button state
        paying = false;
        payBtn.disabled = false;
        payBtn.textContent = '立即支付';
      }
    });
  }

  await loadDetail();
}
