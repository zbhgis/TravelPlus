/**
 * 【私信功能的实现】#/chats 会话列表页渲染模块
 * 职责：展示所有私信会话列表（头像+昵称+最新消息摘要+相对时间+未读红点），左滑显示置顶/删除操作，点击进入聊天页
 * 依赖：api.js (get/put/del)、ui.js (formatDate/imgUrl/imgError/toast)
 * 核心链路：
 *   renderChats(container)
 *     → GET /chat/sessions → 列表渲染（头像/昵称/摘要/时间/未读红点）
 *     → 空态："暂无私信会话"
 *     → 左滑 → 露出置顶/删除按钮
 *     → 点击 → #/chat/{peer.id}
 *     → 置顶/取消置顶 → PUT/DELETE /chat/pin/{peerId} → 重载列表
 *     → 删除 → DELETE /chat/session/{peerId} → 重载列表
 * - 返回按钮：history.back()
 * - 顶部标题："私信"
 */
import { get, put, del } from '../api.js';
import { formatDate, imgUrl, imgError, toast } from '../ui.js';

/** 当前活跃的左滑项——同一时刻只有一个会话打开 */
let activeSwipe = null;
/** 记录当前 render 的 container，供重载使用 */
let _container = null;

export async function renderChats(container) {
  _container = container;
  container.innerHTML = `
    <div id="chats-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#chats-root');

  try {
    const data = await get('/chat/sessions');
    const sessions = Array.isArray(data) ? data : (data && data.records ? data.records : []);

    /**
     * 【私信功能的实现】会话列表 HTML 构建
     * 实现细节：
     * - 顶部：返回按钮 + 标题"私信"
     * - 列表项：外层 .chat-session-swipe（overflow:hidden 定位容器）+ 内层 .chat-session-item（可平移 transform）
     *   + 隐藏操作区 .chat-session-actions（置顶/取消置顶、删除按钮）
     * - 空态："暂无私信会话"
     * - 样式：复用消息列表风格 + 新增左滑操作区样式
     */
    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="chats-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>私信</h2>
      </div>
      ${sessions.length === 0
        ? '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无私信会话</p></div>'
        : '<div class="chat-session-list">' +
          sessions.map(s => buildSessionItem(s)).join('') +
          '</div>'
      }
    `;

    // 返回按钮
    root.querySelector('#chats-back').addEventListener('click', () => window.history.back());

    // 会话项：绑定手势 + 点击事件
    if (sessions.length > 0) {
      setupSwipeHandlers(root);
      // 点击页面其他区域（非会话本体、非操作按钮）关闭当前左滑
      root.addEventListener('pointerdown', (e) => {
        const target = e.target;
        if (activeSwipe && !target.closest('.chat-session-item') && !target.closest('.chat-session-actions')) {
          closeActiveSwipe();
        }
      });
    }

  } catch {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}

/**
 * 【私信功能的实现】重载会话列表
 * 在置顶/取消置顶/删除操作成功后调用，重新 GET /chat/sessions 并渲染
 */
async function reloadSessions() {
  if (_container) {
    await renderChats(_container);
  }
}

/**
 * 【会话左滑的实现】构建单个会话项的 HTML（含滑动容器 + 隐藏操作区）
 * isPinned 为 true 时按钮文字为"取消置顶"
 */
function buildSessionItem(s) {
  const peerId = s.peer?.id || s.peerId || s.id;
  const peerName = s.peer?.nickName || s.peerName || s.nickName || '用户';
  const peerIcon = s.peer?.icon || s.peerIcon || s.icon;
  const lastMsg = s.lastContent || s.lastMsg || s.content || '';
  const lastTime = s.lastTime || s.lastMsgTime || s.updateTime;
  const unread = s.unread || s.unreadCount || 0;
  const isPinned = s.isPinned === true;

  const unreadClass = unread > 0 ? ' chat-session-unread' : '';
  const unreadBadge = unread > 0 ? '<span class="chat-unread-badge">' + unread + '</span>' : '';
  const pinLabel = isPinned ? '取消置顶' : '置顶';

  return `
    <div class="chat-session-swipe" data-peer-id="${escapeHtml(peerId)}">
      <div class="chat-session-item${unreadClass}" style="transform:translateX(0)">
        <img class="avatar" src="${imgUrl(peerIcon)}" alt="" onerror="imgError(event)" data-avatar-uid="${escapeHtml(peerId)}" data-avatar-icon="${imgUrl(peerIcon)}" data-avatar-nick="${escapeHtml(peerName)}"/>
        <div class="chat-session-info">
          <div class="chat-session-top">
            <span class="chat-session-name">${escapeHtml(peerName)}</span>
            <span class="chat-session-time">${formatDate(lastTime)}</span>
          </div>
          <div class="chat-session-bottom">
            <span class="chat-session-msg">${escapeHtml(lastMsg)}</span>
            ${unreadBadge}
          </div>
        </div>
      </div>
      <div class="chat-session-actions">
        <button class="chat-session-action-btn chat-session-pin-btn" data-action="pin">${escapeHtml(pinLabel)}</button>
        <button class="chat-session-action-btn chat-session-del-btn" data-action="delete">删除</button>
      </div>
    </div>`;
}

