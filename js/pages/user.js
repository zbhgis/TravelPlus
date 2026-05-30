import { get, put, post } from '../api.js';
import { imgUrl, imgError, showLoading, showEmpty, toast, loadMore, formatDate, confirmDialog } from '../ui.js';
import { isLoggedIn, getUser, logout } from '../store.js';

/** 【用户主页的实现】用户主页渲染（小红书式分 tab 布局）
 * 实现细节：
 * - 路由 #/user/:id，params.id 为目标用户 ID（字符串）
 * - GET /user/home/{id} 获取用户信息、简介、统计、关注状态
 * - 主页头部保持小红书样式（背景图 cover + 头像负 margin 悬浮叠加 + 昵称/简介居中）
 * - 头部下方新增 tab 栏：游记 | 评论 | 收藏 | 赞过（默认游记）
 * - 每个 tab 页面内切换重渲染内容区：
 *   - 游记：GET /note/of/user?id={id}&current=1 → 瀑布流 note-card（复用）
 *   - 评论：GET /user/comments/{id}?current=1 → 评论卡片（评论内容 + 所属游记标题，点击跳 #/note/{noteId}）
 *   - 收藏：GET /favorite/list?userId={id}&type=note|attraction&current=1 → 子 tab 游记/景点（缩略图+标题，点击跳详情）
 *   - 赞过：GET /user/liked-notes/{id} / GET /user/liked-attractions/{id} → 子 tab 游记/景点
 * - 【用户主页的实现】自己/他人视角差异：
 *   - 自己主页：标题「我的主页」、操作收纳进左侧抽屉侧边栏（与"我的"页一致）→ 编辑资料/浏览记录/客服/我的收藏/隐私设置/退出登录
 *   - 他人主页：标题「TA的主页」、关注/私信按钮（无侧边栏）
 * - 自己主页判断：getUser().id == params.id（宽松比较，兼容数字/字符串）
 * - 自己主页不显示关注按钮；未登录点击关注/统计跳 #/login 带 redirect
 * - 关注按钮：初始 followed 状态 → "已关注"/"+ 关注"；点击 PUT /follow/{id}/{!followed} 切换；失败回滚
 */
