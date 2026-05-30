/**
 * 【聚合首页的实现】#/home 页面渲染模块——三频道聚合首页
 * 职责：URL query ?tab= 驱动频道切换（关注/游记/景点）、景点搜索与分类加载、缓存与无限滚动
 * 依赖：api.js (get)、ui.js (imgUrl/imgError/scoreDisplay/fenToYuan/showLoading/showEmpty/loadMore/toast/getUrlParam)、notes.js (renderNotesContent)、follow.js (renderFollowContent)
 * 核心链路：
 *   renderHome(container)
 *     → 读取 getUrlParam('tab') 决定当前频道（默认 'attraction'）
 *     → 渲染 channel-tabs 子栏目条（关注|游记|景点 pill 切换）
 *     → 根据 tab 调用对应频道渲染函数：
 *       - attraction → renderAttractionChannel → 搜索栏 + 类型 tab 网格 + 景点卡片（带缓存+无限滚动）
 *       - notes → renderNotesContent（复用 notes.js 瀑布流）
 *       - follow → renderFollowContent（复用 follow.js Feed 时间线）
 * 子tab交互：点击 channel-tab → 更新 window.location.hash → hashchange 触发 router 重渲染
 * 搜索：搜索模式 searchMode=true 时，无限滚动按名称搜索而非类型加载
 * 缓存cache：{typeId: list[]}，切换类型 tab 时优先从缓存取，减少重复请求
 * 限频处理：/attraction/of/type 5 秒限 5 次，超限 toast "查询次数过多，请稍后再试"
 */
import { get } from '../api.js';
import { imgUrl, imgError, scoreDisplay, fenToYuan, showLoading, showEmpty, loadMore, toast, getUrlParam } from '../ui.js';
import { renderNotesContent } from './notes.js';
import { renderFollowContent } from './follow.js';

const cache = {};
let activeTypeId = null;
let searchMode = false;
let searchCurrent = 1;
let searchHasMore = true;

export async function renderHome(container) {
  const tab = getUrlParam('tab') || 'attraction'; // 'attraction' | 'notes' | 'follow'

  container.innerHTML = `
    <div class="channel-tabs">
      <div class="channel-tab${tab === 'follow' ? ' active' : ''}" data-tab="follow">关注</div>
      <div class="channel-tab${tab === 'notes' ? ' active' : ''}" data-tab="notes">游记</div>
      <div class="channel-tab${tab === 'attraction' ? ' active' : ''}" data-tab="attraction">景点</div>
    </div>
    <div id="home-channel"></div>
  `;

  // 子栏目条点击：更新 hash 触发 router 重渲染
  container.querySelectorAll('.channel-tab').forEach(tabEl => {
    tabEl.addEventListener('click', () => {
      const t = tabEl.dataset.tab;
      if (t === 'attraction') {
        window.location.hash = '#/home';
      } else {
        window.location.hash = '#/home?tab=' + t;
      }
    });
  });

  const channelEl = container.querySelector('#home-channel');

  if (tab === 'attraction') {
    await renderAttractionChannel(channelEl);
  } else if (tab === 'notes') {
    await renderNotesContent(channelEl);
  } else if (tab === 'follow') {
    await renderFollowContent(channelEl);
  }

  return () => {};
}

/* 景点频道：搜索栏 + 类型 tab + 景点卡片列表 */
async function renderAttractionChannel(container) {
  container.innerHTML = `
    <div class="search-bar">
      <input type="text" id="home-search" placeholder="搜索景点名称..."/>
      <button class="search-btn" id="home-search-btn">搜索</button>
    </div>
    <div id="home-type-tabs" class="tabs-scroll"></div>
    <div id="home-content" class="page-content"></div>
  `;

  const searchInput = container.querySelector('#home-search');
  const searchBtn = container.querySelector('#home-search-btn');
  const contentEl = container.querySelector('#home-content');
  const tabsEl = container.querySelector('#home-type-tabs');

  let types = [];
  try {
    types = await get('/attraction-type/list');
  } catch { types = []; }

  renderTabs(tabsEl, types, contentEl);

  const doSearch = async () => {
    const name = searchInput.value.trim();
    if (!name) {
      searchMode = false;
      const activeTab = tabsEl.querySelector('.tab-item.active');
      if (activeTab) {
        loadAttractions(contentEl, parseInt(activeTab.dataset.typeId));
      }
      return;
    }
    searchMode = true;
    searchCurrent = 1;
    searchHasMore = true;
    showLoading(contentEl);
    try {
      const data = await get('/attraction/of/name?name=' + encodeURIComponent(name) + '&current=1');
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
      searchHasMore = list.length >= 10;
      renderAttractionList(contentEl, list, searchHasMore);
      searchCurrent = 2;
    } catch {
      showEmpty(contentEl, '搜索失败');
    }
  };

  searchBtn.addEventListener('click', doSearch);
  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doSearch();
  });

  if (types.length > 0) {
    loadAttractions(contentEl, types[0].id);
  } else {
    showEmpty(contentEl, '暂无景点分类');
  }
}

