/**
 * 【全局搜索的实现】#/search 全局搜索页渲染模块
 * 职责：搜索框（自动聚焦）+ 热门词条（未输入时）+ 输入联想（300ms 防抖）+ 三类结果 tab（景点/游记/用户）
 * 依赖：api.js (get)、ui.js (imgUrl/imgError/scoreDisplay/fenToYuan/showLoading/showEmpty/loadMore/toast/getUrlParam/formatDate)
 * 核心链路：
 *   renderSearch(container)
 *     → 读取 getUrlParam('q') 决定初始关键词
 *     → 渲染搜索框（自动聚焦）+ 取消/返回按钮
 *     → 未输入：GET /search/hot → 热门词条 chips（点击=提交搜索）
 *     → 输入中（300ms 防抖）：GET /search/suggest?keyword= → 联想列表（点击填框+搜索）
 *     → 提交/点击词条后：GET /search?keyword=&current= → 三类 tab 结果
 *       - 景点卡片：图/名/评分/免费或¥X起
 *       - 游记卡片：图/标题/作者
 *       - 用户行：头像/昵称
 *       - 空态"未找到相关结果"
 *
 * 设计决策：
 *   - 热门词条和输入联想互斥：有输入时显示联想，无输入时显示热门词条
 *   - 防抖 300ms：避免每次按键都请求
 *   - 三类 tab 页面内切换（不依赖子路由），各 tab 独立分页
 *   - 结果为空显示空态，不显示 tab 栏（待输入时也不显示）
 */
import { get } from '../api.js';
import { imgUrl, imgError, scoreDisplay, fenToYuan, showLoading, showEmpty, toast, getUrlParam, formatDate } from '../ui.js';

/** 【全局搜索的实现】防抖工具函数
 *  - 返回 debounced 版本函数，延迟 ms 毫秒执行
 *  - 连续调用时取消前一次 setTimeout
 */
function debounce(fn, ms) {
  let timer = null;
  return function (...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), ms);
  };
}

/** 【全局搜索的实现】XSS 防护 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}

/** 【全局搜索的实现】搜索页分页观察器（自循环版 loadMore）
 * - 修复：ui.js 的 loadMore 触发一次后不再重新注册（unobserve），tab 切换重建列表后新 sentinel 无人观察，
 *   导致"加载更多"失效；本 helper 在每次 loader 完成后重新观察当前 sentinel，直到 sentinel 被移除（无更多数据）
 * - loader 返回 Promise；无更多数据时 loader 内部移除 sentinel → 重入时 querySelector 为空 → 自然终止
 */
function observeSentinel(listEl, sentinelClass, loader) {
  const sentinel = listEl ? listEl.querySelector('.' + sentinelClass) : null;
  if (!sentinel) return;
  const ob = new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting) {
      ob.disconnect();
      Promise.resolve(loader()).finally(() => observeSentinel(listEl, sentinelClass, loader));
    }
  }, { rootMargin: '100px' });
  ob.observe(sentinel);
}

/** 【全局搜索的实现】搜索模块级状态 */
let searchMode = false;      // 是否已提交搜索（true=显示结果，false=显示热门词条/联想）
let activeTab = 'attraction'; // 当前结果 tab：attraction | note | user
let tabPages = {};           // 各 tab 分页游标 { attraction: 1, note: 1, user: 1 }
let tabHasMore = {};         // 各 tab 是否有更多 { attraction: true, note: true, user: true }
let searchKeyword = '';      // 当前搜索关键词
let debounceTimer = null;    // 防抖计时器

