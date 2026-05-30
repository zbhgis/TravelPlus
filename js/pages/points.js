/**
 * 【积分记录的实现】#/points 积分增减流水页面渲染模块
 * 职责：展示积分余额卡、积分活动入口、积分流水列表（分页加载更多）、每条记录（描述+类型标签+变动数值+时间）、空态"暂无积分记录"、返回按钮
 * 依赖：api.js (get)、ui.js (formatDate/showLoading/showEmpty/loadMore/toast)、store.js (isLoggedIn)
 * 核心链路：
 *   renderPoints(container)
 *     → 登录保护（未登录跳 #/login?redirect=points）
 *     → page-header（返回+标题"积分记录"）
 *     → 积分余额卡（GET /user/wallet 的 points）+ 积分活动入口按钮
 *     → GET /user/points/records?current=1 → 列表渲染
 *     → 每行：描述(description) + 类型标签(typeText) + 变动值(changePoints，正数绿色 +2、负数红色 -2) + 时间(formatDate)
 *     → 分页加载更多（loadMore + .load-sentinel 哨兵，参考 messages.js 分类页模式）
 *     → 空态："暂无积分记录"
 *     → 返回按钮：history.back()
 *
 * 【积分活动入口的实现】前端占位按钮
 * 实现细节：
 * - 积分活动按钮："积分活动"→ toast"积分活动开发中，敬请期待"
 * - "下单抵扣"类型在流水列表中原样显示
 * - 说明文案保留"签到每天+2积分"
 */
import { get } from '../api.js';
import { formatDate, showLoading, showEmpty, loadMore, toast } from '../ui.js';
import { isLoggedIn } from '../store.js';

export async function renderPoints(container) {
  // 登录保护
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('points');
    return;
  }

  container.innerHTML = `
    <div id="points-root">
      <div class="page-header">
        <button class="header-back" id="points-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>积分记录</h2>
      </div>
      <div id="points-balance-card" class="points-balance-card">
        <div class="points-balance-left">
          <div class="points-balance-label">当前积分</div>
          <div class="points-balance-value" id="points-balance-num">--</div>
        </div>
        <button class="btn btn-outline btn-sm" id="points-activity-btn">积分活动</button>
      </div>
      <div id="points-content" class="points-content">
        <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
      </div>
      <div class="wallet-tip" style="margin:12px 16px">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="2" style="vertical-align:middle;margin-right:4px"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
        积分说明：签到每天 +2 积分，下单可使用积分抵扣（每 100 积分抵 1 元）
      </div>
    </div>
  `;

  const root = container.querySelector('#points-root');
  root.querySelector('#points-back').addEventListener('click', () => {
    window.history.back();
  });

  const contentEl = root.querySelector('#points-content');

  /** 【积分记录的实现】加载积分余额 */
  try {
    const wallet = await get('/user/wallet');
    const balanceEl = root.querySelector('#points-balance-num');
    if (balanceEl) {
      balanceEl.textContent = wallet && wallet.points != null ? wallet.points : '0';
    }
  } catch {
    // 积分余额加载失败不阻塞列表
  }

  /** 【积分活动入口的实现】绑定活动按钮 */
  const activityBtn = root.querySelector('#points-activity-btn');
  if (activityBtn) {
    activityBtn.addEventListener('click', () => {
      toast('积分活动开发中，敬请期待');
    });
  }

  /** 【积分记录的实现】渲染积分流水列表
   * 实现细节：
   * - GET /user/points/records?current=1 → 解析 records 或直接数组
   * - changePoints 正数 → 绿色 "+2"；负数/零 → 红色 "-2"（扣减场景）
   * - typeText 显示为小标签（药丸样式），辅助理解积分来源（签到/消费/活动等）
   * - description 为主要描述文字，一行展示
   * - createTime 用 formatDate 格式化为绝对时间
   * - 空列表显示"暂无积分记录"
   */
  function formatChange(changePoints) {
    const val = Number(changePoints) || 0;
    if (val > 0) return '+' + val;
    return String(val);
  }

  function changeClass(changePoints) {
    const val = Number(changePoints) || 0;
    return val >= 0 ? 'positive' : 'negative';
  }

  async function loadRecords() {
    showLoading(contentEl);
    try {
      const data = await get('/user/points/records?current=1');
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

      if (!list.length) {
        contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无积分记录</p></div>';
        return;
      }

      let html = '';
      list.forEach(item => {
        html += `
          <div class="points-record">
            <div class="points-desc">
              ${item.description || '积分变动'}
              <div class="points-time">${formatDate(item.createTime)}</div>
            </div>
            ${item.typeText ? '<span class="points-type-tag">' + item.typeText + '</span>' : ''}
            <span class="points-change ${changeClass(item.changePoints)}">${formatChange(item.changePoints)}</span>
          </div>`;
      });
      contentEl.innerHTML = html;

      /** 【积分记录的实现】分页加载更多历史积分记录
       * 实现细节：
       * - 首屏 list.length >= 10 时插入哨兵（.load-sentinel-points），否则不显示分页
       * - 滚动触发 loadMore → GET /user/points/records?current=N 追加记录行
       * - 追加方式：创建 points-record DOM 元素插入哨兵之前
       * - 无更多时移除哨兵；失败静默移除哨兵
       */
      if (list.length >= 10) {
        let current = 2;
        const sentinel = document.createElement('div');
        sentinel.className = 'load-sentinel load-sentinel-points';
        sentinel.style.cssText = 'height:10px;width:100%';
        contentEl.appendChild(sentinel);

        loadMore(contentEl, async () => {
          try {
            const moreData = await get('/user/points/records?current=' + current);
            const moreList = Array.isArray(moreData) ? moreData : (moreData && moreData.records ? moreData.records : []);
            if (!moreList.length) {
              sentinel.remove();
              return;
            }
            moreList.forEach(item => {
              const el = document.createElement('div');
              el.className = 'points-record';
              el.innerHTML = `
                <div class="points-desc">
                  ${item.description || '积分变动'}
                  <div class="points-time">${formatDate(item.createTime)}</div>
                </div>
                ${item.typeText ? '<span class="points-type-tag">' + item.typeText + '</span>' : ''}
                <span class="points-change ${changeClass(item.changePoints)}">${formatChange(item.changePoints)}</span>`;
              contentEl.insertBefore(el, sentinel);
            });
            current++;
          } catch {
            sentinel.remove();
          }
        }, 'load-sentinel-points');
      }

    } catch {
      contentEl.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
    }
  }

  await loadRecords();
}
