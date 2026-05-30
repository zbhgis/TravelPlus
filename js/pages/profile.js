/**
 * 【个人中心的实现】#/profile 页面渲染模块——完整个人主页
 * 职责：头部（背景图+头像+昵称+简介）+ 左上角菜单按钮 → 左侧抽屉侧边栏（编辑资料/浏览记录/客服）、统计行（关注/粉丝/获赞）、我的资产入口（订单管理/我的钱包/积分记录）、签到点阵、tab 区（游记/评论/收藏/赞过/足迹，复用 user.js renderUserTabs）、退出登录
 * 依赖：api.js (get/post)、ui.js (imgUrl/imgError/toast)、store.js (isLoggedIn/getUser/logout)、user.js (renderUserTabs)
 * 核心链路：
 *   renderProfile(container)
 *     → 未登录 → 显示引导 + "去登录"按钮
 *     → 已登录 → 并行加载 GET /user/home/{me}（用户主页数据+统计+简介）+ GET /user/sign/count（签到天数）+ GET /user/sign/list（签到日期列表）
 *     → 渲染头部：背景图（实时 home 数据 user.bgImage cover，无则渐变；localStorage 可能过期）+ avatar-xhs 悬浮头像 + 昵称 + 简介（info.introduce）
 *     → 统计行：关注(followee)/粉丝(fans)/获赞(likedCount) —— 点击跳 #/user/{me}/follows
 *     → 我的资产入口：统计行下方三按钮横向栏（订单管理→#/orders、我的钱包→#/wallet、积分记录→#/points），图标圆角底 + 文字，与现有卡片风格一致
 *     → 侧边栏：左上角☰菜单按钮 → 点击弹出左侧抽屉（编辑资料/浏览记录/客服），点击遮罩或项关闭
 *     → 签到：紧凑点阵（已连续签到N天 + 签到按钮 + 本月圆点行）—— 今天已签到则按钮显示"已签到"禁用态
 *     → tab 区：renderUserTabs(container, meId) → 游记/评论/收藏/赞过/足迹（与 user.js 共用单一实现）
 *     → 退出登录：POST /user/logout 双保险 + logout() + toast + #/home
 * 子功能：
 *   - 签到：POST /user/sign → 刷新天数 + 重新拉取签到列表刷新点阵；今天已签到→按钮禁用显示"已签到"
 *   - renderSignDots()：紧凑签到点阵渲染（保留不变）
 */
