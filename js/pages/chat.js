/**
 * 【私信功能的实现】#/chat/:peerId 聊天页渲染模块
 * 职责：聊天 UI（我右气泡/对方左气泡）、历史消息加载、发送消息、互关状态提示、滚动到底部
 * 依赖：api.js (get/post)、ui.js (formatDate/toast/imgUrl)、store.js (getUser)
 * 核心链路：
 *   renderChat(container, params)
 *     → 获取 peerId 和对方用户信息（GET /user/home/{peerId}）
 *     → 加载历史消息 GET /chat/list?peerId={peerId}&current=1（倒序返回 → 前端 reverse 展示旧→新）
 *     → 检查互关状态 GET /chat/mutual-status?peerId={peerId} → 非互关显示轻提示
 *     → 发送消息 POST /chat/send {toUserId, content} → 成功追加气泡 + 清空输入；失败 toast errorMsg
 *     → 发送按钮禁用防重复
 * - 气泡样式复用 support.js 的 .chat-bubble / .chat-bubble-user / .chat-bubble-support
 * - 返回按钮：history.back()
 * - 空态："还没有消息，打个招呼吧"
 */
import { get, post } from '../api.js';
import { formatDate, imgUrl, toast } from '../ui.js';
import { getUser } from '../store.js';
import { refreshMessagesBadge } from '../router.js';

