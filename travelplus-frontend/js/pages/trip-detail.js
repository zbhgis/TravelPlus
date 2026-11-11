/**
 * 【旅行功能的实现】旅行计划详情页 #/trip/:id——查看计划日程 + 本日路线 + 删除计划
 * 职责：GET /trip/{id} 展示计划详情（标题/目的地/日期 + 每日主题&景点安排）、每日"🧭 本日路线"按钮（内嵌高德地图按游玩顺序驾车规划）、删除按钮（confirmDialog 二次确认）
 * 依赖：api.js (get/del)、ui.js (toast/showEmpty/showLoading/imgUrl/imgError/confirmDialog/formatDate)、router.js (navigate)、store.js (isLoggedIn)、index.html 全局注入的 AMap
 * 核心链路：
 *   renderTripDetail(container, params)
 *     → 登录保护
 *     → GET /trip/{id} → 渲染标题/目的地/日期 + 日程列表（第 N 天 主题 + 景点安排 + 有坐标景点≥2 时显示"本日路线"按钮）
 *     → 本日路线：懒加载展开内嵌高德地图 → 序号标记按 sort 顺序串联 + AMap.Driving 途经点规划 → 折线 + 全程里程/时长
 *     → 删除按钮 → confirmDialog → DELETE /trip/{id} → navigate('/travel')
 *     → 景点名点击跳 #/attraction/{id}
 *     → cleanup 返回函数：销毁本页全部高德地图实例
 */
import { get, del } from '../api.js';
import { toast, showEmpty, showLoading, imgUrl, imgError, confirmDialog, formatDate } from '../ui.js';
import { navigate } from '../router.js';
import { isLoggedIn } from '../store.js';
import { attachLayerToggle } from '../map-layers.js';

