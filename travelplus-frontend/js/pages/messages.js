/**
 * 【消息中心的实现】#/messages 消息通知页面渲染模块（三分类改版 + 私信会话列表 + 独立分类页 + 微信式左滑操作）
 * 职责：顶部三个分类圈（赞和收藏/新增关注/评论和@）、三圈下方私信会话列表（左滑露出置顶/删除按钮）、三圈点击进入独立分类详情页
 * 依赖：api.js (get/put/del)、ui.js (imgUrl/imgError/formatDate/showEmpty/ toast/showLoading/loadMore)、router.js (setMessagesBadge/refreshMessagesBadge/navigate)
 * 核心链路：
 *   renderMessages(container) — 消息页本体（三圈 + 私信会话列表，小红书式布局：三圈在上 + 私信会话在下）
 *     → 并行加载：GET /notify/unread-count + GET /chat/sessions
 *     → 渲染三圈（点击 → #/messages/likes/follows/comments 独立页）
 *     → 渲染私信会话列表（微信式左滑露出置顶/删除按钮，后端 isPinned）
 *     → 不再内嵌默认分类列表（点圈进独立分类页），赞和收藏分类不跑到会话下面
 *     → 进入页面不自动全部已读：三圈未读角标保留显示，点击分类圈进详情页时才已读（详情页 PUT /notify/read）
 *     → 5 秒轮询 refreshSessions() 自动刷新会话列表
 *
 *   renderMessageCategory(container, category) — 独立分类详情页（#/messages/:category）
 *     → 标题（"赞和收藏"/"新增关注"/"评论和@"）+ 返回按钮
 *     → GET /notify/list?category= → 消息列表（谁/时间/哪个笔记）
 *     → 空态"暂无此类消息"
 *     → 消息点击跳转（like/collect/comment/reply/mention → #/note/{targetId}，follow → #/user/{targetId}）
 *
 *   【消息中心的实现】GET /chat/sessions 返回嵌套 peer 对象（{peer:{id,nickName,icon}, lastContent, lastTime, unread, isPinned}），
 *   前端取 peer.id 作为 peerId（点击跳 #/chat/{peerId}、删除/置顶均用），peer.nickName/peer.icon 渲染昵称/头像
 *   【私信功能的实现】左滑手势：Pointer Events + setPointerCapture + 互斥 activeSwipe + 半滑弹回 + suppressClick
 *   【私信功能的实现】置顶 → PUT /chat/pin/{peerId}；取消置顶 → DELETE /chat/pin/{peerId}；删除 → DELETE /chat/session/{peerId}
 *
 * 三分类说明（参考小红书设计）：
 *   - likes：「赞和收藏」— type: like + collect → category=likes
 *   - follows：「新增关注」— type: follow → category=follows
 *   - comments：「评论和@」— type: comment + reply + mention → category=comments
 */
import { get, put, del } from '../api.js';
import { imgUrl, imgError, formatDate, showEmpty, toast, showLoading, loadMore } from '../ui.js';
import { setMessagesBadge, refreshMessagesBadge, navigate } from '../router.js';
import { isLoggedIn, getUser } from '../store.js';

/** 【AI客服的实现】客服虚拟用户 ID（与 support.js 的 CUSTOMER_ID 常量一致，peerId 来自 DOM dataset 为字符串） */
const CUSTOMER_ID = '1021';

/**
 * 【消息中心的实现】防 XSS 转义工具
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}

/**
 * 【消息中心的实现】获取消息类型的 SVG 图标
 */
function getNotifyIcon(type) {
  const icons = {
    like: '<svg width="20" height="20" viewBox="0 0 24 24" fill="#f87171" stroke="#f87171" stroke-width="1.5"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>',
    collect: '<svg width="20" height="20" viewBox="0 0 24 24" fill="#0E9F8A" stroke="#0E9F8A" stroke-width="1.5"><path d="m19 21-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>',
    follow: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.5"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" y1="8" x2="19" y2="14"/><line x1="22" y1="11" x2="16" y2="11"/></svg>',
    comment: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
    reply: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.5"><polyline points="9 14 4 9 9 4"/><path d="M20 20v-7a4 4 0 0 0-4-4H4"/></svg>',
    mention: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="1.5"><circle cx="12" cy="12" r="4"/><path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-3.92 7.94"/></svg>'
  };
  return icons[type] || icons.comment;
}

