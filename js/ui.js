/**
 * 【工具函数集合】通用 UI 与格式化工具模块
 * 职责：toast 轻提示、图片 URL 拼接与降级、金额/评分/日期格式化、加载/空态/错误态渲染、无限滚动 Observer 与 URL 参数读取
 * 依赖：config.js (IMG_BASE)
 * - toast：全局单例，自动排队（新消息取消旧计时器）
 * - imgUrl：兼容 /imgs/ 前缀、http 绝对路径、相对路径，自动拼接 IMG_BASE
 * - imgError：全局降级占位（SVG data URI）+ window.imgError 暴露给 HTML onerror 属性
 * - fenToYuan / scoreDisplay：后端金额（分→元）、评分（×0.1）转换
 * - formatDate：ISO 时间 → 相对时间（刚刚/x分钟前/x小时前/…）或完整日期
 * - loadMore：IntersectionObserver 无限滚动封装，rootMargin=100px 提前触发
 */
import { IMG_BASE } from './config.js';
import { isLoggedIn, getUser } from './store.js';

let toastTimer = null;

/**
 * 【toast】全局轻提示
 * - 实现：修改 #toast DOM 元素的 textContent + class，2.5 秒后自动隐藏
 * - 新 toast 覆盖旧 toast：clearTimeout 取消未完成的隐藏计时器
 */
export function toast(msg) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2500);
}

/**
 * 【imgUrl】图片路径拼接为完整 URL
 * - 实现：空值/已 http 开头直接返回；/imgs/ 前缀直接拼 IMG_BASE；其余拼 /imgs/ 前缀
 * - 注意：防止双斜杠（path.startsWith('/') 判断），否则拼接结果如 /imgs//path
 */
export function imgUrl(path) {
  if (!path) return '';
  if (path.startsWith('http')) return path;
  if (path.startsWith('/imgs/')) return IMG_BASE + path;
  return IMG_BASE + '/imgs' + (path.startsWith('/') ? path : '/' + path);
}

/**
 * 【imgError】图片加载失败全局降级
 * - 实现：清除 onerror 防死循环 → 设置灰底 + 居中 SVG 占位图标
 * - 暴露到 window.imgError 供 HTML onerror 属性引用（需 HTML 中 `onerror="imgError(event)"`）
 */
export function imgError(e) {
  e.target.onerror = null;
  e.target.style.background = '#f0f0f0';
  e.target.style.display = 'flex';
  e.target.style.alignItems = 'center';
  e.target.style.justifyContent = 'center';
  e.target.style.color = '#ccc';
  e.target.style.fontSize = '24px';
  e.target.alt = '图片加载失败';
  if (e.target.tagName === 'IMG') {
    e.target.src = 'data:image/svg+xml,' + encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/></svg>'
    );
  }
}
window.imgError = imgError;

/**
 * 【fenToYuan】金额分→元转换
 * - 实现：(fen / 100).toFixed(2)，null 返回 '0.00'
 * - 注意：后端存储单位为分（Integer），前端显示为元字符串
 */
export function fenToYuan(fen) {
  if (fen == null) return '0.00';
  return (fen / 100).toFixed(2);
}

/**
 * 【scoreDisplay】评分转换（后端 0-50 整数 → 前端 0.0-5.0）
 * - 实现：(score / 10).toFixed(1)，null 返回 '0.0'
 */
export function scoreDisplay(score) {
  if (score == null) return '0.0';
  return (score / 10).toFixed(1);
}

/**
 * 【时间格式化的实现】ISO 时间 → 绝对时间字符串（yyyy-MM-dd HH:mm:ss 秒级精度）
 * 实现细节：
 * - 一律输出绝对时间到秒：yyyy-MM-dd HH:mm:ss（不再使用"x秒前/x分钟前/刚刚"等相对逻辑）
 * - 用户要求显示具体到秒的时间而非相对时间
 * - 边界：iso 为空返回 ''；Date 解析失败返回原字符串
 * - 影响范围：所有调用点（评论时间、游记时间、消息时间、私信时间、浏览记录等）自动生效
 */
export function formatDate(iso) {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    // 若为毫秒时间戳（如浏览记录 time），new Date 也可解析
    if (isNaN(d.getTime())) return iso;
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const ss = String(d.getSeconds()).padStart(2, '0');
    return y + '-' + m + '-' + day + ' ' + hh + ':' + mm + ':' + ss;
  } catch {
    return iso;
  }
}

