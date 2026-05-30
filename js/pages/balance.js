/**
 * 【余额明细的实现】#/balance 余额明细与充值页面渲染模块
 * 职责：展示当前余额大卡、充值入口（快捷金额+微信/支付宝选择）、消费记录流水列表（分页加载更多）、返回按钮
 * 依赖：api.js (get/post)、ui.js (formatDate/showLoading/toast/confirmDialog/loadMore)、store.js (isLoggedIn)
 * 核心链路：
 *   renderBalance(container)
 *     → 登录保护（未登录跳 #/login?redirect=balance）
 *     → page-header（返回+标题"余额"）
 *     → 顶部余额卡：（当前余额大字 ¥xx.xx）
 *     → 充值入口卡："充值"按钮 + 说明文字
 *       → 点击 → 弹出底部面板 modal：金额输入/快捷金额（50/100/200/500）+ 支付方式 tab（微信/支付宝）
 *       → 确认 → POST /user/recharge {amount, payMethod} → toast"充值成功" + 刷新余额与记录
 *     → GET /user/balance/records?current=1 → 消费记录列表（描述+类型徽标+金额+时间）
 *     → 分页加载更多（loadMore 哨兵，参考 orders.js）
 *     → 空态/加载失败兜底；返回 history.back()
 *
 * 【充值交互的实现】底部弹层充值流程
 * 实现细节：
 * - 快捷金额 50/100/200/500 按钮；也可手动输入金额
 * - 支付方式：微信支付/支付宝两个 tab（.recharge-pay-tab）
 * - 确认按钮：防重复点击 + disabled + "充值中…"
 * - POST /user/recharge body {amount:元, payMethod:'wechat'|'ali'} → 模拟直接成功
 * - 成功：toast("充值成功") + 关闭弹层 + 刷新余额卡 + 刷新记录列表
 * - 失败：api.js 已 toast，恢复按钮状态
 */
import { get, post } from '../api.js';
import { formatDate, toast, loadMore } from '../ui.js';
import { isLoggedIn } from '../store.js';

