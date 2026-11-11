/**
 * 【秒杀券的实现】#/coupons 我的秒杀券页面渲染模块
 * 职责：展示秒杀券列表（未使用/已核销两个 tab）、券卡片（票名+券编号+状态+时间）、点击券显示条形码弹层、返回按钮
 * 依赖：api.js (get)、ui.js (formatDate/showLoading/toast)、store.js (isLoggedIn)
 * 核心链路：
 *   renderCoupons(container)
 *     → 登录保护（未登录跳 #/login?redirect=coupons）
 *     → page-header（返回+标题"秒杀券"）
 *     → 未使用/已核销两个 tab（复用 .fav-tabs 风格）
 *     → tab 切换分别请求 GET /ticket-order/list?current=1&status=2（未使用=已支付）和 ?status=3（已核销）
 *     → 券卡片（车票造型）：主券（票名+券号 NO.+购买/支付时间）+ 撕票虚线 + 副券（金额+状态+票码入口），已核销票根置灰
 *     → 点击券卡 → 弹层显示条形码（JsBarcode CDN canvas 渲染 + CSS 降级）+ 完整订单详情
 *     → 空态：每个 tab 独立显示无券提示
 *     → 返回按钮：history.back()
 *
 * 【条形码的实现】JsBarcode CDN + CSS 降级
 * 实现细节：
 * - CDN 引入：index.html 加 <script src="jsbarcode CDN">，同 Leaflet CDN 模式
 * - 渲染：JsBarcode('#barcode', 订单id, {format:'CODE128', width:2, height:80, displayValue:true})
 * - 降级：CDN 不可达时，检测 typeof JsBarcode === 'undefined' → 用纯 CSS 条纹模拟条形码
 * - 弹层结构：modal-mask + 条形码卡片（包含 canvas/CSS 降级 + 完整订单信息 + 有效期说明）
 */
import { get } from '../api.js';
import { formatDate, fenToYuan, showLoading, toast } from '../ui.js';
import { isLoggedIn } from '../store.js';