export async function renderSearch(container) {
  const initialQ = getUrlParam('q') || '';
  searchKeyword = initialQ;
  searchMode = !!initialQ;
  activeTab = 'attraction';
  tabPages = { attraction: 1, note: 1, user: 1 };
  tabHasMore = { attraction: true, note: true, user: true };

  container.innerHTML = `
    <div id="search-root">
      <div class="search-header">
        <div class="search-input-wrap">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>
          <input type="text" id="search-input" class="search-input" placeholder="搜索景点、游记、用户..." value="${escapeHtml(initialQ)}" autocomplete="off"/>
          <button class="search-clear-btn" id="search-clear-btn" style="display:${initialQ ? 'flex' : 'none'}">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
        <button class="search-cancel-btn" id="search-cancel-btn">取消</button>
      </div>
      <div id="search-body"></div>
    </div>
  `;

  const root = container.querySelector('#search-root');
  const searchInput = root.querySelector('#search-input');
  const searchBody = root.querySelector('#search-body');
  const clearBtn = root.querySelector('#search-clear-btn');
  const cancelBtn = root.querySelector('#search-cancel-btn');

  // 取消按钮 → 返回上一页
  cancelBtn.addEventListener('click', () => window.history.back());

  // 清除按钮
  clearBtn.addEventListener('click', () => {
    searchInput.value = '';
    searchKeyword = '';
    searchMode = false;
    clearBtn.style.display = 'none';
    loadHotKeywords(searchBody);
  });

  // 自动聚焦
  setTimeout(() => searchInput.focus(), 100);

  /** 【全局搜索的实现】提交搜索
   * - 设置 searchMode=true → 加载搜索结果显示
   * - 关键词变更时重置 tab 分页游标
   */
  async function submitSearch(keyword) {
    if (!keyword || !keyword.trim()) return;
    const kw = keyword.trim();
    if (kw !== searchKeyword) {
      searchKeyword = kw;
      // 重置分页
      tabPages = { attraction: 1, note: 1, user: 1 };
      tabHasMore = { attraction: true, note: true, user: true };
      activeTab = 'attraction';
    }
    searchMode = true;
    searchInput.value = kw;
    clearBtn.style.display = 'flex';
    await loadSearchResults(searchBody, kw);
  }

  /** 【全局搜索的实现】输入防抖联想（300ms）
   * - 有输入 → 显示联想列表 + 清空按钮
   * - 无输入 → 显示热门词条
   */
  const debouncedSuggest = debounce(async (kw) => {
    if (!kw || !kw.trim()) {
      searchMode = false;
      clearBtn.style.display = 'none';
      loadHotKeywords(searchBody);
      return;
    }
    searchMode = false; // 联想阶段不是搜索结果模式
    clearBtn.style.display = 'flex';
    await loadSuggestions(searchBody, kw.trim());
  }, 300);

  searchInput.addEventListener('input', (e) => {
    const val = e.target.value;
    debouncedSuggest(val);
  });

  // 回车提交
  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      const val = searchInput.value.trim();
      if (val) submitSearch(val);
    }
  });

  // 初始状态：有初始 keyword → 直接搜索；否则显示热门词条
  if (initialQ) {
    await submitSearch(initialQ);
  } else {
    await loadHotKeywords(searchBody);
  }
}

/**
 * 【全局搜索的实现】加载热门词条
 * - GET /search/hot → 热门关键词数组
 * - 渲染为 chips（横向 flex-wrap 排列），点击 chip → submitSearch
 * - 失败静默（不显示）
 */
async function loadHotKeywords(container) {
  try {
    const hotList = await get('/search/hot');
    const list = Array.isArray(hotList) ? hotList : [];
    if (!list.length) {
      container.innerHTML = '<div class="search-hot-wrap"><div class="search-hot-title">热门搜索</div><div class="search-hot-empty">暂无热门词条</div></div>';
      return;
    }
    let html = '<div class="search-hot-wrap"><div class="search-hot-title">热门搜索</div><div class="search-hot-chips">';
    list.forEach(item => {
      const word = typeof item === 'string' ? item : (item.word || item.keyword || item.name || '');
      if (word) {
        html += '<span class="search-hot-chip" data-word="' + escapeHtml(word) + '">' + escapeHtml(word) + '</span>';
      }
    });
    html += '</div></div>';
    container.innerHTML = html;

    // 绑定 chip 点击 → 搜索
    container.querySelectorAll('.search-hot-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const word = chip.dataset.word;
        if (word) {
          const searchInput = document.getElementById('search-input');
          if (searchInput) searchInput.value = word;
          submitSearchFromChip(word);
        }
      });
    });
  } catch {
    // 热门词条失败静默
    container.innerHTML = '';
  }
}

