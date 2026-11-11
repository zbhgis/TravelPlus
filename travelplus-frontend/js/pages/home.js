/**
 * 【聚合首页的实现】#/home 页面渲染模块——三频道聚合首页 + 目的地 tab
 * 职责：URL query ?tab= 驱动频道切换（关注/游记/景点）、景点搜索与分类加载、目的地 tab 城市筛选、缓存与无限滚动
 * 依赖：api.js (get)、ui.js (imgUrl/imgError/scoreDisplay/fenToYuan/showLoading/showEmpty/loadMore/toast/getUrlParam)、notes.js (renderNotesContent)、follow.js (renderFollowContent)
 * 核心链路：
 *   renderHome(container)
 *     → 读取 getUrlParam('tab') 决定当前频道（默认 'attraction'）
 *     → 渲染 channel-tabs 子栏目条（关注|游记|景点 pill 切换）
 *     → 根据 tab 调用对应频道渲染函数：
 *       - attraction → renderAttractionChannel → 城市选择器 + 类型 tab + 景点卡片（带缓存+无限滚动）
 *       - notes → renderNotesContent（复用 notes.js 瀑布流）
 *       - follow → renderFollowContent（复用 follow.js Feed 时间线）
 *
 * 【城市选择器的实现】景点频道顶部城市筛选（独立入口，替代原城市 chip 标签行）
 * - GET /attraction/destinations → 城市列表（如 杭州、厦门...）
 * - 入口条显示当前选择（默认「杭州」），点击弹出底部抽屉多选城市（最多 5 个，超限 toast）
 * - 「全部城市」chip 与城市 chip 互斥；确定后 GET /attraction/of/destination?city=a,b（后端 IN 查询）
 * - 选「全部城市」回原类型 tab 逻辑；支持 ?dest= 深链覆盖默认选择
 */
import { get } from '../api.js';
import { imgUrl, imgError, scoreDisplay, fenToYuan, showLoading, showEmpty, loadMore, toast, getUrlParam } from '../ui.js';
import { renderNotesContent } from './notes.js';
import { renderFollowContent } from './follow.js';

const cache = {};
let activeTypeId = null;

