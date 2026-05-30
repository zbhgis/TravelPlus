import { get } from '../api.js';
import { imgUrl, imgError, showLoading, showEmpty, toast } from '../ui.js';
import { isLoggedIn } from '../store.js';

/** 【浏览记录页的实现】用户浏览记录列表页渲染
 * 实现细节：
 * - 路由 #/user/browse，需登录，否则跳 #/login?redirect=user/browse
 * - GET /user/browse/list → data=List<{type: note|attraction, id, title, image, time}>
 * - time=毫秒时间戳，前端格式化为相对时间（如"x天前"、"刚刚"）
 * - 列表渲染：缩略图 + 标题 + 类型徽标（游记=青色/景点=橙色）+ 相对时间
 * - 点击整行 → #/note/{id}（type=note）或 #/attraction/{id}（type=attraction）
 * - 空列表显示「暂无浏览记录」占位
 * - 后端一次返回 50 条，简单列表不分页
 */
export async function renderUserBrowse(container) {
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=user/browse';
    return;
  }

  container.innerHTML = `
    <div id="browse-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#browse-root');

  try {
    const data = await get('/user/browse/list');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

    /** 【浏览记录页的实现】列表 HTML 构建
     * - 页头：返回按钮 + 标题"浏览记录"
     * - 列表容器 .browse-list
     * - 空列表显示「暂无浏览记录」
     */
    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="browse-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>浏览记录</h2>
      </div>
      <div class="browse-list" id="browse-list"></div>
    `;

    // 返回按钮
    root.querySelector('#browse-back').addEventListener('click', () => window.history.back());

    const listEl = root.querySelector('#browse-list');

    if (!list || !list.length) {
      showEmpty(listEl, '暂无浏览记录');
      return;
    }

    /** 【浏览记录页的实现】渲染浏览记录列表项
     * - 每项 .browse-item 包含：缩略图 .browse-thumb（image 可能为空，用占位色块）
     *   + 信息区 .browse-info（标题 .browse-title + 类型徽标 .browse-badge + 时间 .browse-time）
     * - 类型徽标：type=note → "游记"（--primary 色），type=attraction → "景点"（--warning 色）
     * - 时间格式化：复用 browseFormatTime() 将毫秒时间戳转为相对时间字符串
     */
    let html = '';
    list.forEach(item => {
      const imgSrc = item.image ? imgUrl(item.image) : '';
      const typeLabel = item.type === 'note' ? '游记' : '景点';
      const typeClass = item.type === 'note' ? 'browse-badge-note' : 'browse-badge-attraction';
      const timeStr = browseFormatTime(item.time);
      const targetHash = item.type === 'note'
        ? '#/note/' + item.id
        : '#/attraction/' + item.id;

      html += `
        <div class="browse-item" data-hash="${targetHash}">
          <div class="browse-thumb">
            ${imgSrc
              ? '<img src="' + imgSrc + '" alt="" onerror="imgError(event)"/>'
              : '<div class="browse-thumb-placeholder">' + (item.type === 'note' ? '📝' : '🏞') + '</div>'}
          </div>
          <div class="browse-info">
            <div class="browse-title">${escapeBrowseHtml(item.title || '无标题')}</div>
            <div class="browse-meta">
              <span class="browse-badge ${typeClass}">${typeLabel}</span>
              <span class="browse-time">${timeStr}</span>
            </div>
          </div>
        </div>`;
    });
    listEl.innerHTML = html;

    // 点击跳转对应详情页
    listEl.querySelectorAll('.browse-item').forEach(item => {
      item.addEventListener('click', () => {
        window.location.hash = item.dataset.hash;
      });
    });

    return () => {};
  } catch {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}

/** 【浏览记录页的实现】毫秒时间戳 → 相对时间字符串
 * 实现细节：
 * - 与 ui.js formatDate 逻辑一致但接受毫秒时间戳（而非 ISO 字符串）
 * - 规则：<60s → "刚刚"，<1h → "x分钟前"，<24h → "x小时前"，<7d → "x天前"，≥7d → yyyy-MM-dd
 * - 无效 time 返回空字符串
 */
function browseFormatTime(ms) {
  if (!ms) return '';
  try {
    const d = new Date(ms);
    if (isNaN(d.getTime())) return '';
    const now = new Date();
    const diff = now - d;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前';
    if (diff < 604800000) return Math.floor(diff / 86400000) + '天前';
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return y + '-' + m + '-' + day;
  } catch {
    return '';
  }
}

/** 【浏览记录页的实现】XSS 防护：标题文本 HTML 转义
 * 实现细节：
 * - 参考 note-detail.js escapeHtml 实现
 * - 防止标题中特殊字符破坏 HTML 结构
 */
function escapeBrowseHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
