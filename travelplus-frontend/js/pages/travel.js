/**
 * 【旅行功能的实现】旅行页 #/travel——Leaflet 地图标记景点 + 附近景点推荐 + 旅行概览 + 我的旅行计划列表
 * 职责：渲染地图区（OSM 瓦片 + 景点标记 + popup 跳详情）、附近景点横向卡片、旅行概览三张小卡（打卡景点数/去过城市数（destination去重）/旅行计划数）、旅行计划列表
 * 依赖：api.js (get/post/del)、ui.js (imgUrl/imgError/showEmpty/showLoading/toast/confirmDialog/formatDate)、store.js (isLoggedIn/getUser)、router.js (navigate)
 * 核心链路：
 *   renderTravel(container)
 *     → 检查 Leaflet 全局变量 L 是否可用（CDN 失败降级）
 *     → 初始化地图（中心杭州 120.15/30.26 zoom=11）
 *     → GET /map/points → 全量景点标记 L.marker + popup
 *     → moveend 事件 → haversine 计算中心 5km 内景点 → 渲染"附近景点"横向卡片
 *     → GET /trip/list?current=1 → 渲染旅行计划卡片列表
 *     → GET /user/checkins/{me} → 旅行概览三张小卡（打卡景点数/去过城市数（checkins 每条 destination 去重计数）/旅行计划数）
 *     → cleanup 返回函数：map.remove() 清理地图实例
 */
import { get, del } from '../api.js';
import { imgUrl, imgError, showEmpty, showLoading, toast, formatDate } from '../ui.js';
import { isLoggedIn, getUser } from '../store.js';
import { navigate } from '../router.js';

/**
 * 【旅行功能的实现】Haversine 距离计算（球面两点间最短距离）
 * - 实现：经纬度转弧度 → haversine 公式 → 返回公里数
 * - 用于 moveend 时计算地图中心 5km 内的附近景点
 */
