/**
 * 【关注Feed的实现】#/follow 页面 + 聚合首页关注频道复用模块
 * 职责：关注用户游记瀑布流渲染、滚动分页（current 递增）、作者头像点击跳转用户主页
 * 依赖：api.js (get)、ui.js (imgUrl/imgError/showLoading/showEmpty/loadMore)、store.js (isLoggedIn)
 * 核心链路：
 *   - 全页渲染：renderFollow → 鉴权重定向 → page-header + renderFollowContent
 *   - 频道复用：renderFollowContent（供 home.js 聚合首页调用，不含 page-header）
 *   - 未登录：容器内显示引导按钮（"登录后查看关注动态"），不强制跳转
 *   - 数据加载：GET /note/of/follow?current=1 → 返回 List<TravelNote> 含作者 name/icon/isLike
 *   - 分页：current 递增（实时分页接口，替代原 ZSet 滚动 lastId/offset），数据与关注人数一致
 *   - 瀑布流：CSS columns 实现双列布局，.note-card 单元（与游记频道排布一致）
 *   - 无限滚动：IntersectionObserver + loadMore → GET /note/of/follow?current=N → appendNoteCards
 *   - 头像跳转：全局委托 initAvatarDelegate 统一处理（data-avatar-uid），stopPropagation 防止触发卡片跳详情
 *
 * 【关注Feed的实现】与游记频道同排布（瀑布流）+ 实时分页接口 + 数据与关注人数一致
 * 实现细节：
 * - 接口：GET /note/of/follow?current=N（后端已改实时分页，返回 List<TravelNote> 含作者 name/icon/isLike）
 * - 渲染：瀑布流双列 note-card（封面图/标题/作者头像昵称/点赞数），点击跳 #/note/{id}
 * - 分页：滚动加载（ui.js loadMore，current 递增），空态"关注的人发布游记后会出现在这里"
 * - 模板：复用与 notes.js 一致的 note-card 结构（.notes-waterfall + .note-card），CSS columns 双列
 * - 注意：作者 userId 可能为空，此时不绑定头像点击事件；头像跳转统一由全局委托处理
 */
import { get } from '../api.js';
import { imgUrl, imgError, showLoading, showEmpty, loadMore, toast } from '../ui.js';
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

/**
 * 【关注Feed的实现】频道复用：仅渲染瀑布流内容，不含 page-header
 * 实现细节：
 * - 与游记频道 notes.js renderNotesContent 同排布：CSS columns 双列瀑布流
 * - 接口：GET /note/of/follow?current=N（实时分页，数据与关注人数一致）
 * - 未登录在容器内显示引导按钮
 */
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

  /** 【关注Feed的实现】瀑布流容器：复用 notes-waterfall 样式（CSS columns 双列） */
  container.innerHTML = '<div class="notes-waterfall" id="follow-list"></div>';
  const listEl = container.querySelector('#follow-list');
  let current = 1;
  let hasMore = true;

  /** 【游记发布的实现】检测发布脏标记——若存在则强制重新拉取
   * 实现细节：
   * - 发布/编辑游记成功后 note-edit.js 写 localStorage travelplus_notes_dirty='1'
   * - 关注频道检测并移除标记，确保推送给粉丝的新游记即时出现
   * - 标记仅消费一次（removeItem），避免反复触发
   */
  if (localStorage.getItem('travelplus_notes_dirty')) {
    localStorage.removeItem('travelplus_notes_dirty');
  }

  showLoading(listEl);

  try {
    /** 【关注Feed的实现】接口：GET /note/of/follow?current=N（实时分页，替代原 ZSet 滚动 lastId/offset）
     * - 后端返回 List<TravelNote> 含作者 name/icon/isLike，与关注人数一致 */
    const data = await get('/note/of/follow?current=1');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
    hasMore = list.length > 0;
    renderFollowNoteCards(listEl, list);
    if (hasMore) {
      current = 2;
      addFollowLoadMore(listEl, current);
    } else {
      showEmpty(listEl, '关注的人发布游记后会出现在这里');
    }
  } catch {
    showEmpty(listEl, '加载失败');
  }

  /** 【游记列表的实现】添加侧边浮动按钮组（回到顶部 + 刷新）
   * 实现细节：
   * - 仅列表有内容时显示
   * - 回到顶部：window.scrollTo 平滑滚动
   * - 刷新：重新调用 renderFollowContent 拉取第一页，加载态 + toast"已刷新"
   */
  addFloatActions(container, listEl, async () => {
    await renderFollowContent(container);
  });
}

