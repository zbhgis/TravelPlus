/**
 * 【个人中心页的实现】#/profile 页面渲染模块——小红书样式我的页
 * 职责：背景图头部（悬浮头像）、签到日历、签到按钮、我的游记列表（含删除）、退出登录
 * 依赖：api.js (get/post/del)、ui.js (imgUrl/imgError/showLoading/showEmpty/toast)、store.js (isLoggedIn/getUser/logout)
 * 核心链路：
 *   renderProfile(container)
 *     → 未登录 → 显示引导 + "去登录"按钮
 *     → 已登录 → 并行加载签到天数 (GET /user/sign/count) + 签到日期列表 (GET /user/sign/list)
 *     → 渲染小红书样式头部：.profile-cover（背景图 cover 150px + 头像 avatar-xhs 负 margin 悬浮叠加 + 居中昵称）
 *     → 签到区：已连续签到 N 天 + 签到按钮 + 签到日历
 *     → 我的游记列表：GET /note/of/me → 卡片列表 + × 删除按钮
 *     → 退出登录：POST /user/logout（双保险：无论成败均 logout() + toast"已退出" + 跳 #/home）
 * 子功能：
 *   - 签到：POST /user/sign → 刷新天数 + 重新拉取签到列表刷新日历
 *   - 游记删除：DELETE /note/{id} → 移除卡片 DOM
 *   - 头像点击 → #/user/{自己id}
 */
import { get, post, del } from '../api.js';
import { imgUrl, imgError, showLoading, showEmpty, toast } from '../ui.js';
import { isLoggedIn, getUser, logout } from '../store.js';