function haversineKm(lat1, lon1, lat2, lon2) {
  const R = 6371; // 地球半径 km
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

/**
 * 【旅行功能的实现】防 XSS 转义工具
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}

export async function renderTravel(container) {
  /** 【旅行功能的实现】检查 Leaflet 是否加载成功（CDN 降级处理）
   * - 若 window.L 不存在 → 地图区显示"地图加载失败"占位
   * - 其余功能（旅行概览/热门目的地/结伴/旅行计划列表）仍可用
   */
  const leafletAvailable = typeof window.L !== 'undefined';
  const me = getUser();

  container.innerHTML = '<div class="page-header"><h2>旅行</h2></div><div id="travel-content"><div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div></div>';

  const contentEl = container.querySelector('#travel-content');

  /** 【旅行功能的实现】旅行计划列表渲染
   * - GET /trip/list?current=1 → 计划卡片（标题/目的地/日期）
   * - 新建按钮 → #/trip/new
   * - 空态"还没有旅行计划"
   * - 返回 {total: number} 供旅行概览使用
   */
  async function renderTripPlans(containerEl) {
    try {
      const data = await get('/trip/list?current=1');
      const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
      const total = (data && data.total != null) ? data.total : list.length;

      let html = '<div class="section-title">我的旅行计划</div>';

      if (!list.length) {
        html += '<div class="empty-state"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><path d="M3 7v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-1.5l-1-2h-9l-1 2H5a2 2 0 0 0-2 2z"/><circle cx="12" cy="14" r="3"/></svg><p>还没有旅行计划</p><p style="font-size:12px;margin-top:4px">点击下方按钮创建第一个旅行计划吧</p></div>';
      } else {
        html += '<div class="trip-list">';
        list.forEach(item => {
          html += `
            <div class="trip-card" data-id="${item.id}">
              <div class="trip-card-body">
                <div class="trip-card-title">${escapeHtml(item.title || '未命名计划')}</div>
                <div class="trip-card-dest">目的地：${escapeHtml(item.destination || '未设置')}</div>
                <div class="trip-card-date">${item.startDate || ''} ${item.startDate && item.endDate ? ' ~ ' : ''} ${item.endDate || ''}</div>
              </div>
              <div class="trip-card-arrow">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
              </div>
            </div>`;
        });
        html += '</div>';
      }

      html += '<div class="trip-new-btn-wrap"><button class="btn btn-primary btn-block" id="btn-new-trip">新建旅行计划</button></div>';
      containerEl.innerHTML = html;

      // 绑定计划卡片点击 → #/trip/:id
      containerEl.querySelectorAll('.trip-card').forEach(card => {
        card.addEventListener('click', () => {
          navigate('/trip/' + card.dataset.id);
        });
      });

      // 绑定新建按钮
      const newBtn = containerEl.querySelector('#btn-new-trip');
      if (newBtn) {
        newBtn.addEventListener('click', () => navigate('/trip/new'));
      }

      return total;
    } catch {
      containerEl.innerHTML = '<div class="section-title">我的旅行计划</div><div class="empty-state"><p style="color:var(--text-secondary)">加载失败</p></div><div class="trip-new-btn-wrap"><button class="btn btn-primary btn-block" id="btn-new-trip">新建旅行计划</button></div>';
      const newBtn = containerEl.querySelector('#btn-new-trip');
      if (newBtn) {
        newBtn.addEventListener('click', () => navigate('/trip/new'));
      }
      return 0;
    }
  }

  /** 【旅行概览的实现】加载旅行概览数据（三张小卡）
   *  - 打卡景点数：GET /user/checkins/{me.id} → records.length（checkins 数组长度）
   *  - 去过城市数：checkins 每条的 destination 字段去重计数（后端已补 destination 字段）
   *  - 旅行计划数：由 renderTripPlans 返回的 total
   *  - 概览数据来源=足迹（checkins），城市数按 destination 去重
   */
  async function loadTravelOverview(containerEl, tripPlansTotal) {
    let checkinCount = 0;
    let cityCount = 0;

    if (me && me.id) {
      try {
        const checkinsData = await get('/user/checkins/' + me.id + '?current=1');
        const checkinList = Array.isArray(checkinsData) ? checkinsData : (checkinsData && checkinsData.records ? checkinsData.records : []);
        checkinCount = checkinList.length;

        // 去过城市数：打卡记录中 destination 字段去重计数
        const cities = new Set();
        checkinList.forEach(c => {
          if (c.destination) cities.add(c.destination);
        });
        cityCount = cities.size;
      } catch {
        // 静默降级，打卡数据为空
      }
    }

    const html = `
      <div class="section-title">旅行概览</div>
      <div class="overview-cards">
        <div class="overview-card">
          <div class="overview-card-num">${checkinCount}</div>
          <div class="overview-card-label">打卡景点</div>
        </div>
        <div class="overview-card">
          <div class="overview-card-num">${cityCount}</div>
          <div class="overview-card-label">去过城市</div>
        </div>
        <div class="overview-card">
          <div class="overview-card-num">${tripPlansTotal}</div>
          <div class="overview-card-label">旅行计划</div>
        </div>
      </div>`;
    containerEl.innerHTML = html;
  }

  // 加载景点标记数据
  let points = [];
  try {
    const data = await get('/map/points');
    points = Array.isArray(data) ? data : [];
  } catch {
    // 景点数据加载失败不阻塞页面——地图和其余功能仍可用
  }

  // 构建页面 HTML 结构：地图区 + 附近景点区 + 旅行概览区 + 旅行计划区
  let html = '';

  if (leafletAvailable) {
    html += `
      <div class="map-container" id="travel-map"></div>
      <div class="nearby-section" id="nearby-section">
        <div class="section-title">附近景点</div>
        <div class="nearby-scroll" id="nearby-scroll"></div>
      </div>`;
  } else {
    /** 【旅行功能的实现】Leaflet CDN 降级——地图加载失败占位 */
    html += `
      <div class="map-fallback">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
        <p>地图加载失败</p>
        <p style="font-size:12px;color:var(--text-hint);margin-top:4px">请检查网络连接后刷新页面</p>
      </div>`;
  }

  html += `
    <div id="travel-overview-area"></div>
    <div id="trip-plans-area"></div>`;
  contentEl.innerHTML = html;

  // 地图初始化
  let map = null;

  if (leafletAvailable) {
    /** 【旅行功能的实现】Leaflet 地图初始化
     * - 中心杭州（120.15, 30.26），zoom 11
     * - OSM tileLayer 瓦片
     * - 遍历 points 为每个景点创建 L.marker + popup（名称/评分，点击跳 #/attraction/{id}）
     * - moveend 事件：haversine 计算中心 5km 内景点 → 渲染"附近景点"横向卡片
     */
    const mapEl = contentEl.querySelector('#travel-map');
    if (mapEl) {
      // 确保地图容器有高度（Leaflet 要求）
      mapEl.style.height = '300px';

      map = L.map(mapEl).setView([30.26, 120.15], 11);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
        maxZoom: 18
      }).addTo(map);

      // 添加景点标记
      points.forEach(p => {
        if (p.x != null && p.y != null) {
          const marker = L.marker([p.y, p.x]).addTo(map);
          const scoreHtml = p.score != null ? '<br/>评分：' + (p.score / 10).toFixed(1) : '';
          marker.bindPopup('<b>' + escapeHtml(p.name || '景点') + '</b>' + scoreHtml + '<br/><a href="#/attraction/' + p.id + '">查看详情</a>');
        }
      });

      /** 【旅行功能的实现】附近景点渲染——根据地图中心 haversine 距离 5km 内
       * - 计算中心到每个点的距离 → 筛选 ≤5km → 排序 → 渲染横向卡片
       */
      function updateNearby() {
        const center = map.getCenter();
        const nearbyList = points
          .filter(p => p.x != null && p.y != null)
          .map(p => ({ ...p, distance: haversineKm(center.lat, center.lng, p.y, p.x) }))
          .filter(p => p.distance <= 5)
          .sort((a, b) => a.distance - b.distance);

        const scrollEl = contentEl.querySelector('#nearby-scroll');
        const sectionEl = contentEl.querySelector('#nearby-section');
        if (!scrollEl) return;

        if (!nearbyList.length) {
          if (sectionEl) sectionEl.style.display = 'none';
          return;
        }
        if (sectionEl) sectionEl.style.display = '';

        scrollEl.innerHTML = nearbyList.map(p => `
          <div class="nearby-card" data-id="${p.id}">
            ${p.image ? '<div class="nearby-card-img"><img src="' + imgUrl(p.image) + '" alt="" onerror="imgError(event)"/></div>' : ''}
            <div class="nearby-card-info">
              <div class="nearby-card-name">${escapeHtml(p.name || '未命名')}</div>
              <div class="nearby-card-dist">${p.distance.toFixed(1)}km</div>
            </div>
          </div>
        `).join('');

        // 绑定点击跳转
        scrollEl.querySelectorAll('.nearby-card').forEach(card => {
          card.addEventListener('click', () => {
            navigate('/attraction/' + card.dataset.id);
          });
        });
      }

      // 绑定 moveend 事件
      map.on('moveend', updateNearby);

      // 初始加载后立即触发一次附近景点计算
      setTimeout(updateNearby, 500);
    }
  }

  // 渲染旅行计划列表（先执行，获取 total 供旅行概览使用）
  const tripArea = contentEl.querySelector('#trip-plans-area');
  let tripPlansTotal = 0;
  if (tripArea) {
    tripPlansTotal = await renderTripPlans(tripArea);
  }

  // 【旅行概览的实现】渲染旅行概览三张小卡（地图下方）
  const overviewArea = contentEl.querySelector('#travel-overview-area');
  if (overviewArea) {
    await loadTravelOverview(overviewArea, tripPlansTotal);
  }

  /** 【旅行功能的实现】cleanup 清理函数——页面销毁时移除地图实例
   * - map.remove() 清理 Leaflet 实例，释放 DOM 和事件监听
   */
  return () => {
    if (map) {
      map.remove();
      map = null;
    }
  };
}
