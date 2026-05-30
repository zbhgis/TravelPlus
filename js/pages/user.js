import { get, put } from '../api.js';
import { imgUrl, imgError, showLoading, showEmpty, toast, loadMore } from '../ui.js';
import { isLoggedIn, getUser } from '../store.js';

/** 【用户主页的实现】用户主页渲染
 * 实现细节：
 * - 路由 #/user/:id，params.id 为目标用户 ID（字符串）
 * - GET /user/home/{id} 获取用户信息、简介、统计、关注状态
 * - GET /note/of/user?id={id}&current=1 获取 TA 的游记列表（瀑布流 note-card 复用）
 * - 自己主页判断：getUser().id == params.id（宽松比较，兼容数字/字符串）
 * - 自己主页不显示关注按钮；未登录点击关注/统计跳 #/login 带 redirect
 * - 关注按钮：初始 followed 状态 → "已关注"/"+ 关注"；点击 PUT /follow/{id}/{!followed} 切换；失败回滚
/** 【用户主页的实现】统计行字段语义
 * - fans=粉丝数（有多少人关注 TA / 关注我的人数）
 * - followee=关注数（TA 关注了多少人 / 我关注的人数）
 * - likedCount=获赞数
 * - 关注=followee（TA关注的人，type=following 列表）、粉丝=fans（关注TA的人，type=fans 列表）、获赞=likedCount（不可点击）
 * - 统计行数字可点击 → #/user/{id}/follows?type=following|fans
 * - 游记列表无限滚动：IntersectionObserver + loadMore，sentinel 插入后触发
 * - 若列表接口因隐私返回 fail，api.js 已自动 toast errorMsg
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
     * - 自己主页显示「编辑资料」按钮 → #/user/edit、「浏览记录」入口 → #/user/browse；他人主页不显示
     * - 他人主页显示关注按钮：class .follow-btn，data-followed 记录状态
     * - TA 的游记：class .notes-waterfall 复用瀑布流布局
     */
    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="user-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>TA的主页</h2>
      </div>
      <div class="user-profile">
        <!-- 【用户主页的实现】小红书样式：背景 cover + 头像负 margin 悬浮叠加 -->
        <div class="user-header">
          <div class="user-bg" style="${bgStyle}">${bgFallback}</div>
          <img class="avatar avatar-xhs" src="${imgUrl(user.icon)}" alt="" onerror="imgError(event)"/>
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
        ${isSelf ? `
        <div class="user-self-actions">
          <button class="btn btn-outline btn-sm" id="user-edit-btn">编辑资料</button>
          <button class="btn btn-outline btn-sm" id="user-browse-btn">浏览记录</button>
        </div>` : `
        <div class="user-actions">
          <button class="btn btn-outline btn-sm follow-btn" id="user-follow-btn" data-followed="${followed}">
            ${followed ? '已关注' : '+ 关注'}
          </button>
        </div>`}
      </div>
      <div class="section-title">TA的游记</div>
      <div class="notes-waterfall" id="user-notes-list"></div>
    `;

    // 返回按钮
    root.querySelector('#user-back').addEventListener('click', () => window.history.back());

    /** 【个人主页增强的实现】自己主页操作按钮
     * - 「编辑资料」→ #/user/edit
     * - 「浏览记录」→ #/user/browse
     */
    const editBtn = root.querySelector('#user-edit-btn');
    if (editBtn) {
      editBtn.addEventListener('click', () => {
        window.location.hash = '#/user/edit';
      });
    }
    const browseBtn = root.querySelector('#user-browse-btn');
    if (browseBtn) {
      browseBtn.addEventListener('click', () => {
        window.location.hash = '#/user/browse';
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

    // TA 的游记列表初始渲染
    const notesList = root.querySelector('#user-notes-list');
    renderUserNotes(notesList, notes);

    // 无限滚动加载更多
    if (hasMoreNotes) {
      let current = 2;
      const sentinel = document.createElement('div');
      sentinel.className = 'load-sentinel';
      sentinel.style.cssText = 'height:10px;width:100%;column-span:all';
      notesList.appendChild(sentinel);

      loadMore(notesList, async () => {
        try {
          const data = await get('/note/of/user?id=' + id + '&current=' + current);
          const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
          if (!list.length) {
            sentinel.remove();
            return;
          }
          appendUserNotes(notesList, list, sentinel);
          current++;
        } catch { sentinel.remove(); }
      }, 'load-sentinel');
    }

  } catch {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}

/** 【用户主页的实现】渲染 TA 的游记卡片（瀑布流 note-card 复用）
 * - 复用 .note-card / .notes-waterfall 样式，结构与 notes.js 一致
 * - 显示首图（note.images 逗号分隔取第一个）、标题、点赞数
 * - 点击卡片跳转游记详情 #/note/{id}
 * - 空列表显示「还没有发布过游记」占位
 */
function renderUserNotes(container, list) {
  if (!list.length) {
    showEmpty(container, '还没有发布过游记');
    return;
  }
  let html = '';
  list.forEach(note => {
    const img = note.images ? note.images.split(',')[0] : '';
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
    const img = note.images ? note.images.split(',')[0] : '';
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