/**
 * 【关注Feed的实现】渲染首屏瀑布流卡片
 * 实现细节：
 * - 复用 notes.js note-card 模板：封面图 / 标题 / 作者头像昵称 / 点赞数
 * - isLike 字段控制爱心填充色（已赞=红色实心，未赞=灰色描边）
 * - data-avatar-uid 属性供全局头像委托 initAvatarDelegate 统一处理点击跳转
 */
function renderFollowNoteCards(container, list) {
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
              <img class="avatar-sm" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${note.userId || ''}" data-avatar-icon="${imgUrl(note.icon)}" data-avatar-nick="${note.name || '匿名'}"/>
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

  /** 【关注Feed的实现】绑定卡片点击：跳转游记详情 #/note/{id} */
  container.querySelectorAll('.note-card').forEach(card => {
    card.addEventListener('click', () => {
      window.location.hash = '#/note/' + card.dataset.id;
    });
  });

  /** 【关注Feed的实现】作者头像点击改由全局委托 initAvatarDelegate 统一处理
   * - data-avatar-uid 已加在 img 模板中，无需独立绑定 */
  container.querySelectorAll('.note-author').forEach(author => {
    author.style.cursor = 'pointer';
  });
}

/**
 * 【关注Feed的实现】分页加载更多（滚动触发）
 * 实现细节：
 * - 哨兵 .load-sentinel 需要 column-span:all 跨越双列布局
 * - 滚动触发 → GET /note/of/follow?current=N → appendFollowNoteCards 追加卡片
 * - 无更多 → 移除哨兵
 */
function addFollowLoadMore(el, startCurrent) {
  let current = startCurrent;
  const sentinel = document.createElement('div');
  sentinel.className = 'load-sentinel';
  sentinel.style.cssText = 'height:10px;width:100%;column-span:all';
  el.appendChild(sentinel);

  loadMore(el, async () => {
    try {
      const data = await get('/note/of/follow?current=' + current);
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
      if (!list.length) {
        sentinel.remove();
        return;
      }
      appendFollowNoteCards(el, list, sentinel);
      current++;
    } catch { /* silently stop */ }
  }, 'load-sentinel');
}

/**
 * 【关注Feed的实现】追加瀑布流卡片（在哨兵之前插入）
 * 实现细节：
 * - 创建 note-card DOM 元素插入 .load-sentinel 之前，保持瀑布流列高度平衡
 * - 绑定卡片点击 + 头像 cursor:pointer 视觉提示
 * - 头像跳转由全局委托 initAvatarDelegate 统一处理
 */
function appendFollowNoteCards(container, list, sentinel) {
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
            <img class="avatar-sm" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${note.userId || ''}" data-avatar-icon="${imgUrl(note.icon)}" data-avatar-nick="${note.name || '匿名'}"/>
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

    /** 【关注Feed的实现】追加卡片中作者头像点击改由全局委托统一处理
     * - data-avatar-uid 已加在 img 模板中，无需独立绑定 */
    if (note.userId) {
      const author = el.querySelector('.note-author');
      if (author) {
        author.style.cursor = 'pointer';
      }
    }

    container.insertBefore(el, sentinel);
  });
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
      // 刷新失败由内部 renderFollowContent 处理 toast
    } finally {
      btn.disabled = false;
      btn.classList.remove('spinning');
    }
  });
}
