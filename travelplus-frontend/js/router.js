/**
 * 【路由的实现】hash 路由引擎——注册、匹配、渲染、tabbar 管理
 * 职责：管理 SPA 页面路由注册表、hashchange 监听、路由参数解析、tabbar 显隐与高亮
 * 依赖：config.js (API_BASE)（纯浏览器 API + 内部 routes 数组）
 * 核心链路：
 *   register(pattern, handler) → 存入 routes[]
 *   hashchange → render(currentHash) → matchRoute → handler(app, params) → updateTabbar
 * - 路由匹配：正则替换 :param → ([^/]+) 捕获组，提取命名参数 params{}
 * - 路由顺序：先注册先匹配（main.js 中 /user/edit 先于 /user/:id 注册确保精确路由优先）
 * - tabbar 管理：hiddenPages 列表（登录/注册/详情等页面隐藏底部栏），前缀匹配支持 /attraction/:id /note/:id 等
 * - cleanup 机制：每次渲染前调用上一次 handler 返回的 cleanup 函数（清理定时器等）
 * - start：首次加载无 hash 时默认跳 #/home
 */
import { API_BASE } from './config.js';

const routes = [];
let currentCleanup = null;

/**
 * 【register】注册路由：pattern（支持 :param 占位）→ handler(container, params)
 * - 注意：注册顺序决定匹配优先级，精确路由应先于参数路由注册
 */
export function register(pattern, handler) {
  routes.push({ pattern, handler });
}

/**
 * 【navigate】编程式导航：修改 window.location.hash
 */
export function navigate(hash) {
  window.location.hash = hash;
}

/**
 * 【getCurrentHash】获取当前 hash 路径（去 # 前缀，默认 /home）
 */
export function getCurrentHash() {
  return window.location.hash.slice(1) || '/home';
}

/**
 * 【matchRoute】将 hash 匹配到已注册路由
 * - 实现：pattern 中 :param 替换为正则捕获组，提取命名参数 → 返回 {handler, params}
 * - 边界：无匹配返回 null
 */
function matchRoute(hash) {
  const clean = hash.split('?')[0];
  for (const route of routes) {
    const re = new RegExp('^' + route.pattern.replace(/:\w+/g, '([^/]+)') + '$');
    const m = clean.match(re);
    if (m) {
      const keys = (route.pattern.match(/:\w+/g) || []).map(k => k.slice(1));
      const params = {};
      keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      return { handler: route.handler, params };
    }
  }
  return null;
}

/**
 * 【updateTabbar】根据当前 hash 决定 tabbar 显隐与高亮
 * - 实现：5 个 tab（首页/旅行/发布/消息/我的），hiddenPages 列表中的页面隐藏 tabbar
 * - hiddenPages 匹配：精确匹配 pageName 或 pageName 开头（如 /attraction 匹配 /attraction/:id）
 * - 高亮：当前 hash 与 tab.hash 精确匹配 → active 类
 * - 消息 tab：动态显示红点角标（.tabbar-badge），由 setMessagesBadge 控制
 */
