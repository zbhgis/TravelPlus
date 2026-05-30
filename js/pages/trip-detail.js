/**
 * 【旅行功能的实现】旅行计划详情页 #/trip/:id——查看计划日程 + 删除计划
 * 职责：GET /trip/{id} 展示计划详情（标题/目的地/日期 + 每日主题&景点安排）、删除按钮（confirmDialog 二次确认）
 * 依赖：api.js (get/del)、ui.js (toast/showEmpty/showLoading/imgUrl/imgError/confirmDialog/formatDate)、router.js (navigate)、store.js (isLoggedIn)
 * 核心链路：
 *   renderTripDetail(container, params)
 *     → 登录保护
 *     → GET /trip/{id} → 渲染标题/目的地/日期 + 日程列表（第 N 天 主题 + 景点安排）
 *     → 删除按钮 → confirmDialog → DELETE /trip/{id} → navigate('/travel')
 *     → 景点名点击跳 #/attraction/{id}
 */
import { get, del } from '../api.js';
import { toast, showEmpty, showLoading, imgUrl, imgError, confirmDialog, formatDate } from '../ui.js';
import { navigate } from '../router.js';
import { isLoggedIn } from '../store.js';

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
     * - 返回结构：{title, destination, startDate, endDate, days:[{title, items:[{attractionId, note, attraction:{name,image}}]}]}
     * - 渲染标题/目的地/日期 + 日程列表
     */
    const trip = await get('/trip/' + params.id);

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
        html += '<div class="trip-detail-day">';
        html += '<div class="trip-detail-day-header">';
        html += '<span class="trip-detail-day-num">第 ' + dayNum + ' 天</span>';
        if (day.title) {
          html += '<span class="trip-detail-day-title">' + day.title + '</span>';
        }
        html += '</div>';

        if (day.items && day.items.length > 0) {
          html += '<div class="trip-detail-day-items">';
          day.items.forEach(item => {
            const attName = item.attraction?.name || '景点 #' + item.attractionId;
            const attId = item.attractionId;
            const attImg = item.attraction?.image || '';
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

  } catch {
    showEmpty(contentEl, '加载失败');
  }
}
