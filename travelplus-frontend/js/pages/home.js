/**
 * 【聚合首页的实现】#/home 页面渲染模块——三频道聚合首页 + 目的地 tab
 * 职责：URL query ?tab= 驱动频道切换（关注/游记/景点）、景点搜索与分类加载、目的地 tab 城市筛选、缓存与无限滚动
 * 依赖：api.js (get)、ui.js (imgUrl/imgError/scoreDisplay/fenToYuan/showLoading/showEmpty/loadMore/toast/getUrlParam)、notes.js (renderNotesContent)、follow.js (renderFollowContent)
 * 核心链路：
 *   renderHome(container)
 *     → 读取 getUrlParam('tab') 决定当前频道（默认 'attraction'）
 *     → 渲染 channel-tabs 子栏目条（关注|游记|景点 pill 切换）
 *     → 根据 tab 调用对应频道渲染函数：
 *       - attraction → renderAttractionChannel → 目的地 tab + 类型 tab + 景点卡片（带缓存+无限滚动）
 *       - notes → renderNotesContent（复用 notes.js 瀑布流）
 *       - follow → renderFollowContent（复用 follow.js Feed 时间线）
 *
 * 【目的地 tab 的实现】景点频道顶部目的地城市筛选
 * - GET /attraction/destinations → 城市列表（如 杭州、厦门...）
 * - 渲染横滑 tab 行：「全部」 + 各城市
 * - 点击切换 → 选中城市时 GET /attraction/of/destination?city=&current= 拉取该城市景点
 * - 选中「全部」回原类型 tab 逻辑
 * - 目的地 tab 优先于类型 tab：选中城市时按城市渲染，不选=全部走类型逻辑
 */
import { get } from '../api.js';
import { imgUrl, imgError, scoreDisplay, fenToYuan, showLoading, showEmpty, loadMore, toast, getUrlParam } from '../ui.js';
import { renderNotesContent } from './notes.js';
import { renderFollowContent } from './follow.js';

const cache = {};
let activeTypeId = null;

/** 【目的地 tab 的实现】目的地模块级状态 */
let activeCity = null;          // 当前选中城市（null=全部，走类型逻辑）
let destinations = [];          // 城市列表缓存
let cityCurrent = 1;           // 城市分页游标

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
    <div class="dest-tabs-wrap" id="dest-tabs-wrap">
      <div class="dest-tabs" id="dest-tabs"></div>
    </div>
    <div id="home-type-tabs" class="tabs-scroll"></div>
    <div id="home-content" class="page-content"></div>
  `;

  const contentEl = container.querySelector('#home-content');
  const tabsEl = container.querySelector('#home-type-tabs');
  const destTabsEl = container.querySelector('#dest-tabs');

  /** 【目的地 tab 的实现】加载城市列表并渲染 tab
   *  - GET /attraction/destinations → 城市数组（字符串如['杭州','厦门']）
   *  - 「全部」为第一个 tab（data-city=""），后续为各城市
   *  - 默认选中「全部」
   */
  try {
    const rawDest = await get('/attraction/destinations');
    destinations = Array.isArray(rawDest) ? rawDest : [];
  } catch { destinations = []; }

  if (destinations.length > 0) {
    let destHtml = '<div class="dest-tab active" data-city="">全部</div>';
    destinations.forEach(city => {
      destHtml += '<div class="dest-tab" data-city="' + city + '">' + city + '</div>';
    });
    destTabsEl.innerHTML = destHtml;

    /** 【目的地 tab 的实现】tab 点击切换
     *  - 更新 active 类 → 设置 activeCity → 渲染对应内容
     *  - activeCity 为空字符串=全部走类型逻辑
     *  - activeCity 非空=按城市拉取
     */
    destTabsEl.querySelectorAll('.dest-tab').forEach(destTab => {
      destTab.addEventListener('click', () => {
        destTabsEl.querySelectorAll('.dest-tab').forEach(d => d.classList.remove('active'));
        destTab.classList.add('active');
        const city = destTab.dataset.city;
        activeCity = city || null;  // 空字符串 → null（全部）

        if (activeCity) {
          // 选中城市：隐藏类型 tab，按城市加载
          tabsEl.style.display = 'none';
          loadCityAttractions(contentEl, activeCity);
        } else {
          // 全部：显示类型 tab，走原类型逻辑
          tabsEl.style.display = '';
          if (activeTypeId) {
            loadAttractions(contentEl, activeTypeId);
          } else {
            showLoading(contentEl);
            // 需要重新获取类型列表来首屏加载
            loadInitialTypeAttractions(contentEl, tabsEl);
          }
        }
      });
    });
  }

  let types = [];
  try {
    types = await get('/attraction-type/list');
  } catch { types = []; }

  renderTabs(tabsEl, types, contentEl);

  /** 【目的地 tab 的实现】支持 ?dest= 参数自动切换目的地
   *  - 从 URL hash query 读取 dest 参数（如 #/home?dest=杭州）
   *  - 遍历 .dest-tab 匹配 data-city 属性，找到后触发 click 事件
   *  - 实现细节：自动点击会触发已有 tab 切换逻辑（更新 active 态 → 隐藏类型 tab → loadCityAttractions）
   *  - 若无匹配或 dest 为空，走默认类型 tab 加载逻辑
   */
  const destParam = getUrlParam('dest');
  if (destParam && destinations.length > 0) {
    const matchingTab = destTabsEl.querySelector('.dest-tab[data-city="' + destParam.replace(/"/g, '\\"') + '"]');
    if (matchingTab) {
      matchingTab.click();
      return; // 已切换到城市模式，不需要再加载默认类型景点
    }
  }

  if (types.length > 0) {
    loadAttractions(contentEl, types[0].id);
  } else {
    showEmpty(contentEl, '暂无景点分类');
  }
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

/** 【目的地 tab 的实现】按城市加载景点列表
 *  - GET /attraction/of/destination?city=&current= → 分页列表
 *  - 复用景点卡片渲染 + 无限滚动
 */
async function loadCityAttractions(container, city) {
  cityCurrent = 1;
  showLoading(container);
  try {
    const data = await get('/attraction/of/destination?city=' + encodeURIComponent(city) + '&current=1');
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
          <img src="${imgUrl(img)}" alt="${a.name}" onerror="imgError(event)"/>
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
        if (activeCity) {
          /** 【目的地 tab 的实现】城市分页加载 */
          const data = await get('/attraction/of/destination?city=' + encodeURIComponent(activeCity) + '&current=' + cityCurrent);
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
        <img src="${imgUrl(img)}" alt="${a.name}" onerror="imgError(event)"/>
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