function updateTabbar(hash) {
  const tabbar = document.getElementById('tabbar');
  if (!tabbar) return;

  const pageName = hash.split('?')[0];

  /** 【全局搜索的实现】仅首页（/home）显示全局搜索条，其他 tab 页隐藏
   * 实现细节：
   * - 获取 #global-search 元素和 #app 容器
   * - 仅 pageName === '/home' 时显示搜索条 + #app 加 has-global-search 类（padding-top 留空）
   * - 其他页（/travel、/messages、/profile 等 tab 页及所有非 tab 页）→ 隐藏搜索条 + 移除 has-global-search 类
   * - 搜索条点击 → navigate('#/search') 跳转全局搜索页
   */
  const globalSearch = document.getElementById('global-search');
  const appContainer = document.getElementById('app');

  if (globalSearch && appContainer) {
    const isHomeOnly = pageName === '/home';
    if (isHomeOnly) {
      globalSearch.classList.remove('hidden');
      appContainer.classList.add('has-global-search');
      // 绑定点击跳转搜索页（只绑定一次）
      if (!globalSearch.dataset.bound) {
        globalSearch.dataset.bound = '1';
        globalSearch.addEventListener('click', () => {
          navigate('/search');
        });
      }
    } else {
      globalSearch.classList.add('hidden');
      appContainer.classList.remove('has-global-search');
    }
  }

  /** 【旅行功能的实现】tabbar 5 个——发布放正中间（index 2），顺序：首页/旅行/发布/消息/我的 */
  const tabs = [
    { name: 'home', label: '首页', icon: 'home', hash: '/home' },
    { name: 'travel', label: '旅行', icon: 'travel', hash: '/travel' },
    { name: 'edit', label: '发布', icon: 'edit', hash: '/edit' },
    { name: 'messages', label: '消息', icon: 'messages', hash: '/messages' },
    { name: 'profile', label: '我的', icon: 'profile', hash: '/profile' }
  ];

  /** 【旅行功能的实现】hiddenPages 加 /trip 前缀——/trip/new、/trip/:id 等隐藏 tabbar，/travel 是 tab 页不隐藏 */
  const hiddenPages = ['/login', '/register', '/attraction', '/note', '/notes', '/follow', '/user', '/support', '/privacy', '/favorites', '/chat', '/chats', '/trip', '/search', '/orders', '/order', '/wallet', '/points', '/balance', '/coupons', '/ai-chat'];
  const shouldHide = hiddenPages.some(p => pageName === p || pageName.startsWith(p + '/'));
  /** 【手机端体验的实现】全屏页去除 #app 底部 tabbar 占位（app-container.no-tabbar）
   * - tabbar 隐藏的页面（聊天/详情/登录等）→ #app 只保留底部安全区，不再留 56px 灰条
   * - tab 页恢复常规占位；类挂在 #app 上由 CSS 统一控制
   */
  if (shouldHide) {
    tabbar.style.display = 'none';
    appContainer?.classList.add('no-tabbar');
    return;
  }

  tabbar.style.display = 'flex';
  appContainer?.classList.remove('no-tabbar');
  tabbar.innerHTML = tabs.map(t => {
    // 消息 tab 高亮：匹配 /messages 精确路径或 /messages/:category 子路由
    let active = pageName === t.hash;
    if (t.name === 'messages' && pageName.startsWith('/messages/')) {
      active = true;
    }
    const activeClass = active ? ' active' : '';
    const badgeHtml = t.name === 'messages' ? '<span class="tabbar-badge" style="display:none"></span>' : '';
    return '<div class="tabbar-item' + activeClass + '" data-hash="' + t.hash + '">' +
      '<span class="tabbar-icon-wrap">' + getIcon(t.icon, active) + badgeHtml + '</span>' +
      '<span>' + t.label + '</span></div>';
  }).join('');

  tabbar.querySelectorAll('.tabbar-item').forEach(item => {
    item.addEventListener('click', () => {
      navigate(item.dataset.hash);
    });
  });

  // 刷新消息红点：非消息页时检查未读消息数（消息页本体和分类子页都不刷新红点）
  if (!pageName.startsWith('/messages')) {
    refreshBadge();
  }
}

/**
 * 【消息中心的实现】从后端拉取未读消息数并刷新红点
 * - 实现：GET /notify/unread-count → 返回值 > 0 则显示红点
 * - 失败静默：网络异常时不显示红点（不阻塞页面渲染）
 */
async function refreshBadge() {
  try {
    const token = localStorage.getItem('travelplus_token') || '';
    const res = await fetch(API_BASE + '/notify/unread-count', {
      headers: token ? { authorization: token } : {}
    });
    const body = await res.json();
    if (body && body.success) {
      const count = body.data;
      setMessagesBadge(count > 0);
    }
  } catch {
    // 静默失败
  }
}

/**
 * 【消息中心的实现】设置消息 tabbar 红点角标
 * - 实现：查找 .tabbar-item[data-hash="/messages"] 下的 .tabbar-badge span
 * - show=true → 显示红点；show=false → 隐藏红点
 */