/**
 * 【消息中心的实现】获取分类圈的 SVG 图标
 */
function getCategoryIcon(category) {
  const icons = {
    likes: '<svg width="32" height="32" viewBox="0 0 24 24" fill="#f87171" stroke="#f87171" stroke-width="1.2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>',
    follows: '<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.5"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" y1="8" x2="19" y2="14"/><line x1="22" y1="11" x2="16" y2="11"/></svg>',
    comments: '<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="1.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>'
  };
  return icons[category] || icons.likes;
}

/**
 * 【消息中心的实现】获取分类页标题
 */
function getCategoryTitle(category) {
  const titles = { likes: '赞和收藏', follows: '新增关注', comments: '评论和@' };
  return titles[category] || '消息';
}

/**
 * 【消息中心的实现】分类圈配置
 */
const categoriesConfig = [
  { key: 'likes', label: '赞和收藏' },
  { key: 'follows', label: '新增关注' },
  { key: 'comments', label: '评论和@' }
];

/**
 * 【分类未读的实现】分类圈角标数字文案：1-9 显示数字；10-99 显示"9+"；≥100 显示"99+"
 * （小红书式封顶角标，避免数字过长撑破圆点）
 */
function formatBadgeText(n) {
  if (n >= 100) return '99+';
  if (n >= 10) return '9+';
  return String(n);
}

/**
 * 【消息中心的实现】渲染三分类圈 HTML（含分类未读角标）
 * 【分类未读的实现】按 data-cat 从 categoryUnreadCount 结果取各分类未读数：
 * - 未读 0 → 不输出角标（隐藏）
 * - 未读 1-9 → .msg-cat-badge 红色圆点内显示数字
 * - 未读 ≥10 → 显示"9+"（≥100 显示"99+"），角标加 .msg-cat-badge-lg 加宽
 * @param catUnread Map{likes, follows, comments}（接口失败时前端兜底全 0）
 */
function renderCategoryCircles(catUnread) {
  const counts = catUnread || {};
  return '<div class="msg-categories" id="msg-categories">' +
    categoriesConfig.map(cat => {
      const n = Number(counts[cat.key]) || 0;
      const badgeHtml = n > 0
        ? '<span class="msg-cat-badge' + (n >= 10 ? ' msg-cat-badge-lg' : '') + '">' + formatBadgeText(n) + '</span>'
        : '';
      return '<div class="msg-cat-item" data-cat="' + cat.key + '">' +
        badgeHtml +
        '<div class="msg-cat-circle">' + getCategoryIcon(cat.key) + '</div>' +
        '<span class="msg-cat-label">' + cat.label + '</span>' +
      '</div>';
    }).join('') +
    '</div>';
}

/**
 * 【消息中心的实现】渲染通知消息列表（用于独立分类页 + 消息页本体）
 * - 谁/时间/哪个笔记 格式："{userName} {action}了你的笔记「{noteTitle}」"
 * - 空列表显示"暂无此类消息"
 */
function renderMessageList(msgList) {
  if (!msgList.length) {
    return '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>暂无此类消息</p></div>';
  }
  return '<div class="message-list" id="msg-list">' +
    msgList.map(item => {
      /* 【通知实时昵称的实现】displayText 由后端实时拼装"触发者昵称+动作"（改昵称后历史通知自动显示新昵称，小红书方案）；旧数据 content 兜底 */
      return `
        <div class="message-item${item.isRead !== true ? ' unread' : ''}" data-id="${item.id}" data-type="${item.type || ''}" data-target="${item.targetId || ''}">
          <div class="message-icon">${getNotifyIcon(item.type)}</div>
          <div class="message-info">
            <div class="message-content">${escapeHtml(item.displayText || item.content || '')}</div>
            <div class="message-time">${formatDate(item.createTime)}</div>
          </div>
        </div>`;
    }).join('') +
    '</div>';
}

/**
 * 【消息中心的实现】绑定消息项点击跳转
 */