export async function renderUser(container, params) {
  const id = params.id;
  const me = getUser();
  const isSelf = me && String(me.id) === String(id);

  container.innerHTML = `
    <div id="user-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#user-root');

  try {
    const [userData, notesData] = await Promise.all([
      get('/user/home/' + id),
      get('/note/of/user?id=' + id + '&current=1')
    ]);

    const { user, info, fans, followee, likedCount, followed } = userData;
    const notes = Array.isArray(notesData) ? notesData : (notesData && notesData.records ? notesData.records : []);
    const hasMoreNotes = notes.length > 0;

    /** 【用户主页的实现】背景图头部（小红书样式）
     * - user.bgImage 非空 → 作为头部大图 background-size:cover，高 150px
     * - 为空 → CSS 默认渐变（--primary-light → #c8e6e0）
     * - bgImage 加载失败 → 隐藏 img onerror 清除 inline background-image，回退 CSS 渐变
     */
    const bgImageUrl = user.bgImage ? imgUrl(user.bgImage) : '';
    const bgStyle = bgImageUrl ? 'background-image:url(' + bgImageUrl + ')' : '';
    // 背景图加载失败时隐藏 img 触发 onerror → 清除 inline style → CSS 渐变回退
    const bgFallback = bgImageUrl
      ? '<img src="' + bgImageUrl + '" style="display:none" onerror="this.parentElement.style.backgroundImage=\'\'">'
      : '';

    /** 【用户主页的实现】主页 HTML 构建（小红书样式）
     * - 头部：.user-bg 全宽 150px 背景图 cover → .avatar-xhs 负 margin(-36px) 悬浮叠加在背景底部 → 昵称/简介居中
     * - 布局要点：背景大图 + 头像白边(3px) + 阴影 + 悬浮感（小红书经典 layout）
     * - 统计行：三个 .user-stat-item，带 data-type 区分 fans/following，仅前两个可点击
     * - 自己主页：标题「我的主页」、☰ 菜单按钮（操作收纳进左侧抽屉侧边栏，与"我的"页一致）
     * - 他人主页：标题「TA的主页」、关注/私信按钮（无侧边栏）
     */
    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="user-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        ${isSelf ? '<button class="menu-btn" id="user-menu-btn" aria-label="菜单"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg></button>' : ''}
        <h2>${isSelf ? '我的主页' : 'TA的主页'}</h2>
      </div>
      <div class="user-profile">
        <!-- 【用户主页的实现】小红书样式：背景 cover + 头像负 margin 悬浮叠加 -->
        <div class="user-header">
          <div class="user-bg" style="${bgStyle}">${bgFallback}</div>
          <img class="avatar avatar-xhs" src="${imgUrl(user.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${user.id}" data-avatar-icon="${imgUrl(user.icon)}" data-avatar-nick="${user.nickName || '用户'}"/>
          <div class="user-header-info">
            <div class="user-profile-name">${user.nickName || '用户'}</div>
            ${info ? `
              <div class="user-profile-bio">${info.introduce || ''}</div>
              ${info.city ? '<div class="user-profile-city">' + info.city + '</div>' : ''}
            ` : ''}
          </div>
        </div>
        <div class="user-stats">
          <div class="user-stat-item" data-type="following">
            <span class="user-stat-num">${followee || 0}</span>
            <span class="user-stat-label">关注</span>
          </div>
          <div class="user-stat-item" data-type="fans">
            <span class="user-stat-num">${fans || 0}</span>
            <span class="user-stat-label">粉丝</span>
          </div>
          <div class="user-stat-item">
            <span class="user-stat-num">${likedCount || 0}</span>
            <span class="user-stat-label">获赞</span>
          </div>
        </div>
        ${isSelf ? '' : `
        <div class="user-actions">
          <button class="btn btn-outline btn-sm follow-btn" id="user-follow-btn" data-followed="${followed}">
            ${followed ? '已关注' : '+ 关注'}
          </button>
          <!-- 【私信功能的实现】他人主页「私信」按钮 → 跳转聊天页 #/chat/{userId} -->
          <button class="btn btn-outline btn-sm" id="user-chat-btn">私信</button>
        </div>`}
      </div>
      
      <!-- 【个人中心的实现】tab 区容器：由 renderUserTabs 动态渲染 tab 栏 + 内容 -->
      <div id="user-tab-area"></div>
    `;

    // 返回按钮
    root.querySelector('#user-back').addEventListener('click', () => window.history.back());

    /** 【用户主页的实现】自己主页：侧边栏打开/关闭逻辑（与"我的"页一致）
     * 实现细节：
     * - ☰ 菜单按钮 → 显示遮罩 + 左侧抽屉从左边滑入
     * - 点击遮罩（.side-mask）→ 关闭抽屉
     * - 点击关闭按钮（.side-drawer-close）→ 关闭抽屉
     * - 点击抽屉内菜单项 → 跳转 hash + 关闭抽屉
     * - .side-mask 和 .side-drawer 挂载到 document.body（避免 transform 父容器污染 fixed 定位）
     * - 退出登录 → confirmDialog → POST /user/logout + logout() + toast + #/home
     */
    if (isSelf) {
      // 先移除可能残留的旧抽屉（从 body 上）
      const oldMask = document.getElementById('side-mask');
      const oldDrawer = document.getElementById('side-drawer');
      if (oldMask) oldMask.remove();
      if (oldDrawer) oldDrawer.remove();

      // 创建遮罩并挂载到 body
      const sideMask = document.createElement('div');
      sideMask.className = 'side-mask';
      sideMask.id = 'side-mask';
      sideMask.style.display = 'none';
      document.body.appendChild(sideMask);

      // 创建抽屉并挂载到 body
      const sideDrawer = document.createElement('div');
      sideDrawer.className = 'side-drawer';
      sideDrawer.id = 'side-drawer';
      sideDrawer.innerHTML = `
        <div class="side-drawer-header">
          <span>菜单</span>
          <button class="side-drawer-close" id="side-drawer-close">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
        <div class="side-drawer-item" data-hash="#/user/edit">编辑资料</div>
        <div class="side-drawer-item" data-hash="#/user/browse">浏览记录</div>
        <div class="side-drawer-item" data-hash="#/support">客服</div>
        <div class="side-drawer-item" data-hash="#/favorites">我的收藏</div>
        <!-- 【隐私设置的实现】侧边栏独立「隐私设置」项 → #/privacy -->
        <div class="side-drawer-item" data-hash="#/privacy">隐私设置</div>
        <div class="side-drawer-item side-drawer-logout" id="side-logout-item">退出登录</div>
      `;
      document.body.appendChild(sideDrawer);

      const menuBtn = root.querySelector('#user-menu-btn');

      function openDrawer() {
        sideMask.style.display = 'block';
        void sideMask.offsetWidth;
        sideMask.classList.add('show');
        sideDrawer.classList.add('show');
      }

      function closeDrawer() {
        sideMask.classList.remove('show');
        sideDrawer.classList.remove('show');
        setTimeout(() => {
          if (!sideMask.classList.contains('show')) {
            sideMask.style.display = 'none';
          }
        }, 250);
      }

      if (menuBtn) menuBtn.addEventListener('click', openDrawer);
      if (sideMask) sideMask.addEventListener('click', closeDrawer);
      const closeBtn = sideDrawer.querySelector('#side-drawer-close');
      if (closeBtn) closeBtn.addEventListener('click', closeDrawer);

      // 侧边栏菜单项点击 → 跳转 + 关闭（排除退出登录项）
      sideDrawer.querySelectorAll('.side-drawer-item').forEach(item => {
        item.addEventListener('click', () => {
          const hash = item.dataset.hash;
          if (hash) window.location.hash = hash;
          closeDrawer();
        });
      });

      /** 【用户主页的实现】侧边栏「退出登录」项点击
       * - confirmDialog 确认 → POST /user/logout
       * - 无论服务端成败均执行本地清理：logout() 清 token + 清 user
       * - toast "已退出" → 跳 #/home
       */
      const logoutItem = sideDrawer.querySelector('#side-logout-item');
      if (logoutItem) {
        logoutItem.addEventListener('click', async () => {
          closeDrawer();
          const ok = await confirmDialog('退出登录', '确定要退出登录吗？');
          if (!ok) return;
          try {
            await post('/user/logout');
          } catch {
            // 即使服务端失败也不影响本地清理
          }
          logout();
          toast('已退出');
          window.location.hash = '#/home';
        });
      }
    }

    /** 【私信功能的实现】他人主页「私信」按钮点击 → #/chat/{userId}
     * - 未登录时跳登录页（带 redirect 回当前页）
     * - 已登录直接跳转聊天页
     */
    const chatBtn = root.querySelector('#user-chat-btn');
    if (chatBtn) {
      chatBtn.addEventListener('click', () => {
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('chat/' + id);
          return;
        }
        window.location.hash = '#/chat/' + id;
      });
    }

    // 统计行点击 → 关注/粉丝列表页（未登录跳登录）
    root.querySelectorAll('.user-stat-item[data-type]').forEach(item => {
      item.addEventListener('click', () => {
        const type = item.dataset.type;
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('user/' + id + '/follows?type=' + type);
          return;
        }
        window.location.hash = '#/user/' + id + '/follows?type=' + type;
      });
    });

    /** 【用户主页的实现】关注/取关按钮交互
     * - 乐观更新 UI：先改状态再发请求，失败回滚
     * - 未登录点击跳 #/login 带 redirect 回当前页
     * - PUT /follow/{id}/{true|false} 切换关注状态
     * - 【关注功能的实现】乐观更新统计数字：关注 → 粉丝数 +1，取关 → 粉丝数 -1（下限 0），失败回滚数字
     */
    const followBtn = root.querySelector('#user-follow-btn');
    if (followBtn) {
      // 粉丝数显示元素（.user-stat-item[data-type="fans"] .user-stat-num）
      const fansNumEl = root.querySelector('.user-stat-item[data-type="fans"] .user-stat-num');

      followBtn.addEventListener('click', async () => {
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('user/' + id);
          return;
        }
        const currentState = followBtn.dataset.followed === 'true';
        const newState = !currentState;

        // 乐观更新：记录原始粉丝数（用于失败回滚）
        const oldFans = parseInt(fansNumEl.textContent) || 0;

        // 乐观更新按钮状态
        followBtn.dataset.followed = String(newState);
        followBtn.textContent = newState ? '已关注' : '+ 关注';
        followBtn.classList.toggle('followed', newState);

        // 乐观更新粉丝数：关注 +1，取关 -1（下限 0）
        if (fansNumEl) {
          fansNumEl.textContent = newState ? oldFans + 1 : Math.max(0, oldFans - 1);
        }

        try {
          await put('/follow/' + id + '/' + newState);
        } catch {
          // 失败回滚按钮状态
          followBtn.dataset.followed = String(currentState);
          followBtn.textContent = currentState ? '已关注' : '+ 关注';
          followBtn.classList.toggle('followed', currentState);
          // 失败回滚粉丝数字
          if (fansNumEl) {
            fansNumEl.textContent = oldFans;
          }
        }
      });
      if (followed) followBtn.classList.add('followed');
    }

    /** 【个人中心的实现】调用可复用 tab 渲染函数（与 profile.js 共用单一实现）
     * 实现细节：
     * - 传入 Promise.all 预加载的 notes 首屏数据，避免重复请求
     * - root 传入用于足迹地图 Leaflet 清理映射（cleanupFootprintMap 复用）
     * - 自身/他人主页行为由 renderUser 调用侧控制（他人无签到/操作组，tab 内容一致）
     */
    renderUserTabs(root.querySelector('#user-tab-area'), id, root, notes);

    /** 【用户主页的实现】cleanup：页面销毁时移除 body 上的侧边栏 DOM
     * - 自己主页创建的 .side-mask 和 .side-drawer 挂载在 body 上，需在页面切换时清理
     * - 他人主页无侧边栏，cleanup 为空操作（getElementById 均返回 null）
     */
    return () => {
      const mask = document.getElementById('side-mask');
      const drawer = document.getElementById('side-drawer');
      if (mask) mask.remove();
      if (drawer) drawer.remove();
    };

  } catch {
    // 清理可能已创建的侧边栏 DOM
    const mask = document.getElementById('side-mask');
    const drawer = document.getElementById('side-drawer');
    if (mask) mask.remove();
    if (drawer) drawer.remove();
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}