export function setMessagesBadge(show) {
  const item = document.querySelector('.tabbar-item[data-hash="/messages"]');
  if (!item) return;
  const badge = item.querySelector('.tabbar-badge');
  if (badge) {
    badge.style.display = show ? 'block' : 'none';
  }
}

/**
 * 【消息中心的实现】公共红点刷新——供 chat/support 等页面发送消息后调用
 * 实现细节：
 * - 委托内部 refreshBadge() 异步 GET /notify/unread-count 获取未读数
 * - 非阻塞：异步调用，不影响发送流程
 * - 调用方自行控制频率（不在此处防抖）
 */
export function refreshMessagesBadge() {
  refreshBadge();
}

/**
 * 【getIcon】获取 tabbar SVG 图标
 * - 实现：根据 active 状态切换颜色（主题色 #0E9F8A / 灰色 #999）
 */
function getIcon(name, active) {
  const color = active ? '#0E9F8A' : '#999';
  const icons = {
    home: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="1.5"><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>',
    notes: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>',
    edit: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="M12 8v8"/><path d="M8 12h8"/></svg>',
    follow: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="1.5"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
    /** 【旅行功能的实现】旅行图标——飞机/地图针风格 SVG，active 时主题色，非 active 时灰色 */
    travel: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="1.5"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>',
    messages: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="1.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
    profile: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="1.5"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>'
  };
  return icons[name] || '';
}

/**
 * 【render】核心渲染函数：匹配路由 → 执行 handler → 更新 tabbar
 * - 实现：调用 matchRoute → 拒绝旧 cleanup（清理定时器等）→ handler(app, params) → 存新 cleanup
 * - 边界：无匹配路由显示 404 页面；handler 异常显示错误页面
 */
/** 【手机端体验的实现】页面滚动位置记忆（会话级）
 * - 离开页面时记录 scrollY；再次进入（返回）时恢复，未记录过（前进）则回顶
 * - 模拟原生 App 的导航栈体验：列表 → 详情 → 返回，列表停在原处
 */
const scrollMemory = new Map();
let lastRenderedHash = null;

async function render(hash) {
  const app = document.getElementById('app');
  if (!app) return;

  // 记录离开页的滚动位置（同 hash 重渲染不记录）
  if (lastRenderedHash && lastRenderedHash !== hash) {
    scrollMemory.set(lastRenderedHash, window.scrollY);
  }
  if (currentCleanup) {
    try { currentCleanup(); } catch {}
    currentCleanup = null;
  }

  const match = matchRoute(hash);
  if (!match) {
    app.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>页面不存在</p></div>';
    updateTabbar(hash);
    lastRenderedHash = hash;
    return;
  }

  try {
    const cleanup = await match.handler(app, match.params);
    if (typeof cleanup === 'function') {
      currentCleanup = cleanup;
    }
  } catch (e) {
    console.error('Route render error:', e);
    app.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>页面加载出错</p></div>';
  }

  // 恢复/回顶：返回旧页面（hash 一致，含 query）恢复原滚动位置，前进新页面回顶
  const saved = scrollMemory.get(hash);
  window.scrollTo(0, saved != null ? saved : 0);

  updateTabbar(hash);
  lastRenderedHash = hash;
}

/**
 * 【start】启动路由引擎：绑定 hashchange 事件 + 执行首次渲染
 * - 边界：无 hash 或 hash='#' 时默认跳 #/home
 */
export function start() {
  /* 【手机端体验的实现】接管滚动恢复——禁用浏览器对 hash 导航的原生滚动恢复，统一由 scrollMemory 管理 */
  if ('scrollRestoration' in history) {
    history.scrollRestoration = 'manual';
  }
  window.addEventListener('hashchange', () => {
    render(getCurrentHash());
  });

  if (!window.location.hash || window.location.hash === '#') {
    window.location.hash = '#/home';
    return;
  }

  render(getCurrentHash());
}
