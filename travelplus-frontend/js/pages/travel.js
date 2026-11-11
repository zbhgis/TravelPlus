/**
 * 【旅行功能的实现】旅行页 #/travel——高德地图标记景点 + 路线规划 + 附近景点推荐 + 旅行概览 + 我的旅行计划列表
 * 职责：渲染地图区（高德 JS API 2.0 底图 + 景点标记 + InfoWindow 跳详情 + 路线模式驾车/步行规划）、附近景点横向卡片、旅行概览三张小卡（打卡景点数/去过城市数（destination去重）/旅行计划数）、旅行计划列表
 * 依赖：api.js (get/post/del)、ui.js (imgUrl/imgError/showEmpty/showLoading/toast/confirmDialog/formatDate)、store.js (isLoggedIn/getUser)、router.js (navigate)、index.html 全局注入的 AMap（key+安全密钥）
 * 核心链路：
 *   renderTravel(container)
 *     → 检查 AMap 全局变量是否可用（CDN 失败降级）
 *     → 初始化高德地图（中心杭州 120.15/30.26 zoom=12，GCJ-02 与景点坐标同源无偏移）
 *     → GET /map/points → 全量景点标记 AMap.Marker（自定义绿点）+ 点击 InfoWindow
 *     → 路线模式：点标记依次加入路线 → AMap.Driving（驾车含途经点）/AMap.Walking（步行逐段）规划 → 折线 + 总里程/时长
 *     → moveend 事件 → haversine 计算中心 5km 内景点 → 渲染"附近景点"横向卡片 + 标题栏实时计数
 *     → GET /trip/list?current=1 → 渲染旅行计划车票卡列表
 *     → GET /user/checkins/{me} → 旅行足迹条（打卡景点数/去过城市数（destination去重）/旅行计划数）
 *     → cleanup 返回函数：map.destroy() 清理地图实例
 */