/** HTML 转义（与其他页面本地实现一致）：城市名来自后端数据，进 HTML 前统一转义防注入 */
function escapeHtml(str) {
  return String(str == null ? '' : str)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** 【城市选择器的实现】目的地模块级状态
 *  - selectedCities：选中城市数组（城市必选，至少 1 个；默认 ['杭州']）
 *  - 最多同时选择 5 个城市（MAX_CITY_PICK），后端 /attraction/of/destination 支持逗号分隔多城轮转查询
 */
let selectedCities = ['杭州'];  // 默认杭州
const DEFAULT_CITY = '杭州';    // 重置时的默认城市
let destinations = [];          // 城市列表缓存
let cityCurrent = 1;           // 城市分页游标
const MAX_CITY_PICK = 5;       // 单次最多选择城市数
/** 【景点分类 tab 的实现】城市模式类型筛选（null=全部类型）
 * - 城市选择器下方类型 tab 条：全部 | 自然风光 | 人文古迹 | 其他（/attraction-type/list）
 * - 解决"200 个景点滚到底要划拉半天"：点类型即刻缩小范围（后端 destination 接口叠加 typeId 过滤）
 */
let cityTypeId = null;
/** 【类型精简显示的实现】常显的前 2 个类型 id集合（「其他」聚合 tab 需在后端排除的类型） */
let featuredTypeIds = [];

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

/* 【景点列表的实现】景点频道：目的地 tab + 类型 tab + 景点卡片列表
 * - 已移除频道内搜索栏：搜索由顶部全局搜索条（#global-search）统一提供（点击 → #/search）
 * - 避免重复入口，频道内仅保留目的地 tab 筛选与类型 tab 分类
 */
async function renderAttractionChannel(container) {
  container.innerHTML = `
    <div class="city-picker-bar" id="city-picker-bar" role="button" title="选择目的地">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0z"/><circle cx="12" cy="10" r="3"/></svg>
      <div class="city-picker-chips" id="city-picker-chips"></div>
      <span class="city-picker-arrow">▾</span>
    </div>
    <div class="city-sheet-mask" id="city-sheet-mask">
      <div class="city-sheet">
        <div class="city-sheet-head">选择目的地<span class="city-sheet-sub">最多可选 ${MAX_CITY_PICK} 个</span><span class="city-sheet-close" id="city-sheet-close">×</span></div>
        <div class="city-sheet-grid" id="city-sheet-grid"></div>
        <div class="city-sheet-actions">
          <button class="city-sheet-btn ghost" id="city-sheet-reset">重置</button>
          <button class="city-sheet-btn primary" id="city-sheet-ok">确定</button>
        </div>
      </div>
    </div>
    <div id="home-type-tabs" class="tabs-scroll"></div>
    <div id="home-content" class="page-content"></div>
  `;

  const contentEl = container.querySelector('#home-content');
  const tabsEl = container.querySelector('#home-type-tabs');
  const pickerBar = container.querySelector('#city-picker-bar');
  const sheetMask = container.querySelector('#city-sheet-mask');
  const sheetGrid = container.querySelector('#city-sheet-grid');

  try {
    const rawDest = await get('/attraction/destinations');
    destinations = Array.isArray(rawDest) ? rawDest : [];
  } catch { destinations = []; }

  /** 【城市选择器的实现】入口条渲染：展开显示全部选中城市（每城带 × 可直接移除）
   *  - 至少保留一个城市：移除最后一城时 toast 提示（完整改选请进抽屉）
   */
  const renderPickerChips = () => {
    const chipsEl = container.querySelector('#city-picker-chips');
    if (!chipsEl) return;
    chipsEl.innerHTML = selectedCities.map(city =>
      '<span class="city-picker-chip">' + escapeHtml(city)
      + '<span class="city-picker-chip-x" data-city="' + escapeHtml(city) + '">×</span></span>'
    ).join('');
    chipsEl.querySelectorAll('.city-picker-chip-x').forEach(xEl => {
      xEl.addEventListener('click', e => {
        e.stopPropagation(); // 不触发入口条的打开抽屉
        const city = xEl.dataset.city;
        if (selectedCities.length <= 1) {
          toast('至少保留一个城市，可点右侧箭头重新选择');
          return;
        }
        selectedCities = selectedCities.filter(c => c !== city);
        applyCities();
      });
    });
  };

  /** 【城市选择器的实现】按当前 selectedCities 加载列表（城市必选 ≥1，恒为城市模式） */
  const applyCities = () => {
    renderPickerChips();
    /* 类型 tab 条显示状态由 renderCityTypeTabs 管理（城市变更后保持当前类型筛选重查） */
    loadCityAttractions(contentEl, selectedCities.join(','), cityTypeId);
  };

  /** 【城市选择器的实现】渲染抽屉城市宫格（草稿态 draftCities，确定后才生效）
   *  - 城市必选 ≥1（无"全部城市"选项）；选中 chip 显示序号徽标（1~5）
   */
  const renderSheet = () => {
    sheetGrid.innerHTML = destinations.map(city => {
      const idx = draftCities.indexOf(city);
      return '<div class="city-chip' + (idx > -1 ? ' selected' : '') + '" data-city="' + city + '">' + escapeHtml(city)
        + (idx > -1 ? '<span class="city-chip-badge">' + (idx + 1) + '</span>' : '') + '</div>';
    }).join('');
    sheetGrid.querySelectorAll('.city-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const city = chip.dataset.city;
        const idx = draftCities.indexOf(city);
        if (idx > -1) {
          /* 至少保留一个城市：减到 0 时回退并提示 */
          if (draftCities.length <= 1) {
            toast('至少选择一个城市');
            return;
          }
          draftCities.splice(idx, 1);
        } else {
          if (draftCities.length >= MAX_CITY_PICK) {
            toast('最多选择 ' + MAX_CITY_PICK + ' 个城市');
            return;
          }
          draftCities.push(city);
        }
        renderSheet();
      });
    });
  };

  /** 【城市选择器的实现】抽屉开/关（草稿在打开时快照，取消即丢弃） */
  let draftCities = [];
  const openSheet = () => {
    draftCities = [...selectedCities];
    renderSheet();
    sheetMask.classList.add('show');
  };
  const closeSheet = () => sheetMask.classList.remove('show');

  pickerBar.addEventListener('click', openSheet);
  /* 点遮罩空白处关闭（只拦遮罩自身，不拦面板内冒泡） */
  sheetMask.addEventListener('click', e => { if (e.target === sheetMask) closeSheet(); });
  container.querySelector('#city-sheet-close').addEventListener('click', closeSheet);
  container.querySelector('#city-sheet-reset').addEventListener('click', () => {
    /* 重置 = 恢复默认杭州（城市必选，无空态） */
    draftCities = [DEFAULT_CITY];
    renderSheet();
  });
  container.querySelector('#city-sheet-ok').addEventListener('click', () => {
    if (!draftCities.length) {
      toast('至少选择一个城市');
      return;
    }
    selectedCities = [...draftCities];
    closeSheet();
    applyCities();
  });

  /* ?dest= 深链：覆盖默认选择（如 #/home?dest=南京） */
  const destParam = getUrlParam('dest');
  if (destParam && destinations.includes(destParam)) {
    selectedCities = [destParam];
  }

  /* 初始加载：城市必选，恒为城市模式（默认杭州） */
  renderPickerChips();
  tabsEl.style.display = 'none';
  await loadCityAttractions(contentEl, selectedCities.join(','), cityTypeId);

  let types = [];
  try {
    types = await get('/attraction-type/list');
  } catch { types = []; }

  /* 【景点分类 tab 的实现】城市模式类型 tab（全部 + 各类型）——城市与类型双维筛选 */
  renderCityTypeTabs(tabsEl, types, contentEl);
}