export async function renderBalance(container) {
  // 登录保护
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('balance');
    return;
  }

  container.innerHTML = `
    <div id="balance-root">
      <div class="page-header">
        <button class="header-back" id="balance-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>余额</h2>
      </div>
      <div id="balance-content" class="balance-content">
        <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
      </div>
    </div>
  `;

  const root = container.querySelector('#balance-root');
  root.querySelector('#balance-back').addEventListener('click', () => {
    window.history.back();
  });

  const contentEl = root.querySelector('#balance-content');
  let currentBalance = '0.00';

  /** 【余额明细的实现】格式化变动金额显示 */
  function formatAmount(changeAmount) {
    const val = Number(changeAmount) || 0;
    if (val > 0) return '+' + val.toFixed(2);
    if (val < 0) return val.toFixed(2);
    return '0.00';
  }

  function amountClass(changeAmount) {
    const val = Number(changeAmount) || 0;
    return val >= 0 ? 'positive' : 'negative';
  }

  /** 【余额明细的实现】渲染顶部余额卡 + 充值入口 */
  function renderBalanceCard(balance) {
    let html = '<div class="balance-top-card">';
    html += '<div class="balance-top-label">当前余额</div>';
    html += '<div class="balance-top-value">&yen;' + (Number(balance).toFixed(2)) + '</div>';
    html += '</div>';
    html += '<div class="balance-recharge-card">';
    html += '<div class="balance-recharge-info">';
    html += '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#d97706" stroke-width="1.5" style="flex-shrink:0"><rect x="1" y="4" width="22" height="16" rx="2"/><line x1="1" y1="10" x2="23" y2="10"/></svg>';
    html += '<div><div class="balance-recharge-title">余额充值</div><div class="balance-recharge-desc">支持微信支付 / 支付宝</div></div>';
    html += '</div>';
    html += '<button class="btn btn-primary btn-sm" id="balance-recharge-btn">充值</button>';
    html += '</div>';
    return html;
  }

  /** 【余额明细的实现】渲染消费记录行 */
  function renderRecordRow(item) {
    return '<div class="balance-record">' +
      '<div class="balance-record-desc">' +
        (item.description || item.typeText || '交易记录') +
        '<div class="balance-record-time">' + formatDate(item.createTime) + '</div>' +
      '</div>' +
      (item.typeText ? '<span class="balance-type-tag">' + item.typeText + '</span>' : '') +
      '<span class="balance-change ' + amountClass(item.changeAmount) + '">' + formatAmount(item.changeAmount) + '</span>' +
    '</div>';
  }

  /** 【余额明细的实现】加载并渲染完整页面 */
  async function loadPage() {
    contentEl.innerHTML = '<div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>';
    try {
      // 并行加载钱包余额和记录
      const [walletData, recordsData] = await Promise.all([
        get('/user/wallet'),
        get('/user/balance/records?current=1')
      ]);

      currentBalance = (walletData && walletData.balance != null) ? String(walletData.balance) : '0.00';
      const list = Array.isArray(recordsData) ? recordsData : (recordsData && recordsData.records ? recordsData.records : []);

      let html = renderBalanceCard(currentBalance);

      if (!list.length) {
        html += '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无消费记录</p></div>';
      } else {
        html += '<div class="balance-records-section">';
        html += '<div class="section-title">消费记录</div>';
        list.forEach(item => {
          html += renderRecordRow(item);
        });
        html += '</div>';
      }

      contentEl.innerHTML = html;

      // 绑定充值按钮
      const rechargeBtn = document.getElementById('balance-recharge-btn');
      if (rechargeBtn) {
        rechargeBtn.addEventListener('click', () => showRechargeModal());
      }

      // 分页加载更多
      if (list.length >= 10) {
        let current = 2;
        const recordsSection = contentEl.querySelector('.balance-records-section');
        const sentinel = document.createElement('div');
        sentinel.className = 'load-sentinel load-sentinel-balance';
        sentinel.style.cssText = 'height:10px;width:100%';
        if (recordsSection) recordsSection.appendChild(sentinel);

        loadMore(contentEl, async () => {
          try {
            const moreData = await get('/user/balance/records?current=' + current);
            const moreList = Array.isArray(moreData) ? moreData : (moreData && moreData.records ? moreData.records : []);
            if (!moreList.length) {
              sentinel.remove();
              return;
            }
            moreList.forEach(item => {
              const el = document.createElement('div');
              el.className = 'balance-record';
              el.innerHTML =
                '<div class="balance-record-desc">' +
                  (item.description || item.typeText || '交易记录') +
                  '<div class="balance-record-time">' + formatDate(item.createTime) + '</div>' +
                '</div>' +
                (item.typeText ? '<span class="balance-type-tag">' + item.typeText + '</span>' : '') +
                '<span class="balance-change ' + amountClass(item.changeAmount) + '">' + formatAmount(item.changeAmount) + '</span>';
              contentEl.insertBefore(el, sentinel);
            });
            current++;
          } catch {
            sentinel.remove();
          }
        }, 'load-sentinel-balance');
      }

    } catch {
      contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
    }
  }

  /** 【充值交互的实现】弹出充值底部面板 */
  function showRechargeModal() {
    // 移除已存在的充值弹层
    const existing = document.querySelector('.recharge-mask');
    if (existing) existing.remove();

    const quickAmounts = [50, 100, 200, 500];

    const mask = document.createElement('div');
    mask.className = 'recharge-mask';
    mask.innerHTML = `
      <div class="recharge-panel">
        <div class="recharge-panel-header">
          <span class="recharge-panel-title">余额充值</span>
          <button class="recharge-panel-close">&times;</button>
        </div>
        <div class="recharge-panel-body">
          <div class="recharge-input-row">
            <span class="recharge-currency">&yen;</span>
            <input type="number" class="recharge-amount-input" id="recharge-amount" placeholder="请输入金额" min="0.01" step="0.01" autofocus>
          </div>
          <div class="recharge-quick-amounts">
            ${quickAmounts.map(a => '<button class="recharge-quick-btn" data-amount="' + a + '">' + a + '元</button>').join('')}
          </div>
          <div class="recharge-pay-label">支付方式</div>
          <div class="recharge-pay-tabs">
            <button class="recharge-pay-tab active" data-method="wechat">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M8.5 11a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z"/><path d="M15.5 11a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z"/><path d="M5 14c3.5 3 10.5 3 14 0"/><circle cx="12" cy="12" r="10"/></svg>
              <span>微信支付</span>
            </button>
            <button class="recharge-pay-tab" data-method="ali">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="1" y="4" width="22" height="16" rx="2"/><line x1="1" y1="10" x2="23" y2="10"/></svg>
              <span>支付宝</span>
            </button>
          </div>
        </div>
        <div class="recharge-panel-footer">
          <button class="btn btn-primary btn-block" id="recharge-confirm-btn">确认充值</button>
        </div>
      </div>
    `;

    document.body.appendChild(mask);
    // 入场动画
    requestAnimationFrame(() => {
      mask.classList.add('show');
    });

    let payMethod = 'wechat';

    // 关闭弹层
    function closePanel() {
      mask.classList.remove('show');
      setTimeout(() => {
        if (mask.parentNode) mask.remove();
      }, 250);
    }

    mask.querySelector('.recharge-panel-close').addEventListener('click', closePanel);
    mask.addEventListener('click', (e) => {
      if (e.target === mask) closePanel();
    });

    // 快捷金额点击
    mask.querySelectorAll('.recharge-quick-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const amt = btn.dataset.amount;
        const input = mask.querySelector('#recharge-amount');
        if (input) input.value = amt;
      });
    });

    // 支付方式 tab 切换
    mask.querySelectorAll('.recharge-pay-tab').forEach(tab => {
      tab.addEventListener('click', () => {
        mask.querySelectorAll('.recharge-pay-tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        payMethod = tab.dataset.method;
      });
    });

    // 确认充值按钮
    const confirmBtn = mask.querySelector('#recharge-confirm-btn');
    let recharging = false;
    confirmBtn.addEventListener('click', async () => {
      if (recharging) return;
      const input = mask.querySelector('#recharge-amount');
      const amount = parseFloat(input.value);
      if (isNaN(amount) || amount <= 0) {
        toast('请输入有效充值金额');
        return;
      }

      recharging = true;
      confirmBtn.disabled = true;
      confirmBtn.textContent = '充值中…';

      try {
        await post('/user/recharge', { amount: amount, payMethod: payMethod });
        toast('充值成功');
        closePanel();
        // 刷新整个页面
        await loadPage();
      } catch {
        recharging = false;
        confirmBtn.disabled = false;
        confirmBtn.textContent = '确认充值';
      }
    });
  }

  await loadPage();
}