function renderTabs(el, types, contentEl) {
  if (!types.length) {
    el.style.display = 'none';
    return;
  }
  el.innerHTML = types.map((t, i) =>
    '<div class="tab-item' + (i === 0 ? ' active' : '') + '" data-type-id="' + t.id + '">' + t.name + '</div>'
  ).join('');

  const items = el.querySelectorAll('.tab-item');
  items.forEach(item => {
    item.addEventListener('click', () => {
      items.forEach(it => it.classList.remove('active'));
      item.classList.add('active');
      const typeId = parseInt(item.dataset.typeId);
      loadAttractions(contentEl, typeId);
    });
  });
}

async function loadAttractions(container, typeId) {
  activeTypeId = typeId;
  searchMode = false;

  if (cache[typeId]) {
    renderAttractionList(container, cache[typeId], false);
    return;
  }

  showLoading(container);
  try {
    const data = await get('/attraction/of/type?typeId=' + typeId + '&current=1');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
    cache[typeId] = list;
    renderAttractionList(container, list, list.length > 0);
  } catch (e) {
    const msg = e.message || '';
    if (msg.includes('查询次数过多')) {
      toast('查询次数过多，请稍后再试');
      showEmpty(container, '查询频率限制，请稍后再试');
    } else {
      showEmpty(container, '加载失败，请重试');
    }
  }
}

function renderAttractionList(container, list, hasMore) {
  if (!list || list.length === 0) {
    showEmpty(container, '暂无景点');
    return;
  }

  let html = '';
  list.forEach(a => {
    const img = a.images ? a.images.split(',')[0] : '';
    html += `
      <div class="attraction-card" data-id="${a.id}">
        <div class="card-img">
          <img src="${imgUrl(img)}" alt="${a.name}" onerror="imgError(event)"/>
        </div>
        <div class="card-info">
          <div class="card-name">${a.name}</div>
          <div class="card-meta">${a.area || ''}${a.area && a.address ? '·' : ''}${a.address || ''}</div>
          <div class="card-bottom">
            <span class="card-score">★ ${scoreDisplay(a.score)}</span>
            <span class="card-price">¥${fenToYuan(a.avgPrice)}起</span>
            <span class="card-sold">已售${a.sold || 0}</span>
          </div>
        </div>
      </div>`;
  });

  if (hasMore) {
    html += '<div class="load-sentinel" style="height:10px"></div>';
  }

  container.innerHTML = html;

  container.querySelectorAll('.attraction-card').forEach(card => {
    card.addEventListener('click', () => {
      window.location.hash = '#/attraction/' + card.dataset.id;
    });
  });

  if (hasMore) {
    let current = 2;
    loadMore(container, async () => {
      try {
        const typeId = activeTypeId;
        if (searchMode) {
          const name = document.getElementById('home-search')?.value?.trim() || '';
          if (!name) return;
          const data = await get('/attraction/of/name?name=' + encodeURIComponent(name) + '&current=' + searchCurrent);
          const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
          if (!list.length) {
            searchHasMore = false;
            return;
          }
          appendAttractions(container, list);
          searchCurrent++;
        } else if (typeId) {
          const data = await get('/attraction/of/type?typeId=' + typeId + '&current=' + current);
          const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
          if (!list.length) return;
          if (cache[typeId]) {
            cache[typeId] = [...cache[typeId], ...list];
          }
          appendAttractions(container, list);
          current++;
        }
      } catch (e) {
        const msg = e.message || '';
        if (msg.includes('查询次数过多')) {
          toast('查询次数过多，请稍后再试');
        }
      }
    }, 'load-sentinel');
  }
}

function appendAttractions(container, list) {
  const sentinel = container.querySelector('.load-sentinel');
  list.forEach(a => {
    const img = a.images ? a.images.split(',')[0] : '';
    const el = document.createElement('div');
    el.className = 'attraction-card';
    el.dataset.id = a.id;
    el.innerHTML = `
      <div class="card-img">
        <img src="${imgUrl(img)}" alt="${a.name}" onerror="imgError(event)"/>
      </div>
      <div class="card-info">
        <div class="card-name">${a.name}</div>
        <div class="card-meta">${a.area || ''}${a.area && a.address ? '·' : ''}${a.address || ''}</div>
        <div class="card-bottom">
          <span class="card-score">★ ${scoreDisplay(a.score)}</span>
          <span class="card-price">¥${fenToYuan(a.avgPrice)}起</span>
          <span class="card-sold">已售${a.sold || 0}</span>
        </div>
      </div>`;
    el.addEventListener('click', () => {
      window.location.hash = '#/attraction/' + a.id;
    });
    if (sentinel) {
      container.insertBefore(el, sentinel);
    } else {
      container.appendChild(el);
    }
  });
}