/**
 * 【showLoading】显示加载中状态
 * - 实现：替换 container innerHTML 为 loading 动画 + 提示文字
 */
export function showLoading(container) {
  container.innerHTML = '<div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>';
}

/**
 * 【showEmpty】显示空数据占位
 * - 实现：搜索图标 SVG + 自定义 msg 或默认"暂无数据"
 */
export function showEmpty(container, msg) {
  container.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>' + (msg || '暂无数据') + '</p></div>';
}

/**
 * 【showError】显示错误状态（含可选重试按钮）
 * - 实现：红色 × 图标 + 错误消息 + 可选重试按钮（onRetry 传入函数时显示）
 * - 注意：重试按钮使用 inline onclick 拼接，仅适合简单场景
 */
export function showError(container, msg, onRetry) {
  const retryHtml = onRetry ? '<button class="btn btn-sm btn-primary" onclick="arguments[0].stopPropagation();(' + onRetry.toString() + ')()">重试</button>' : '';
  container.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>' + (msg || '加载失败') + '</p>' + retryHtml + '</div>';
}

/**
 * 【loadMore】无限滚动封装——IntersectionObserver 自动触发加载
 * - 实现：监听 sentinelClass 元素进入视口（rootMargin=100px 提前 100px 触发）→ 取消监听 → 执行 loader()
 * - 注意：loader 执行完成后需由调用方重新插入 sentinel 以继续监听
 */
export function loadMore(container, loader, sentinelClass) {
  const sentinel = container.querySelector('.' + sentinelClass);
  if (!sentinel) return;
  const observer = new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting) {
      observer.unobserve(sentinel);
      loader();
    }
  }, { rootMargin: '100px' });
  observer.observe(sentinel);
}

/**
 * 【getUrlParam】从 hash 路由的 query 部分读取参数
 * - 实现：解析 location.hash 中 ? 之后部分，URLSearchParams.get(key)
 * - 示例：#/home?tab=notes → getUrlParam('tab') = 'notes'
 */
export function getUrlParam(key) {
  const params = new URLSearchParams(window.location.hash.split('?')[1] || '');
  return params.get(key);
}

/**
 * 【头像交互的实现】放大头像预览弹窗
 * 实现细节：
 * - 创建半透明遮罩（复用 modal-mask 样式）+ 居中卡片，显示大图（200px 圆角方形）+ 昵称 +（若是自己）"这是您自己"标签
 * - 点击遮罩背景或「关闭」按钮关闭弹窗（fadeOut 动画后移除 DOM）
 * - 大图加载失败走全局 imgError 降级
 * - 确保同一时间只有一个预览弹窗（多次调用移除上一个）
 */
export function showAvatarPreview(icon, nickName, isSelf) {
  // 移除已存在的预览弹窗（确保单一实例）
  const existing = document.querySelector('.avatar-preview-mask');
  if (existing) existing.remove();

  const mask = document.createElement('div');
  mask.className = 'avatar-preview-mask';
  mask.innerHTML = `
    <div class="avatar-preview-card">
      <img class="avatar-preview-img" src="${icon}" alt="" onerror="imgError(event)"/>
      <div class="avatar-preview-name">${nickName || '用户'}</div>
      ${isSelf ? '<div class="avatar-preview-self-tag">这是您自己</div>' : ''}
      <button class="avatar-preview-close">关闭</button>
    </div>
  `;

  // 点击遮罩背景 → 关闭
  mask.addEventListener('click', (e) => {
    if (e.target === mask) {
      closeAvatarPreview(mask);
    }
  });

  // 关闭按钮
  mask.querySelector('.avatar-preview-close').addEventListener('click', () => {
    closeAvatarPreview(mask);
  });

  document.body.appendChild(mask);
  // 入场动画：下一帧添加 .show 类触发 CSS transition
  requestAnimationFrame(() => {
    mask.classList.add('show');
  });
}

/** 【头像交互的实现】关闭头像预览弹窗并移除 DOM */
function closeAvatarPreview(mask) {
  mask.classList.remove('show');
  setTimeout(() => {
    if (mask.parentNode) mask.remove();
  }, 220);
}

