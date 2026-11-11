/**
 * 【旅行功能的实现】创建旅行计划页 #/trip/new——表单填写 + 每日行程编排 + 景点选择
 * 职责：标题/目的地/日期/天数表单 → 每日主题&景点安排（从景点列表选择）→ POST /trip 提交
 * 依赖：api.js (get/post)、ui.js (toast/imgUrl/imgError)、router.js (navigate)、store.js (isLoggedIn)
 * 核心链路：
 *   renderTripEdit(container)
 *     → 登录保护
 *     → 渲染表单：标题/目的地/开始日期/结束日期/天数
 *     → GET /map/points 加载景点列表供选择
 *     → 根据天数动态生成每日行程区（当天主题 + 景点选择下拉 + 已选景点列表）
 *     → 提交前校验 → POST /trip {title,destination,startDate,endDate,days:[{title,items:[{attractionId,note}]}]}
 *     → 成功 → toast + navigate('/travel')；失败 toast errorMsg
 */
import { get, post } from '../api.js';
import { toast, imgUrl, imgError } from '../ui.js';
import { navigate } from '../router.js';
import { isLoggedIn } from '../store.js';

export async function renderTripEdit(container) {
  // 登录保护
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=' + encodeURIComponent('trip/new');
    return;
  }

  container.innerHTML = `
    <div class="page-header">
      <button class="header-back" id="tripedit-back"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg></button>
      <h2>新建旅行计划</h2>
    </div>
    <div id="tripedit-content"><div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div></div>
  `;

  const backBtn = container.querySelector('#tripedit-back');
  backBtn.addEventListener('click', () => window.history.back());

  const contentEl = container.querySelector('#tripedit-content');

  // 加载景点列表数据
  let attractions = [];
  try {
    const data = await get('/map/points');
    attractions = Array.isArray(data) ? data : [];
  } catch {
    // 景点加载失败不阻塞表单渲染
  }

  /** 【旅行功能的实现】计算两个日期的天数差（+1 包含首日）
   * - 实现：(endDate - startDate) / 86400000 + 1
   * - 返回正整数，若日期无效返回 1
   */
  function calcDays(start, end) {
    if (!start || !end) return 1;
    const s = new Date(start);
    const e = new Date(end);
    const diff = Math.round((e - s) / 86400000);
    return Math.max(1, diff + 1);
  }

  /** 【旅行功能的实现】重新渲染每日行程区——根据 daysCount 动态生成
   * - 每天包含：主题输入 + 景点选择下拉 + 已选景点列表（含备注和删除）
   * - attractions 作为全局景点数据源
   */
  function renderDaySections() {
    const daysCount = parseInt(document.getElementById('trip-days')?.value || '1', 10);
    const daySectionsEl = document.getElementById('day-sections');
    if (!daySectionsEl) return;

    let html = '';
    for (let i = 0; i < daysCount; i++) {
      const dayNum = i + 1;
      html += `
        <div class="day-section" data-day="${dayNum}">
          <div class="day-header">
            <span class="trip-detail-day-num">第 ${dayNum} 天</span>
            <input type="text" class="day-theme" placeholder="当日主题，如：湖滨漫步" maxlength="30"/>
          </div>
          <div class="day-attraction-picker">
            <select class="day-attraction-select" data-day="${dayNum}">
              <option value="">选择景点...</option>
              ${attractions.map(a => '<option value="' + a.id + '">' + (a.name || '景点' + a.id) + '</option>').join('')}
            </select>
            <button class="btn btn-sm btn-primary day-add-btn" data-day="${dayNum}">+ 添加</button>
          </div>
          <div class="day-items" data-day="${dayNum}"></div>
          <div class="day-empty-hint">尚无景点安排，从上方选择景点添加</div>
        </div>
      `;
    }
    daySectionsEl.innerHTML = html;

    // 绑定添加景点按钮
    daySectionsEl.querySelectorAll('.day-add-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const day = parseInt(btn.dataset.day, 10);
        const select = daySectionsEl.querySelector('.day-attraction-select[data-day="' + day + '"]');
        const attractionId = select?.value;
        if (!attractionId) {
          toast('请先选择景点');
          return;
        }
        const attraction = attractions.find(a => String(a.id) === String(attractionId));
        addDayItem(day, attractionId, attraction?.name || '景点');
        if (select) select.value = '';
      });
    });
  }

  /** 【旅行功能的实现】向指定天添加已选景点条目
   * - 传入 day/attractionId/attractionName → 渲染条目 DOM（名称 + 备注输入 + 删除按钮）
   * - 每条目 data-attraction-id + data-day 标记便于收集数据
   */
  function addDayItem(day, attractionId, attractionName) {
    const itemsEl = contentEl.querySelector('.day-items[data-day="' + day + '"]');
    if (!itemsEl) return;

    const itemHtml = `
      <div class="day-item" data-attraction-id="${attractionId}" data-day="${day}">
        <div class="day-item-name">${attractionName}</div>
        <input type="text" class="day-item-note" placeholder="备注（选填）" maxlength="100"/>
        <button class="day-item-remove" title="移除">×</button>
      </div>
    `;

    itemsEl.insertAdjacentHTML('beforeend', itemHtml);

    // 绑定删除按钮
    const lastItem = itemsEl.lastElementChild;
    if (lastItem) {
      lastItem.querySelector('.day-item-remove').addEventListener('click', () => {
        lastItem.remove();
      });
    }
  }

  /** 【旅行功能的实现】收集表单数据——遍历每天的主题和景点安排
   * - days: [{title, items:[{attractionId, note}]}]
   * - 每天 items 可为空数组（但至少 1 天有内容才允许提交）
   */
  function collectFormData() {
    const title = document.getElementById('trip-title')?.value?.trim() || '';
    const destination = document.getElementById('trip-destination')?.value?.trim() || '';
    const startDate = document.getElementById('trip-start')?.value || '';
    const endDate = document.getElementById('trip-end')?.value || '';

    const days = [];
    const daySections = contentEl.querySelectorAll('.day-section');
    daySections.forEach(section => {
      const theme = section.querySelector('.day-theme')?.value?.trim() || '';
      const items = [];
      section.querySelectorAll('.day-item').forEach(itemEl => {
        const aId = itemEl.dataset.attractionId;
        const note = itemEl.querySelector('.day-item-note')?.value?.trim() || '';
        items.push({ attractionId: parseInt(aId, 10), note });
      });
      days.push({ title: theme, items });
    });

    return { title, destination, startDate, endDate, days };
  }

  /** 【旅行功能的实现】前端校验
   * - 标题/目的地/日期必填
   * - 日期逻辑：结束日期 ≥ 开始日期
   * - 至少 1 天有至少 1 个景点（或至少 1 天有主题）
   */
  function validate(data) {
    if (!data.title) return '请输入计划标题';
    if (!data.destination) return '请输入目的地';
    if (!data.startDate) return '请选择开始日期';
    if (!data.endDate) return '请选择结束日期';
    if (new Date(data.endDate) < new Date(data.startDate)) return '结束日期不能早于开始日期';
    // 至少 1 天有内容（有主题或有景点）
    const hasContent = data.days.some(d => d.title || (d.items && d.items.length > 0));
    if (!hasContent) return '请至少为一天添加主题或景点安排';
    return null;
  }

  /** 【旅行功能的实现】日期变化时自动更新天数
   * - 监听 start/end 日期变化 → calcDays → 更新天数输入框
   * - 天数变化后重新渲染每日行程区
   */
  function updateDaysFromDates() {
    const start = document.getElementById('trip-start')?.value;
    const end = document.getElementById('trip-end')?.value;
    const daysInput = document.getElementById('trip-days');
    if (start && end && daysInput) {
      const days = calcDays(start, end);
      daysInput.value = days;
      renderDaySections();
    }
  }

  // 渲染表单 HTML（基础信息收进一张白卡，与每日行程 day-section 卡片风格统一）
  let formHtml = `
    <div class="page-content trip-edit-form">
      <div class="trip-edit-base">
        <div class="form-group">
          <label>计划标题 <span style="color:var(--danger)">*</span></label>
          <input type="text" id="trip-title" placeholder="如：杭州三日游" maxlength="50"/>
        </div>
        <div class="form-group">
          <label>目的地 <span style="color:var(--danger)">*</span></label>
          <input type="text" id="trip-destination" placeholder="如：杭州" maxlength="30"/>
        </div>
        <div class="form-row">
          <div class="form-group" style="flex:1">
            <label>开始日期 <span style="color:var(--danger)">*</span></label>
            <input type="date" id="trip-start"/>
          </div>
          <div class="form-group" style="flex:1">
            <label>结束日期 <span style="color:var(--danger)">*</span></label>
            <input type="date" id="trip-end"/>
          </div>
        </div>
        <div class="form-group">
          <label>天数（自动计算或手动修改）</label>
          <input type="number" id="trip-days" value="1" min="1" max="30"/>
        </div>
      </div>

      <div class="section-title">每日行程安排</div>
      <div id="day-sections"></div>

      <div class="form-group" style="margin-top:20px">
        <button class="btn btn-primary btn-block" id="btn-submit-trip">创建旅行计划</button>
      </div>
    </div>
  `;

  contentEl.innerHTML = formHtml;

  // 绑定日期变化 → 自动更新天数
  const startInput = contentEl.querySelector('#trip-start');
  const endInput = contentEl.querySelector('#trip-end');
  startInput?.addEventListener('change', updateDaysFromDates);
  endInput?.addEventListener('change', updateDaysFromDates);

  // 绑定天数变化 → 重新渲染每日行程区
  const daysInput = contentEl.querySelector('#trip-days');
  daysInput?.addEventListener('change', () => {
    renderDaySections();
  });

  // 初始渲染每日行程区
  renderDaySections();

  /** 【旅行功能的实现】提交按钮——校验 + POST /trip
   * - 收集数据 → 校验 → POST → 成功 toast + navigate('/travel')
   * - 失败 toast errorMsg（由 api.js 自动处理）
   */
  const submitBtn = contentEl.querySelector('#btn-submit-trip');
  submitBtn?.addEventListener('click', async () => {
    const data = collectFormData();
    const error = validate(data);
    if (error) {
      toast(error);
      return;
    }
    try {
      submitBtn.disabled = true;
      submitBtn.textContent = '创建中...';
      const result = await post('/trip', data);
      toast('旅行计划创建成功');
      if (result && result.id) {
        navigate('/trip/' + result.id);
      } else {
        navigate('/travel');
      }
    } catch {
      // api.js 已 toast errorMsg
      submitBtn.disabled = false;
      submitBtn.textContent = '创建旅行计划';
    }
  });
}