export async function renderCoupons(container) {
  // 登录保护
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('coupons');
    return;
  }

  container.innerHTML = `
    <div id="coupons-root">
      <div class="page-header">
        <button class="header-back" id="coupons-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>秒杀券</h2>
      </div>
      <div class="fav-tabs" id="coupons-tabs">
        <div class="fav-tab active" data-status="2">未使用</div>
        <div class="fav-tab" data-status="3">已核销</div>
      </div>
      <div id="coupons-content" class="coupons-content">
        <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
      </div>
    </div>
  `;

  const root = container.querySelector('#coupons-root');
  root.querySelector('#coupons-back').addEventListener('click', () => {
    window.history.back();
  });

  const contentEl = root.querySelector('#coupons-content');
  const tabsContainer = root.querySelector('#coupons-tabs');

  /** 【秒杀券详情的实现】渲染券列表 */
  function renderCouponList(list) {
    if (!list.length) {
      contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无秒杀券</p></div>';
      return;
    }
    let html = '';
    list.forEach(item => {
      /* 车票造型：左侧主券（票名/券号/时间）+ 撕票虚线 + 右侧副券（金额/状态/票码入口） */
      const used = Number(item.status) === 3;
      const payTimeHtml = item.payTime
        ? `<div class="coupon-ticket-meta">支付 ${formatDate(item.payTime)}</div>`
        : '';
      html += `
        <div class="coupon-ticket${used ? ' used' : ''}" data-order-id="${item.id}">
          <div class="coupon-ticket-main">
            <div class="coupon-ticket-name">${item.ticketName || '门票'}</div>
            <div class="coupon-ticket-no">NO.${item.id}</div>
            <div class="coupon-ticket-meta">购买 ${formatDate(item.createTime)}</div>
            ${payTimeHtml}
          </div>
          <div class="coupon-ticket-stub">
            <div class="coupon-ticket-price">¥${fenToYuan(item.ticketPrice)}</div>
            <div class="coupon-ticket-status">${item.statusText || ''}</div>
            <div class="coupon-ticket-cta">票码 ›</div>
          </div>
        </div>`;
    });
    contentEl.innerHTML = html;

    // 绑定券卡片点击 → 显示条形码弹层（按索引回传完整 item）
    contentEl.querySelectorAll('.coupon-ticket').forEach((card, i) => {
      card.addEventListener('click', () => {
        showBarcodeModal(list[i]);
      });
    });
  }

  /** 【秒杀券详情的实现】显示条形码弹层（含完整订单详情） */
  function showBarcodeModal(item) {
    // 移除已存在的弹层
    const existing = document.querySelector('.barcode-mask');
    if (existing) existing.remove();

    const orderId = String(item.id);
    const ticketName = item.ticketName || '门票';
    const payTimeHtml = item.payTime
      ? `<div class="barcode-info-row"><span class="barcode-info-label">支付时间</span><span class="barcode-info-value">${formatDate(item.payTime)}</span></div>`
      : '';

    const mask = document.createElement('div');
    mask.className = 'barcode-mask';
    mask.innerHTML = `
      <div class="barcode-card">
        <div class="barcode-card-header">
          <span>秒杀券条形码</span>
          <button class="barcode-card-close">&times;</button>
        </div>
        <div class="barcode-card-body">
          <div class="barcode-canvas-wrap">
            <canvas id="barcode-canvas" width="300" height="120"></canvas>
            <div class="barcode-fallback" id="barcode-fallback" style="display:none">
              <!-- CSS 条纹降级 -->
              ${generateBarcodeCSS(orderId)}
              <div class="barcode-fallback-num">${orderId}</div>
            </div>
          </div>
          <div class="barcode-info">
            <div class="barcode-info-row"><span class="barcode-info-label">订单号</span><span class="barcode-info-value">${orderId}</span></div>
            <div class="barcode-info-row"><span class="barcode-info-label">票名</span><span class="barcode-info-value">${ticketName}</span></div>
            <div class="barcode-info-row"><span class="barcode-info-label">金额</span><span class="barcode-info-value">¥${fenToYuan(item.ticketPrice)}</span></div>
            <div class="barcode-info-row"><span class="barcode-info-label">购买时间</span><span class="barcode-info-value">${formatDate(item.createTime)}</span></div>
            ${payTimeHtml}
            <div class="barcode-info-row"><span class="barcode-info-label">状态</span><span class="barcode-info-value">${item.statusText || ''}</span></div>
            <div class="barcode-info-tip">有效期：购买日起 30 天内有效</div>
          </div>
        </div>
      </div>
    `;

    document.body.appendChild(mask);
    requestAnimationFrame(() => {
      mask.classList.add('show');
    });

    // 关闭按钮
    function closeBarcode() {
      mask.classList.remove('show');
      setTimeout(() => {
        if (mask.parentNode) mask.remove();
      }, 220);
    }

    mask.querySelector('.barcode-card-close').addEventListener('click', closeBarcode);
    mask.addEventListener('click', (e) => {
      if (e.target === mask) closeBarcode();
    });

    // 尝试 JsBarcode 渲染，失败则降级
    try {
      if (typeof JsBarcode !== 'undefined') {
        JsBarcode('#barcode-canvas', String(orderId), {
          format: 'CODE128',
          width: 2,
          height: 80,
          displayValue: true
        });
        const fallback = mask.querySelector('#barcode-fallback');
        if (fallback) fallback.style.display = 'none';
      } else {
        throw new Error('JsBarcode not loaded');
      }
    } catch {
      // 降级：隐藏 canvas，显示 CSS 条纹
      const canvas = mask.querySelector('#barcode-canvas');
      const fallback = mask.querySelector('#barcode-fallback');
      if (canvas) canvas.style.display = 'none';
      if (fallback) fallback.style.display = 'block';
    }
  }

  /** 【条形码的实现】用纯 CSS 条纹模拟条形码降级显示 */
  function generateBarcodeCSS(idStr) {
    // 基于 id 字符串生成伪随机宽度的竖线条纹
    let html = '<div class="barcode-stripes">';
    for (let i = 0; i < idStr.length; i++) {
      const charCode = idStr.charCodeAt(i);
      const width = 2 + (charCode % 4); // 2-5px 宽度
      const marginRight = 1 + (charCode % 2); // 1-2px 间距
      html += '<span style="display:inline-block;width:' + width + 'px;height:80px;background:#333;margin-right:' + marginRight + 'px"></span>';
    }
    // 额外填充条纹使条形码更长
    for (let i = 0; i < 20; i++) {
      const w = 2 + (i % 4);
      const mr = 1 + (i % 2);
      html += '<span style="display:inline-block;width:' + w + 'px;height:80px;background:#333;margin-right:' + mr + 'px"></span>';
    }
    html += '</div>';
    return html;
  }

  /** 【秒杀券的实现】按 status 加载券列表 */
  async function loadCoupons(status) {
    showLoading(contentEl);
    try {
      const data = await get('/ticket-order/list?current=1&status=' + status);
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
      renderCouponList(list);
    } catch {
      contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
    }
  }

  // tab 切换事件
  tabsContainer.querySelectorAll('.fav-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      tabsContainer.querySelectorAll('.fav-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      loadCoupons(tab.dataset.status);
    });
  });

  // 默认加载未使用（status=2）
  await loadCoupons('2');
}
