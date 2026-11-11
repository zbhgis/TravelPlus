/**
 * 【企业级客服Agent的实现】#/support 客服对话页（SSE 流式，与旅行 AI 助手一致）
 * 后端：POST /support/chat/stream
 * 事件：{"meta":{runId,sessionId}} / {"delta":"增量"} / {"thinking":true}
 *       {"tool":{name,label,status,ms}} / {"replace":"护栏修正后的全文"}
 *       {"error":{code,message}} / {"done":true,messageId?}
 * 交互：
 *   - AI 模式：打字机渲染 + 工具执行时间线；done 后用 messageId 挂载 👍/👎 反馈；
 *   - WAITING/HUMAN：AI 静默，展示人工横幅并低频轮询人工回复；
 *   - 历史消息分页加载；用户输入 textContent 渲染，杜绝 XSS。
 */
import { get, post, del } from '../api.js';
import { toast, confirmDialog } from '../ui.js';
import { refreshMessagesBadge } from '../router.js';
import { API_BASE } from '../config.js';
import { getToken, clearToken } from '../store.js';
import { renderMd, escapeHtml } from '../markdown.js';

/** 客服虚拟用户 ID（与后端 tb_chat_message 一致） */
const CUSTOMER_ID = 1021;

/** 空历史欢迎语（仅前端展示，不写库） */
const WELCOME_MSG = '你好，我是 TravelPlus 智能客服，可以帮你查订单、积分签到、秒杀场次，也能解答平台规则。';

/** SSE 错误码 → 友好文案 */
const ERROR_TEXT = {
  INVALID_INPUT: '请输入有效的问题再发送～',
  UNAUTHENTICATED: '登录已过期，请重新登录',
  RATE_LIMITED: '咨询太频繁了，请稍后再试～'
};