/**
 * 【用户主页的实现】XSS 防护：HTML 转义
 * - 创建临时 div 写入 textContent 再取 innerHTML，防止 XSS 注入
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}

/** 【用户主页的实现】切换 tab 并加载对应内容
 * 实现细节：
 * - 清空内容区 → 根据 tabName 调用对应渲染函数
 * - notes 使用预加载的首屏数据（notesPreloaded），后续分页由各 loader 内部管理
 * - state 对象承载所有分页游标（引用传递，loader 内部修改生效）
 */
async function switchUserTab(container, userId, tabName, notesPreloaded, state) {
  showLoading(container);
  try {
    switch (tabName) {
      case 'notes':
        await loadUserTabNotes(container, userId, notesPreloaded, state);
        break;
      case 'comments':
        await loadUserTabComments(container, userId, state);
        break;
      case 'favorites':
        await loadUserTabFavorites(container, userId, state);
        break;
      case 'likes':
        await loadUserTabLikes(container, userId, state);
        break;
      case 'footprints':
        await loadUserTabFootprints(container, userId, state);
        break;
    }
  } catch {
    showEmpty(container, '加载失败');
  }
}

/** 【用户主页的实现】游记 tab 内容渲染
 * - 首屏使用预加载的 notes 数据直接渲染（无需额外请求）
 * - 后续分页 GET /note/of/user?id=&current= → IntersectionObserver 无限滚动
 * - 复用 renderUserNotes / appendUserNotes 函数渲染瀑布流卡片
 * - state.notesCurrent 跟踪当前分页，切换 tab 时重置为 2
 */