/**
 * 【会话左滑的实现】为所有会话项绑定 Pointer Events 手势
 * - pointerdown 记录起始位置
 * - pointermove 检测水平拖拽（|dx| > |dy| 且 |dx| > 阈值），平移项
 * - pointerup 根据位移决定打开/弹回
 * - 打开前关闭其他已打开项
 * - 点击已打开项 = 关闭（不导航）
 * - 点击未打开项 = 导航到聊天页
 * - 页面离开时重置状态
 */
function setupSwipeHandlers(root) {
  // 重置全局状态
  activeSwipe = null;

  const swipeContainers = root.querySelectorAll('.chat-session-swipe');
  // 计算操作区宽度（取第一个容器测量，所有项等宽）
  const firstActions = swipeContainers[0]?.querySelector('.chat-session-actions');
  const actionWidth = firstActions ? firstActions.offsetWidth : 150;

  swipeContainers.forEach(swipe => {
    const item = swipe.querySelector('.chat-session-item');
    const peerId = item.dataset.peerId || swipe.dataset.peerId;
    const pinBtn = swipe.querySelector('.chat-session-pin-btn');
    const delBtn = swipe.querySelector('.chat-session-del-btn');

    let startX = 0, startY = 0;
    let startTranslateX = 0;
    let isDragging = false;
    let pointerId = null;
    // Pointer Capture 后拖拽释放会触发一次 click（捕获使 down/up 命中同一元素），
    // 用于抑制该误触 click，避免已打开的左滑被立即关闭
    let suppressClick = false;

    /* ---- pointer events ---- */
    item.addEventListener('pointerdown', (e) => {
      // 仅处理主指针（鼠标左键/第一根手指）
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      suppressClick = false;
      startX = e.clientX;
      startY = e.clientY;
      startTranslateX = getTranslateX(item);
      isDragging = false;
      pointerId = e.pointerId;
      // 不阻止默认——让浏览器处理垂直滚动；若后续进入水平拖拽再阻止
      // Pointer Capture：捕获后指针越界（滑出 item 左边界）时
      // pointermove/pointerup 仍持续派发给 item，不会触发 pointerleave 把 transform 归零
      try {
        item.setPointerCapture(e.pointerId);
      } catch (err) {
        // 不支持 Pointer Capture 的环境（个别旧浏览器）忽略
      }
    });

    item.addEventListener('pointermove', (e) => {
      if (pointerId !== e.pointerId) return;
      const dx = e.clientX - startX;
      const dy = e.clientY - startY;

      if (!isDragging && Math.abs(dx) > 8) {
        // 判断方向：水平位移 > 垂直位移 才算水平拖拽
        if (Math.abs(dx) > Math.abs(dy)) {
          isDragging = true;
          // 关闭其他已打开的会话
          if (activeSwipe && activeSwipe !== swipe) {
            closeSwipe(activeSwipe);
          }
        } else {
          // 垂直为主：重置起点，不作为拖拽
          startX = e.clientX;
          startY = e.clientY;
          startTranslateX = getTranslateX(item);
          return;
        }
      }

      if (isDragging) {
        e.preventDefault(); // 阻止页面水平滚动
        let newX = startTranslateX + dx;
        // 夹紧：只能向左拖（负数），不超过操作区宽度
        newX = Math.max(-actionWidth, Math.min(0, newX));
        item.style.transform = 'translateX(' + newX + 'px)';
        item.style.transition = 'none';
      }
    });

    item.addEventListener('pointerup', (e) => {
      if (pointerId !== e.pointerId) return;
      pointerId = null;

      if (!isDragging) {
        // 非拖拽：如果是打开态 → 关闭；否则由 click 处理导航
        const tx = getTranslateX(item);
        if (tx < -5) {
          closeSwipe(swipe);
        }
        return;
      }

      isDragging = false;
      suppressClick = true; // 拖拽释放后 Chrome 会补发 click，抑制之
      const tx = getTranslateX(item);
      item.style.transition = 'transform 0.25s ease';

      if (tx < -(actionWidth * 0.5)) {
        // 超过一半 → 保持打开
        item.style.transform = 'translateX(' + (-actionWidth) + 'px)';
        activeSwipe = swipe;
      } else {
        // 弹回
        item.style.transform = 'translateX(0)';
        activeSwipe = null;
      }
      // 释放 Pointer Capture
      try { item.releasePointerCapture(e.pointerId); } catch (err) {}
    });

    item.addEventListener('pointerleave', () => {
      // Pointer Capture 生效后，拖拽期间指针越界不再触发本事件。
      // 若个别浏览器仍触发，不得重置 transform（否则操作区弹回，左滑失败）。
      // 这里仅做无拖拽时的指针状态安全清理。
      if (pointerId === null) return;
      if (!isDragging) {
        pointerId = null;
      }
    });

    item.addEventListener('pointercancel', () => {
      if (isDragging) {
        isDragging = false;
        pointerId = null;
        const tx = getTranslateX(item);
        item.style.transition = 'transform 0.25s ease';
        // 保持当前状态
        if (tx < -(actionWidth * 0.5)) {
          item.style.transform = 'translateX(' + (-actionWidth) + 'px)';
          activeSwipe = swipe;
        } else {
          item.style.transform = 'translateX(0)';
          activeSwipe = null;
        }
      }
      // 释放 Pointer Capture
      try { item.releasePointerCapture(e.pointerId); } catch (err) {}
    });

    /* ---- click: 导航或关闭 ---- */
    item.addEventListener('click', (e) => {
      // 拖拽释放后浏览器补发的 click，直接忽略（不关闭左滑、不导航）
      if (suppressClick) {
        suppressClick = false;
        return;
      }
      const tx = getTranslateX(item);
      if (tx < -5) {
        // 已左滑打开 → 关闭，不导航
        closeSwipe(swipe);
        return;
      }
      // 未滑动 → 进入聊天页
      if (peerId) {
        window.location.hash = '#/chat/' + peerId;
      }
    });

    /* ---- 操作按钮点击 ---- */
    if (pinBtn) {
      pinBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        handlePinAction(peerId, pinBtn);
      });
    }

    if (delBtn) {
      delBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        handleDeleteAction(peerId, delBtn);
      });
    }
  });

  /* ---- 页面离开时重置 ---- */
  const cleanup = () => {
    closeActiveSwipe();
  };
  window.addEventListener('hashchange', cleanup, { once: true });
}

