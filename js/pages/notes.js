/**
 * 【热门游记瀑布流的实现】#/notes 页面 + 聚合首页游记频道复用模块
 * 职责：热门游记双列瀑布流渲染、无限滚动、作者头像点击跳转用户主页
 * 依赖：api.js (get)、ui.js (imgUrl/imgError/showLoading/showEmpty/loadMore)
 * 核心链路：
 *   - 全页渲染：renderNotes → page-header + renderNotesContent → GET /note/hot
 *   - 频道复用：renderNotesContent（供 home.js 聚合首页调用，不含 page-header）
 *   - 瀑布流：CSS columns 实现双列布局，.note-card 单元
 *   - 无限滚动：IntersectionObserver + loadMore → GET /note/hot?current=N → appendNoteCards
 *   - 头像跳转：.note-author 内头像点击 → #/user/{userId}，stopPropagation 防止触发卡片跳详情
 * 注意：作者 userId 可能为空，此时不绑定头像点击事件
 */
import { get } from '../api.js';
import { imgUrl, imgError, showLoading, showEmpty, loadMore } from '../ui.js';

/* 全页渲染：page-header + 内容（保留供直接路由使用） */
export async function renderNotes(container) {
  container.innerHTML = `
    <div class="page-header">
      <h2>热门游记</h2>
    </div>
    <div id="notes-wrapper"></div>
  `;
  const wrapper = container.querySelector('#notes-wrapper');
  await renderNotesContent(wrapper);
}

/* 频道复用：仅渲染瀑布流内容，不含 page-header */
export async function renderNotesContent(container) {
  container.innerHTML = '<div class="notes-waterfall" id="notes-list"></div>';
  const listEl = container.querySelector('#notes-list');
  let current = 1;
  let hasMore = true;

  showLoading(listEl);

  try {
    const data = await get('/note/hot?current=1');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
    hasMore = list.length > 0;
    renderNoteCards(listEl, list);
    if (hasMore) {
      current = 2;
      addLoadMore(listEl, current);
    } else {
      showEmpty(listEl, '暂无游记');
    }
  } catch {
    showEmpty(listEl, '加载失败');
  }
}

function addLoadMore(el, startCurrent) {
  let current = startCurrent;
  const sentinel = document.createElement('div');
  sentinel.className = 'load-sentinel';
  sentinel.style.cssText = 'height:10px;width:100%;column-span:all';
  el.appendChild(sentinel);

  loadMore(el, async () => {
    try {
      const data = await get('/note/hot?current=' + current);
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
      if (!list.length) {
        sentinel.remove();
        return;
      }
      appendNoteCards(el, list, sentinel);
      current++;
    } catch { /* silently stop */ }
  }, 'load-sentinel');
}

function renderNoteCards(container, list) {
  let html = '';
  list.forEach(note => {
    const img = note.images ? note.images.split(',')[0] : '';
    html += `
      <div class="note-card" data-id="${note.id}" data-author-id="${note.userId || ''}">
        <div class="note-img">
          <img src="${imgUrl(img)}" alt="" onerror="imgError(event)"/>
        </div>
        <div class="note-body">
          <div class="note-title">${note.title}</div>
          <div class="note-footer">
            <div class="note-author">
              <img class="avatar-sm" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)"/>
              <span>${note.name || '匿名'}</span>
            </div>
            <div class="note-likes">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="${note.isLike ? '#f87171' : 'none'}" stroke="${note.isLike ? '#f87171' : '#999'}" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
              <span>${note.liked || 0}</span>
            </div>
          </div>
        </div>
      </div>`;
  });
  container.innerHTML = html;

  container.querySelectorAll('.note-card').forEach(card => {
    card.addEventListener('click', () => {
      window.location.hash = '#/note/' + card.dataset.id;
    });
  });

  /** 【用户主页的实现】瀑布流作者头像点击跳转用户主页
   * - .note-author 内头像点击 → #/user/{userId}
   * - stopPropagation 防止触发卡片点击跳详情
   * - userId 不存在时静默忽略
   */
  container.querySelectorAll('.note-author').forEach(author => {
    const card = author.closest('.note-card');
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

function appendNoteCards(container, list, sentinel) {
  list.forEach(note => {
    const img = note.images ? note.images.split(',')[0] : '';
    const el = document.createElement('div');
    el.className = 'note-card';
    el.dataset.id = note.id;
    el.dataset.authorId = note.userId || '';
    el.innerHTML = `
      <div class="note-img">
        <img src="${imgUrl(img)}" alt="" onerror="imgError(event)"/>
      </div>
      <div class="note-body">
        <div class="note-title">${note.title}</div>
        <div class="note-footer">
          <div class="note-author">
            <img class="avatar-sm" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)"/>
            <span>${note.name || '匿名'}</span>
          </div>
          <div class="note-likes">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="${note.isLike ? '#f87171' : 'none'}" stroke="${note.isLike ? '#f87171' : '#999'}" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
            <span>${note.liked || 0}</span>
          </div>
        </div>
      </div>`;
    el.addEventListener('click', () => {
      window.location.hash = '#/note/' + note.id;
    });

    /** 【用户主页的实现】追加卡片中作者头像点击跳转用户主页
     * - 同 renderNoteCards 逻辑：stopPropagation + userId 校验
     */
    if (note.userId) {
      const author = el.querySelector('.note-author');
      if (author) {
        author.style.cursor = 'pointer';
        author.addEventListener('click', (e) => {
          e.stopPropagation();
          window.location.hash = '#/user/' + note.userId;
        });
      }
    }

    container.insertBefore(el, sentinel);
  });
}

export function renderNotesCleanup() {}
