import { get } from '../api.js';
import { imgUrl, imgError, showLoading, showEmpty, loadMore, getUrlParam } from '../ui.js';

/** 【关注粉丝列表的实现】关注/粉丝列表页渲染
 * 实现细节：
 * - 路由 #/user/:id/follows?type=following|fans，params.id 为目标用户 ID
 * - type 从 URL query 参数获取（getUrlParam('type')），默认 'fans'
 * - fans=粉丝数（关注TA的人数）、followee=关注数（TA关注的人数）
 * - type=following → 标题"TA的关注"，接口 GET /follow/list/{id}?type=following&current=1（TA关注的用户列表）
 * - type=fans → 标题"TA的粉丝"，接口 GET /follow/list/{id}?type=fans&current=1（关注TA的用户列表）
 * - 返回数据：List<UserDTO{id,nickName,icon}>，兼容直接数组与 {records:[]} 分页格式
 * - 渲染用户列表：头像 avatar + 昵称 + 右箭头；点击整行跳转 #/user/{uid}
 * - 无限滚动：IntersectionObserver + loadMore，sentinel 触发加载下一页
 * - 空态：type=following → "暂无关注"，type=fans → "暂无粉丝"
 */
export async function renderFollows(container, params) {
  const id = params.id;
  const type = getUrlParam('type') || 'fans';
  const title = type === 'following' ? 'TA的关注' : 'TA的粉丝';
  const emptyMsg = type === 'following' ? '暂无关注' : '暂无粉丝';

  container.innerHTML = `
    <div id="follows-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#follows-root');

  try {
    const data = await get('/follow/list/' + id + '?type=' + type + '&current=1');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="follows-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>${title}</h2>
      </div>
      <div class="follows-list" id="follows-list"></div>
    `;

    // 返回按钮
    root.querySelector('#follows-back').addEventListener('click', () => window.history.back());

    const listEl = root.querySelector('#follows-list');
    renderFollowUsers(listEl, list, emptyMsg);

    // 无限滚动加载更多
    if (list.length > 0) {
      let current = 2;
      const sentinel = document.createElement('div');
      sentinel.className = 'load-sentinel';
      sentinel.style.cssText = 'height:10px;';
      listEl.appendChild(sentinel);

      loadMore(listEl, async () => {
        try {
          const nextData = await get('/follow/list/' + id + '?type=' + type + '&current=' + current);
          const nextList = Array.isArray(nextData) ? nextData : (nextData && nextData.records ? nextData.records : []);
          if (!nextList.length) {
            sentinel.remove();
            return;
          }
          appendFollowUsers(listEl, nextList, sentinel);
          current++;
        } catch { sentinel.remove(); }
      }, 'load-sentinel');
    }

  } catch {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}

/** 【关注粉丝列表的实现】渲染用户列表
 * - 每个 .follows-item 包含头像（avatar）、昵称、右箭头指示
 * - 点击整行 → #/user/{uid}
 * - 空列表显示 emptyMsg（"暂无关注"/"暂无粉丝"）
 */
function renderFollowUsers(container, list, emptyMsg) {
  if (!list.length) {
    showEmpty(container, emptyMsg);
    return;
  }

  container.innerHTML = list.map(u => `
    <div class="follows-item" data-uid="${u.id}">
      <img class="avatar" src="${imgUrl(u.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${u.id}" data-avatar-icon="${imgUrl(u.icon)}" data-avatar-nick="${u.nickName || '用户'}"/>
      <div class="follows-item-info">
        <span class="follows-item-name">${u.nickName || '用户'}</span>
      </div>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
    </div>
  `).join('');

  container.querySelectorAll('.follows-item').forEach(item => {
    item.addEventListener('click', () => {
      window.location.hash = '#/user/' + item.dataset.uid;
    });
  });
}

/** 【关注粉丝列表的实现】追加更多用户项（无限滚动）
 * - 新 .follows-item 插入 sentinel 之前
 * - 结构与 renderFollowUsers 一致
 */
function appendFollowUsers(container, list, sentinel) {
  list.forEach(u => {
    const el = document.createElement('div');
    el.className = 'follows-item';
    el.dataset.uid = u.id;
    el.innerHTML = `
      <img class="avatar" src="${imgUrl(u.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${u.id}" data-avatar-icon="${imgUrl(u.icon)}" data-avatar-nick="${u.nickName || '用户'}"/>
      <div class="follows-item-info">
        <span class="follows-item-name">${u.nickName || '用户'}</span>
      </div>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
    `;
    el.addEventListener('click', () => {
      window.location.hash = '#/user/' + u.id;
    });
    container.insertBefore(el, sentinel);
  });
}
