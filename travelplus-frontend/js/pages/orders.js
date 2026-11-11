/**
 * 【订单管理的实现】#/orders 秒杀券订单列表页面渲染模块
 * 职责：展示用户秒杀券订单列表（分页加载更多）、订单卡片（票名+金额+状态+时间）、点击订单进入详情、空态"暂无订单"、返回按钮
 * 依赖：api.js (get)、ui.js (fenToYuan/formatDate/showLoading/showEmpty/loadMore)、store.js (isLoggedIn)
 * 核心链路：
 *   renderOrders(container)
 *     → 登录保护（未登录跳 #/login?redirect=orders）
 *     → page-header（返回+标题"订单管理"）
 *     → GET /ticket-order/list?current=1 → 列表渲染
 *     → 每单卡片：票名(ticketName) + 金额(ticketPrice，分→元 ¥x.xx) + 状态徽标(statusText) + 下单时间(createTime) + 支付时间(payTime)
 *     → 点击订单卡片 → #/order/:id 进入订单详情
 *     → 分页加载更多（loadMore + .load-sentinel 哨兵，参考 messages.js 分类页模式）
 *     → 空态："暂无订单"
 *     → 返回按钮：history.back()
 */
import { get } from '../api.js';
import { fenToYuan, formatDate, showLoading, showEmpty, loadMore } from '../ui.js';
import { isLoggedIn } from '../store.js';

export async function renderOrders(container) {
  // 登录保护
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('orders');
    return;
  }

  container.innerHTML = `
    <div id="orders-root">
      <div class="page-header">
        <button class="header-back" id="orders-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>订单管理</h2>
      </div>
      <div id="orders-content" class="orders-content">
        <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
      </div>
    </div>
  `;

  const root = container.querySelector('#orders-root');
  root.querySelector('#orders-back').addEventListener('click', () => {
    window.history.back();
  });

  const contentEl = root.querySelector('#orders-content');

  /** 【订单管理的实现】渲染订单列表
   * 实现细节：
   * - GET /ticket-order/list?current=1 → 解析 records 或直接数组
   * - ticketPrice 为后端存储单位"分"，前端用 fenToYuan 转为"元"显示 ¥x.xx
   * - statusText 状态徽标：根据 status 字段追加 CSS 类名（done/pending/cancel）实现不同颜色
   * - createTime 和 payTime 均用 formatDate 格式化为绝对时间
   * - 空列表显示"暂无订单"
   */
  async function loadOrders() {
    showLoading(contentEl);
    try {
      const data = await get('/ticket-order/list?current=1');
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

      if (!list.length) {
        contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无订单</p></div>';
        return;
      }

      /** 【订单管理的实现】状态 CSS 类名映射
       * - 后端 status 为数字：1 未支付 → pending；2 已支付 / 3 已核销 → done；4 已取消 / 5 退款中 / 6 已退款 → cancel
       * - 归一为数字后映射（不依赖 toLowerCase，避免对数字调用 String 方法抛异常）
       * - 未知状态（含 NaN）默认无额外类名（使用默认灰色）
       */
      function statusClass(status) {
        const s = Number(status);
        if (s === 2 || s === 3) return 'done';
        if (s === 1) return 'pending';
        if (s === 4 || s === 5 || s === 6) return 'cancel';
        return '';
      }

      /** 【订单管理的实现】支付方式文本映射：payType 1 余额支付 / 2 支付宝 / 3 微信支付 */
      function payTypeText(payType) {
        const map = { 1: '余额支付', 2: '支付宝', 3: '微信支付' };
        return map[Number(payType)] || '余额支付';
      }

      /** 【订单管理的实现】单个订单卡片 HTML（首屏渲染与分页追加共用，避免两处重复）
       * - 结构：票名+状态 → 金额主行（大字+支付方式）→ NO.订单号+时间（小字）
       * - 支付方式：payType 数字映射为中文文本
       */
      function orderCardHtml(item) {
        return `
          <div class="order-card" data-order-id="${item.id}">
            <div class="order-card-header">
              <span class="order-title">${item.ticketName || '门票'}</span>
              <span class="order-status ${statusClass(item.status)}">${item.statusText || item.status || ''}</span>
            </div>
            <div class="order-amount-row">
              <span class="order-price">&yen;${fenToYuan(item.ticketPrice)}</span>
              ${item.payType ? '<span class="order-paytype">' + payTypeText(item.payType) + '</span>' : ''}
            </div>
            <div class="order-info">
              <div class="order-card-id">NO.${item.id}</div>
              <div class="order-time">下单 ${formatDate(item.createTime)}</div>
              ${item.payTime ? '<div class="order-time">支付 ' + formatDate(item.payTime) + '</div>' : ''}
            </div>
            ${Number(item.status) === 1 ? '<div class="order-pay-tip">请在 15 分钟内完成付款，超时订单自动取消并回补库存</div>' : ''}
          </div>`;
      }

      let html = '';
      list.forEach(item => {
        html += orderCardHtml(item);
      });
      contentEl.innerHTML = html;

      /** 【订单详情导航】点击订单卡片 → 进入订单详情页 #/order/:id
       * 实现细节：
       * - 使用事件委托在 contentEl 层监听，统一处理首屏和分页加载的卡片
       * - data-order-id 属性标记订单 ID，点击时取 closest('.order-card') 获取
       */
      contentEl.addEventListener('click', (e) => {
        const card = e.target.closest('.order-card');
        if (card && card.dataset.orderId) {
          window.location.hash = '#/order/' + card.dataset.orderId;
        }
      });

      /** 【订单管理的实现】分页加载更多历史订单
       * 实现细节：
       * - 首屏 list.length >= 10 时插入哨兵（.load-sentinel-orders），否则不显示分页
       * - 滚动触发 loadMore → GET /ticket-order/list?current=N 追加订单卡片
       * - 追加方式：创建 order-card DOM 元素插入哨兵之前
       * - 无更多时移除哨兵；失败静默移除哨兵
       */
      if (list.length >= 10) {
        let current = 2;
        const sentinel = document.createElement('div');
        sentinel.className = 'load-sentinel load-sentinel-orders';
        sentinel.style.cssText = 'height:10px;width:100%';
        contentEl.appendChild(sentinel);

        loadMore(contentEl, async () => {
          try {
            const moreData = await get('/ticket-order/list?current=' + current);
            const moreList = Array.isArray(moreData) ? moreData : (moreData && moreData.records ? moreData.records : []);
            if (!moreList.length) {
              sentinel.remove();
              return;
            }
            moreList.forEach(item => {
              const wrap = document.createElement('div');
              wrap.innerHTML = orderCardHtml(item);
              const el = wrap.firstElementChild;
              contentEl.insertBefore(el, sentinel);
            });
            current++;
          } catch {
            sentinel.remove();
          }
        }, 'load-sentinel-orders');
      }

    } catch {
      contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
    }
  }

  await loadOrders();
}