/** 【景点分类 tab 的实现】城市模式类型 tab 条渲染
 * - 【类型精简显示的实现】只显示三类：全部 + 前 2 个类型 + 其他（/attraction-type/list）
 * - 「其他」= 除前 2 个常显类型外的全部类型合选（点击传 excludeTypeIds 后端 notIn 排除过滤）；
 *   类型总数 ≤ 2 时无需聚合，直接平铺
 * - 点击 tab：更新 cityTypeId（'other' 为字符串哨兵值）→ loadCityAttractions
 * - 类型列表为空时隐藏条（静默降级）
 */
function renderCityTypeTabs(el, types, contentEl) {
  if (!types.length) {
    el.style.display = 'none';
    return;
  }
  el.classList.add('city-type-tabs');
  const featured = types.slice(0, 2);
  featuredTypeIds = featured.map(t => t.id);
  const items = [{ id: null, name: '全部' }, ...featured.map(t => ({ id: t.id, name: t.name }))];
  if (types.length > 2) items.push({ id: 'other', name: '其他' });
  el.innerHTML = items.map((t, i) =>
    '<div class="tab-item' + (i === 0 ? ' active' : '') + '" data-type-id="' + (t.id == null ? '' : t.id) + '">' + t.name + '</div>'
  ).join('');
  el.style.display = '';

  const tabEls = el.querySelectorAll('.tab-item');
  tabEls.forEach(item => {
    item.addEventListener('click', () => {
      tabEls.forEach(it => it.classList.remove('active'));
      item.classList.add('active');
      const v = item.dataset.typeId;
      cityTypeId = v === '' ? null : (v === 'other' ? 'other' : parseInt(v, 10));
      loadCityAttractions(contentEl, selectedCities.join(','), cityTypeId);
    });
  });
}