/** 【头像交互的实现】全局事件委托——统一处理所有头像点击
 * 实现细节：
 * - 使用 capture 阶段监听 document click，优先于卡片/区域点击处理
 * - 命中 [data-avatar-uid] 元素或其子元素 → e.stopPropagation() 阻止冒泡到卡片/父级
 * - 获取 data-avatar-uid（用户 ID）、data-avatar-icon（头像 URL，可选，img 自动取 src）、data-avatar-nick（昵称，可选）
 * - uid === 当前登录用户 id → showAvatarPreview(icon, nickName, true)（"这是您自己"标签）
 * - uid !== 当前用户 → window.location.hash = '#/user/' + uid（跳主页）
 * - 未登录时点击他人头像照常跳转（访客模式可查看他人主页）
 */
export function initAvatarDelegate() {
  document.addEventListener('click', function(e) {
    // 查找最近的可标记元素（支持点击 img 内部或父级 span）
    const avatarEl = e.target.closest('[data-avatar-uid]');
    if (!avatarEl) return;

    // 阻止冒泡：防止触发父级卡片点击（如 note-card → #/note/{id}）
    e.stopPropagation();
    e.preventDefault();

    const uid = avatarEl.dataset.avatarUid;
    if (!uid) return;

    // 头像图标 URL：优先取 data-avatar-icon，其次从 img 子元素/自身 src 获取
    let icon = avatarEl.dataset.avatarIcon || '';
    if (!icon) {
      const img = avatarEl.tagName === 'IMG' ? avatarEl : avatarEl.querySelector('img');
      if (img) icon = img.src;
    }

    // 昵称：优先取 data-avatar-nick，其次 title/alt
    const nickName = avatarEl.dataset.avatarNick || avatarEl.getAttribute('title') || avatarEl.getAttribute('alt') || '';

    const me = getUser();
    if (me && String(me.id) === String(uid)) {
      // 点击自己头像 → 放大预览
      showAvatarPreview(icon, nickName || me.nickName || '我', true);
    } else {
      // 点击他人头像 → 跳转主页
      window.location.hash = '#/user/' + uid;
    }
  }, true); // capture 阶段：优先于卡片/区域的 click 冒泡处理
}

/**
 * 【confirmDialog】确认对话框
 * 实现细节：
 * - 创建半透明遮罩（.modal-mask）+ 圆角卡片（.modal-card），包含标题/文案/取消/确认按钮
 * - 确认按钮使用 --primary 主题色，与设计 token 一致
 * - 返回 Promise<boolean>：确认 resolve(true)、取消或遮罩点击 resolve(false)
 * - 对话框居中显示，带 fadeIn 入场动画，关闭时 fadeOut + 自动移除 DOM
 * - 支持异步 await 调用：`const ok = await confirmDialog('删除', '确定要删除吗？'); if(!ok) return;`
 * - 确保同一时间只有一个对话框（多次调用时移除上一个）
 */
export function confirmDialog(title, message) {
  return new Promise((resolve) => {
    // 移除已存在的对话框（确保单一实例）
    const existing = document.querySelector('.modal-mask');
    if (existing) existing.remove();

    // 构建遮罩 + 卡片
    const mask = document.createElement('div');
    mask.className = 'modal-mask';

    mask.innerHTML = `
      <div class="modal-card">
        <div class="modal-title">${title}</div>
        <div class="modal-body">${message}</div>
        <div class="modal-footer">
          <button class="modal-btn modal-btn-cancel">取消</button>
          <button class="modal-btn modal-btn-confirm">确认</button>
        </div>
      </div>
    `;

    // 取消按钮：resolve(false) + 关闭
    mask.querySelector('.modal-btn-cancel').addEventListener('click', () => {
      closeModal(mask, () => resolve(false));
    });

    // 确认按钮：resolve(true) + 关闭
    mask.querySelector('.modal-btn-confirm').addEventListener('click', () => {
      closeModal(mask, () => resolve(true));
    });

    // 点击遮罩（卡片外部）：取消
    mask.addEventListener('click', (e) => {
      if (e.target === mask) {
        closeModal(mask, () => resolve(false));
      }
    });

    document.body.appendChild(mask);

    // 入场动画：下一帧添加 .show 类触发 CSS transition
    requestAnimationFrame(() => {
      mask.classList.add('show');
    });
  });
}

/**
 * 【弹窗组件的实现】关闭对话框并移除 DOM
 * - 先移除 .show 类触发 fadeOut 动画
 * - 动画结束后移除元素并执行回调（resolve）
 */
function closeModal(mask, callback) {
  mask.classList.remove('show');
  // 等待 CSS transition 结束（opacity 0.2s）后移除 DOM
  setTimeout(() => {
    if (mask.parentNode) mask.remove();
    callback();
  }, 220);
}