/** 【全局搜索的实现】chip 点击提交（需要访问 submitSearch 闭包内的函数）
 * - 通过自定义事件委托给 search-root 处理
 */
function submitSearchFromChip(keyword) {
  const root = document.getElementById('search-root');
  if (!root) return;
  const input = root.querySelector('#search-input');
  if (input) input.value = keyword;
  const clearBtn = root.querySelector('#search-clear-btn');
  if (clearBtn) clearBtn.style.display = 'flex';
  // 直接调用 loadSearchResults on searchBody
  const body = root.querySelector('#search-body');
  if (body) {
    searchKeyword = keyword;
    searchMode = true;
    tabPages = { attraction: 1, note: 1, user: 1 };
    tabHasMore = { attraction: true, note: true, user: true };
    activeTab = 'attraction';
    loadSearchResults(body, keyword);
  }
}

/**
 * 【全局搜索的实现】加载输入联想
 * - GET /search/suggest?keyword= → 联想词列表
 * - 渲染为列表，点击 → 填搜索框 + 提交搜索
 */
async function loadSuggestions(container, keyword) {
  try {
    const data = await get('/search/suggest?keyword=' + encodeURIComponent(keyword));
    const list = Array.isArray(data) ? data : [];
    if (!list.length) {
      container.innerHTML = '';
      return;
    }
    let html = '<div class="search-suggest-list">';
    list.forEach(item => {
      const word = typeof item === 'string' ? item : (item.word || item.keyword || item.name || '');
      if (word) {
        html +=
          '<div class="search-suggest-item" data-word="' + escapeHtml(word) + '">' +
            '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>' +
            '<span>' + escapeHtml(word) + '</span>' +
          '</div>';
      }
    });
    html += '</div>';
    container.innerHTML = html;

    // 绑定联想项点击
    container.querySelectorAll('.search-suggest-item').forEach(item => {
      item.addEventListener('click', () => {
        const word = item.dataset.word;
        if (word) submitSearchFromChip(word);
      });
    });
  } catch {
    container.innerHTML = '';
  }
}

/**
 * 【全局搜索的实现】加载搜索结果 — 三类 tab 切换
 * - 渲染 tab 栏 + 内容区
 * - GET /search?keyword=&current= 获取当前 tab 数据
 */