import { get, del } from '../api.js';
import { imgUrl, imgError, showEmpty, showLoading, toast, formatDate } from '../ui.js';
import { isLoggedIn, getUser } from '../store.js';
import { navigate } from '../router.js';
import { attachLayerToggle } from '../map-layers.js';

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
  /** 【旅行功能的实现】检查高德 JS API 是否加载成功（CDN 降级处理）
   * - 若 window.AMap 不存在 → 地图区显示"地图加载失败"占位
   * - 其余功能（旅行概览/热门目的地/结伴/旅行计划列表）仍可用
   */
  const mapReady = typeof window.AMap !== 'undefined';
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

      let html = '<div class="tv-sec-head"><span class="tv-sec-title">我的旅行计划</span><span class="tv-sec-meta">' + total + ' 张待出发</span></div>';

      if (!list.length) {
        /* 空态：罗盘 + 虚线航线的线性插画，一句引导代替模板文案堆叠 */
        html += `
          <div class="trip-empty">
            <svg width="56" height="56" viewBox="0 0 64 64" fill="none">
              <circle cx="32" cy="32" r="24" stroke="#0E9F8A" stroke-width="2" stroke-dasharray="4 5" opacity="0.5"/>
              <circle cx="32" cy="32" r="19" stroke="#c9ced2" stroke-width="1.5"/>
              <path d="M40 24 34.5 34.5 24 40l5.5-10.5L40 24z" fill="#0E9F8A"/>
              <circle cx="32" cy="32" r="2" fill="#fff"/>
            </svg>
            <div class="trip-empty-title">还没有行程</div>
            <div class="trip-empty-hint">建一张票，从下一座城市开始</div>
          </div>`;
      } else {
        /* 车票式行程卡：主票面（标题/目的地）+ 撕票根（日期，竖虚线+打孔） */
        html += '<div class="trip-list">';
        list.forEach(item => {
          const startDate = item.startDate || '';
          const endDate = item.endDate || '';
          const stubHtml = startDate
            ? `<div class="trip-card-stub">
                 <span class="trip-card-stub-label">出发</span>
                 <span class="trip-card-date">${escapeHtml(startDate)}</span>
                 ${endDate ? '<span class="trip-card-stub-label" style="margin-top:2px">返回</span>' + '<span class="trip-card-date">' + escapeHtml(endDate) + '</span>' : ''}
               </div>`
            : '<div class="trip-card-stub"><span class="trip-card-stub-label">日期待定</span></div>';
          html += `
            <div class="trip-card" data-id="${item.id}">
              <div class="trip-card-body">
                <div class="trip-card-title">${escapeHtml(item.title || '未命名计划')}</div>
                <div class="trip-card-dest">目的地：${escapeHtml(item.destination || '未设置')}</div>
              </div>
              ${stubHtml}
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
      containerEl.innerHTML = '<div class="tv-sec-head"><span class="tv-sec-title">我的旅行计划</span></div><div class="trip-empty"><div class="trip-empty-title">加载失败</div><div class="trip-empty-hint">请稍后重试</div></div><div class="trip-new-btn-wrap"><button class="btn btn-primary btn-block" id="btn-new-trip">新建旅行计划</button></div>';
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

    /* 足迹条：去卡片化，三段数字内联（衬线数字 + 细竖线分隔） */
    const html = `
      <div class="tv-sec-head"><span class="tv-sec-title">旅行足迹</span><span class="tv-sec-meta">来自打卡记录</span></div>
      <div class="tv-footprint">
        <div class="tv-footprint-item">
          <div class="tv-footprint-num">${checkinCount}</div>
          <div class="tv-footprint-label">打卡景点</div>
        </div>
        <div class="tv-footprint-item">
          <div class="tv-footprint-num">${cityCount}</div>
          <div class="tv-footprint-label">去过城市</div>
        </div>
        <div class="tv-footprint-item">
          <div class="tv-footprint-num">${tripPlansTotal}</div>
          <div class="tv-footprint-label">旅行计划</div>
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

  if (mapReady) {
    html += `
      <div class="map-wrap">
        <div class="map-container" id="travel-map"></div>
        <!-- 【地图路线规划的实现】路线模式开关（地图右上角悬浮） -->
        <button class="route-mode-btn" id="route-mode-btn">规划路线</button>
        <!-- 【我在哪的实现】回到我的位置：静默定位蓝点标记 + 点击平移复位 -->
        <button class="locate-btn" id="locate-btn" aria-label="回到我的位置">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="7"/><line x1="12" y1="2" x2="12" y2="5"/><line x1="12" y1="19" x2="12" y2="22"/><line x1="2" y1="12" x2="5" y2="12"/><line x1="19" y1="12" x2="22" y2="12"/><circle cx="12" cy="12" r="2" fill="currentColor" stroke="none"/></svg>
        </button>
        <!-- 【地图路线规划的实现】路线浮条：停靠点芯片 + 出行方式切换 + 图上选点 + 里程/时长摘要 -->
        <div class="route-bar" id="route-bar" style="display:none">
          <div class="route-bar-head">
            <span class="route-bar-title">路线规划</span>
            <span class="route-bar-hint" id="route-hint">点击地图上的景点标记，依次加入路线</span>
          </div>
          <div class="route-stops" id="route-stops"></div>
          <div class="route-summary" id="route-summary"></div>
          <div class="route-bar-actions">
            <div class="route-mode-tabs">
              <span class="route-mode-tab active" data-mode="driving">驾车优先</span>
              <span class="route-mode-tab" data-mode="walking">步行优先</span>
              <span class="route-mode-tab" data-mode="transit">公交优先</span>
            </div>
            <div class="route-bar-tools">
              <span class="route-mode-tab route-ab-tab" id="route-ab-tab">图上选点</span>
              <span class="route-clear" id="route-clear">清空</span>
            </div>
          </div>
        </div>
      </div>
      <div class="nearby-section" id="nearby-section">
        <div class="tv-sec-head">
          <span class="tv-sec-title">附近景点</span>
          <span class="tv-sec-meta" id="nearby-meta">移动地图查看周边</span>
        </div>
        <div class="nearby-scroll" id="nearby-scroll"></div>
      </div>`;
  } else {
    /** 【旅行功能的实现】高德 JS API CDN 降级——地图加载失败占位 */
    html += `
      <div class="map-fallback">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
        <p>地图加载失败</p>
        <p style="font-size:12px;color:var(--text-hint);margin-top:4px">请检查网络连接后刷新页面</p>
      </div>`;
  }

  html += `
    <div id="travel-overview-area"></div>
    <div id="trip-plans-area"></div>
    <!-- 【旅游AI助手的实现】AI 助手悬浮入口：攻略规划场景常驻，点击进全屏对话页 -->
    <button class="ai-fab" id="ai-fab" aria-label="旅游AI助手">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 2l2.4 5.8L20 9l-4.2 3.8L17 19l-5-2.8L7 19l1.2-6.2L4 9l5.6-1.2L12 2z"/></svg>
      AI 助手
    </button>`;
  contentEl.innerHTML = html;

  // 地图初始化
  let map = null;
  /* 附近景点初始计算的延迟定时器句柄（cleanup 时清理，防销毁后触发） */
  let nearbyTimer = null;

  /* 【地图路线规划的实现】路线模式状态
   * - routeMode：是否处于路线规划模式（地图标记点击=加入路线，否则=弹 InfoWindow 详情）
   * - routeStops：已加入路线的景点 [{p, marker}]，数组顺序即途经顺序
   * - routeLines：已绘制的路线折线（重绘/退出前清理）
   * - routeModeType：'driving'（驾车，途经点一次规划）| 'walking'（步行，逐段链接规划）| 'transit'（公交，逐段链接规划）
   */
  let routeMode = false;
  let routeStops = [];
  let routeLines = [];
  let routeModeType = 'driving';

  if (mapReady) {
    /** 【旅行功能的实现】高德地图初始化（JS API 2.0，替换原 Leaflet+OSM）
     * - 中心杭州（120.15, 30.26），zoom 12；GCJ-02 坐标系与景点数据同源，标记无偏移
     * - 遍历 points 为每个景点创建自定义内容标记（.tv-pin 绿点）+ 点击 InfoWindow（名称/评分/详情）
     * - 路线模式：点标记依次加入路线 → AMap.Driving（驾车）/AMap.Walking（步行）规划 → 主题绿折线 + 里程/时长摘要
     * - moveend 事件：haversine 计算中心 5km 内景点 → 渲染"附近景点"横向卡片 + 标题栏实时计数
     */
    const mapEl = contentEl.querySelector('#travel-map');
    if (mapEl) {
      // 确保地图容器有高度（高德要求）
      mapEl.style.height = '320px';

      map = new AMap.Map(mapEl, { zoom: 12, center: [120.15, 30.26], zooms: [3, 18] });

      /* 【影像地图切换的实现】全站共享图层切换组件（卫星/标准，左上角胶囊按钮；右上角已有路线模式/准星按钮） */
      attachLayerToggle(map, mapEl);

      // 共享 InfoWindow：普通模式点击标记弹出（名称/评分/详情链接）
      const infoWindow = new AMap.InfoWindow({ offset: new AMap.Pixel(0, -12) });

      // 自定义景点标记：绿点（.tv-pin）；加入路线后切换为带序号的 .tv-pin-num
      const pinHtml = n => n ? '<span class="tv-pin tv-pin-num">' + n + '</span>' : '<span class="tv-pin"></span>';

      const pinMarkers = [];
      points.forEach(p => {
        if (p.x != null && p.y != null) {
          const marker = new AMap.Marker({
            position: [p.x, p.y],
            content: pinHtml(),
            anchor: 'center',
            zIndex: 30
          });
          marker.on('click', () => {
            /* 路线模式下点击标记 = 依序加入路线（图上选点模式下忽略）；普通模式 = 弹详情气泡 */
            if (routeMode) {
              if (abMode) return;
              addRouteStop(p, marker);
              return;
            }
            const scoreHtml = p.score != null ? '<br/>评分：' + (p.score / 10).toFixed(1) : '';
            infoWindow.setContent('<div class="amap-pin-pop"><b>' + escapeHtml(p.name || '景点') + '</b>' + scoreHtml + '<br/><a href="#/attraction/' + p.id + '">查看详情</a></div>');
            infoWindow.open(map, [p.x, p.y]);
          });
          map.add(marker);
          pinMarkers.push({ p, marker });
        }
      });

      /* ==================== 我的位置 ==================== */
      /* 【我在哪的实现】高德定位插件：进页静默定位一次，蓝点呼吸标记；右上角准星按钮随时平移复位
       * - 浏览器拒绝授权/非安全源（真机 HTTP 局域网）时定位失败：静默定位不打扰，按钮触发时 toast 提示
       * - 定位结果为高德坐标系，与底图同系无需换算
       */
      let userMarker = null;
      const geolocation = new AMap.Geolocation({ enableHighAccuracy: true, timeout: 8000, showButton: false, showMarker: false });
      function locateUser(pan) {
        geolocation.getCurrentPosition((status, result) => {
          if (status === 'complete' && result.position) {
            const pos = [result.position.lng, result.position.lat];
            if (!userMarker) {
              userMarker = new AMap.Marker({
                position: pos,
                content: '<span class="locate-dot"><i></i></span>',
                anchor: 'center',
                zIndex: 120
              });
              map.add(userMarker);
            } else {
              userMarker.setPosition(pos);
            }
            if (pan) {
              map.panTo(pos);
              if (map.getZoom() < 13) map.setZoom(13);
            }
          } else if (pan) {
            toast('定位失败，请检查浏览器定位权限');
          }
        });
      }
      locateUser(false);
      contentEl.querySelector('#locate-btn').addEventListener('click', () => locateUser(true));

      /* ==================== 路线规划 ==================== */
      const routeBar = contentEl.querySelector('#route-bar');
      const routeStopsEl = contentEl.querySelector('#route-stops');
      const routeSummaryEl = contentEl.querySelector('#route-summary');
      const routeHintEl = contentEl.querySelector('#route-hint');
      const routeModeBtn = contentEl.querySelector('#route-mode-btn');

      /** 重绘标记序号：stops 增删后同步各标记内容（未加入的恢复绿点） */
      function renumberStops() {
        pinMarkers.forEach(({ p, marker }) => {
          const idx = routeStops.findIndex(s => s.p.id === p.id);
          marker.setContent(pinHtml(idx > -1 ? idx + 1 : null));
        });
      }

      /** 清除已绘制的路线折线 */
      function clearRouteLines() {
        routeLines.forEach(line => map.remove(line));
        routeLines = [];
      }

      /** 时长格式化：分钟数 → "X分钟" / "X小时Y分" */
      function fmtDur(min) {
        return min >= 60 ? Math.floor(min / 60) + '小时' + Math.round(min % 60) + '分' : Math.max(1, Math.round(min)) + '分钟';
      }

      /** 路线摘要条：总里程/时长 +（步行模式的）每段明细 chips */
      function renderRouteSummary(totalKm, totalMin, legs) {
        let html = '总里程 <b>' + totalKm.toFixed(1) + '</b> km · 约 <b>' + fmtDur(totalMin) + '</b>';
        if (legs && legs.length) {
          html += '<div class="route-legs">' + legs.map(l =>
            '<span class="route-leg">' + escapeHtml(l.from) + ' → ' + escapeHtml(l.to) + ' ' + l.km.toFixed(1) + 'km·' + fmtDur(l.min) + '</span>'
          ).join('') + '</div>';
        }
        routeSummaryEl.innerHTML = html;
      }

      /** 加入路线停靠点：序号徽章重绘 + 芯片条重绘 + 满 2 个自动规划 */
      function addRouteStop(p, marker) {
        if (routeStops.some(s => s.p.id === p.id)) {
          toast('该景点已在路线中');
          return;
        }
        routeStops.push({ p, marker });
        renumberStops();
        renderRouteStops();
        if (routeStops.length >= 2) planRoute();
        else routeSummaryEl.innerHTML = '';
      }

      /** 停靠点芯片条渲染：首尾标"起/终"角色徽章；点击 × 移除（图上选点的标记同步移除）并按需重规划 */
      function renderRouteStops() {
        routeStopsEl.innerHTML = routeStops.map((s, i) => {
          let role = '';
          if (routeStops.length >= 2) {
            if (i === 0) role = '<em class="route-role role-start">起</em>';
            else if (i === routeStops.length - 1) role = '<em class="route-role role-end">终</em>';
          }
          return '<span class="route-stop-chip">' + role + '<i>' + (i + 1) + '</i>' + escapeHtml(s.p.name || '停靠点') + '<b class="route-stop-x" data-i="' + i + '">×</b></span>';
        }).join('');
        routeStopsEl.querySelectorAll('.route-stop-x').forEach(x => {
          x.addEventListener('click', e => {
            e.stopPropagation();
            const idx = parseInt(x.dataset.i, 10);
            const rm = routeStops[idx];
            if (rm && rm.custom && rm.marker) map.remove(rm.marker);
            routeStops.splice(idx, 1);
            renumberStops();
            renderRouteStops();
            if (routeStops.length >= 2) planRoute();
            else { clearRouteLines(); routeSummaryEl.innerHTML = ''; }
          });
        });
      }

      /** 清空停靠点：移除图上选点标记、恢复景点标记、清折线与摘要（清空/退出/切换选点模式共用） */
      function clearRouteStops() {
        routeStops.forEach(s => { if (s.custom && s.marker) map.remove(s.marker); });
        routeStops = [];
        renumberStops();
        renderRouteStops();
        clearRouteLines();
        routeSummaryEl.innerHTML = '';
      }

      /** 【地图路线规划的实现】按当前停靠点与出行方式规划路线
       * - driving：AMap.Driving 单次调用串联全部停靠点。注意 JS API 2.0 的 search 第三参是
       *   opts 对象（{waypoints: [...]}）——旧版 1.4 的「直接传数组」签名在 2.0 下会被当作 opts
       *   解析导致途经点被忽略（只规划起终点直连），这是本次修复的核心 bug；
       * - walking：步行规划不支持途经点 → 相邻两两逐段请求；
       * - transit：公交规划（AMap.Transfer）同样不支持途经点 → 逐段链接规划（demo 数据均在
       *   杭州，city 固定传杭州；跨城公交不在平台场景内）；
       * - 路径绘制：取 result.routes[0].steps[].path 合并为 Polyline（主题绿，含起终点圆滑线帽）
       */
      function planRoute() {
        clearRouteLines();
        if (routeStops.length < 2) { routeSummaryEl.innerHTML = ''; return; }
        routeSummaryEl.innerHTML = '<span class="route-planning">路线规划中...</span>';
        const ll = s => new AMap.LngLat(s.p.x, s.p.y);
        const pathToLine = path => {
          const line = new AMap.Polyline({
            path, strokeColor: '#0E9F8A', strokeWeight: 5, strokeOpacity: 0.9,
            lineJoin: 'round', lineCap: 'round', zIndex: 50
          });
          map.add(line);
          routeLines.push(line);
        };

        if (routeModeType === 'driving') {
          const driving = new AMap.Driving({ policy: 0 });
          const mid = routeStops.slice(1, -1).map(ll);
          driving.search(ll(routeStops[0]), ll(routeStops[routeStops.length - 1]),
            mid.length ? { waypoints: mid } : undefined,
            (status, result) => {
              if (status === 'complete' && result.routes && result.routes.length) {
                const r = result.routes[0];
                const path = [];
                r.steps.forEach(st => { if (Array.isArray(st.path)) path.push.apply(path, st.path); });
                pathToLine(path);
                renderRouteSummary(r.distance / 1000, r.time / 60, null);
              } else {
                routeSummaryEl.innerHTML = '<span class="route-error">驾车路线规划失败，请稍后重试</span>';
              }
            });
          return;
        }

        /* 步行/公交：不支持途经点 → 逐段链接规划，完成段数达标后统一汇总 */
        const isTransit = routeModeType === 'transit';
        const legs = [];
        let noData = 0;
        let done = 0;
        const legTotal = routeStops.length - 1;
        const label = isTransit ? '公交' : '步行';
        const makePlanner = () => isTransit
          ? new AMap.Transfer({ policy: 0, city: '杭州' })
          : new AMap.Walking({ policy: 0 });
        for (let i = 0; i < legTotal; i++) {
          const from = routeStops[i], to = routeStops[i + 1];
          /* 每段用独立 planner 实例：避免复用实例的内部状态串段（高德导航对象非线程安全语义） */
          makePlanner().search(ll(from), ll(to), (status, result) => {
            done++;
            if (status === 'complete' && result.routes && result.routes.length) {
              const r = result.routes[0];
              const path = [];
              r.steps.forEach(st => { if (Array.isArray(st.path)) path.push.apply(path, st.path); });
              pathToLine(path);
              legs.push({
                from: from.p.name || '停靠点' + (i + 1),
                to: to.p.name || '停靠点' + (i + 2),
                km: (r.distance || 0) / 1000,
                min: (r.time || 0) / 60
              });
            } else if (isTransit && status === 'no_data') {
              /* 公交短距离常见：两站间无合理公交方案（高德返回 no_data），单独计数给可操作建议 */
              noData++;
            }
            if (done === legTotal) {
              if (!legs.length) {
                /* 公交无方案在高德侧可能返回 no_data 或 error（短距离/无直达/跨城均可能），
                 * 统一给可操作建议而不是"稍后重试"——重试也不会有方案 */
                routeSummaryEl.innerHTML = isTransit
                  ? '<span class="route-error">当前停靠点之间暂无合适的公交方案（可能距离过近或无直达线路），试试驾车或步行</span>'
                  : '<span class="route-error">' + label + '路线规划失败，请稍后重试</span>';
                return;
              }
              const km = legs.reduce((s, l) => s + l.km, 0);
              const min = legs.reduce((s, l) => s + l.min, 0);
              renderRouteSummary(km, min, legs);
              if (noData) {
                routeSummaryEl.innerHTML += '<div class="route-notice">有 ' + noData + ' 段暂无公交方案，未计入</div>';
              }
            }
          });
        }
      }

      /** 出行方式切换（驾车优先/步行优先/公交优先）→ 立即重规划
       *  注意 [data-mode] 过滤：图上选点 tab 同样挂着 .route-mode-tab 类但无 data-mode，
       *  若不排除，点选点会误触发本处理器把方式改成 undefined（落到步行分支） */
      contentEl.querySelectorAll('.route-mode-tab[data-mode]').forEach(tab => {
        tab.addEventListener('click', () => {
          contentEl.querySelectorAll('.route-mode-tab').forEach(t => t.classList.remove('active'));
          tab.classList.add('active');
          routeModeType = tab.dataset.mode;
          if (routeStops.length >= 2) planRoute();
        });
      });

      /** 清空停靠点与路线（保持路线模式开启，提示语随模式切换） */
      contentEl.querySelector('#route-clear').addEventListener('click', () => {
        clearRouteStops();
        routeHintEl.textContent = abMode ? '在地图上点击设置起点，再次点击设置终点' : '点击地图上的景点标记，依次加入路线';
      });

      /** 路线模式开关：浮条显隐 + 按钮文案切换；退出时清空路线状态（含图上选点标记） */
      routeModeBtn.addEventListener('click', () => {
        routeMode = !routeMode;
        routeBar.style.display = routeMode ? '' : 'none';
        routeModeBtn.textContent = routeMode ? '退出路线' : '规划路线';
        routeModeBtn.classList.toggle('active', routeMode);
        if (!routeMode) {
          abMode = false;
          abTabEl.classList.remove('active');
          clearRouteStops();
          routeHintEl.textContent = '点击地图上的景点标记，依次加入路线';
        }
      });

      /* ==================== 图上选点（自定义起点终点） ==================== */
      /* 【图上选点的实现】路线模式子模式：点击地图空白处依次设起点(起)/终点(终)
       * - 与点选景点标记互斥：选点模式下景点标记点击无效
       * - 两点齐了自动规划；再次点击地图 = 重新开始选点
       * - 选出的点走同一条 routeStops 管线（芯片可删、驾车/步行可切）
       */
      let abMode = false;
      const abTabEl = contentEl.querySelector('#route-ab-tab');
      map.on('click', e => {
        if (!abMode || !routeMode) return;
        /* 已有成对选点 → 再点一次地图 = 重新开始选点 */
        if (routeStops.filter(s => s.custom).length >= 2) clearRouteStops();
        const isStart = routeStops.length === 0;
        const marker = new AMap.Marker({
          position: [e.lnglat.lng, e.lnglat.lat],
          content: '<span class="tv-pin tv-pin-num' + (isStart ? '' : ' tv-pin-end') + '">' + (isStart ? '起' : '终') + '</span>',
          anchor: 'center',
          zIndex: 60
        });
        map.add(marker);
        routeStops.push({
          p: { id: 'ab-' + Date.now(), name: isStart ? '图上起点' : '图上终点', x: e.lnglat.lng, y: e.lnglat.lat },
          marker,
          custom: true
        });
        renderRouteStops();
        if (routeStops.length >= 2) planRoute();
        else routeSummaryEl.innerHTML = '';
      });
      abTabEl.addEventListener('click', () => {
        abMode = !abMode;
        abTabEl.classList.toggle('active', abMode);
        routeHintEl.textContent = abMode ? '在地图上点击设置起点，再次点击设置终点' : '点击地图上的景点标记，依次加入路线';
        if (abMode) clearRouteStops();
      });

      /** 【旅行功能的实现】附近景点渲染——根据地图中心 haversine 距离 5km 内
       * - 计算中心到每个点的距离 → 筛选 ≤5km → 排序 → 渲染横向卡片 + 标题栏计数
       */
      function updateNearby() {
        /* cleanup 竞态防护：500ms 延迟触发时页面可能已销毁（map 已 destroy 置空） */
        if (!map || !map.getCenter) return;
        const center = map.getCenter();
        const nearbyList = points
          .filter(p => p.x != null && p.y != null)
          .map(p => ({ ...p, distance: haversineKm(center.lat, center.lng, p.y, p.x) }))
          .filter(p => p.distance <= 5)
          .sort((a, b) => a.distance - b.distance);

        const scrollEl = contentEl.querySelector('#nearby-scroll');
        const sectionEl = contentEl.querySelector('#nearby-section');
        if (!scrollEl) return;

        // 标题栏右侧实时计数（结构即信息：随地图移动更新）
        const metaEl = contentEl.querySelector('#nearby-meta');
        if (metaEl) {
          metaEl.textContent = nearbyList.length ? '5km 内 ' + nearbyList.length + ' 处' : '5km 内暂无景点';
        }

        if (!nearbyList.length) {
          if (sectionEl) sectionEl.style.display = 'none';
          return;
        }
        if (sectionEl) sectionEl.style.display = '';

        /* 大图卡：距离徽章悬浮图左上，名称压图底部（渐隐遮罩保证可读） */
        scrollEl.innerHTML = nearbyList.slice(0, 20).map(p => `
          <div class="nearby-card" data-id="${p.id}">
            ${p.image ? '<div class="nearby-card-img"><img src="' + imgUrl(p.image) + '" alt="" onerror="imgError(event)"/></div>' : ''}
            <span class="nearby-card-badge">${p.distance.toFixed(1)}km</span>
            <div class="nearby-card-info">
              <div class="nearby-card-name">${escapeHtml(p.name || '未命名')}</div>
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

      // 初始加载后立即触发一次附近景点计算（句柄交 cleanup 清理，防销毁后触发）
      nearbyTimer = setTimeout(updateNearby, 500);
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

  /* 【旅游AI助手的实现】悬浮球入口 → 全屏 AI 对话页 */
  contentEl.querySelector('#ai-fab')?.addEventListener('click', () => navigate('/ai-chat'));

  /** 【旅行功能的实现】cleanup 清理函数——页面销毁时销毁高德地图实例
   * - map.destroy() 释放地图容器、覆盖物与事件监听（高德 JS API 销毁接口）
   */
  return () => {
    if (nearbyTimer) {
      clearTimeout(nearbyTimer);
      nearbyTimer = null;
    }
    if (map) {
      map.destroy();
      map = null;
    }
  };
}
