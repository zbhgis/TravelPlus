/**
 * 【关注动态Feed的实现】#/follow 页面 + 聚合首页关注频道复用模块
 * 职责：关注用户游记时间线 Feed 渲染、无限滚动（游标分页 lastId+offset）、作者头像点击跳转用户主页
 * 依赖：api.js (get)、ui.js (imgUrl/imgError/showLoading/showEmpty/loadMore)、store.js (isLoggedIn)
 * 核心链路：
 *   - 全页渲染：renderFollow → 鉴权重定向 → page-header + renderFollowContent
 *   - 频道复用：renderFollowContent（供 home.js 聚合首页调用，不含 page-header）
 *   - 未登录：容器内显示引导按钮（"登录后查看关注动态"），不强制跳转
 *   - 数据加载：GET /note/of/follow?lastId={Date.now()}&offset=0 → data{list, minTime, offset}
 *   - 游标分页：lastId=minTime, offset=data.offset → 下次请求传回继续加载
 *   - 无限滚动：IntersectionObserver + loadMore → append 模式渲染新卡片
 *   - 头像跳转：.note-author 内头像点击 → #/user/{userId}，stopPropagation 防止触发卡片跳详情
 * 注意：游标 lastId/offset 需从 loadFeed 返回值获取后更新外部变量（修复：F4 分页游标不更新）
 */
import { get } from '../api.js';
import { imgUrl, imgError, showLoading, showEmpty, loadMore } from '../ui.js';
import { isLoggedIn } from '../store.js';

/* 全页渲染：page-header + 鉴权重定向 + 内容 */
export async function renderFollow(container) {
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=follow';
    return;
  }

  container.innerHTML = `
    <div class="page-header">
      <h2>关注动态</h2>
    </div>
    <div id="follow-wrapper"></div>
  `;
  const wrapper = container.querySelector('#follow-wrapper');
  await renderFollowContent(wrapper);
}

/* 频道复用：仅渲染 Feed 时间线内容，不含 page-header；未登录在容器内显示引导 */
export async function renderFollowContent(container) {
  if (!isLoggedIn()) {
    container.innerHTML = `
      <div class="empty-state">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5">
          <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/>
          <circle cx="9" cy="7" r="4"/>
          <path d="M22 21v-2a4 4 0 0 0-3-3.87"/>
          <path d="M16 3.13a4 4 0 0 1 0 7.75"/>
        </svg>
        <p>登录后查看关注动态</p>
        <button class="btn btn-primary" style="margin-top:16px" onclick="window.location.hash='#/login?redirect=home'">去登录</button>
      </div>`;
    return;
  }

  container.innerHTML = '<div id="follow-list" class="feed-list"></div>';
  const listEl = container.querySelector('#follow-list');
  let offset = 0;
  let lastId = Date.now();

  showLoading(listEl);

  try {
    const res = await loadFeed(listEl, lastId, offset);
    if (res) { lastId = res.lastId; offset = res.offset; }
  } catch {
    showEmpty(listEl, '加载失败');
  }

  const sentinel = document.createElement('div');
  sentinel.className = 'load-sentinel';
  sentinel.style.cssText = 'height:10px;';
  listEl.appendChild(sentinel);

  loadMore(listEl, async () => {
    try {
      const res = await loadFeed(listEl, lastId, offset, /* append */ true);
      if (res) { lastId = res.lastId; offset = res.offset; } else { sentinel.remove(); }
    } catch { sentinel.remove(); }
  }, 'load-sentinel');
}

async function loadFeed(listEl, _lastId, _offset, append) {
  let lid = _lastId;
  let off = _offset;
  const data = await get('/note/of/follow?lastId=' + lid + '&offset=' + off);
  if (!data || !data.list || !data.list.length) {
    if (!append) {
      showEmpty(listEl, '关注的人发布游记后会出现在这里');
    }
    return null;
  }

  const notes = data.list;
  if (data.minTime != null) lid = data.minTime;
  if (data.offset != null) off = data.offset;

  const html = notes.map(note => {
    const img = note.images ? note.images.split(',')[0] : '';
    return `
      <div class="feed-item feed-note-card" data-id="${note.id}" data-author-id="${note.userId || ''}">
        ${img ? '<div class="note-img"><img src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/></div>' : ''}
        <div class="note-body">
          <div class="note-title">${note.title}</div>
          <div class="note-footer">
            <div class="note-author">
              <img class="avatar-sm" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)"/>
              <span>${note.name || '匿名'}</span>
            </div>
            <span style="color:var(--text-hint);font-size:12px">${note.liked || 0} 赞</span>
          </div>
        </div>
      </div>`;
  }).join('');

  if (append) {
    const sentinel = listEl.querySelector('.load-sentinel');
    const temp = document.createElement('div');
    temp.innerHTML = html;
    const nodes = Array.from(temp.children);
    nodes.forEach(node => {
      node.addEventListener('click', () => {
        window.location.hash = '#/note/' + node.dataset.id;
      });

      /** 【用户主页的实现】追加 Feed 卡片中作者头像点击跳转
       * - 同初始加载逻辑：stopPropagation + userId 校验
       */
      const author = node.querySelector('.note-author');
      const uid = node.dataset.authorId;
      if (author && uid) {
        author.style.cursor = 'pointer';
        author.addEventListener('click', (e) => {
          e.stopPropagation();
          window.location.hash = '#/user/' + uid;
        });
      }

      listEl.insertBefore(node, sentinel);
    });
  } else {
    listEl.innerHTML = html;
    listEl.querySelectorAll('.feed-note-card').forEach(card => {
      card.addEventListener('click', () => {
        window.location.hash = '#/note/' + card.dataset.id;
      });
    });

    /** 【用户主页的实现】关注 Feed 作者头像点击跳转用户主页
     * - .note-author 内头像点击 → #/user/{userId}
     * - stopPropagation 防止触发卡片点击跳详情
     */
    listEl.querySelectorAll('.note-author').forEach(author => {
      const card = author.closest('.feed-note-card');
      const uid = card ? card.dataset.authorId : null;
      if (uid) {
        author.style.cursor = 'pointer';
        author.addEventListener('click', (e) => {
          e.stopPropagation();
          window.location.hash = '#/user/' + uid;
        });
      }
    });
  }

  return { lastId: lid, offset: off };
}
