/**
 * 【收藏列表页的实现】#/favorites 收藏列表页面渲染模块
 * 职责：展示收藏列表（支持游记/景点类型切换 tab）、缩略图列表、空态提示、点击跳转详情
 * 依赖：api.js (get)、ui.js (imgUrl/imgError/formatDate/showEmpty/showLoading)、store.js (isLoggedIn)
 * 核心链路：
 *   renderFavorites(container)
 *     → 登录保护（未登录跳 #/login?redirect=favorites）
 *     → 顶部类型 tab（游记 / 景点）→ 默认选中游记
 *     → GET /favorite/list?type=note|attraction&current=1 → 列表渲染
 *     → 列表项：缩略图 + 标题 + 添加时间 → 点击跳对应详情 #/note/{id} 或 #/attraction/{id}
 *     → 空态："暂无收藏"
 *     → 返回按钮：history.back()
 */
import { get } from '../api.js';
import { imgUrl, imgError, formatDate, showLoading, showEmpty } from '../ui.js';
import { isLoggedIn } from '../store.js';

export async function renderFavorites(container) {
  // 登录保护
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('favorites');
    return;
  }

  container.innerHTML = '<div class="page-header"><button class="header-back" id="fav-back"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg></button><h2>我的收藏</h2></div><div id="fav-content"><div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div></div>';

  const backBtn = container.querySelector('#fav-back');
  backBtn.addEventListener('click', () => window.history.back());

  const contentEl = container.querySelector('#fav-content');

  /** 【收藏功能的实现】类型 tab——游记/景点 切换
   *  - 默认选中游记（note），activeType 驱动 API 请求
   *  - 点击 tab 切换 activeType 并重新拉取列表
   */
  let activeType = 'note';

  function renderTabs() {
    return `
      <div class="fav-tabs" id="fav-tabs">
        <div class="fav-tab${activeType === 'note' ? ' active' : ''}" data-type="note">游记</div>
        <div class="fav-tab${activeType === 'attraction' ? ' active' : ''}" data-type="attraction">景点</div>
      </div>
    `;
  }

  /** 【收藏功能的实现】加载当前类型的收藏列表
   *  - GET /favorite/list?type=note|attraction&current=1 → 解析 records 或直接数组
   *  - 渲染列表项：缩略图 + 标题 + 时间 + 点击跳转
   *  - 空列表显示"暂无收藏"
   */
  async function loadList() {
    showLoading(contentEl);
    try {
      const data = await get('/favorite/list?type=' + activeType + '&current=1');
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

      if (!list.length) {
        contentEl.innerHTML = renderTabs() + '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无收藏</p></div>';
        bindTabEvents();
        return;
      }

      let html = renderTabs();

      /** 【收藏功能的实现】列表渲染
       *  - image 字段：取第一张图（多图逗号分隔时取第一张），无图时显示默认占位
       *  - title 字段：直接显示
       *  - createTime 字段：格式化为相对时间
       *  - 点击跳转：游记 → #/note/{id}，景点 → #/attraction/{id}
       */
      html += '<div class="fav-list">';
      list.forEach(item => {
        const img = item.image ? item.image.split(',')[0] : '';
        const targetHash = activeType === 'note' ? '#/note/' + item.targetId : '#/attraction/' + item.targetId;
        html += `
          <div class="fav-item" data-url="${targetHash}">
            ${img ? '<img class="fav-item-thumb" src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : '<div class="fav-item-thumb" style="background:var(--border);display:flex;align-items:center;justify-content:center;color:var(--text-hint);font-size:12px">无图</div>'}
            <div class="fav-item-info">
              <div class="fav-item-title">${item.title || '无标题'}</div>
              <div class="fav-item-time">${formatDate(item.createTime)}</div>
            </div>
          </div>`;
      });
      html += '</div>';

      contentEl.innerHTML = html;
      bindTabEvents();

      // 绑定列表项点击跳转
      contentEl.querySelectorAll('.fav-item').forEach(itemEl => {
        itemEl.addEventListener('click', () => {
          const url = itemEl.dataset.url;
          if (url) window.location.hash = url;
        });
      });
    } catch {
      contentEl.innerHTML = renderTabs() + '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
      bindTabEvents();
    }
  }

  /** 【收藏功能的实现】绑定 tab 切换事件
   *  - 点击 tab → 更新 activeType → 重新渲染列表
   */
  function bindTabEvents() {
    contentEl.querySelectorAll('.fav-tab').forEach(tab => {
      tab.addEventListener('click', () => {
        const type = tab.dataset.type;
        if (type === activeType) return;
        activeType = type;
        loadList();
      });
    });
  }

  await loadList();
}