import { get, post } from '../api.js';
import { imgUrl, imgError, toast, confirmDialog } from '../ui.js';
import { isLoggedIn, getUser, logout } from '../store.js';
import { renderUserTabs } from './user.js';

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
      <!-- 【个人中心的实现】左上角菜单按钮：☰ 图标，点击打开左侧抽屉侧边栏 -->
      <button class="menu-btn" id="profile-menu-btn" aria-label="菜单">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
      </button>
      <h2>我的</h2>
    </div>
    <div id="profile-content">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;

  const contentEl = container.querySelector('#profile-content');

  try {
    /** 【个人中心的实现】并行加载用户主页数据 + 签到数据
     * 实现细节：
     * - GET /user/home/{me} → user/info/统计（followee/fans/likedCount）+ introduce 简介
     * - GET /user/sign/count → 签到天数
     * - GET /user/sign/list → 本月签到日期数组
     * - 任一失败静默降级（signCount=0, signedDays=[], userHomeData=null）
     */
    const meId = user.id;
    let signCount = 0;
    let signedDays = [];
    let userHomeData = null;
    try {
      const results = await Promise.all([
        get('/user/home/' + meId).catch(() => null),
        get('/user/sign/count').catch(() => 0),
        get('/user/sign/list').catch(() => [])
      ]);
      userHomeData = results[0];
      signCount = results[1] || 0;
      signedDays = results[2] || [];
    } catch {
      // 静默降级，使用默认值
    }

    const info = (userHomeData && userHomeData.info) ? userHomeData.info : {};
    const stats = userHomeData || {};

    /** 【个人中心的实现】背景/头像取实时主页数据
     * 实现细节：
     * - 优先取 GET /user/home/{me} 返回的 userHomeData.user（后端实时 DB 数据，含最新 bgImage/icon）
     * - 未返回时兜底 getUser()（localStorage 缓存，可能过期——用户更新过背景/头像后缓存未刷新）
     * - 由此保证用户编辑资料后再次进入「我的」页背景图/头像立即生效，不依赖缓存刷新
     */
    const homeUser = (userHomeData && userHomeData.user) ? userHomeData.user : user;

    /** 【用户主页的实现】我的页背景图（小红书样式）
     * - 实时 homeUser.bgImage 非空 → 全宽 150px cover 大图头部（localStorage 可能过期，实时优先）
     * - 为空 → CSS 默认渐变（--primary-light → #c8e6e0）
     * - 加载失败 → 隐藏 img onerror 清除 inline style → 回退 CSS 渐变
     */
    const bgImageUrl = homeUser.bgImage ? imgUrl(homeUser.bgImage) : '';
    const bgStyle = bgImageUrl ? 'background-image:url(' + bgImageUrl + ')' : '';
    const bgFallback = bgImageUrl
      ? '<img src="' + bgImageUrl + '" style="display:none" onerror="this.parentElement.style.backgroundImage=\'\'">'
      : '';

    /** 【个人中心的实现】完整我的主页渲染
     * 实现细节：
     * - 头部：背景图（cover 150px）+ 头像 avatar-xhs 悬浮叠加 + 昵称 + 简介（GET /user/home/{me} → info.introduce）
     * - 统计行：关注(followee)/粉丝(fans)/获赞(likedCount) —— 点击关注/粉丝跳 #/user/{meId}/follows
     * - 操作收纳进侧边栏：左上角☰菜单按钮 → 左侧抽屉（编辑资料/浏览记录/客服），点击遮罩或项关闭（界面更简洁）
     * - 签到：紧凑点阵（已有逻辑保留不变）
     * - tab 区：复用 renderUserTabs（游记/评论/收藏/赞过/足迹，与 user.js 共用单一实现）
     * - 退出登录：页尾按钮（保留不变）
     */
    contentEl.innerHTML = `
      <!-- 【个人中心的实现】头部：背景图 + 头像 + 昵称 + 简介 -->
      <div class="profile-cover">
        <div class="user-bg" style="${bgStyle}">${bgFallback}</div>
        <img class="avatar avatar-xhs" src="${imgUrl(homeUser.icon)}" alt="" onerror="imgError(event)" id="profile-avatar" data-avatar-uid="${user.id}" data-avatar-icon="${imgUrl(homeUser.icon)}" data-avatar-nick="${user.nickName || '用户'}"/>
        <div class="profile-cover-name">${user.nickName || '用户'}</div>
        ${info.introduce ? '<div class="profile-cover-bio">' + info.introduce + '</div>' : ''}
      </div>
      <!-- 【个人中心的实现】统计行：关注(followee)/粉丝(fans)/获赞(likedCount) -->
      <div class="user-stats">
        <div class="user-stat-item" data-type="following">
          <span class="user-stat-num">${stats.followee || 0}</span>
          <span class="user-stat-label">关注</span>
        </div>
        <div class="user-stat-item" data-type="fans">
          <span class="user-stat-num">${stats.fans || 0}</span>
          <span class="user-stat-label">粉丝</span>
        </div>
        <div class="user-stat-item">
          <span class="user-stat-num">${stats.likedCount || 0}</span>
          <span class="user-stat-label">获赞</span>
        </div>
      </div>
      <!-- 【我的资产入口的实现】统计行下方三按钮栏：订单管理 / 我的钱包 / 积分记录
           实现细节：
           - .profile-assets 容器 + 3 个 .profile-assets-item，横向等宽排列
           - 每个入口含图标圆角底（profile-assets-icon）+ 文字标签
           - 点击分别跳转 #/orders、#/wallet、#/points -->
      <div class="profile-assets">
        <div class="profile-assets-item" data-hash="#/orders">
          <div class="profile-assets-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.5"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="3" y1="10" x2="21" y2="10"/><rect x="5" y="12" width="4" height="2" rx="0.5"/><rect x="11" y="12" width="4" height="2" rx="0.5"/></svg>
          </div>
          <span class="profile-assets-label">订单管理</span>
        </div>
        <div class="profile-assets-item" data-hash="#/wallet">
          <div class="profile-assets-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.5"><rect x="1" y="4" width="22" height="16" rx="2"/><line x1="1" y1="10" x2="23" y2="10"/></svg>
          </div>
          <span class="profile-assets-label">我的钱包</span>
        </div>
        <div class="profile-assets-item" data-hash="#/points">
          <div class="profile-assets-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.5"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
          </div>
          <span class="profile-assets-label">积分记录</span>
        </div>
      </div>
      <!-- 【签到功能的实现】紧凑签到条：天数胶囊 + 按钮 + 本月签到点阵 -->
      <div class="sign-card">
        <div class="sign-info">已连续签到 <span class="sign-days" id="sign-days">${signCount}</span> 天</div>
        <button class="btn btn-primary btn-sm" id="sign-btn">签到</button>
      </div>
      <div class="sign-dots-wrap" id="sign-dots-wrap"></div>
      <!-- 【个人中心的实现】tab 区容器：由 renderUserTabs 动态渲染（游记/评论/收藏/赞过/足迹） -->
      <div id="profile-tab-area"></div>
      <!-- 【个人中心的实现】侧边栏遮罩与抽屉：由 JS 动态创建并挂载到 document.body（见下方 createSideDrawer） -->
    `;

    // Sign-in
    const signBtn = contentEl.querySelector('#sign-btn');
    /** 【签到功能的实现】签到按钮状态修复：今天已签到→显示"已签到"禁用态
     * 实现细节：
     * - 今天日期号 ∈ signedDays（后端返回的本月已签日期数组）→ 按钮显示"已签到" + disabled + 样式变灰
     * - 今天未签到 → 按钮显示"签到"可点击
     * - 点击签到成功后立即切换为"已签到"禁用态 + toast"签到成功，积分+2"
     * - 重复签到被后端拒绝时 api.js 自动 toast 后端 errorMsg
     */
    const todayDate = new Date().getDate();
    if (signedDays && signedDays.includes(todayDate)) {
      signBtn.disabled = true;
      signBtn.textContent = '已签到';
    }
    signBtn.addEventListener('click', async () => {
      try {
        await post('/user/sign');
        const cnt = await get('/user/sign/count');
        contentEl.querySelector('#sign-days').textContent = cnt;
        signBtn.disabled = true;
        signBtn.textContent = '已签到';
        toast('签到成功，积分+2');
        /** 【签到功能的实现】签到后刷新点阵
         * - 重新拉取签到列表，刷新本月签到点阵
         * - 本地更新今天圆点高亮（即使接口失败也尝试本地更新）
         */
        try {
          const updatedDays = await get('/user/sign/list');
          renderSignDots(contentEl.querySelector('#sign-dots-wrap'), updatedDays || []);
        } catch {
          // 本地更新：直接追加今天日期到已有列表
          const merged = [...new Set([...(signedDays || []), todayDate])];
          renderSignDots(contentEl.querySelector('#sign-dots-wrap'), merged);
        }
      } catch {}
    });

    /** 【签到功能的实现】初始渲染签到点阵
     * - 用已加载的 signedDays 渲染本月 1 号到今天的圆点行
     * - 失败时 renderSignDots 内部隐藏容器
     */
    renderSignDots(contentEl.querySelector('#sign-dots-wrap'), signedDays || []);

    /** 【头像交互的实现】自己头像点击改由全局委托 initAvatarDelegate 统一处理
     * - data-avatar-uid 已加在 #profile-avatar 模板中 → 自己头像→预览弹窗（含"这是您自己"标签）
     * - 旧独立绑定已移除
     */

    /** 【个人中心的实现】侧边栏打开/关闭逻辑
     * 实现细节：
     * - ☰ 菜单按钮 → 显示遮罩 + 左侧抽屉从左边滑入
     * - 点击遮罩（.side-mask）→ 关闭抽屉
     * - 点击关闭按钮（.side-drawer-close）→ 关闭抽屉
     * - 点击抽屉内菜单项 → 跳转 hash + 关闭抽屉
     * - 动画：CSS transition transform + opacity
     *
     * 【个人中心的实现】抽屉 fixed 定位与 transform 父容器兼容处理
     * 实现细节：
     * - 侧边栏 .side-drawer 使用 position:fixed，在 CSS 规范中若任何祖先元素有 transform/perspective/filter
     *   属性，fixed 会变成相对于该祖先定位（而非 viewport），导致抽屉在手机视口下位置偏移
     * - 修复：将 .side-mask 和 .side-drawer 直接挂载到 document.body 子级，确保不经过任何可能含 transform
     *   的祖先（如 .app-container 或 .tabbar），fixed 定位基准始终为 viewport
     * - 页面销毁时自动移除 body 上的抽屉 DOM，防止残留
     */
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
      <!-- 【隐私设置的实现】侧边栏独立「隐私设置」项（从编辑资料页抽离）→ #/privacy -->
      <div class="side-drawer-item" data-hash="#/privacy">隐私设置</div>
      <div class="side-drawer-item" data-hash="#/user/browse">浏览记录</div>
      <div class="side-drawer-item" data-hash="#/support">客服</div>
      <!-- 【个人中心的实现】退出登录收纳进侧边栏
           实现细节：
           - 红字样式（.side-drawer-logout），视觉醒目提示危险操作
           - 点击 → confirmDialog 确认 → 现有退出逻辑（POST /user/logout + logout() + toast + #/home）
           - 退出入口统一收纳侧边栏，页尾不再有独立退出按钮 -->
      <div class="side-drawer-item side-drawer-logout" id="side-logout-item">退出登录</div>
    `;
    document.body.appendChild(sideDrawer);

    const menuBtn = container.querySelector('#profile-menu-btn');

    function openDrawer() {
      sideMask.style.display = 'block';
      // 强制回流后添加 show 类触发动画
      void sideMask.offsetWidth;
      sideMask.classList.add('show');
      sideDrawer.classList.add('show');
    }

    function closeDrawer() {
      sideMask.classList.remove('show');
      sideDrawer.classList.remove('show');
      // 动画结束后隐藏遮罩
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

    /** 【个人中心的实现】侧边栏「退出登录」项点击
     * 实现细节：
     * - confirmDialog 确认 → 调 POST /user/logout（api.js 自动带 authorization 头）
     * - 无论服务端成败，均执行本地清理：logout() 清 token + 清 user
     * - toast "已退出" → 跳 #/home
     * - 退出入口统一收纳侧边栏，页尾不再有独立退出按钮
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
          // api.js already toasts error；即使服务端失败也不影响本地清理
        }
        logout();
        toast('已退出');
        window.location.hash = '#/home';
      });
    }

    // 侧边栏菜单项点击 → 跳转 + 关闭（排除退出登录项，已单独处理）
    sideDrawer.querySelectorAll('.side-drawer-item').forEach(item => {
      item.addEventListener('click', () => {
        const hash = item.dataset.hash;
        if (hash) window.location.hash = hash;
        closeDrawer();
      });
    });

    /** 【我的资产入口的实现】资产入口三按钮点击 → 跳转对应 hash
     * 实现细节：
     * - 订单管理 → #/orders、我的钱包 → #/wallet、积分记录 → #/points
     * - 点击跳转无需登录保护（目标页面各自处理）
     */
    contentEl.querySelectorAll('.profile-assets-item').forEach(item => {
      item.addEventListener('click', () => {
        const hash = item.dataset.hash;
        if (hash) window.location.hash = hash;
      });
    });

    /** 【我的资产入口的实现】统计行点击 → 关注/粉丝列表页（未登录跳登录）
     * 实现细节：
     * - followee（关注）→ data-type="following" → #/user/{meId}/follows?type=following
     * - fans（粉丝）→ data-type="fans" → #/user/{meId}/follows?type=fans
     * - 获赞不可点击
     */
    contentEl.querySelectorAll('.user-stat-item[data-type]').forEach(item => {
      item.addEventListener('click', () => {
        const type = item.dataset.type;
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('user/' + meId + '/follows?type=' + type);
          return;
        }
        window.location.hash = '#/user/' + meId + '/follows?type=' + type;
      });
    });

    /** 【个人中心的实现】调用可复用 tab 渲染函数（与 user.js 共用单一实现）
     * 实现细节：
     * - container = #profile-tab-area（tab 栏 + 内容全在此容器内渲染）
     * - userId = meId（自己的 ID）
     * - 无预加载游记数据（tab 内自行 GET /note/of/user 拉取）
     * - 内部自动处理足迹 Leaflet 地图初始化与 tab 切换清理
     */
    renderUserTabs(contentEl.querySelector('#profile-tab-area'), meId, contentEl);

  } catch {
    contentEl.innerHTML = '<div class="empty-state"><p>加载失败</p></div>';
  }

  /** 【个人中心的实现】cleanup：页面销毁时移除 body 上的侧边栏 DOM
   * 实现细节：
   * - router.js render 函数在每次页面切换前调用 currentCleanup() 清理上一页资源
   * - 返回的 cleanup 函数移除 .side-mask 和 .side-drawer（挂载在 body 上），防止 DOM 残留
   */
  return () => {
    const mask = document.getElementById('side-mask');
    const drawer = document.getElementById('side-drawer');
    if (mask) mask.remove();
    if (drawer) drawer.remove();
  };
}

/** 【签到功能的实现】渲染紧凑签到点阵（替代完整月历网格）
 * 实现细节：
 * - 紧凑签到条：一行显示本月 1 号到今天的圆点（每个小圆点 6~8px）
 * - 已签到的日期（signedDays 整数数组，如 [1,3,5]）→ 主题色实心圆点（.sign-dot.signed）
 * - 未签到的日期 → 浅灰空心圆点（.sign-dot 默认 border-only）
 * - 今天日期 → 主题色描边（.sign-dot.today，已签则实心+描边）
 * - 横向 flex-wrap 排列，大幅缩小版面（对比原来的 7 列月历网格）
 * - 数据失败时隐藏容器 display:none，不阻塞页面（静默降级）
 * - 接口调用与数据不变：POST /user/sign、GET /user/sign/count、GET /user/sign/list
 */
function renderSignDots(container, signedDays) {
  if (!container) return;
  try {
    const now = new Date();
    const today = now.getDate();
    const signedSet = new Set((signedDays || []).map(Number));

    // 构建本月 1 号到今天的圆点行
    let html = '<div class="sign-dots">';
    for (let d = 1; d <= today; d++) {
      const cls = [];
      if (signedSet.has(d)) cls.push('signed');
      if (d === today) cls.push('today');
      html += '<span class="sign-dot' + (cls.length ? ' ' + cls.join(' ') : '') + '" title="' + d + '日"></span>';
    }
    html += '</div>';
    container.innerHTML = html;
    container.style.display = '';
  } catch {
    // 点阵渲染失败不阻塞页面
    container.style.display = 'none';
  }
}
