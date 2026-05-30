/**
 * 【路由的实现】hash 路由引擎——注册、匹配、渲染、tabbar 管理
 * 职责：管理 SPA 页面路由注册表、hashchange 监听、路由参数解析、tabbar 显隐与高亮
 * 依赖：无外部模块依赖（纯浏览器 API + 内部 routes 数组）
 * 核心链路：
 *   register(pattern, handler) → 存入 routes[]
 *   hashchange → render(currentHash) → matchRoute → handler(app, params) → updateTabbar
 * - 路由匹配：正则替换 :param → ([^/]+) 捕获组，提取命名参数 params{}
 * - 路由顺序：先注册先匹配（main.js 中 /user/edit 先于 /user/:id 注册确保精确路由优先）
 * - tabbar 管理：hiddenPages 列表（登录/注册/详情等页面隐藏底部栏），前缀匹配支持 /attraction/:id /note/:id 等
 * - cleanup 机制：每次渲染前调用上一次 handler 返回的 cleanup 函数（清理定时器等）
 * - start：首次加载无 hash 时默认跳 #/home
 */
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
 * - 实现：3 个 tab（首页/发布/我的），hiddenPages 列表中的页面隐藏 tabbar
 * - hiddenPages 匹配：精确匹配 pageName 或 pageName 开头（如 /attraction 匹配 /attraction/:id）
 * - 高亮：当前 hash 与 tab.hash 精确匹配 → active 类
 */
function updateTabbar(hash) {
  const tabbar = document.getElementById('tabbar');
  if (!tabbar) return;

  const pageName = hash.split('?')[0];

  const tabs = [
    { name: 'home', label: '首页', icon: 'home', hash: '/home' },
    { name: 'edit', label: '发布', icon: 'edit', hash: '/edit' },
    { name: 'profile', label: '我的', icon: 'profile', hash: '/profile' }
  ];

  const hiddenPages = ['/login', '/register', '/attraction', '/note', '/notes', '/follow', '/user'];
  const shouldHide = hiddenPages.some(p => pageName === p || pageName.startsWith(p + '/'));

  if (shouldHide) {
    tabbar.style.display = 'none';
    return;
  }

  tabbar.style.display = 'flex';
  tabbar.innerHTML = tabs.map(t => {
    const active = pageName === t.hash ? ' active' : '';
    return '<div class="tabbar-item' + active + '" data-hash="' + t.hash + '">' +
      getIcon(t.icon, active) +
      '<span>' + t.label + '</span></div>';
  }).join('');

  tabbar.querySelectorAll('.tabbar-item').forEach(item => {
    item.addEventListener('click', () => {
      navigate(item.dataset.hash);
    });
  });
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
    profile: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="1.5"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>'
  };
  return icons[name] || '';
}

/**
 * 【render】核心渲染函数：匹配路由 → 执行 handler → 更新 tabbar
 * - 实现：调用 matchRoute → 拒绝旧 cleanup（清理定时器等）→ handler(app, params) → 存新 cleanup
 * - 边界：无匹配路由显示 404 页面；handler 异常显示错误页面
 */
async function render(hash) {
  const app = document.getElementById('app');
  if (!app) return;

  if (currentCleanup) {
    try { currentCleanup(); } catch {}
    currentCleanup = null;
  }

  const match = matchRoute(hash);
  if (!match) {
    app.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>页面不存在</p></div>';
    updateTabbar(hash);
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

  updateTabbar(hash);
}

/**
 * 【start】启动路由引擎：绑定 hashchange 事件 + 执行首次渲染
 * - 边界：无 hash 或 hash='#' 时默认跳 #/home
 */
export function start() {
  window.addEventListener('hashchange', () => {
    render(getCurrentHash());
  });

  if (!window.location.hash || window.location.hash === '#') {
    window.location.hash = '#/home';
    return;
  }

  render(getCurrentHash());
}