/**
 * 【会话左滑的实现】获取元素当前的 translateX 值（px）
 */
function getTranslateX(el) {
  const style = el.style.transform;
  if (!style || style === 'none') return 0;
  const match = style.match(/translateX\(([-\d.]+)px\)/);
  return match ? parseFloat(match[1]) : 0;
}

/**
 * 【会话左滑的实现】关闭指定会话的左滑（弹回 translateX(0)）
 */
function closeSwipe(swipe) {
  if (!swipe) return;
  const item = swipe.querySelector('.chat-session-item');
  if (!item) return;
  item.style.transition = 'transform 0.25s ease';
  item.style.transform = 'translateX(0)';
  if (activeSwipe === swipe) {
    activeSwipe = null;
  }
}

/**
 * 【会话左滑的实现】关闭当前活跃的左滑
 */
function closeActiveSwipe() {
  if (activeSwipe) {
    closeSwipe(activeSwipe);
    activeSwipe = null;
  }
}

/**
 * 【会话左滑的实现】置顶/取消置顶操作
 * 根据按钮当前文字判断调用 PUT 还是 DELETE，成功后重载列表
 */
async function handlePinAction(peerId, btn) {
  if (!peerId) return;
  // 根据按钮文字判断当前状态
  const isPinned = btn.textContent.trim() === '取消置顶';
  btn.disabled = true;
  btn.textContent = '处理中...';

  try {
    if (isPinned) {
      await del('/chat/pin/' + peerId);
      toast('已取消置顶');
    } else {
      await put('/chat/pin/' + peerId);
      toast('已置顶');
    }
    await reloadSessions();
  } catch {
    // 错误已在 api.js request() 中 toast，这里只恢复按钮状态
    btn.textContent = isPinned ? '取消置顶' : '置顶';
  } finally {
    btn.disabled = false;
  }
}

/**
 * 【会话左滑的实现】删除会话操作
 * 调用 DELETE /chat/session/{peerId}，成功后重载列表
 */
async function handleDeleteAction(peerId, btn) {
  if (!peerId) return;
  btn.disabled = true;
  btn.textContent = '处理中...';

  try {
    await del('/chat/session/' + peerId);
    toast('会话已删除');
    await reloadSessions();
  } catch {
    btn.textContent = '删除';
  } finally {
    btn.disabled = false;
  }
}

/**
 * 【私信功能的实现】XSS 防护：HTML 实体转义
 * 实现细节：
 * - 创建临时 div.textContent 再取 innerHTML，防止昵称/消息摘要含 XSS 注入
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}