export async function renderSupport(container) {
  container.innerHTML = `
    <div id="support-root">
      <div class="chat-page">
        <div class="page-header">
          <button class="header-back" id="support-back">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
          </button>
          <h2>TravelPlus 客服</h2>
          <div class="support-header-actions">
            <button class="support-header-btn" id="support-human">转人工</button>
            <button class="support-header-btn danger" id="support-clear">清空</button>
          </div>
        </div>
        <div class="support-banner hidden" id="support-banner"></div>
        <div class="chat-messages" id="chat-messages"></div>
        <div class="chat-input-bar">
          <input type="text" id="chat-input" placeholder="输入你的问题..."/>
          <button class="btn btn-primary btn-sm" id="chat-send">发送</button>
        </div>
      </div>
    </div>
  `;

  const root = container.querySelector('#support-root');
  const chatMessages = root.querySelector('#chat-messages');
  const chatInput = root.querySelector('#chat-input');
  const chatSend = root.querySelector('#chat-send');
  const banner = root.querySelector('#support-banner');
  const humanBtn = root.querySelector('#support-human');

  /** 会话展示副本（服务端消息表为真源） */
  let chatHistory = [];
  /** 后端当前最大客服消息 id（人工回复轮询基线 / 反馈兜底） */
  let backendMaxAssistantId = 0;
  /** 人工回复轮询定时器 */
  let pollTimer = null;
  /** 人工等待提示定时器（90 秒只提示一次） */
  let waitingHintTimer = null;
  let waitingHintShown = false;
  /** 当前服务模式：AI / WAITING / HUMAN */
  let sessionMode = 'AI';
  let handoffTicketId = null;
  let lastIntent = '';
  /** 当前是否正在流式输出（防并发发送） */
  let streaming = false;

  /** 历史分页 */
  const PAGE_SIZE = 10;
  let currentPage = 1;
  let hasMore = false;

  root.querySelector('#support-back').addEventListener('click', () => window.history.back());

  function scrollToBottom() {
    chatMessages.scrollTop = chatMessages.scrollHeight;
  }

  /** 构建气泡内容：用户 textContent 防 XSS；客服走受限 Markdown（内部先转义） */
  function buildBubbleContent(type, text) {
    const content = document.createElement('div');
    content.className = 'chat-bubble-content';
    if (type === 'user') {
      content.textContent = text;
    } else {
      content.innerHTML = renderMd(text);
    }
    return content;
  }

  /** 构建完整气泡（历史消息用，客服消息带反馈按钮） */
  function buildBubble(type, text, msgId) {
    const bubble = document.createElement('div');
    bubble.className = 'chat-bubble chat-bubble-' + (type === 'user' ? 'user' : 'support');
    bubble.appendChild(buildBubbleContent(type, text));
    if (type !== 'user' && msgId) {
      bubble.appendChild(buildFeedback(msgId));
    }
    return bubble;
  }

  function appendBubble(type, text, msgId) {
    const bubble = buildBubble(type, text, msgId);
    chatMessages.appendChild(bubble);
    scrollToBottom();
    return bubble;
  }

  /** 本地提示气泡（不落库，不参与反馈） */
  function appendHint(text) {
    const bubble = document.createElement('div');
    bubble.className = 'chat-bubble chat-bubble-support chat-bubble-hint';
    bubble.textContent = text;
    chatMessages.appendChild(bubble);
    scrollToBottom();
    return bubble;
  }

  /** 反馈按钮：👍 直接提交；👎 展开原因输入（可选） */
  function buildFeedback(msgId) {
    const wrap = document.createElement('div');
    wrap.className = 'chat-feedback';
    const submit = async (helpful, reason) => {
      try {
        await post('/support/feedback', {
          messageId: Number(msgId),
          helpful,
          reason: reason || undefined,
          intent: lastIntent || undefined
        });
        wrap.innerHTML = '<span class="chat-feedback-done">'
          + (helpful ? '感谢反馈' : '已记录，我们会改进') + '</span>';
      } catch {
        wrap.querySelectorAll('button').forEach(b => { b.disabled = false; });
      }
    };
    const up = document.createElement('button');
    up.type = 'button';
    up.textContent = '👍';
    up.title = '有帮助';
    up.addEventListener('click', () => {
      wrap.querySelectorAll('button').forEach(b => { b.disabled = true; });
      submit(true);
    });
    const down = document.createElement('button');
    down.type = 'button';
    down.textContent = '👎';
    down.title = '没帮助';
    down.addEventListener('click', () => {
      wrap.innerHTML = '';
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'chat-feedback-reason';
      input.placeholder = '说说哪里没帮助（可选）';
      input.maxLength = 200;
      const ok = document.createElement('button');
      ok.type = 'button';
      ok.textContent = '提交';
      const send = () => {
        if (ok.disabled) return;
        ok.disabled = true;
        input.disabled = true;
        ok.textContent = '提交中…';
        submit(false, input.value.trim());
      };
      ok.addEventListener('click', send);
      input.addEventListener('keydown', e => {
        if (e.key === 'Enter') { e.preventDefault(); send(); }
      });
      wrap.appendChild(input);
      wrap.appendChild(ok);
      input.focus();
    });
    wrap.appendChild(up);
    wrap.appendChild(down);
    return wrap;
  }

  /* ================= 历史消息 ================= */

  function normalizeRecords(data) {
    return Array.isArray(data) ? data : (data && data.records ? data.records : []);
  }

  async function loadHistoryFromBackend() {
    try {
      const records = normalizeRecords(await get('/chat/list?peerId=' + CUSTOMER_ID + '&current=1'));
      currentPage = 1;
      if (records.length > 0) {
        chatHistory = records
          .slice()
          .sort((a, b) => Number(a.id) - Number(b.id))
          .map(msg => ({
            id: msg.id,
            role: String(msg.fromUserId) === String(CUSTOMER_ID) ? 'assistant' : 'user',
            content: msg.content || '',
            time: msg.createTime || ''
          }));
        backendMaxAssistantId = chatHistory
          .filter(m => m.role === 'assistant')
          .reduce((acc, m) => Math.max(acc, Number(m.id) || 0), 0);
        hasMore = records.length >= PAGE_SIZE;
      } else {
        chatHistory = [{ role: 'assistant', content: WELCOME_MSG, time: new Date().toISOString() }];
        backendMaxAssistantId = 0;
        hasMore = false;
      }
    } catch {
      chatHistory = [{ role: 'assistant', content: WELCOME_MSG, time: new Date().toISOString() }];
      backendMaxAssistantId = 0;
      hasMore = false;
    }
  }

  function renderAllHistory() {
    chatMessages.innerHTML = '';
    if (hasMore) chatMessages.appendChild(buildLoadMoreBtn());
    chatHistory.forEach(msg => chatMessages.appendChild(buildBubble(msg.role, msg.content, msg.id)));
    scrollToBottom();
  }

  function buildLoadMoreBtn() {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'chat-load-more';
    btn.textContent = '加载更早消息';
    btn.addEventListener('click', async () => {
      if (btn.disabled) return;
      btn.disabled = true;
      btn.textContent = '加载中…';
      try {
        const records = normalizeRecords(
          await get('/chat/list?peerId=' + CUSTOMER_ID + '&current=' + (currentPage + 1)));
        const known = new Set(chatHistory.map(m => String(m.id)));
        const older = records
          .filter(r => !known.has(String(r.id)))
          .sort((a, b) => Number(a.id) - Number(b.id));
        older.forEach(msg => {
          const role = String(msg.fromUserId) === String(CUSTOMER_ID) ? 'assistant' : 'user';
          chatHistory.unshift({ id: msg.id, role, content: msg.content || '', time: msg.createTime || '' });
          btn.insertAdjacentElement('afterend', buildBubble(role, msg.content || '', msg.id));
        });
        currentPage++;
        if (older.length < PAGE_SIZE) {
          hasMore = false;
          btn.remove();
        } else {
          btn.disabled = false;
          btn.textContent = '加载更早消息';
        }
      } catch {
        btn.disabled = false;
        btn.textContent = '加载更早消息';
      }
    });
    return btn;
  }

  /* ================= 会话状态 ================= */

  async function loadStatus() {
    try {
      const st = await get('/support/status');
      sessionMode = st.mode || 'AI';
      handoffTicketId = st.ticketId || null;
      lastIntent = st.intent || lastIntent;
      updateBanner(st.waitingCount);
      return st;
    } catch {
      return null;
    }
  }

  function updateBanner(waitingCount) {
    if (sessionMode === 'WAITING') {
      banner.classList.remove('hidden');
      banner.innerHTML = '<span>已提交人工工单'
        + (handoffTicketId ? ' #' + handoffTicketId : '')
        + (waitingCount != null ? '，前方排队 ' + waitingCount + ' 人' : '')
        + '，请稍候…</span><button id="support-resume">恢复AI</button>';
      humanBtn.classList.add('hidden');
      chatInput.placeholder = '人工客服接入中，可继续补充信息…';
    } else if (sessionMode === 'HUMAN') {
      banner.classList.remove('hidden');
      banner.innerHTML = '<span>人工客服正在为你服务，回复会显示在下方</span>'
        + '<button id="support-resume">结束人工</button>';
      humanBtn.classList.add('hidden');
      chatInput.placeholder = '人工客服服务中，可继续对话…';
    } else {
      banner.classList.add('hidden');
      banner.innerHTML = '';
      humanBtn.classList.remove('hidden');
      chatInput.placeholder = '输入你的问题...';
    }
  }

  /* ================= 工具时间线 ================= */

  function createStreamingBubble() {
    const div = document.createElement('div');
    div.className = 'chat-bubble chat-bubble-support streaming';
    const steps = document.createElement('div');
    steps.className = 'ai-steps';
    const text = document.createElement('div');
    text.className = 'ai-text';
    text.textContent = '思考中…';
    div.appendChild(steps);
    div.appendChild(text);
    chatMessages.appendChild(div);
    scrollToBottom();
    return { div, steps, text };
  }

  function addStep(bubble, label) {
    const row = document.createElement('div');
    row.className = 'ai-step running';
    row.innerHTML = '<span class="ai-step-spin"></span><span class="ai-step-label">'
      + escapeHtml(label) + '</span>';
    bubble.steps.appendChild(row);
    scrollToBottom();
    return row;
  }

  function finishStep(row, status, ms) {
    if (!row) return;
    const label = row.querySelector('.ai-step-label')?.textContent || '';
    row.classList.remove('running');
    row.classList.add(status);
    row.innerHTML = (status === 'done'
      ? '<span class="ai-step-mark ok">✓</span>'
      : '<span class="ai-step-mark fail">✗</span>')
      + '<span class="ai-step-label">' + escapeHtml(label) + '</span>'
      + (status === 'done' && ms != null ? '<span class="ai-step-ms">' + ms + 'ms</span>' : '');
  }

  /* ================= SSE 流式发送 ================= */

  /**
   * 发送消息：SSE 流式读取；meta/delta/thinking/tool/replace/error/done 分发。
   * done 携带 messageId，用于挂载反馈按钮；结束后刷新会话模式（可能已转人工）。
   */
  async function sendMessage() {
    const text = chatInput.value.trim();
    if (!text || streaming) return;

    streaming = true;
    chatSend.disabled = true;
    chatSend.textContent = '发送中…';
    appendBubble('user', text);
    chatHistory.push({ role: 'user', content: text, time: new Date().toISOString() });
    chatInput.value = '';

    const bubble = createStreamingBubble();
    let full = '';
    let messageId = null;
    let errorText = '';
    const stepRows = new Map();

    try {
      const resp = await fetch(API_BASE + '/support/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(getToken() ? { authorization: getToken() } : {})
        },
        body: JSON.stringify({ message: text })
      });
      if (resp.status === 401) {
        clearToken();
        window.location.hash = '#/login';
        throw new Error('登录已过期，请重新登录');
      }
      if (!resp.ok || !resp.body) {
        throw new Error('HTTP ' + resp.status);
      }

      const reader = resp.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buf = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const parts = buf.split('\n\n');
        buf = parts.pop() || '';
        for (const part of parts) {
          const dataLines = part.split('\n')
            .filter(l => l.startsWith('data:'))
            .map(l => l.slice(5).replace(/^ /, ''));
          if (!dataLines.length) continue;
          const payload = dataLines.join('\n');
          if (!payload || payload === '[DONE]') continue;
          let node;
          try { node = JSON.parse(payload); } catch { continue; }

          if (node.delta) {
            full += node.delta;
            bubble.text.innerHTML = renderMd(full);
            scrollToBottom();
          }
          if (node.replace != null) {
            full = node.replace;
            bubble.text.innerHTML = renderMd(full);
          }
          if (node.thinking && !full) {
            bubble.text.textContent = '思考中…';
          }
          if (node.tool && node.tool.name) {
            const t = node.tool;
            if (t.status === 'running') {
              stepRows.set(t.name, addStep(bubble, t.label || '正在处理…'));
            } else if (stepRows.has(t.name)) {
              finishStep(stepRows.get(t.name), t.status, t.ms);
            }
          }
          if (node.error) {
            errorText = node.error.message || ERROR_TEXT[node.error.code] || '客服暂时无法回复';
            if (node.error.code === 'UNAUTHENTICATED') {
              clearToken();
            }
          }
          if (node.done && node.messageId != null) {
            messageId = Number(node.messageId);
          }
        }
      }

      if (!full) {
        bubble.text.textContent = errorText || '（客服暂时无法回复，请稍后再试）';
      }
      bubble.div.classList.remove('streaming');

      if (messageId) {
        backendMaxAssistantId = Math.max(backendMaxAssistantId, messageId);
        bubble.div.appendChild(buildFeedback(messageId));
      }
      chatHistory.push({
        id: messageId || undefined,
        role: 'assistant',
        content: full || bubble.text.textContent || '',
        time: new Date().toISOString()
      });
      refreshMessagesBadge();

      /* 本轮可能触发了转人工（request_human_handoff），刷新模式并决定是否进入人工监听 */
      await loadStatus();
      if (sessionMode !== 'AI') {
        startHumanWatch(backendMaxAssistantId);
      }
    } catch (e) {
      bubble.div.classList.remove('streaming');
      if (!full) {
        bubble.text.textContent = errorText || '网络异常，请稍后再试';
      }
      if (e && e.message && !e.message.startsWith('登录已过期')) {
        toast(e.message);
      }
    } finally {
      streaming = false;
      chatSend.disabled = false;
      chatSend.textContent = '发送';
      scrollToBottom();
    }
  }

  /* ================= 人工回复监听 ================= */

  function clearTimers() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    if (waitingHintTimer) { clearTimeout(waitingHintTimer); waitingHintTimer = null; }
  }

  function stopHumanWatch() {
    clearTimers();
  }

  /**
   * 人工阶段低频轮询：只监听新增客服消息（人工回复/结束提示），不再生成 AI 回复。
   * before 为已经展示过的最大客服消息 id，避免重复追加。
   */
  function startHumanWatch(before) {
    clearTimers();
    const poll = async () => {
      if (!document.contains(chatMessages)) {
        stopHumanWatch();
        return;
      }
      try {
        const records = normalizeRecords(await get('/chat/list?peerId=' + CUSTOMER_ID + '&current=1'));
        const assistantMsgs = records.filter(r => String(r.fromUserId) === String(CUSTOMER_ID));
        const currentMax = assistantMsgs.reduce((acc, r) => Math.max(acc, Number(r.id) || 0), 0);
        if (currentMax > before) {
          chatMessages.querySelectorAll('.chat-bubble-hint').forEach(b => b.remove());
          assistantMsgs
            .filter(r => Number(r.id) > before)
            .sort((a, b) => Number(a.id) - Number(b.id))
            .forEach(msg => {
              appendBubble('assistant', msg.content || '', msg.id);
              chatHistory.push({ id: msg.id, role: 'assistant', content: msg.content || '', time: msg.createTime || '' });
            });
          backendMaxAssistantId = currentMax;
          refreshMessagesBadge();
          await loadStatus();
          if (sessionMode !== 'AI') {
            startHumanWatch(currentMax);
          } else {
            stopHumanWatch();
          }
        }
      } catch {
        // 轮询失败静默继续，页面离开守卫兜底
      }
    };
    pollTimer = setInterval(poll, 4000);

    if (!waitingHintShown) {
      waitingHintTimer = setTimeout(() => {
        waitingHintShown = true;
        if (!document.contains(chatMessages) || sessionMode === 'AI') return;
        appendHint('人工客服暂时繁忙，你可以继续等待，或点击上方「恢复AI」先由 AI 客服协助。');
      }, 90000);
    }
  }

  /* ================= 事件绑定 ================= */

  chatSend.addEventListener('click', sendMessage);
  chatInput.addEventListener('keydown', e => {
    if (e.key === 'Enter') {
      e.preventDefault();
      sendMessage();
    }
  });

  humanBtn.addEventListener('click', async () => {
    if (sessionMode !== 'AI') return;
    const ok = await confirmDialog('转人工', '确定要转接人工客服吗？');
    if (!ok) return;
    try {
      const r = await post('/support/handoff', { reason: '用户主动请求人工' });
      sessionMode = r && r.mode ? r.mode : 'WAITING';
      handoffTicketId = r ? r.ticketId : null;
      updateBanner(r ? r.waitingCount : null);
      toast('已转人工，请等待客服接入');
      startHumanWatch(backendMaxAssistantId);
    } catch {
      // api.js 已 toast
    }
  });

  banner.addEventListener('click', async (e) => {
    if (!e.target || e.target.id !== 'support-resume') return;
    try {
      await post('/support/resume', {});
      sessionMode = 'AI';
      handoffTicketId = null;
      updateBanner();
      toast('已恢复 AI 客服');
      startHumanWatch(backendMaxAssistantId);
    } catch {
      // api.js 已 toast
    }
  });

  root.querySelector('#support-clear').addEventListener('click', async () => {
    const ok = await confirmDialog('清空对话', '确定要清空所有聊天记录吗？人工工单不会被取消。');
    if (!ok) return;
    try {
      await del('/support/session').catch(() => {});
      await del('/chat/session/' + CUSTOMER_ID);
      chatHistory = [{ role: 'assistant', content: WELCOME_MSG, time: new Date().toISOString() }];
      backendMaxAssistantId = 0;
      renderAllHistory();
      await loadStatus();
      toast('对话已清空');
    } catch {
      // api.js 已 toast
    }
  });

  /* 首屏：先读状态，再渲染历史；人工阶段自动进入低频监听 */
  await loadStatus();
  await loadHistoryFromBackend();
  renderAllHistory();
  if (sessionMode !== 'AI') {
    startHumanWatch(backendMaxAssistantId);
  }
}