async function loadSearchResults(container, keyword) {
  showLoading(container);
  try {
    const data = await get('/search?keyword=' + encodeURIComponent(keyword) + '&current=1');
    // 后端返回 { attractions: [], notes: [], users: [] } 或数组
    const attractions = (data && data.attractions) ? data.attractions : [];
    const notes = (data && data.notes) ? data.notes : [];
    const users = (data && data.users) ? data.users : [];

    // 判断各 tab 是否有更多数据
    tabHasMore.attraction = attractions.length >= 10;
    tabHasMore.note = notes.length >= 10;
    tabHasMore.user = users.length >= 10;

    const totalCount = attractions.length + notes.length + users.length;

    container.innerHTML = `
      <div class="search-result-tabs" id="search-result-tabs">
        <div class="search-result-tab${activeTab === 'attraction' ? ' active' : ''}" data-tab="attraction">景点<span class="search-tab-count">${attractions.length}</span></div>
        <div class="search-result-tab${activeTab === 'note' ? ' active' : ''}" data-tab="note">游记<span class="search-tab-count">${notes.length}</span></div>
        <div class="search-result-tab${activeTab === 'user' ? ' active' : ''}" data-tab="user">用户<span class="search-tab-count">${users.length}</span></div>
      </div>
      <div id="search-result-content"></div>
    `;

    const resultContent = container.querySelector('#search-result-content');

    // 绑定 tab 切换
    container.querySelectorAll('#search-result-tabs .search-result-tab').forEach(tab => {
      tab.addEventListener('click', () => {
        const newTab = tab.dataset.tab;
        if (newTab === activeTab) return;
        activeTab = newTab;
        // 更新 tab 选中态
        container.querySelectorAll('#search-result-tabs .search-result-tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        // 切换内容
        renderTabContent(resultContent, keyword, { attractions, notes, users });
      });
    });

    // 渲染默认 tab 内容
    renderTabContent(resultContent, keyword, { attractions, notes, users });

  } catch {
    showEmpty(container, '搜索失败');
  }
}

/**
 * 【全局搜索的实现】渲染当前选中 tab 的内容
 * - 根据 activeTab 渲染景点/游记/用户列表
 * - 首屏数据由 loadSearchResults 传入（避免重复请求首屏）
 * - 后续分页由各 render 函数内部 loadMore 处理
 */
function renderTabContent(container, keyword, allData) {
  switch (activeTab) {
    case 'attraction':
      renderAttractionResults(container, allData.attractions || [], keyword);
      break;
    case 'note':
      renderNoteResults(container, allData.notes || [], keyword);
      break;
    case 'user':
      renderUserResults(container, allData.users || [], keyword);
      break;
  }
}

/** 【全局搜索的实现】渲染景点结果卡片列表 */
function renderAttractionResults(container, list, keyword) {
  if (!list.length) {
    container.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>未找到相关结果</p></div>';
    return;
  }

  let html = '<div class="search-result-list" id="search-attraction-list">';
  list.forEach(a => {
    const img = (a.images || a.image || '').split(',')[0];
    html += `
      <div class="attraction-card" data-id="${a.id}">
        <div class="card-img">
          <img loading="lazy" src="${imgUrl(img)}" alt="${escapeHtml(a.name)}" onerror="imgError(event)"/>
        </div>
        <div class="card-info">
          <div class="card-name">${escapeHtml(a.name)}</div>
          <div class="card-meta">${escapeHtml(a.area || '')}${a.area && a.address ? '·' : ''}${escapeHtml(a.address || '')}</div>
          <div class="card-bottom">
            <span class="card-score">★ ${scoreDisplay(a.avgScore || a.score)}</span>
            ${/** 【全局搜索的实现】价格显示：有票且 avgPrice>0 → ¥X起，否则"免费" */
            '<span class="card-price">' + (a.hasTicket && a.avgPrice > 0
              ? '¥' + fenToYuan(a.avgPrice) + '起' + (a.hasSeckill ? ' <span class="seckill-badge">秒杀</span>' : '')
              : '<span class="card-free">免费</span>') + '</span>'}
          </div>
        </div>
      </div>`;
  });
  html += '</div>';
  container.innerHTML = html;

  container.querySelectorAll('.attraction-card').forEach(card => {
    card.addEventListener('click', () => {
      window.location.hash = '#/attraction/' + card.dataset.id;
    });
  });

  // 分页加载更多
  if (tabHasMore.attraction) {
    const listEl = container.querySelector('#search-attraction-list');
    const sentinel = document.createElement('div');
    sentinel.className = 'load-sentinel load-sentinel-search-attr';
    sentinel.style.cssText = 'height:10px;width:100%';
    listEl.appendChild(sentinel);

    let current = tabPages.attraction || 2;
    observeSentinel(listEl, 'load-sentinel-search-attr', async () => {
      try {
        const data = await get('/search?keyword=' + encodeURIComponent(keyword) + '&current=' + current);
        const moreList = (data && data.attractions) ? data.attractions : [];
        if (!moreList.length) { sentinel.remove(); return; }
        /* 防御性去重：按已渲染卡片 data-id 过滤，防重复追加 */
        const existingIds = new Set([...listEl.querySelectorAll('.attraction-card')].map(el => el.dataset.id));
        moreList.forEach(a => {
          if (existingIds.has(String(a.id))) return;
          existingIds.add(String(a.id));
          const img = (a.images || a.image || '').split(',')[0];
          const el = document.createElement('div');
          el.className = 'attraction-card';
          el.dataset.id = a.id;
          el.innerHTML = `
            <div class="card-img">
              <img loading="lazy" src="${imgUrl(img)}" alt="${escapeHtml(a.name)}" onerror="imgError(event)"/>
            </div>
            <div class="card-info">
              <div class="card-name">${escapeHtml(a.name)}</div>
              <div class="card-meta">${escapeHtml(a.area || '')}${a.area && a.address ? '·' : ''}${escapeHtml(a.address || '')}</div>
              <div class="card-bottom">
                <span class="card-score">★ ${scoreDisplay(a.avgScore || a.score)}</span>
                <span class="card-price">${a.hasTicket && a.avgPrice > 0 ? '¥' + fenToYuan(a.avgPrice) + '起' : '<span class="card-free">免费</span>'}</span>
              </div>
            </div>`;
          el.addEventListener('click', () => {
            window.location.hash = '#/attraction/' + a.id;
          });
          listEl.insertBefore(el, sentinel);
        });
        current++;
        tabPages.attraction = current;
      } catch { sentinel.remove(); }
    }, 'load-sentinel-search-attr');
  }
}

/** 【全局搜索的实现】渲染游记结果卡片（瀑布流） */
function renderNoteResults(container, list, keyword) {
  if (!list.length) {
    container.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>未找到相关结果</p></div>';
    return;
  }

  let html = '<div class="notes-waterfall" id="search-note-list">';
  list.forEach(note => {
    /** 【全局搜索的实现】图片字段兼容：images||image */
    const img = (note.images || note.image || '').split(',')[0];
    html += `
      <div class="note-card" data-id="${note.id}">
        <div class="note-img">
          <img loading="lazy" src="${imgUrl(img)}" alt="" onerror="imgError(event)"/>
        </div>
        <div class="note-body">
          <div class="note-title">${escapeHtml(note.title || '')}</div>
          <div class="note-footer">
            <div class="note-author">
              ${note.icon ? '<img class="avatar avatar-mask" src="' + imgUrl(note.icon) + '" alt="" onerror="imgError(event)" data-avatar-uid="' + (note.userId || '') + '" data-avatar-icon="' + imgUrl(note.icon) + '" data-avatar-nick="' + escapeHtml(note.name || '') + '"/>' : ''}
              <span>${escapeHtml(note.name || '')}</span>
            </div>
            <div class="note-likes">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="${note.isLike ? '#f87171' : 'none'}" stroke="${note.isLike ? '#f87171' : '#999'}" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
              <span>${note.liked || 0}</span>
            </div>
          </div>
        </div>
      </div>`;
  });
  html += '</div>';
  container.innerHTML = html;

  container.querySelectorAll('.note-card').forEach(card => {
    card.addEventListener('click', () => {
      window.location.hash = '#/note/' + card.dataset.id;
    });
  });

  // 分页加载更多
  if (tabHasMore.note) {
    const listEl = container.querySelector('#search-note-list');
    const sentinel = document.createElement('div');
    sentinel.className = 'load-sentinel load-sentinel-search-note';
    sentinel.style.cssText = 'height:10px;width:100%;column-span:all';
    listEl.appendChild(sentinel);

    let current = tabPages.note || 2;
    observeSentinel(listEl, 'load-sentinel-search-note', async () => {
      try {
        const data = await get('/search?keyword=' + encodeURIComponent(keyword) + '&current=' + current);
        const moreList = (data && data.notes) ? data.notes : [];
        if (!moreList.length) { sentinel.remove(); return; }
        /* 防御性去重：按已渲染卡片 data-id 过滤，防重复追加 */
        const existingIds = new Set([...listEl.querySelectorAll('.note-card')].map(el => el.dataset.id));
        moreList.forEach(note => {
          if (existingIds.has(String(note.id))) return;
          existingIds.add(String(note.id));
          const img = (note.images || note.image || '').split(',')[0];
          const el = document.createElement('div');
          el.className = 'note-card';
          el.dataset.id = note.id;
          el.innerHTML = `
            <div class="note-img">
              <img loading="lazy" src="${imgUrl(img)}" alt="" onerror="imgError(event)"/>
            </div>
            <div class="note-body">
              <div class="note-title">${escapeHtml(note.title || '')}</div>
              <div class="note-footer">
                <div class="note-author">
                  ${note.icon ? '<img class="avatar avatar-mask" src="' + imgUrl(note.icon) + '" alt="" onerror="imgError(event)" data-avatar-uid="' + (note.userId || '') + '" data-avatar-icon="' + imgUrl(note.icon) + '" data-avatar-nick="' + escapeHtml(note.name || '') + '"/>' : ''}
                  <span>${escapeHtml(note.name || '')}</span>
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
          listEl.insertBefore(el, sentinel);
        });
        current++;
        tabPages.note = current;
      } catch { sentinel.remove(); }
    }, 'load-sentinel-search-note');
  }
}

/** 【全局搜索的实现】渲染用户结果行（头像+昵称，点击跳主页） */
function renderUserResults(container, list, keyword) {
  if (!list.length) {
    container.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>未找到相关结果</p></div>';
    return;
  }

  let html = '<div class="search-user-list" id="search-user-list">';
  list.forEach(u => {
    html += `
      <div class="search-user-item" data-uid="${u.id}">
        <img class="avatar" src="${imgUrl(u.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${u.id}" data-avatar-icon="${imgUrl(u.icon)}" data-avatar-nick="${escapeHtml(u.nickName || '')}"/>
        <div class="search-user-info">
          <div class="search-user-name">${escapeHtml(u.nickName || '用户')}</div>
          ${u.sign ? '<div class="search-user-sign">' + escapeHtml(u.sign) + '</div>' : ''}
        </div>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
      </div>`;
  });
  html += '</div>';
  container.innerHTML = html;

  container.querySelectorAll('.search-user-item').forEach(item => {
    item.addEventListener('click', () => {
      window.location.hash = '#/user/' + item.dataset.uid;
    });
  });

  // 分页加载更多
  if (tabHasMore.user) {
    const listEl = container.querySelector('#search-user-list');
    const sentinel = document.createElement('div');
    sentinel.className = 'load-sentinel load-sentinel-search-user';
    sentinel.style.cssText = 'height:10px;width:100%';
    listEl.appendChild(sentinel);

    let current = tabPages.user || 2;
    observeSentinel(listEl, 'load-sentinel-search-user', async () => {
      try {
        const data = await get('/search?keyword=' + encodeURIComponent(keyword) + '&current=' + current);
        const moreList = (data && data.users) ? data.users : [];
        if (!moreList.length) { sentinel.remove(); return; }
        /* 防御性去重：按已渲染条目 data-uid 过滤，防重复追加 */
        const existingIds = new Set([...listEl.querySelectorAll('.search-user-item')].map(el => el.dataset.uid));
        moreList.forEach(u => {
          if (existingIds.has(String(u.id))) return;
          existingIds.add(String(u.id));
          const el = document.createElement('div');
          el.className = 'search-user-item';
          el.dataset.uid = u.id;
          el.innerHTML = `
            <img class="avatar" src="${imgUrl(u.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${u.id}" data-avatar-icon="${imgUrl(u.icon)}" data-avatar-nick="${escapeHtml(u.nickName || '')}"/>
            <div class="search-user-info">
              <div class="search-user-name">${escapeHtml(u.nickName || '用户')}</div>
              ${u.sign ? '<div class="search-user-sign">' + escapeHtml(u.sign) + '</div>' : ''}
            </div>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>`;
          el.addEventListener('click', () => {
            window.location.hash = '#/user/' + u.id;
          });
          listEl.insertBefore(el, sentinel);
        });
        current++;
        tabPages.user = current;
      } catch { sentinel.remove(); }
    }, 'load-sentinel-search-user');
  }
}