/** 【用户主页的实现】游记 tab 内容渲染
 * 实现细节：
 * - notes == null（未预加载，如 profile.js 调用 renderUserTabs 未传 notesPreloaded）→ 主动 GET /note/of/user 拉取首屏 + 分页
 * - notes 为数组（已预加载或已拉取）→ 原逻辑：空数组=真实空数据（"还没有发布过游记"），有数据=渲染+分页
 * - 共用 buildUserNotesPagination helper 避免分页逻辑重复
 */
function loadUserTabNotes(container, userId, notes, state) {
  // 未预加载 → 主动拉取
  if (notes == null) {
    showLoading(container);
    (async () => {
      try {
        const data = await get('/note/of/user?id=' + userId + '&current=1');
        const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
        if (!list.length) {
          showEmpty(container, '还没有发布过游记');
          return;
        }
        container.innerHTML = '<div class="notes-waterfall" id="user-notes-list"></div>';
        const notesList = container.querySelector('#user-notes-list');
        renderUserNotes(notesList, list);
        // 首屏 page 1 已用完，分页从 page 2 开始
        buildUserNotesPagination(notesList, userId, 2, state);
      } catch {
        showEmpty(container, '加载失败');
      }
    })();
    return;
  }

  if (!notes.length) {
    showEmpty(container, '还没有发布过游记');
    return;
  }
  container.innerHTML = '<div class="notes-waterfall" id="user-notes-list"></div>';
  const notesList = container.querySelector('#user-notes-list');
  renderUserNotes(notesList, notes);
  // 已有预加载 page 1 数据，分页从 page 2 开始
  buildUserNotesPagination(notesList, userId, state.notesCurrent, state);
}

/** 【用户主页的实现】游记分页无限滚动 helper（共用，避免 loadUserTabNotes 两个分支重复）
 * - listEl：瀑布流容器 DOM 元素
 * - userId：目标用户 ID
 * - startPage：起始页码（通常为 2，首屏 page 1 已渲染）
 * - state：分页游标引用（state.notesCurrent 由本函数更新）
 */
function buildUserNotesPagination(listEl, userId, startPage, state) {
  let current = startPage;
  const sentinel = document.createElement('div');
  sentinel.className = 'load-sentinel';
  sentinel.style.cssText = 'height:10px;width:100%;column-span:all';
  listEl.appendChild(sentinel);

  loadMore(listEl, async () => {
    try {
      const data = await get('/note/of/user?id=' + userId + '&current=' + current);
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
      if (!list.length) { sentinel.remove(); return; }
      appendUserNotes(listEl, list, sentinel);
      current++;
      state.notesCurrent = current;
    } catch { sentinel.remove(); }
  }, 'load-sentinel');
}

/** 【用户主页的实现】评论 tab 内容渲染
 * - GET /user/comments/{userId}?current=1 → 评论卡片列表
 * - 每条评论：评论内容（escapeHtml 防 XSS） + 所属游记标题（可点击跳 #/note/{noteId}） + 时间
 * - 空态"暂无评论"
 * - 分页：IntersectionObserver + loadMore 无限滚动（state.commentsCurrent）
 */
async function loadUserTabComments(container, userId, state) {
  const data = await get('/user/comments/' + userId + '?current=1');
  const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

  if (!list.length) {
    container.innerHTML = '<div class="user-tab-content"><div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg><p>暂无评论</p></div></div>';
    return;
  }

  let html = '<div class="user-tab-content"><div class="user-comment-list">';
  list.forEach(c => {
    html +=
      '<div class="user-comment-item" data-note-id="' + (c.noteId || '') + '">' +
        '<div class="user-comment-text">' + escapeHtml(c.content || '') + '</div>' +
        '<div class="user-comment-note">' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>' +
          '<span>' + escapeHtml(c.noteTitle || '查看游记') + '</span>' +
        '</div>' +
        '<div class="user-comment-time">' + formatDate(c.createTime || c.commentTime) + '</div>' +
      '</div>';
  });
  html += '</div></div>';
  container.innerHTML = html;

  // 绑定评论卡片点击 → 跳游记详情
  container.querySelectorAll('.user-comment-item').forEach(item => {
    item.addEventListener('click', () => {
      const noteId = item.dataset.noteId;
      if (noteId) window.location.hash = '#/note/' + noteId;
    });
  });

  // 无限滚动加载更多评论
  if (list.length >= 10) {
    let current = 2;
    const sentinel = document.createElement('div');
    sentinel.className = 'load-sentinel load-sentinel-comments';
    sentinel.style.cssText = 'height:10px;width:100%';
    container.querySelector('.user-comment-list').appendChild(sentinel);

    loadMore(container.querySelector('.user-comment-list'), async () => {
      try {
        const moreData = await get('/user/comments/' + userId + '?current=' + current);
        const moreList = Array.isArray(moreData) ? moreData : (moreData && moreData.records ? moreData.records : []);
        if (!moreList.length) { sentinel.remove(); return; }
        const parent = container.querySelector('.user-comment-list');
        moreList.forEach(c => {
          const el = document.createElement('div');
          el.className = 'user-comment-item';
          el.dataset.noteId = c.noteId || '';
          el.innerHTML =
            '<div class="user-comment-text">' + escapeHtml(c.content || '') + '</div>' +
            '<div class="user-comment-note">' +
              '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>' +
              '<span>' + escapeHtml(c.noteTitle || '查看游记') + '</span>' +
            '</div>' +
            '<div class="user-comment-time">' + formatDate(c.createTime || c.commentTime) + '</div>';
          el.addEventListener('click', () => {
            if (c.noteId) window.location.hash = '#/note/' + c.noteId;
          });
          parent.insertBefore(el, sentinel);
        });
        current++;
        state.commentsCurrent = current;
      } catch { sentinel.remove(); }
    }, 'load-sentinel-comments');
  }
}