export async function renderProfile(container) {
  const user = getUser();

  if (!isLoggedIn() || !user) {
    container.innerHTML = `
      <div class="page-header">
        <h2>我的</h2>
      </div>
      <div class="profile-login-prompt">
        <p>登录后查看个人信息</p>
        <button class="btn btn-primary" id="go-login">去登录</button>
      </div>
    `;
    container.querySelector('#go-login').addEventListener('click', () => {
      window.location.hash = '#/login?redirect=profile';
    });
    return;
  }

  container.innerHTML = `
    <div class="page-header">
      <h2>我的</h2>
    </div>
    <div id="profile-content">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;

  const contentEl = container.querySelector('#profile-content');

  try {
    let signCount = 0;
    try { signCount = await get('/user/sign/count'); } catch {}

    /** 【签到功能的实现】加载本月签到日期列表
     * - GET /user/sign/list 返回本月已签到日期号数组 List<Integer>（如 [1,3,5]）
     * - 失败静默：隐藏日历容器不阻塞页面
     */
    let signedDays = [];
    try { signedDays = await get('/user/sign/list'); } catch { signedDays = []; }

    /** 【用户主页的实现】我的页背景图（小红书样式）
     * - getUser().bgImage 非空 → 全宽 150px cover 大图头部
     * - 为空 → CSS 默认渐变（--primary-light → #c8e6e0）
     * - 加载失败 → 隐藏 img onerror 清除 inline style → 回退 CSS 渐变
     */
    const bgImageUrl = user.bgImage ? imgUrl(user.bgImage) : '';
    const bgStyle = bgImageUrl ? 'background-image:url(' + bgImageUrl + ')' : '';
    const bgFallback = bgImageUrl
      ? '<img src="' + bgImageUrl + '" style="display:none" onerror="this.parentElement.style.backgroundImage=\'\'">'
      : '';

    contentEl.innerHTML = `
      <!-- 【用户主页的实现】我的页小红书样式头部：背景 cover + 头像负 margin 悬浮叠加 -->
      <div class="profile-cover">
        <div class="user-bg" style="${bgStyle}">${bgFallback}</div>
        <img class="avatar avatar-xhs" src="${imgUrl(user.icon)}" alt="" onerror="imgError(event)" id="profile-avatar"/>
        <div class="profile-cover-name">${user.nickName || '用户'}</div>
      </div>
      <div class="sign-card">
        <div class="sign-info">已连续签到 <span class="sign-days" id="sign-days">${signCount}</span> 天</div>
        <button class="btn btn-primary btn-sm" id="sign-btn">签到</button>
      </div>
      <div class="sign-calendar-wrap" id="sign-calendar-wrap"></div>
      <div class="section-title">我的游记</div>
      <div class="page-content" id="my-notes-list"></div>
      <div style="padding:20px 16px;">
        <button class="btn btn-outline btn-block" id="logout-btn">退出登录</button>
      </div>
    `;

    // Sign-in
    const signBtn = contentEl.querySelector('#sign-btn');
    signBtn.addEventListener('click', async () => {
      try {
        await post('/user/sign');
        const cnt = await get('/user/sign/count');
        contentEl.querySelector('#sign-days').textContent = cnt;
        signBtn.disabled = true;
        signBtn.textContent = '已签到';
        toast('签到成功');
        /** 【签到功能的实现】签到后刷新日历
         * - 重新拉取签到列表，刷新日历网格
         * - 本地更新今天格子高亮（即使接口失败也尝试本地更新）
         */
        try {
          const updatedDays = await get('/user/sign/list');
          renderSignCalendar(contentEl.querySelector('#sign-calendar-wrap'), updatedDays || []);
        } catch {
          // 本地更新：直接追加今天日期到已有列表
          const today = new Date().getDate();
          const merged = [...new Set([...(signedDays || []), today])];
          renderSignCalendar(contentEl.querySelector('#sign-calendar-wrap'), merged);
        }
      } catch {}
    });

    /** 【签到功能的实现】初始渲染签到日历
     * - 用已加载的 signedDays 渲染当月日历
     * - 失败时 renderSignCalendar 内部隐藏日历容器
     */
    renderSignCalendar(contentEl.querySelector('#sign-calendar-wrap'), signedDays || []);

    /** 【用户主页的实现】自己头像点击跳转用户主页
     * - profile 页面右上角头像 → #/user/{自己id}
     */
    const profileAvatar = contentEl.querySelector('#profile-avatar');
    if (profileAvatar && user.id) {
      profileAvatar.style.cursor = 'pointer';
      profileAvatar.addEventListener('click', () => {
        window.location.hash = '#/user/' + user.id;
      });
    }

    // My notes
    const notesList = contentEl.querySelector('#my-notes-list');
    loadMyNotes(notesList);

    /** 【登出功能的实现】前后端配合双保险
     *  - 先调 POST /user/logout（api.js 自动带 authorization 头），删除服务端 Redis 登录态
     *  - 无论服务端接口成败，均执行本地清理：logout() 清 token + 清 user
     *  - toast "已退出" → 跳 #/home
     *  - 设计意图：服务端失效登出态 + 本地清理双保险，确保即使后端接口异常也不会残留登录状态
     */
    contentEl.querySelector('#logout-btn').addEventListener('click', async () => {
      try {
        await post('/user/logout');
      } catch {
        // api.js already toasts error；即使服务端失败也不影响本地清理
      }
      logout();
      toast('已退出');
      window.location.hash = '#/home';
    });

  } catch {
    contentEl.innerHTML = '<div class="empty-state"><p>加载失败</p></div>';
  }
}

async function loadMyNotes(container) {
  showLoading(container);
  try {
    const data = await get('/note/of/me?current=1');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

    if (!list.length) {
      showEmpty(container, '还没有发布过游记');
      return;
    }

    container.innerHTML = list.map(note => {
      const img = note.images ? note.images.split(',')[0] : '';
      return `
        <div class="feed-item feed-note-card my-note-item" data-id="${note.id}">
          ${img ? '<div class="note-img"><img src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/></div>' : ''}
          <div class="note-body">
            <div class="note-title">${note.title}</div>
            <div class="note-footer">
              <span>${note.liked || 0} 赞</span>
            </div>
          </div>
          <button class="my-note-delete" data-id="${note.id}" title="删除">×</button>
        </div>`;
    }).join('');

    container.querySelectorAll('.feed-note-card').forEach(card => {
      card.addEventListener('click', (e) => {
        // 点击删除按钮时不跳转详情
        if (e.target.closest('.my-note-delete')) return;
        window.location.hash = '#/note/' + card.dataset.id;
      });
    });

    /** 【游记删除的实现】我的游记列表删除按钮
     * - 每条游记右上角显示 × 删除按钮（class .my-note-delete）
     * - 点击确认 → DELETE /note/{id} → 成功 toast + 移除卡片 DOM
     * - 失败由 api.js 统一 toast errorMsg，卡片不移除
     */
    container.querySelectorAll('.my-note-delete').forEach(btn => {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        const noteId = btn.dataset.id;
        if (!confirm('确认删除该游记？')) return;
        try {
          await del('/note/' + noteId);
          toast('删除成功');
          const card = btn.closest('.feed-note-card');
          if (card) card.remove();
        } catch {
          // api.js 已 toast errorMsg
        }
      });
    });
  } catch {
    showEmpty(container, '加载失败');
  }
}

/** 【签到功能的实现】渲染本月签到日历
 * 实现细节：
 * - 用 JS Date 计算当月天数（daysInMonth）与星期对齐（firstDayOfWeek: 0=周日）
 * - 已签到日期（signedDays 整数数组，如 [1,3,5]）高亮主题色背景（--primary-light）
 * - 今天日期描边高亮（box-shadow inset），如果是已签到日则满背景色
 * - 7 列网格（日一二三四五六），1 号之前的空白格用 .empty 占位
 * - 数据失败时隐藏日历容器 display:none，不阻塞页面（静默降级）
 */
function renderSignCalendar(container, signedDays) {
  if (!container) return;
  try {
    const now = new Date();
    const year = now.getFullYear();
    const month = now.getMonth(); // 0-based，1 月=0
    const today = now.getDate();
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    const firstDay = new Date(year, month, 1).getDay(); // 0=周日

    const signedSet = new Set((signedDays || []).map(Number));

    let html = '<div class="sign-calendar"><div class="calendar-weekdays">';
    const weekLabels = ['日', '一', '二', '三', '四', '五', '六'];
    weekLabels.forEach(l => { html += '<span>' + l + '</span>'; });
    html += '</div><div class="calendar-grid">';

    // 填充 1 号之前的空白格（星期对齐）
    for (let i = 0; i < firstDay; i++) {
      html += '<div class="calendar-day empty"></div>';
    }

    // 渲染每一天：已签到 .signed、今天 .today
    for (let d = 1; d <= daysInMonth; d++) {
      const cls = [];
      if (d === today) cls.push('today');
      if (signedSet.has(d)) cls.push('signed');
      html += '<div class="calendar-day' + (cls.length ? ' ' + cls.join(' ') : '') + '">' + d + '</div>';
    }

    html += '</div></div>';
    container.innerHTML = html;
    container.style.display = '';
  } catch {
    // 日历渲染失败不阻塞页面
    container.style.display = 'none';
  }
}