/** 【目的地 tab 的实现】动态重新获取类型列表并加载首屏 */
async function loadInitialTypeAttractions(contentEl, tabsEl) {
  try {
    const types = await get('/attraction-type/list');
    renderTabs(tabsEl, types, contentEl);
    if (types.length > 0) {
      loadAttractions(contentEl, types[0].id);
    }
  } catch {
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

  /** 【景点列表的实现】脏标记失效缓存——主页重新拉取最新评分
   * 实现细节：
   * - 详情页打分成功后 attraction.js 写 localStorage travelplus_attraction_dirty='1'
   * - loadAttractions 每次被调用时检测脏标记 → 清空全部缓存（cache={}）+ removeItem 移除标记
   * - 之后正常走 GET /attraction/of/type 重新拉取（包含最新 avgScore）
   * - 兼顾限流：只清一次缓存（脏标记 removeItem 后不再重复清），避免频繁触发限流（5秒5次）
   */
  if (localStorage.getItem('travelplus_attraction_dirty')) {
    Object.keys(cache).forEach(k => delete cache[k]);
    localStorage.removeItem('travelplus_attraction_dirty');
  }

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

/** 【目的地 tab 的实现】按城市加载景点列表（可叠加类型筛选/其他聚合筛选）
 *  - GET /attraction/of/destination?city=&current=1[&typeId=|&excludeTypeIds=] → 分页列表
  * - typeId 非空时后端叠加 type_id 过滤（城市模式类型 tab）
 *  - 【类型精简显示的实现】typeId='other'（其他聚合 tab）→ 不传 typeId，改传 excludeTypeIds
 *    排除常显的前 2 个类型，返回其余全部类型
 *  - 复用景点卡片渲染 + 无限滚动
 */
async function loadCityAttractions(container, city, typeId) {
  cityCurrent = 1;
  showLoading(container);
  try {
    const isOther = typeId === 'other';
    const typeQuery = (typeId != null && !isOther) ? '&typeId=' + typeId : '';
    const excludeQuery = (isOther && featuredTypeIds.length) ? '&excludeTypeIds=' + featuredTypeIds.join(',') : '';
    const data = await get('/attraction/of/destination?city=' + encodeURIComponent(city) + typeQuery + excludeQuery + '&current=1');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
    renderAttractionList(container, list, list.length > 0);
    cityCurrent = 2;
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
          <img loading="lazy" src="${imgUrl(img)}" alt="${a.name}" onerror="imgError(event)"/>
        </div>
        <div class="card-info">
          <div class="card-name">${a.name}</div>
          <div class="card-meta">${a.area || ''}${a.area && a.address ? '·' : ''}${a.address || ''}</div>
          <div class="card-bottom">
            <span class="card-score">★ ${scoreDisplay(a.avgScore || a.score)}</span>
            ${/** 【景点列表的实现】价格显示：有票且 avgPrice>0 → ¥X起，否则显示"免费"
             * - 实现细节：0 元 / 无票 = 免费（avgPrice=0 或 hasTicket=false） */
            '<span class="card-price">' + (a.hasTicket && a.avgPrice > 0
              ? '¥' + fenToYuan(a.avgPrice) + '起' + (a.hasSeckill ? ' <span class="seckill-badge">秒杀</span>' : '')
              : '<span class="card-free">免费</span>') + '</span>'}
            <span class="card-sold">${a.checkinCount || 0}人来过</span>
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
        if (selectedCities.length) {
          /** 【城市选择器的实现】多城市分页加载（join(',') 与后端 IN 查询约定一致；typeId 随类型 tab）
           * 【类型精简显示的实现】cityTypeId='other' → excludeTypeIds 排除常显类型（与首屏口径一致） */
          const isOther = cityTypeId === 'other';
          const typeQuery = (cityTypeId != null && !isOther) ? '&typeId=' + cityTypeId : '';
          const excludeQuery = (isOther && featuredTypeIds.length) ? '&excludeTypeIds=' + featuredTypeIds.join(',') : '';
          const data = await get('/attraction/of/destination?city=' + encodeURIComponent(selectedCities.join(',')) + typeQuery + excludeQuery + '&current=' + cityCurrent);
          const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
          if (!list.length) return;
          appendAttractions(container, list);
          cityCurrent++;
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
        <img loading="lazy" src="${imgUrl(img)}" alt="${a.name}" onerror="imgError(event)"/>
      </div>
      <div class="card-info">
        <div class="card-name">${a.name}</div>
        <div class="card-meta">${a.area || ''}${a.area && a.address ? '·' : ''}${a.address || ''}</div>
        <div class="card-bottom">
          <span class="card-score">★ ${scoreDisplay(a.avgScore || a.score)}</span>
          ${/** 【景点列表的实现】价格显示：有票且 avgPrice>0 → ¥X起，否则"免费"（同上渲染逻辑） */
          '<span class="card-price">' + (a.hasTicket && a.avgPrice > 0
            ? '¥' + fenToYuan(a.avgPrice) + '起' + (a.hasSeckill ? ' <span class="seckill-badge">秒杀</span>' : '')
            : '<span class="card-free">免费</span>') + '</span>'}
          <span class="card-sold">${a.checkinCount || 0}人来过</span>
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