/** 【用户主页的实现】收藏 tab 内容渲染（含子 tab：游记/景点）
 * - GET /favorite/list?userId={id}&type=note|attraction&current=1
 * - 子 tab 切换 → 重新拉取对应类型列表
 * - 列表项：缩略图 + 标题 + 时间，点击跳 #/note/{targetId} 或 #/attraction/{targetId}
 * - 空态"暂无收藏"
 * - 分页：IntersectionObserver 无限滚动
 */
async function loadUserTabFavorites(container, userId, state) {
  const subType = state.favSubType || 'note';

  function subTabsHtml() {
    return '<div class="fav-tabs" id="ufav-tabs">' +
      '<div class="fav-tab' + (subType === 'note' ? ' active' : '') + '" data-type="note">游记</div>' +
      '<div class="fav-tab' + (subType === 'attraction' ? ' active' : '') + '" data-type="attraction">景点</div>' +
      '</div>';
  }

  async function loadFavList(currentPage) {
    const data = await get('/favorite/list?userId=' + userId + '&type=' + subType + '&current=' + (currentPage || 1));
    return Array.isArray(data) ? data : (data && data.records ? data.records : []);
  }

  const list = await loadFavList(1);

  if (!list.length) {
    container.innerHTML = '<div class="user-tab-content">' + subTabsHtml() + '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无收藏</p></div></div>';
    bindUserFavSubTabs(container, userId, state, loadFavList);
    return;
  }

  let html = '<div class="user-tab-content">' + subTabsHtml() + '<div class="fav-list" id="ufav-list">';
  list.forEach(item => {
    const img = item.image ? item.image.split(',')[0] : '';
    const targetHash = subType === 'note' ? '#/note/' + item.targetId : '#/attraction/' + item.targetId;
    html +=
      '<div class="fav-item" data-url="' + targetHash + '">' +
        (img ? '<img class="fav-item-thumb" src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : '<div class="fav-item-thumb" style="background:var(--border);display:flex;align-items:center;justify-content:center;color:var(--text-hint);font-size:12px">无图</div>') +
        '<div class="fav-item-info">' +
          '<div class="fav-item-title">' + escapeHtml(item.title || '无标题') + '</div>' +
          '<div class="fav-item-time">' + formatDate(item.createTime) + '</div>' +
        '</div>' +
      '</div>';
  });
  html += '</div></div>';
  container.innerHTML = html;

  // 绑定收藏卡片点击
  container.querySelectorAll('.fav-item').forEach(itemEl => {
    itemEl.addEventListener('click', () => {
      const url = itemEl.dataset.url;
      if (url) window.location.hash = url;
    });
  });

  bindUserFavSubTabs(container, userId, state, loadFavList);

  // 无限滚动
  if (list.length >= 10) {
    let current = 2;
    const listEl = container.querySelector('#ufav-list');
    const sentinel = document.createElement('div');
    sentinel.className = 'load-sentinel load-sentinel-fav';
    sentinel.style.cssText = 'height:10px;width:100%';
    listEl.appendChild(sentinel);

    loadMore(listEl, async () => {
      try {
        const moreList = await loadFavList(current);
        if (!moreList.length) { sentinel.remove(); return; }
        moreList.forEach(item => {
          const img = item.image ? item.image.split(',')[0] : '';
          const targetHash = subType === 'note' ? '#/note/' + item.targetId : '#/attraction/' + item.targetId;
          const el = document.createElement('div');
          el.className = 'fav-item';
          el.dataset.url = targetHash;
          el.innerHTML =
            (img ? '<img class="fav-item-thumb" src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : '<div class="fav-item-thumb" style="background:var(--border);display:flex;align-items:center;justify-content:center;color:var(--text-hint);font-size:12px">无图</div>') +
            '<div class="fav-item-info">' +
              '<div class="fav-item-title">' + escapeHtml(item.title || '无标题') + '</div>' +
              '<div class="fav-item-time">' + formatDate(item.createTime) + '</div>' +
            '</div>';
          el.addEventListener('click', () => {
            if (el.dataset.url) window.location.hash = el.dataset.url;
          });
          listEl.insertBefore(el, sentinel);
        });
        current++;
        state.favCurrent = current;
      } catch { sentinel.remove(); }
    }, 'load-sentinel-fav');
  }
}

/** 【用户主页的实现】收藏子 tab 切换事件绑定
 * - 点击游记/景点 → 更新 state.favSubType → 重新拉取并渲染列表
 */
function bindUserFavSubTabs(container, userId, state, loadFavList) {
  container.querySelectorAll('#ufav-tabs .fav-tab').forEach(tab => {
    tab.addEventListener('click', async () => {
      const type = tab.dataset.type;
      if (type === state.favSubType) return;
      state.favSubType = type;
      // 重新渲染整个收藏区域
      loadUserTabFavorites(container, userId, state);
    });
  });
}

/** 【用户主页的实现】赞过 tab 内容渲染（含子 tab：游记/景点）
 * - 游记：GET /user/liked-notes/{userId} → 瀑布流卡片（复用 note-card）
 * - 景点：GET /user/liked-attractions/{userId} → 列表（缩略图+标题，点击跳 #/attraction/{id}）
 * - 子 tab 切换 → 重新拉取对应类型数据
 * - 空态"暂无赞过"
 */
async function loadUserTabLikes(container, userId, state) {
  const subType = state.likesSubType || 'note';

  function subTabsHtml() {
    return '<div class="fav-tabs" id="ulikes-tabs">' +
      '<div class="fav-tab' + (subType === 'note' ? ' active' : '') + '" data-type="note">游记</div>' +
      '<div class="fav-tab' + (subType === 'attraction' ? ' active' : '') + '" data-type="attraction">景点</div>' +
      '</div>';
  }

  async function loadList() {
    if (subType === 'note') {
      const data = await get('/user/liked-notes/' + userId);
      return Array.isArray(data) ? data : (data && data.records ? data.records : []);
    } else {
      const data = await get('/user/liked-attractions/' + userId);
      return Array.isArray(data) ? data : (data && data.records ? data.records : []);
    }
  }

  try {
    const list = await loadList();

    if (!list.length) {
      container.innerHTML = '<div class="user-tab-content">' + subTabsHtml() + '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg><p>暂无' + (subType === 'note' ? '赞过的游记' : '赞过的景点') + '</p></div></div>';
      bindUserLikesSubTabs(container, userId, state);
      return;
    }

    let html = '<div class="user-tab-content">' + subTabsHtml();

    if (subType === 'note') {
      // 游记：瀑布流卡片
      html += '<div class="notes-waterfall" id="ulikes-notes-list">';
      list.forEach(note => {
        /** 【用户主页的实现】图片字段兼容：赞过游记 note 返回 image 字段（而非 images），
         *   同时兼容 images 兜底（后端 liked-notes 接口返回 image 字段） */
        const img = (note.images || note.image || '').split(',')[0];
        html +=
          '<div class="note-card" data-id="' + note.id + '">' +
            '<div class="note-img"><img src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/></div>' +
            '<div class="note-body">' +
              '<div class="note-title">' + escapeHtml(note.title || '') + '</div>' +
              '<div class="note-footer">' +
                '<div class="note-likes">' +
                  '<svg width="14" height="14" viewBox="0 0 24 24" fill="' + (note.isLike ? '#f87171' : 'none') + '" stroke="' + (note.isLike ? '#f87171' : '#999') + '" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>' +
                  '<span>' + (note.liked || 0) + '</span>' +
                '</div>' +
              '</div>' +
            '</div>' +
          '</div>';
      });
      html += '</div>';
      container.innerHTML = html;
      // 绑定卡片点击
      container.querySelectorAll('.note-card').forEach(card => {
        card.addEventListener('click', () => {
          window.location.hash = '#/note/' + card.dataset.id;
        });
      });
    } else {
      // 景点：缩略图列表
      html += '<div class="fav-list">';
      list.forEach(item => {
        const img = (item.images || item.image || '').split(',')[0];
        const itemId = item.id || item.targetId;
        html +=
          '<div class="fav-item" data-url="#/attraction/' + itemId + '">' +
            (img ? '<img class="fav-item-thumb" src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : '<div class="fav-item-thumb" style="background:var(--border);display:flex;align-items:center;justify-content:center;color:var(--text-hint);font-size:12px">无图</div>') +
            '<div class="fav-item-info">' +
              '<div class="fav-item-title">' + escapeHtml(item.name || item.title || '无标题') + '</div>' +
            '</div>' +
          '</div>';
      });
      html += '</div>';
      container.innerHTML = html;
      // 绑定景点卡片点击
      container.querySelectorAll('.fav-item').forEach(itemEl => {
        itemEl.addEventListener('click', () => {
          if (itemEl.dataset.url) window.location.hash = itemEl.dataset.url;
        });
      });
    }

    bindUserLikesSubTabs(container, userId, state);
  } catch {
    showEmpty(container, '加载失败');
  }
}

/** 【用户主页的实现】赞过子 tab 切换事件绑定
 * - 点击游记/景点 → 更新 state.likesSubType → 重新拉取并渲染列表
 */
function bindUserLikesSubTabs(container, userId, state) {
  container.querySelectorAll('#ulikes-tabs .fav-tab').forEach(tab => {
    tab.addEventListener('click', async () => {
      const type = tab.dataset.type;
      if (type === state.likesSubType) return;
      state.likesSubType = type;
      loadUserTabLikes(container, userId, state);
    });
  });
}


/** 【用户足迹的实现】足迹 tab 内容渲染（含地图 + 打卡列表）
 * - GET /user/checkins/{userId}?current= → 打卡景点列表
 * - 卡片：缩略图 + 名称 + 打卡时间，点击跳 #/attraction/{id}
 * - 地图：Leaflet 加载该用户打卡点标记，点击标记 popup 跳景点详情
 * - 自己 + 他人主页都可看足迹（足迹公开）
 * - 复用 travel.js 的 Leaflet 模式（中心杭州、OSM 瓦片、L.marker + popup）
 */
async function loadUserTabFootprints(container, userId, state) {
  showLoading(container);
  try {
    const data = await get('/user/checkins/' + userId + '?current=1');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

    if (!list.length) {
      container.innerHTML = '<div class="user-tab-content"><div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg><p>还没有打卡记录</p><p style="font-size:12px;margin-top:4px">去景点打卡，留下你的足迹吧</p></div></div>';
      return;
    }

    // 构建 HTML：地图容器 + 打卡列表
    const leafletAvailable = typeof window.L !== 'undefined';
    let html = '<div class="user-tab-content">';

    if (leafletAvailable) {
      html += '<div class="footprint-map-container" id="footprint-map" style="height:250px;margin:0"></div>';
    } else {
      html += '<div class="map-fallback" style="height:80px;display:flex;align-items:center;justify-content:center;color:var(--text-hint);font-size:13px;background:var(--surface)"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5" style="margin-right:8px"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>地图加载失败</div>';
    }

    html += '<div class="footprint-list" id="footprint-list">';

    list.forEach(item => {
      /** 【用户足迹的实现】图片字段兼容：足迹接口返回 image 字段（而非 images），同时兼容 images 兜底 */
      const img = (item.images || item.image || '').split(',')[0];
      html += `
        <div class="footprint-item" data-id="${item.id}" data-lat="${item.y || ''}" data-lng="${item.x || ''}" data-name="${escapeHtml(item.name || '')}">
          ${img ? '<img class="footprint-item-thumb" src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : '<div class="footprint-item-thumb" style="background:var(--border);display:flex;align-items:center;justify-content:center;color:var(--text-hint);font-size:12px">无图</div>'}
          <div class="footprint-item-info">
            <div class="footprint-item-name">${escapeHtml(item.name || '未命名景点')}</div>
            <div class="footprint-item-time">${formatDate(item.checkinTime || item.createTime)}</div>
          </div>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
        </div>`;
    });

    html += '</div></div>';
    container.innerHTML = html;

    // 绑定打卡卡片点击 → 跳景点详情
    container.querySelectorAll('.footprint-item').forEach(item => {
      item.addEventListener('click', () => {
        window.location.hash = '#/attraction/' + item.dataset.id;
      });
    });

    /** 【用户足迹的实现】Leaflet 地图初始化（复用 travel.js 模式）
     * - 中心杭州（30.26, 120.15）zoom=11
     * - 遍历打卡点为每个景点创建 L.marker + popup（名称 + 点击跳详情）
     * - cleanup：页面销毁时 map.remove()
     */
    if (leafletAvailable) {
      const mapEl = container.querySelector('#footprint-map');
      if (mapEl) {
        mapEl.style.height = '250px';
        const map = L.map(mapEl).setView([30.26, 120.15], 11);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
          attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
          maxZoom: 18
        }).addTo(map);

        let hasMarker = false;
        list.forEach(item => {
          if (item.x != null && item.y != null) {
            const marker = L.marker([item.y, item.x]).addTo(map);
            marker.bindPopup('<b>' + (item.name || '景点') + '</b><br/><a href="#/attraction/' + item.id + '">查看详情</a>');
            hasMarker = true;
          }
        });

        // 如果有标记，调整地图视野
        if (hasMarker) {
          const group = new L.featureGroup(
            list.filter(i => i.x != null && i.y != null).map(i => L.marker([i.y, i.x]))
          );
          if (group.getLayers().length > 0) {
            map.fitBounds(group.getBounds().pad(0.1));
          }
        }

        // 保存 map 到 container 以便 cleanup
        container._footprintMap = map;
      }
    }

    // 无限滚动加载更多打卡
    if (list.length >= 10) {
      let current = 2;
      const listEl = container.querySelector('#footprint-list');
      const sentinel = document.createElement('div');
      sentinel.className = 'load-sentinel load-sentinel-footprints';
      sentinel.style.cssText = 'height:10px;width:100%';
      listEl.appendChild(sentinel);

      loadMore(listEl, async () => {
        try {
          const moreData = await get('/user/checkins/' + userId + '?current=' + current);
          const moreList = Array.isArray(moreData) ? moreData : (moreData && moreData.records ? moreData.records : []);
          if (!moreList.length) { sentinel.remove(); return; }
          moreList.forEach(item => {
            /** 【用户足迹的实现】图片字段兼容：足迹接口返回 image 字段（而非 images），同时兼容 images 兜底 */
            const img = (item.images || item.image || '').split(',')[0];
            const el = document.createElement('div');
            el.className = 'footprint-item';
            el.dataset.id = item.id;
            el.dataset.lat = item.y || '';
            el.dataset.lng = item.x || '';
            el.dataset.name = item.name || '';
            el.innerHTML =
              (img ? '<img class="footprint-item-thumb" src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : '<div class="footprint-item-thumb" style="background:var(--border);display:flex;align-items:center;justify-content:center;color:var(--text-hint);font-size:12px">无图</div>') +
              '<div class="footprint-item-info">' +
                '<div class="footprint-item-name">' + escapeHtml(item.name || '未命名景点') + '</div>' +
                '<div class="footprint-item-time">' + formatDate(item.checkinTime || item.createTime) + '</div>' +
              '</div>' +
              '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>';
            el.addEventListener('click', () => {
              window.location.hash = '#/attraction/' + item.id;
            });
            listEl.insertBefore(el, sentinel);
          });
          current++;
        } catch { sentinel.remove(); }
      }, 'load-sentinel-footprints');
    }

  } catch {
    showEmpty(container, '加载失败');
  }
}

/** 【用户足迹的实现】页面销毁时清理 Leaflet 地图实例 */
export function cleanupFootprintMap(container) {
  if (container && container._footprintMap) {
    container._footprintMap.remove();
    container._footprintMap = null;
  }
}

/** 【个人中心的实现】可复用 tab 渲染函数（游记/评论/收藏/赞过/足迹）
 * 实现细节：
 * - container：渲染 tab 栏 + 内容区的 DOM 元素
 * - userId：目标用户 ID（自己或他人）
 * - root：父级根元素引用（用于事件托管/清理映射，默认同 container）
 * - notesPreloaded：可选预加载游记数据（Promise.all 中预取的首屏数据，为空时 tab 内自行拉取）
 * - profile.js 与 user.js 共用此实现（单一数据源），他人主页无签到/操作组由调用侧控制
 * - 足迹地图 Leaflet 初始化/清理逻辑封装在内，切换 tab 时自动清理（map.remove()）
 * - 收藏/赞过子 tab 切换与分页游标均在 tabState 中管理（引用传递，loader 内部修改）
 */
export function renderUserTabs(container, userId, root, notesPreloaded) {
  root = root || container;
  // 【修复】不再把 undefined/null 强转为 [] —— 保留未预加载标记透传，由 loadUserTabNotes 区分"未预加载需拉取"与"空数组=真无数据"

  // 渲染 tab 栏 HTML + 内容区
  container.innerHTML = `
    <div class="user-tabs" id="user-tabs">
      <div class="user-tab active" data-tab="notes">游记</div>
      <div class="user-tab" data-tab="comments">评论</div>
      <div class="user-tab" data-tab="favorites">收藏</div>
      <div class="user-tab" data-tab="likes">赞过</div>
      <div class="user-tab" data-tab="footprints">足迹</div>
    </div>
    <div id="user-tab-content"></div>
  `;

  const tabContent = container.querySelector('#user-tab-content');
  let currentTab = 'notes';
  const tabState = {
    notesCurrent: 2,   // 游记分页游标（首屏 page 1 由预加载或自行拉取）
    commentsCurrent: 1,
    favCurrent: 1,
    favSubType: 'note',    // 收藏子 tab：游记/景点
    likesSubType: 'note'   // 赞过子 tab：游记/景点
  };

  // Tab 点击切换
  container.querySelectorAll('#user-tabs .user-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      const newTab = tab.dataset.tab;
      if (newTab === currentTab) return;
      /** 【用户足迹的实现】切换离开足迹 tab 时清理 Leaflet 地图 */
      if (currentTab === 'footprints') {
        if (tabContent._footprintMap) {
          tabContent._footprintMap.remove();
          tabContent._footprintMap = null;
        }
      }
      // 更新 tab 选中态
      container.querySelectorAll('#user-tabs .user-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      currentTab = newTab;
      // 重置分页游标
      tabState.notesCurrent = 2;
      tabState.commentsCurrent = 1;
      tabState.favCurrent = 1;
      switchUserTab(tabContent, userId, newTab, notesPreloaded, tabState);
    });
  });

  // 默认加载游记 tab（有预加载数据直接用，无则空数组触发内部拉取）
  switchUserTab(tabContent, userId, 'notes', notesPreloaded, tabState);
}