export async function renderChat(container, params) {
  const peerId = params.peerId;
  const me = getUser();

  container.innerHTML = `
    <div id="chat-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#chat-root');

  try {
    // 并行获取：对方信息 + 历史消息 + 互关状态
    const [userData, msgData, mutualData] = await Promise.all([
      get('/user/home/' + peerId),
      get('/chat/list?peerId=' + peerId + '&current=1'),
      get('/chat/mutual-status?peerId=' + peerId).catch(() => null) // 互关状态接口失败静默
    ]);

    const peer = userData && userData.user ? userData.user : { nickName: '用户', icon: '' };
    const messages = Array.isArray(msgData) ? msgData : (msgData && msgData.records ? msgData.records : []);
    // 后端倒序返回（最新在前），前端 reverse 为旧→新顺序展示
    messages.reverse();
    const isMutual = mutualData && mutualData.mutual === true;

    /**
     * 【私信功能的实现】聊天页 HTML 构建
     * - 顶部：返回按钮 + 对方昵称
     * - 互关提示条：非互关时显示轻提示（不阻断聊天）
     * - 消息列表区：复用 .chat-messages / .chat-bubble 样式（与 support.js 一致）
     * - 底部输入栏：输入框 + 发送按钮
     */
    root.innerHTML = `
      <div class="chat-page">
        <div class="page-header">
          <button class="header-back" id="chat-back">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
          </button>
          <h2>${escapeHtml(peer.nickName || '用户')}</h2>
        </div>
        ${!isMutual ? '<div class="chat-mutual-hint">你们尚未互相关注，今日可发送有限条私信</div>' : ''}
        <div class="chat-messages" id="chat-messages">
          ${messages.length === 0 ? '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg><p>还没有消息，打个招呼吧</p></div>' : ''}
        </div>
        <div class="chat-input-bar">
          <input type="text" id="chat-input" placeholder="输入消息..."/>
          <button class="btn btn-primary btn-sm" id="chat-send">发送</button>
        </div>
      </div>
    `;

    const chatMessages = root.querySelector('#chat-messages');
    const chatInput = root.querySelector('#chat-input');
    const chatSend = root.querySelector('#chat-send');

    // 返回按钮
    root.querySelector('#chat-back').addEventListener('click', () => window.history.back());

    /**
     * 【私信功能的实现】渲染历史消息气泡
     * 实现细节：
     * - 根据消息的 fromUserId 判断是我（me.id）还是对方 → 我右对齐（chat-bubble-user）、对方左对齐（chat-bubble-support）
     * - 每条消息显示：内容 + 时间（formatDate 秒级精度）
     */
    function renderMessages(list) {
      let html = '';
      list.forEach(msg => {
        const isMine = String(msg.fromUserId) === String(me ? me.id : '');
        const cls = isMine ? 'chat-bubble-user' : 'chat-bubble-support';
        html += `
          <div class="chat-bubble ${cls}">
            <span>${escapeHtml(msg.content || '')}</span>
            <div class="chat-bubble-time">${formatDate(msg.createTime)}</div>
          </div>`;
      });
      return html;
    }

    // 初始渲染历史消息
    if (messages.length > 0) {
      chatMessages.innerHTML = renderMessages(messages);
    }

    // 滚动到底部
    function scrollToBottom() {
      chatMessages.scrollTop = chatMessages.scrollHeight;
    }
    scrollToBottom();

    /**
     * 【私信功能的实现】轮询新消息（每 5 秒 GET /chat/list，id 去重追加对方新消息）
     * 实现细节：
     * - 用已渲染消息 id 集合去重，避免重复追加
     * - 只追加对方（fromPeer）新消息，不追加自己刚发的
     * - 若发送后重新拉取列表（sendMessage catch 回滚后），pollNewMessages 的 id 集合已包含已渲染消息
     * - 用户接近底部（scrollHeight - scrollTop - clientHeight < 150px）时自动滚到底部；否则不滚动避免打断阅读
     * - 不打断输入框：轮询是后台 GET，不操作输入框
     * - cleanup 清定时器：页面销毁时 router 调用 cleanup() 释放资源
     */
    const renderedMsgIds = new Set();
    messages.forEach(m => { if (m.id != null) renderedMsgIds.add(String(m.id)); });

    let pollTimer = null;

    async function pollNewMessages() {
      try {
        const data = await get('/chat/list?peerId=' + peerId + '&current=1');
        const latest = Array.isArray(data) ? data : (data && data.records ? data.records : []);
        if (!latest.length) return;

        // 后端倒序返回（最新在前），按旧→新顺序遍历追加
        const ordered = [...latest].reverse();
        const isNearBottom = chatMessages.scrollHeight - chatMessages.scrollTop - chatMessages.clientHeight < 150;

        for (const msg of ordered) {
          const mid = String(msg.id != null ? msg.id : '');
          if (!mid || renderedMsgIds.has(mid)) continue;
          // 只追加对方的新消息（自己发的已在 sendMessage 中即时渲染）
          const isMine = String(msg.fromUserId) === String(me ? me.id : '');
          if (isMine) {
            renderedMsgIds.add(mid);
            continue;
          }
          // 追加对方气泡
          const bubble = document.createElement('div');
          bubble.className = 'chat-bubble chat-bubble-support';
          bubble.innerHTML = '<span>' + escapeHtml(msg.content || '') + '</span><div class="chat-bubble-time">' + formatDate(msg.createTime) + '</div>';
          chatMessages.appendChild(bubble);
          renderedMsgIds.add(mid);
        }

        if (isNearBottom) {
          scrollToBottom();
        }
      } catch {
        // 轮询失败静默：不影响聊天体验
      }
    }

    // 启动轮询计时器
    pollTimer = setInterval(pollNewMessages, 5000);

    /**
     * 【私信功能的实现】追加消息气泡
     * 实现细节：
     * - type='user'（我）：右对齐主题色背景
     * - type='peer'（对方）：左对齐白底
     * - 返回气泡 DOM 元素
     */
    function appendBubble(type, text) {
      const bubble = document.createElement('div');
      bubble.className = 'chat-bubble chat-bubble-' + (type === 'user' ? 'user' : 'support');
      bubble.innerHTML = '<span>' + escapeHtml(text) + '</span><div class="chat-bubble-time">' + formatDate(new Date().toISOString()) + '</div>';
      chatMessages.appendChild(bubble);
      scrollToBottom();
      return bubble;
    }

    /**
     * 【私信功能的实现】发送消息主流程
     * 实现细节：
     * 1. 空内容直接 return
     * 2. 发送中防重复：按钮 disabled + 文字"发送中…"
     * 3. 追加用户气泡 → 清空输入框
     * 4. POST /chat/send {toUserId: peerId, content: text}
     * 5. 成功 → 移除刚才追加的临时气泡（可选，实际上不回拉列表，本地气泡即最终结果）
     * 6. 失败 → toast errorMsg（含互关限制提示如"对方未关注你，今日私信次数已用完"）
     * 7. 恢复按钮状态
     */
    async function sendMessage() {
      const text = chatInput.value.trim();
      if (!text) return;

      // 防重复发送
      chatSend.disabled = true;
      chatSend.textContent = '发送中…';

      // 先追加用户气泡（乐观 UI）
      const tmpBubble = appendBubble('user', text);
      // 清空输入
      chatInput.value = '';

      try {
        await post('/chat/send', { toUserId: peerId, content: text });
        /** 【消息中心的实现】发消息后刷新 tabbar 消息红点
         * 实现细节：
         * - 发送成功后异步刷新未读消息数（他人回复的未读红点）
         * - 非阻塞：不等待刷新完成，不影响聊天体验
         */
        refreshMessagesBadge();
      } catch {
        // 发送失败：移除临时气泡（回滚）
        tmpBubble.remove();
        // api.js 已 toast errorMsg（含互关限制等后端返回的错误信息）
      } finally {
        chatSend.disabled = false;
        chatSend.textContent = '发送';
      }
    }

    // 发送按钮点击
    chatSend.addEventListener('click', sendMessage);

    // 回车发送
    chatInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        sendMessage();
      }
    });

    /**
     * 【私信功能的实现】返回 cleanup 函数：页面销毁时清除轮询定时器
     * 实现细节：
     * - router 在切换页面时调用上一页 handler 返回的 cleanup 释放资源
     * - 清除 pollTimer 避免离开聊天页后仍发请求
     */
    return () => {
      if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
      }
    };

  } catch {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}

/**
 * 【私信功能的实现】XSS 防护：HTML 实体转义
 * 实现细节：
 * - 创建临时 div.textContent 再取 innerHTML，防止用户输入 <script> 等 XSS 注入
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}