function bindMessageClicks(containerEl) {
  (containerEl || document).querySelectorAll('.message-item').forEach(item => {
    item.addEventListener('click', () => {
      const type = item.dataset.type;
      const targetId = item.dataset.target;
      if (!targetId) return;
      if (type === 'follow') {
        window.location.hash = '#/user/' + targetId;
      } else {
        window.location.hash = '#/note/' + targetId;
      }
    });
  });
}

// ============================================================
//  私信会话列表：微信式左滑操作（与 #/chats 页一致）
// ============================================================

/** 当前活跃的左滑项——同一时刻只有一个会话打开 */
let activeSwipe = null;
/** 记录当前 render 的 root 元素，供刷新重绑定使用 */
let _root = null;

/**
 * 【会话左滑的实现】构建单个会话项的 HTML（含滑动容器 + 隐藏操作区）
 * - 会话行头像不设 data-avatar-uid——避免全局头像委托拦截点击导致无法跳转聊天页
 * - isPinned 为 true 时按钮文字为"取消置顶"
 * - 排序由后端完成（置顶组在前），前端不自排序
 */
function buildSessionItem(s) {
  const peerId = s.peer?.id || '';
  const peerName = s.peer?.nickName || '用户';
  const peerIcon = s.peer?.icon || '';
  const lastMsg = s.lastContent || '';
  const lastTime = s.lastTime;
  const unread = s.unread || 0;
  const isPinned = s.isPinned === true;

  const unreadClass = unread > 0 ? ' chat-session-unread' : '';
  const unreadBadge = unread > 0 ? '<span class="chat-unread-badge">' + unread + '</span>' : '';
  const pinLabel = isPinned ? '取消置顶' : '置顶';

  return `
    <div class="chat-session-swipe" data-peer-id="${escapeHtml(peerId)}">
      <div class="chat-session-item${unreadClass}" style="transform:translateX(0)">
        <img class="avatar" src="${imgUrl(peerIcon)}" alt="" onerror="imgError(event)"/>
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
 * 【私信功能的实现】渲染私信会话列表（微信式左滑样式）
 * - 排序由后端完成（isPinned 置顶组在前)，前端不自排序
 * - 每行：头像 + 昵称 + 最新消息摘要 + 时间 + 未读红点
 * - 空态"暂无会话，去主页找人聊聊吧"
 */
function renderChatSessionList(sessions) {
  const sessionsArr = Array.isArray(sessions) ? sessions : (sessions && sessions.records ? sessions.records : []);

  if (!sessionsArr.length) {
    return '<div class="empty-state"><svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg><p>暂无会话</p><p style="font-size:12px;color:var(--text-hint);margin-top:4px">去主页找人聊聊吧</p></div>';
  }

  return '<div class="chat-session-list" id="chat-session-list">' +
    sessionsArr.map(s => buildSessionItem(s)).join('') +
    '</div>';
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
 * 根据按钮当前文字判断调用 PUT 还是 DELETE，成功后重渲染
 */
async function handlePinAction(peerId, btn) {
  if (!peerId) return;
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
    await refreshSessions();
  } catch {
    btn.textContent = isPinned ? '取消置顶' : '置顶';
  } finally {
    btn.disabled = false;
  }
}

/**
 * 【会话左滑的实现】删除会话操作（无 confirmDialog，与 chats.js 一致）
 * 调用 DELETE /chat/session/{peerId}，成功后重渲染
 */
async function handleDeleteAction(peerId, btn) {
  if (!peerId) return;
  btn.disabled = true;
  btn.textContent = '处理中...';

  try {
    await del('/chat/session/' + peerId);
    toast('会话已删除');
    await refreshSessions();
  } catch {
    btn.textContent = '删除';
  } finally {
    btn.disabled = false;
  }
}

/**
 * 【会话左滑的实现】为所有会话项绑定 Pointer Events 手势
 * - pointerdown 记录起始位置
 * - pointermove 检测水平拖拽（|dx| > |dy| 且 |dx| > 阈值），平移项
 * - pointerup 根据位移决定打开/弹回
 * - 打开前关闭其他已打开项
 * - 点击已打开项 = 关闭（不导航）
 * - 点击未打开项 = 导航到聊天页（客服 1021 → #/support，普通 → #/chat/{peerId}）
 * - 点击页面其他区域关闭当前左滑
 */
function setupSwipeHandlers(root) {
  // 重置全局状态
  activeSwipe = null;

  const swipeContainers = root.querySelectorAll('.chat-session-swipe');
  if (!swipeContainers.length) return;

  // 计算操作区宽度（取第一个容器测量，所有项等宽）
  const firstActions = swipeContainers[0].querySelector('.chat-session-actions');
  const actionWidth = firstActions ? firstActions.offsetWidth : 140;

  swipeContainers.forEach(swipe => {
    const item = swipe.querySelector('.chat-session-item');
    const peerId = swipe.dataset.peerId;
    const pinBtn = swipe.querySelector('.chat-session-pin-btn');
    const delBtn = swipe.querySelector('.chat-session-del-btn');

    let startX = 0, startY = 0;
    let startTranslateX = 0;
    let isDragging = false;
    let pointerId = null;
    let suppressClick = false;

    /* ---- pointer events ---- */
    item.addEventListener('pointerdown', (e) => {
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      suppressClick = false;
      startX = e.clientX;
      startY = e.clientY;
      startTranslateX = getTranslateX(item);
      isDragging = false;
      pointerId = e.pointerId;
      try {
        item.setPointerCapture(e.pointerId);
      } catch (err) {
        // 不支持 Pointer Capture 的环境忽略
      }
    });

    item.addEventListener('pointermove', (e) => {
      if (pointerId !== e.pointerId) return;
      const dx = e.clientX - startX;
      const dy = e.clientY - startY;

      if (!isDragging && Math.abs(dx) > 8) {
        if (Math.abs(dx) > Math.abs(dy)) {
          isDragging = true;
          if (activeSwipe && activeSwipe !== swipe) {
            closeSwipe(activeSwipe);
          }
        } else {
          startX = e.clientX;
          startY = e.clientY;
          startTranslateX = getTranslateX(item);
          return;
        }
      }

      if (isDragging) {
        e.preventDefault();
        let newX = startTranslateX + dx;
        newX = Math.max(-actionWidth, Math.min(0, newX));
        item.style.transform = 'translateX(' + newX + 'px)';
        item.style.transition = 'none';
      }
    });

    item.addEventListener('pointerup', (e) => {
      if (pointerId !== e.pointerId) return;
      pointerId = null;

      if (!isDragging) {
        const tx = getTranslateX(item);
        if (tx < -5) {
          closeSwipe(swipe);
        }
        return;
      }

      isDragging = false;
      suppressClick = true;
      const tx = getTranslateX(item);
      item.style.transition = 'transform 0.25s ease';

      if (tx < -(actionWidth * 0.5)) {
        item.style.transform = 'translateX(' + (-actionWidth) + 'px)';
        activeSwipe = swipe;
      } else {
        item.style.transform = 'translateX(0)';
        activeSwipe = null;
      }
      try { item.releasePointerCapture(e.pointerId); } catch (err) {}
    });

    item.addEventListener('pointerleave', () => {
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
        if (tx < -(actionWidth * 0.5)) {
          item.style.transform = 'translateX(' + (-actionWidth) + 'px)';
          activeSwipe = swipe;
        } else {
          item.style.transform = 'translateX(0)';
          activeSwipe = null;
        }
      }
      try { item.releasePointerCapture(e.pointerId); } catch (err) {}
    });

    /* ---- click: 导航或关闭 ---- */
    item.addEventListener('click', (e) => {
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
      // 未滑动 → 进入聊天页（客服 1021 → #/support，普通 → #/chat/{peerId}）
      if (peerId) {
        /**
         * 【AI客服的实现】客服会话（peerId=1021）进入客服聊天页（AI 回复），普通会话进私信页
         */
        if (peerId === CUSTOMER_ID) {
          window.location.hash = '#/support';
        } else {
          window.location.hash = '#/chat/' + peerId;
        }
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

  /* ---- 点击页面其他区域关闭当前左滑 ---- */
  root.addEventListener('pointerdown', (e) => {
    const target = e.target;
    if (activeSwipe && !target.closest('.chat-session-item') && !target.closest('.chat-session-actions')) {
      closeActiveSwipe();
    }
  });

  /* ---- 页面离开时重置 ---- */
  const cleanup = () => {
    closeActiveSwipe();
  };
  window.addEventListener('hashchange', cleanup, { once: true });
}

/**
 * 【私信功能的实现】刷新私信会话列表（供删除/置顶后重新渲染 + 轮询）
 * - 关闭当前活跃的左滑（重渲染后 DOM 变更，旧引用失效）
 * - GET /chat/sessions → renderChatSessionList → setupSwipeHandlers
 */
async function refreshSessions() {
  closeActiveSwipe();
  try {
    const sessions = await get('/chat/sessions').catch(() => []);
    const wrap = _root ? _root.querySelector('#msg-sessions-wrap') : null;
    if (wrap) {
      wrap.innerHTML = renderChatSessionList(sessions);
      setupSwipeHandlers(_root);
    }
  } catch {
    // 静默失败
  }
}

// ============================================================
//  消息页本体：三圈（点击进独立页） + 私信会话列表
// ============================================================

export async function renderMessages(container) {
  container.innerHTML = `
    <div id="messages-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#messages-root');
  _root = root;

  try {
    /** 【分类未读的实现】并行加载：三分类未读数 + 私信会话列表
     *  - 进入消息页不再自动全部已读：微信/小红书语义为「消息中心展示各分类未读角标，
     *    用户点击分类圈进入详情页时才已读」（详情页 renderMessageCategory 内 PUT /notify/read）
     *  - tabbar 红点不再在此强制隐藏，保持到用户真正已读（5s 轮询 refreshMessagesBadge 自动随已读状态更新）
     *  - 失败兜底全 0/[]（接口异常不影响消息页其余内容加载） */
    const [chatSessions, catUnread] = await Promise.all([
      get('/chat/sessions').catch(() => []),
      get('/notify/category-unread-count').catch(() => ({ likes: 0, follows: 0, comments: 0 }))
    ]);

    // 初始渲染
    root.innerHTML = `
      <div class="page-header">
        <h2>消息</h2>
      </div>
      ${renderCategoryCircles(catUnread)}
      <!-- 【消息中心的实现】三圈下方私信会话列表（微信式左滑：露出置顶/删除按钮）
           实现细节：
            - 不再内嵌默认分类列表（赞和收藏等），点圈进入独立分类详情页（#/messages/likes 等）
            - isPinned 取后端字段，排序由后端完成 -->
      <div class="section-title" style="padding-bottom:4px">私信</div>
      <div id="msg-sessions-wrap">
        ${renderChatSessionList(chatSessions)}
      </div>
    `;

    // 绑定左滑手势
    setupSwipeHandlers(root);

    /** 【分类未读的实现】进入消息页不再自动全部已读、不主动清除三圈角标：
     *  - 角标由 renderCategoryCircles(catUnread) 渲染，保持显示直到用户点击分类圈进详情页已读
     *  - 用户从详情页返回消息页（hash 回退重新渲染）→ 重新拉取 category-unread-count 自然归零 → 角标消失
     *  - tabbar 红点同理保留（由 router refreshMessagesBadge 轮询按未读状态更新） */

    /** 【消息中心的实现】三圈点击 → 导航到独立分类详情页 */
    const catContainer = root.querySelector('#msg-categories');
    if (catContainer) {
      catContainer.querySelectorAll('.msg-cat-item').forEach(catItem => {
        catItem.addEventListener('click', () => {
          const cat = catItem.dataset.cat;
          navigate('/messages/' + cat);
        });
      });
    }

    /** 【消息中心的实现】会话列表轮询——停留消息页时每 5 秒自动刷新私信会话列表 + 未读红点
     * 实现细节：
     * - 轮询动作：refreshSessions() 重新拉取会话列表并重渲染 + 重绑左滑手势，
     *   再调 refreshMessagesBadge() 刷新 tabbar 未读红点
     * - 周期 5 秒，失败静默
     * - 轮询时若用户正在左滑，refreshSessions() 会先 closeActiveSwipe() 关闭当前左滑再重渲染，
     *   重渲染后 activeSwipe 自动重置为 null（旧 DOM 引用已失效）
     * - 清理：返回 cleanup 函数 clearInterval
     */
    const pollTimer = setInterval(() => {
      refreshSessions();
      refreshMessagesBadge();
    }, 5000);

    return function cleanup() {
      clearInterval(pollTimer);
      _root = null;
      activeSwipe = null;
    };

  } catch {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}

// ============================================================
//  独立分类详情页：#/messages/likes | /follows | /comments
// ============================================================

/**
 * 【消息中心的实现】渲染独立分类详情页
 * - 实现细节：
 *   - 标题（"赞和收藏"/"新增关注"/"评论和@"）+ 返回按钮（history.back()）
 *   - GET /notify/list?category= → 消息列表（谁/时间/哪个笔记）
 *   - 空态"暂无此类消息"
 *   - 消息点击跳转（like/collect/comment/reply/mention → #/note/{targetId}，follow → #/user/{targetId}）
 *   - 【消息中心的实现】分页加载更多：初始 current=1 → 列表尾部哨兵 → 滚动加载追加
 *     GET /notify/list?category=&current=N → 追加消息项到列表 → 无更多时移除哨兵
 */
export async function renderMessageCategory(container, params) {
  const category = params.category || 'likes';
  const title = getCategoryTitle(category);

  container.innerHTML = `
    <div id="msg-category-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#msg-category-root');

  try {
    const data = await get('/notify/list?category=' + category + '&current=1');
    const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);

    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="msg-cat-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>${title}</h2>
      </div>
      <div class="message-list-wrap" id="msg-cat-list-wrap">
        ${renderMessageList(list)}
      </div>
    `;

    // 返回按钮
    root.querySelector('#msg-cat-back').addEventListener('click', () => {
      window.history.back();
    });

    // 绑定消息点击
    bindMessageClicks(root);

    /** 【消息中心的实现】分页加载更多历史通知
     * 实现细节：
     * - 首屏 list.length >= 10 时插入哨兵（.load-sentinel-msg-cat），否则不显示分页
     * - 滚动触发 loadMore → GET /notify/list?category=&current=N 追加消息
     * - 追加方式：创建 message-item DOM 元素插入哨兵之前
     * - 无更多时移除哨兵；失败静默
     */
    const listWrap = root.querySelector('#msg-cat-list-wrap');
    if (list.length >= 10) {
      let current = 2;
      const sentinel = document.createElement('div');
      sentinel.className = 'load-sentinel load-sentinel-msg-cat';
      sentinel.style.cssText = 'height:10px;width:100%';
      listWrap.appendChild(sentinel);

      loadMore(listWrap, async () => {
        try {
          const moreData = await get('/notify/list?category=' + category + '&current=' + current);
          const moreList = Array.isArray(moreData) ? moreData : (moreData && moreData.records ? moreData.records : []);
          if (!moreList.length) {
            sentinel.remove();
            return;
          }
          /* 【通知实时昵称的实现】displayText 由后端实时拼装"触发者昵称+动作"（改昵称后历史通知自动显示新昵称，小红书方案）；旧数据 content 兜底 */
          moreList.forEach(item => {
            const el = document.createElement('div');
            el.className = 'message-item' + (item.isRead !== true ? ' unread' : '');
            el.dataset.id = item.id || '';
            el.dataset.type = item.type || '';
            el.dataset.target = item.targetId || '';
            el.innerHTML =
              '<div class="message-icon">' + getNotifyIcon(item.type) + '</div>' +
              '<div class="message-info">' +
                '<div class="message-content">' + escapeHtml(item.displayText || item.content || '') + '</div>' +
                '<div class="message-time">' + formatDate(item.createTime) + '</div>' +
              '</div>';
            el.addEventListener('click', () => {
              const type = el.dataset.type;
              const targetId = el.dataset.target;
              if (!targetId) return;
              if (type === 'follow') {
                window.location.hash = '#/user/' + targetId;
              } else {
                window.location.hash = '#/note/' + targetId;
              }
            });
            listWrap.insertBefore(el, sentinel);
          });
          current++;
        } catch {
          sentinel.remove();
        }
      }, 'load-sentinel-msg-cat');
    }

    // 标记已读
    try { await put('/notify/read'); setMessagesBadge(false); } catch {}

  } catch {
    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="msg-cat-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>${title}</h2>
      </div>
      <div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>
    `;
    root.querySelector('#msg-cat-back').addEventListener('click', () => {
      window.history.back();
    });
  }
}
