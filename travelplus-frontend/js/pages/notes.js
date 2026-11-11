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
import { imgUrl, imgError, showLoading, showEmpty, loadMore, toast } from '../ui.js';

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
  /** 【游记发布的实现】检测发布脏标记——若存在则强制重新拉取
   * 实现细节：
   * - 发布/编辑游记成功后 note-edit.js 写 localStorage travelplus_notes_dirty='1'
   * - 此处检测并移除标记，确保下次进入游记频道时重新拉取最新数据
   * - 标记仅消费一次（removeItem），避免反复触发
   */
  if (localStorage.getItem('travelplus_notes_dirty')) {
    localStorage.removeItem('travelplus_notes_dirty');
  }

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

  /** 【游记列表的实现】添加侧边浮动按钮组（回到顶部 + 刷新）
   * 实现细节：
   * - 仅列表有内容时显示
   * - 回到顶部：window.scrollTo 平滑滚动
   * - 刷新：重新调用 renderNotesContent 拉取第一页，加载态 + toast"已刷新"
   */
  addFloatActions(container, listEl, async () => {
    await renderNotesContent(container);
  });
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
          <img loading="lazy" src="${imgUrl(img)}" alt="" onerror="imgError(event)"/>
        </div>
        <div class="note-body">
          <div class="note-title">${escapeHtml(note.title || '')}</div>
          <div class="note-footer">
            <div class="note-author">
              <img class="avatar-sm" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${note.userId || ''}" data-avatar-icon="${imgUrl(note.icon)}" data-avatar-nick="${escapeHtml(note.name || '匿名')}"/>
              <span>${escapeHtml(note.name || '匿名')}</span>
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

  /** 【头像交互的实现】作者头像点击改由全局委托 initAvatarDelegate 统一处理
   * - data-avatar-uid 已加在 img 模板中，无需独立绑定
   * - 卡片点击（跳详情）与头像点击（委托 stopPropagation）互不冲突
   */
  container.querySelectorAll('.note-author').forEach(author => {
    // 保留 cursor pointer 样式提示
    author.style.cursor = 'pointer';
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
        <img loading="lazy" src="${imgUrl(img)}" alt="" onerror="imgError(event)"/>
      </div>
      <div class="note-body">
        <div class="note-title">${escapeHtml(note.title || '')}</div>
        <div class="note-footer">
          <div class="note-author">
            <img class="avatar-sm" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${note.userId || ''}" data-avatar-icon="${imgUrl(note.icon)}" data-avatar-nick="${escapeHtml(note.name || '匿名')}"/>
            <span>${escapeHtml(note.name || '匿名')}</span>
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

    /** 【头像交互的实现】追加卡片中作者头像点击改由全局委托统一处理
     * - data-avatar-uid 已加在 img 模板中，无需独立绑定
     */
    if (note.userId) {
      const author = el.querySelector('.note-author');
      if (author) {
        author.style.cursor = 'pointer';
      }
    }

    container.insertBefore(el, sentinel);
  });
}

export function renderNotesCleanup() {}

/**
 * 【XSS 防护】HTML 转义——用户可控内容（游记标题、作者昵称）插入 innerHTML 前必须转义
 * - 与 note-detail.js / chat.js 的 escapeHtml 实现一致：textContent 赋值后取 innerHTML
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}

/**
 * 【游记列表的实现】添加侧边浮动按钮组（回到顶部 + 刷新）
 * 实现细节：
 * - 创建两个圆形按钮：↑ 回到顶部 + ⟳ 刷新
 * - 仅当列表中有 .note-card 元素时显示（show class）
 * - 定位使用 .float-actions CSS 类（fixed + 右对齐）
 * - 回到顶部：window.scrollTo({top:0, behavior:'smooth'})
 * - 刷新：调用 refreshFn（重新拉取第一页），按钮旋转动画 + 完成后 toast"已刷新"
 * - 刷新防重复：按钮 disabled 状态
 */
function addFloatActions(container, listEl, refreshFn) {
  const existing = container.querySelector('.float-actions');
  if (existing) existing.remove();

  const cards = listEl.querySelectorAll('.note-card');
  const actions = document.createElement('div');
  actions.className = 'float-actions';
  if (cards.length > 0) {
    actions.classList.add('show');
  }
  actions.innerHTML = `
    <button class="float-btn" id="float-back-top" title="回到顶部">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m18 15-6-6-6 6"/></svg>
    </button>
    <button class="float-btn" id="float-refresh" title="刷新">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
    </button>
  `;
  container.appendChild(actions);

  actions.querySelector('#float-back-top').addEventListener('click', () => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  actions.querySelector('#float-refresh').addEventListener('click', async () => {
    const btn = actions.querySelector('#float-refresh');
    btn.disabled = true;
    btn.classList.add('spinning');
    try {
      await refreshFn();
      toast('已刷新');
    } catch {
      // 刷新失败由内部 renderNotesContent 处理 toast
    } finally {
      btn.disabled = false;
      btn.classList.remove('spinning');
    }
  });
}
