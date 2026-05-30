/**
 * 【钱包资产的实现】#/wallet 我的钱包页面渲染模块
 * 职责：展示用户三大资产（余额/积分/秒杀券）三张卡片并排、返回按钮、积分说明轻提示
 * 依赖：api.js (get)、ui.js (showLoading)、store.js (isLoggedIn)
 * 核心链路：
 *   renderWallet(container)
 *     → 登录保护（未登录跳 #/login?redirect=wallet）
 *     → page-header（返回+标题"我的钱包"）
 *     → GET /user/wallet → 渲染三资产卡片：余额（¥balance，balance 为 BigDecimal 元）、积分（points 分）、秒杀券（couponCount 张）
 *     → 三卡横向等宽排列（.wallet-cards → .wallet-card），每张卡可点击跳转各自详情页，右上角小箭头提示可点
 *     → 积分说明轻提示（签到每天+2积分）
 *     → 返回按钮：history.back()
 *
 * 【钱包卡点击跳转的实现】三资产卡点击分别跳转各自详情页
 * 实现细节：
 * - 余额卡 → #/balance（余额明细+充值+消费记录）
 * - 积分卡 → #/points（积分流水+积分活动入口）
 * - 秒杀券卡 → #/coupons（未使用/已核销 tab+条形码弹层）
 * - 卡片加 cursor:pointer + :active 反馈（CSS .wallet-card-clickable 类）
 * - 右上角小箭头 SVG 提示可点（.wallet-card-arrow 元素）
 */
import { get } from '../api.js';
import { showLoading } from '../ui.js';
import { isLoggedIn } from '../store.js';

export async function renderWallet(container) {
  // 登录保护
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('wallet');
    return;
  }

  container.innerHTML = `
    <div id="wallet-root">
      <div class="page-header">
        <button class="header-back" id="wallet-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>我的钱包</h2>
      </div>
      <div id="wallet-content" class="wallet-content">
        <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
      </div>
    </div>
  `;

  const root = container.querySelector('#wallet-root');
  root.querySelector('#wallet-back').addEventListener('click', () => {
    window.history.back();
  });

  const contentEl = root.querySelector('#wallet-content');

  /** 【钱包资产的实现】加载钱包资产数据并渲染三卡片
   * 实现细节：
   * - GET /user/wallet → data=Map{balance(BigDecimal 元), points(积分 Long), couponCount(秒杀券数)}
   * - balance 已是元字符串（如 "0.00"），直接显示 ¥xxx
   * - points 为整数 Long，直接显示数字
   * - couponCount 为整数，显示"x张"
   * - 三卡横向等宽排列（flex 三等分），图标圆形底区分颜色（金色=余额、绿色=积分、蓝色=秒杀券）
   */
  try {
    const data = await get('/user/wallet');
    const balance = data && data.balance != null ? data.balance : '0.00';
    const points = data && data.points != null ? data.points : 0;
    const couponCount = data && data.couponCount != null ? data.couponCount : 0;

    contentEl.innerHTML = `
      <div class="wallet-cards">
        <div class="wallet-card wallet-card-clickable" data-nav="balance">
          <div class="wallet-card-arrow">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#bbb" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
          </div>
          <div class="wallet-card-icon balance">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#d97706" stroke-width="1.5"><rect x="1" y="4" width="22" height="16" rx="2"/><line x1="1" y1="10" x2="23" y2="10"/></svg>
          </div>
          <div class="wallet-card-value">&yen;${balance}</div>
          <div class="wallet-card-label">余额</div>
        </div>
        <div class="wallet-card wallet-card-clickable" data-nav="points">
          <div class="wallet-card-arrow">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#bbb" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
          </div>
          <div class="wallet-card-icon points">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.5"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
          </div>
          <div class="wallet-card-value">${points}</div>
          <div class="wallet-card-label">积分</div>
        </div>
        <div class="wallet-card wallet-card-clickable" data-nav="coupons">
          <div class="wallet-card-arrow">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#bbb" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
          </div>
          <div class="wallet-card-icon coupon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#2563eb" stroke-width="1.5"><path d="M20 12V8H6a2 2 0 0 1-2-2c0-1.1.9-2 2-2h12v4"/><path d="M4 6v12c0 1.1.9 2 2 2h14v-4"/><path d="M18 12a2 2 0 0 0 0 4h4v-4h-4z"/></svg>
          </div>
          <div class="wallet-card-value">${couponCount} 张</div>
          <div class="wallet-card-label">秒杀券</div>
        </div>
      </div>
      <div class="wallet-tip">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="2" style="vertical-align:middle;margin-right:4px"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
        积分说明：签到每天 +2 积分，参与活动也可获取积分
      </div>
    `;

    // 绑定卡片点击跳转
    contentEl.querySelectorAll('.wallet-card-clickable').forEach(card => {
      card.addEventListener('click', () => {
        const nav = card.dataset.nav;
        if (nav) window.location.hash = '#/' + nav;
      });
    });
  } catch {
    contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}