export async function renderTripDetail(container, params) {
  // 登录保护
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('trip/' + params.id);
    return;
  }

  container.innerHTML = '<div class="page-header"><button class="header-back" id="td-back"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg></button><h2>计划详情</h2></div><div id="td-content"><div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div></div>';

  const backBtn = container.querySelector('#td-back');
  backBtn.addEventListener('click', () => window.history.back());

  const contentEl = container.querySelector('#td-content');

  try {
    /** 【旅行功能的实现】GET /trip/{id} 加载计划详情
     * - 返回结构：{title, destination, startDate, endDate, days:[{title, items:[{attractionId, note, attractionName, attractionImage}]}]}
     * - 渲染标题/目的地/日期 + 日程列表
     */
    const trip = await get('/trip/' + params.id);

    /* 【地图路线规划的实现】记录本页创建的高德地图实例，页面销毁时统一 destroy（router cleanup）
     * 注意声明在 try 作用域：buildDayRoute 与 cleanup 都要访问 */
    const dayMaps = [];

    let html = '<div class="page-content trip-detail">';

    // 计划头部信息
    html += '<div class="trip-detail-header">';
    html += '<div class="trip-detail-title">' + (trip.title || '未命名计划') + '</div>';
    html += '<div class="trip-detail-meta">';
    html += '<div class="trip-detail-item"><span style="color:var(--text-secondary)">目的地：</span>' + (trip.destination || '未设置') + '</div>';
    html += '<div class="trip-detail-item"><span style="color:var(--text-secondary)">日期：</span>' + (trip.startDate || '') + (trip.startDate && trip.endDate ? ' ~ ' : '') + (trip.endDate || '') + '</div>';
    html += '</div>';
    html += '</div>';

    // 每日行程列表
    if (trip.days && trip.days.length > 0) {
      html += '<div class="trip-detail-days">';
      trip.days.forEach((day, index) => {
        const dayNum = index + 1;
        /* 【地图路线规划的实现】有坐标的景点 ≥2 个 → 日程头显示"本日路线"按钮（内嵌高德路线图） */
        const coordItems = (day.items || []).filter(it => it.attractionX != null && it.attractionY != null);
        const canRoute = coordItems.length >= 2 && typeof window.AMap !== 'undefined';
        html += '<div class="trip-detail-day">';
        html += '<div class="trip-detail-day-header">';
        html += '<span class="trip-detail-day-num">第 ' + dayNum + ' 天</span>';
        if (day.title) {
          html += '<span class="trip-detail-day-title">' + day.title + '</span>';
        }
        if (canRoute) {
          html += '<span class="trip-day-route-btn" data-day-idx="' + dayNum + '">🧭 本日路线</span>';
        }
        html += '</div>';

        if (day.items && day.items.length > 0) {
          html += '<div class="trip-detail-day-items">';
          day.items.forEach(item => {
            /* 后端返回扁平字段 attractionName/attractionImage（非嵌套 attraction 对象） */
            const attName = item.attractionName || '景点 #' + item.attractionId;
            const attId = item.attractionId;
            const attImg = item.attractionImage || '';
            html += '<div class="trip-detail-item-row" data-attraction-id="' + attId + '">';
            if (attImg) {
              html += '<img class="trip-detail-item-img" src="' + imgUrl(attImg) + '" alt="" onerror="imgError(event)"/>';
            }
            html += '<div class="trip-detail-item-info">';
            html += '<div class="trip-detail-item-name">' + attName + '</div>';
            if (item.note) {
              html += '<div class="trip-detail-item-note">' + item.note + '</div>';
            }
            html += '</div>';
            html += '<div class="trip-detail-item-arrow"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg></div>';
            html += '</div>';
          });
          html += '</div>';
        } else {
          html += '<div class="trip-detail-day-empty">暂无景点安排</div>';
        }
        /* 【地图路线规划的实现】内嵌本日路线图容器（点击"本日路线"展开，懒加载初始化高德地图+驾车规划） */
        if (canRoute) {
          html += '<div class="trip-day-route-area" id="trip-day-route-' + dayNum + '" style="display:none"></div>';
        }
        html += '</div>';
      });
      html += '</div>';
    } else {
      html += '<div class="empty-state"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无行程安排</p></div>';
    }

    // 删除按钮
    html += '<div class="trip-detail-actions">';
    html += '<button class="btn btn-danger btn-block" id="btn-delete-trip">删除此计划</button>';
    html += '</div>';

    html += '</div>';

    contentEl.innerHTML = html;

    // 绑定景点点击跳转
    contentEl.querySelectorAll('.trip-detail-item-row').forEach(row => {
      row.addEventListener('click', () => {
        navigate('/attraction/' + row.dataset.attractionId);
      });
    });

    /** 【旅行功能的实现】删除计划按钮——confirmDialog 二次确认
     * - 确认 → DELETE /trip/{id} → 成功 toast + navigate('/travel')
     * - 取消 → 不做任何操作
     */
    const deleteBtn = contentEl.querySelector('#btn-delete-trip');
    deleteBtn?.addEventListener('click', async () => {
      const ok = await confirmDialog('删除计划', '确定要删除此旅行计划吗？删除后不可恢复。');
      if (!ok) return;
      try {
        await del('/trip/' + params.id);
        toast('计划已删除');
        navigate('/travel');
      } catch {
        // api.js 已 toast errorMsg
      }
    });

    /* 【地图路线规划的实现】"本日路线"展开/收起（懒加载：首次展开时初始化高德地图并规划驾车路线） */
    contentEl.querySelectorAll('.trip-day-route-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const dayIdx = parseInt(btn.dataset.dayIdx, 10);
        const area = contentEl.querySelector('#trip-day-route-' + dayIdx);
        if (!area) return;
        if (area.style.display !== 'none') {
          area.style.display = 'none';
          return;
        }
        area.style.display = '';
        if (!area.dataset.inited) {
          area.dataset.inited = '1';
          buildDayRoute(dayIdx, area);
        }
      });
    });

    /** 【地图路线规划的实现】构建"本日路线"：序号标记按当日安排顺序串联 + 驾车路线（途经点一次规划）+ 摘要
     * - 坐标来源：GET /trip/{id} items 的 attractionX/attractionY（GCJ-02，与高德底图同系）
     * - 路线顺序 = 当日 items 的 sort 顺序（即用户攻略中的游玩顺序）
     */
    function buildDayRoute(dayIdx, area) {
      const day = trip.days[dayIdx - 1];
      const pts = (day.items || []).filter(it => it.attractionX != null && it.attractionY != null);
      area.innerHTML = '<div class="trip-day-route-summary" id="tdr-summary-' + dayIdx + '">路线规划中...</div><div class="trip-day-map" id="tdr-map-' + dayIdx + '"></div>';
      const mapEl = area.querySelector('#tdr-map-' + dayIdx);
      mapEl.style.height = '240px';
      const map = new AMap.Map(mapEl, { zoom: 12, center: [pts[0].attractionX, pts[0].attractionY], zooms: [3, 18] });
      dayMaps.push(map);

      /* 【影像地图切换的实现】全站共享图层切换组件（卫星/标准，左上角胶囊按钮） */
      attachLayerToggle(map, mapEl);

      const ll = it => new AMap.LngLat(it.attractionX, it.attractionY);
      pts.forEach((it, i) => {
        const marker = new AMap.Marker({
          position: [it.attractionX, it.attractionY],
          content: '<span class="tv-pin tv-pin-num">' + (i + 1) + '</span>',
          anchor: 'center',
          zIndex: 30,
          title: it.attractionName || ''
        });
        marker.on('click', () => navigate('/attraction/' + it.attractionId));
        map.add(marker);
      });
      const driving = new AMap.Driving({ policy: 0 });
      /* JS API 2.0 修复：search 第三参是 opts 对象（{waypoints}）——直接传数组（1.4 旧签名）
       * 在 2.0 下途经点会被忽略，多景点计划只规划首尾直连 */
      const mid = pts.slice(1, -1).map(ll);
      driving.search(ll(pts[0]), ll(pts[pts.length - 1]),
        mid.length ? { waypoints: mid } : undefined, (status, result) => {
        const summaryEl = area.querySelector('#tdr-summary-' + dayIdx);
        if (status === 'complete' && result.routes && result.routes.length) {
          const r = result.routes[0];
          const path = [];
          r.steps.forEach(st => { if (Array.isArray(st.path)) path.push.apply(path, st.path); });
          map.add(new AMap.Polyline({
            path, strokeColor: '#0E9F8A', strokeWeight: 5, strokeOpacity: 0.9,
            lineJoin: 'round', lineCap: 'round', zIndex: 50
          }));
          const min = Math.round(r.time / 60);
          const durTxt = min >= 60 ? Math.floor(min / 60) + '小时' + (min % 60) + '分' : min + '分钟';
          summaryEl.innerHTML = '全程 <b>' + (r.distance / 1000).toFixed(1) + '</b> km · 约 <b>' + durTxt + '</b> · ' + pts.length + ' 个景点按游玩顺序串联';
          map.setFitView(null, false, [40, 40, 40, 40]);
        } else {
          summaryEl.innerHTML = '路线规划失败，请稍后重试';
        }
      });
    }

    /** 【地图路线规划的实现】router cleanup：页面销毁时销毁本页全部高德地图实例 */
    return () => {
      dayMaps.forEach(m => { m.destroy(); });
      dayMaps.length = 0;
    };

  } catch {
    showEmpty(contentEl, '加载失败');
  }
}