function renderUserNotes(container, list) {
  if (!list.length) {
    showEmpty(container, '还没有发布过游记');
    return;
  }
  let html = '';
  list.forEach(note => {
    /** 【用户主页的实现】图片字段兼容：兼容 images 与 image（后端可能返回 image 字段） */
    const img = (note.images || note.image || '').split(',')[0];
    html += `
      <div class="note-card" data-id="${note.id}">
        <div class="note-img">
          <img src="${imgUrl(img)}" alt="" onerror="imgError(event)"/>
        </div>
        <div class="note-body">
          <div class="note-title">${note.title}</div>
          <div class="note-footer">
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
}

/** 【用户主页的实现】瀑布流追加游记卡片（无限滚动）
 * - 新卡片插入 sentinel 之前，保持瀑布流顺序
 * - 结构与 renderUserNotes 一致
 */
function appendUserNotes(container, list, sentinel) {
  list.forEach(note => {
    /** 【用户主页的实现】图片字段兼容：兼容 images 与 image（后端可能返回 image 字段） */
    const img = (note.images || note.image || '').split(',')[0];
    const el = document.createElement('div');
    el.className = 'note-card';
    el.dataset.id = note.id;
    el.innerHTML = `
      <div class="note-img">
        <img src="${imgUrl(img)}" alt="" onerror="imgError(event)"/>
      </div>
      <div class="note-body">
        <div class="note-title">${note.title}</div>
        <div class="note-footer">
          <div class="note-likes">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="${note.isLike ? '#f87171' : 'none'}" stroke="${note.isLike ? '#f87171' : '#999'}" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
            <span>${note.liked || 0}</span>
          </div>
        </div>
      </div>`;
    el.addEventListener('click', () => {
      window.location.hash = '#/note/' + note.id;
    });
    container.insertBefore(el, sentinel);
  });
}
