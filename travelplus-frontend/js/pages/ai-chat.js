/**
 * 【旅游AI助手的实现】#/ai-chat——旅游攻略 AI 助手对话页（Agent 架构 v2）
 * 后端：agent 包（AgentOrchestrator 编排 + Redis 服务端会话记忆 + 限流/熔断/指标）
 * 协议（SSE v2 结构化事件，data 均为 JSON）：
 *   {"meta":{"runId","sessionId"}}          首事件：会话 id 落 localStorage 实现跨刷新续连
 *   {"delta":"增量文本"}                    正文增量（打字机渲染）
 *   {"tool":{"name","label","status","ms"}} 工具执行时间线（running→done/failed + 耗时）
 *   {"error":{"code","message"}}            类型化错误（限流/熔断/内部错误差异化文案）
 *   {"done":true}                           收敛事件
 * 请求体：{"sessionId?","message"}——历史由服务端持有（多端续连），本地仅存展示副本；
 * 页面加载时若 localStorage 有 sessionId，GET /ai/history 拉回历史完整还原会话。
 * 依赖：router.js (navigate)；API_BASE；后端 /ai/chat /ai/history
 */
import { navigate } from '../router.js';
import { API_BASE } from '../config.js';
import { getToken } from '../store.js';
import { escapeHtml, renderMd } from '../markdown.js';

/** 会话 id 本地存储键（服务端记忆的引用句柄） */
const SESSION_KEY = 'travelplus_ai_session';

/** 错误码 → 友好文案（与后端 error 事件 code 对应） */
const ERROR_TEXT = {
  RATE_LIMITED: '请求太频繁了，请稍等片刻再试～',
  LLM_UNAVAILABLE: 'AI 服务暂时不可用，请稍后再试',
  LLM_ERROR: 'AI 服务异常，请稍后再试',
  INTERNAL_ERROR: 'AI 助手开小差了，请稍后再试',
  INVALID_INPUT: '请输入有效的问题再发送～'
};

/** 快捷问题 chips（点击即发送） */
const QUICK_PROMPTS = [
  '杭州一日游怎么安排？',
  '下雨天在杭州可以去哪玩？',
  '带老人和小孩的轻松路线',
  '人均 200 预算的周末玩法'
];

function getSessionId() {
  try { return localStorage.getItem(SESSION_KEY) || ''; } catch { return ''; }
}

function saveSessionId(id) {
  try { id ? localStorage.setItem(SESSION_KEY, id) : localStorage.removeItem(SESSION_KEY); } catch { /* 隐私模式忽略 */ }
}

/** 会话展示副本（模块级驻留：跨页面访问保留；服务端记忆为模型上下文真源） */
const history = [];

export async function renderAiChat(container) {
  container.innerHTML = `
    <div class="chat-page">
      <div class="page-header">
        <button class="header-back" id="ai-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>旅游助手</h2>
        <button class="ai-clear" id="ai-clear">清空</button>
      </div>
      <div class="ai-banner">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
        攻略规划 · 行程建议 · 回答基于平台真实景点数据
      </div>
      <div class="chat-messages" id="ai-messages">
        ${history.length === 0 ? welcomeHtml() : ''}
      </div>
      <div class="ai-chips" id="ai-chips">
        ${QUICK_PROMPTS.map(p => '<button class="ai-chip" data-q="' + escapeHtml(p) + '">' + escapeHtml(p) + '</button>').join('')}
      </div>
      <div class="chat-input-bar">
        <input type="text" id="ai-input" placeholder="问我任何旅行问题…"/>
        <button class="btn btn-primary btn-sm" id="ai-send">发送</button>
      </div>
    </div>
  `;

  const messagesEl = container.querySelector('#ai-messages');
  const inputEl = container.querySelector('#ai-input');
  const sendBtn = container.querySelector('#ai-send');

  container.querySelector('#ai-back').addEventListener('click', () => window.history.back());

  /** 清空会话：本地展示副本 + 服务端会话句柄一并重置（下次提问服务端签发新会话） */
  container.querySelector('#ai-clear').addEventListener('click', () => {
    history.length = 0;
    saveSessionId('');
    messagesEl.innerHTML = welcomeHtml();
  });

  /** 滚动到底部（流式输出时持续跟随） */
  function scrollToBottom() {
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  /** 追加一条用户气泡（右） */
  function appendUserBubble(text) {
    const empty = messagesEl.querySelector('.ai-welcome');
    if (empty) empty.remove();
    const div = document.createElement('div');
    div.className = 'chat-bubble chat-bubble-user';
    div.innerHTML = '<span>' + escapeHtml(text) + '</span>';
    messagesEl.appendChild(div);
    scrollToBottom();
  }

  /** 渲染一条已存在的历史气泡（回显用） */
  function appendHistoryBubble(role, content) {
    const div = document.createElement('div');
    div.className = 'chat-bubble ' + (role === 'user' ? 'chat-bubble-user' : 'chat-bubble-support');
    const text = document.createElement('div');
    text.className = 'ai-text';
    text.innerHTML = role === 'user' ? escapeHtml(content) : renderMd(content);
    div.appendChild(text);
    messagesEl.appendChild(div);
  }

  /** 创建流式助手气泡（左）：工具时间线容器 + 正文容器 */
  function createStreamingBubble() {
    const div = document.createElement('div');
    div.className = 'chat-bubble chat-bubble-support streaming';
    const steps = document.createElement('div');
    steps.className = 'ai-steps';
    const text = document.createElement('div');
    text.className = 'ai-text';
    text.textContent = '';
    div.appendChild(steps);
    div.appendChild(text);
    messagesEl.appendChild(div);
    scrollToBottom();
    return { div, text, steps };
  }

  /** 工具时间线：新增一行 running 步骤，返回行元素供 finishStep 收尾 */
  function addStep(bubble, label) {
    const row = document.createElement('div');
    row.className = 'ai-step running';
    row.innerHTML = '<span class="ai-step-spin"></span><span class="ai-step-label">' + escapeHtml(label) + '</span>';
    bubble.steps.appendChild(row);
    scrollToBottom();
    return row;
  }

  /** 工具时间线：步骤收尾（done=✓+耗时 / failed=✗） */
  function finishStep(row, status, ms) {
    if (!row) return;
    const label = row.querySelector('.ai-step-label')?.textContent || '';
    row.classList.remove('running');
    row.classList.add(status);
    row.innerHTML = (status === 'done' ? '<span class="ai-step-mark ok">✓</span>' : '<span class="ai-step-mark fail">✗</span>')
      + '<span class="ai-step-label">' + escapeHtml(label) + '</span>'
      + (status === 'done' && ms != null ? '<span class="ai-step-ms">' + ms + 'ms</span>' : '');
  }

  /** 错误事件：running 步骤全部标失败；正文为空时以错误文案兜底显示 */
  function applyError(bubble, code, message) {
    clearThinking(bubble);
    bubble.steps.querySelectorAll('.ai-step.running').forEach(r => finishStep(r, 'failed', null));
    const friendly = message || ERROR_TEXT[code] || 'AI 助手暂时无法回复，请稍后再试';
    if (!bubble.text.textContent) {
      bubble.text.textContent = friendly;
    }
  }

  /** 【Agent架构升级】思考态：每轮 LLM 往返前服务端推送 thinking 事件——
   *  工具步骤全部 ✓ 后正文有 2~5s 空窗，没有这行用户会以为"卡在检索"。
   *  同一气泡多轮思考复用同一行；首个 delta / 下一个工具 running 即移除。 */
  function showThinking(bubble) {
    if (!bubble.thinkingRow || !bubble.thinkingRow.isConnected) {
      bubble.thinkingRow = addStep(bubble, '正在思考…');
    }
    scrollToBottom();
  }

  function clearThinking(bubble) {
    if (bubble.thinkingRow) {
      bubble.thinkingRow.remove();
      bubble.thinkingRow = null;
    }
  }

  /** 发送：POST /ai/chat（SSE v2）流式读取，meta/delta/tool/error/done 分发 */
  let streaming = false;
  async function send(text) {
    text = (text || '').trim();
    if (!text || streaming) return;
    inputEl.value = '';
    history.push({ role: 'user', content: text });
    appendUserBubble(text);

    const bubble = createStreamingBubble();
    streaming = true;
    sendBtn.disabled = true;
    let full = '';
    /* 工具时间线：name → 行元素（同工具 running/done 两事件配对收尾） */
    const stepRows = new Map();
    try {
      const sid = getSessionId();
      const resp = await fetch(API_BASE + '/ai/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          /* 【旅游AI助手的实现】携带登录 token：Agent 写工具（加景点/建计划）需要用户身份 */
          ...(getToken() ? { authorization: getToken() } : {})
        },
        body: JSON.stringify({ message: text, ...(sid ? { sessionId: sid } : {}) })
      });
      /* 限流背压：HTTP 429（先于 SSE 开流返回）→ 差异化文案 */
      if (resp.status === 429) {
        applyError(bubble, 'RATE_LIMITED');
      } else if (!resp.ok || !resp.body) {
        throw new Error('HTTP ' + resp.status);
      } else {
        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buf = '';
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buf += decoder.decode(value, { stream: true });
          /* SSE 事件以空行分隔；同一事件的多个 data: 行按规范拼接还原多行文本 */
          const parts = buf.split('\n\n');
          buf = parts.pop() || '';
          for (const part of parts) {
            const dataLines = part.split('\n')
              .filter(l => l.startsWith('data:'))
              .map(l => l.slice(5).replace(/^ /, ''));
            if (!dataLines.length) continue;
            const payload = dataLines.join('\n');
            if (!payload || payload === '[DONE]') continue;
            try {
              const node = JSON.parse(payload);
              /* 会话 id：服务端签发/续连，落 localStorage 实现刷新/换设备还原会话 */
              if (node.meta && node.meta.sessionId) {
                saveSessionId(node.meta.sessionId);
              }
              if (node.delta) {
                clearThinking(bubble);
                full += node.delta;
                bubble.text.innerHTML = renderMd(full);
                scrollToBottom();
              }
              /* 思考态：LLM 往返前展示"正在思考…"行 */
              if (node.thinking) {
                showThinking(bubble);
              }
              /* 工具执行时间线：running 开行 → done/failed 收尾（附耗时） */
              if (node.tool && node.tool.name) {
                const t = node.tool;
                if (t.status === 'running') {
                  clearThinking(bubble);
                  stepRows.set(t.name, addStep(bubble, t.label || '正在处理…'));
                } else if (stepRows.has(t.name)) {
                  finishStep(stepRows.get(t.name), t.status, t.ms);
                }
              }
              if (node.error) {
                applyError(bubble, node.error.code, node.error.message);
              }
            } catch { /* 非法分片忽略 */ }
          }
        }
      }
      history.push({ role: 'assistant', content: full || bubble.text.textContent || '（未获取到回复）' });
      if (!full && !bubble.text.textContent) {
        bubble.text.textContent = '（AI 助手暂时无法回复，请稍后再试）';
      }
    } catch {
      bubble.text.innerHTML = renderMd(full || '（网络异常，请稍后再试）');
      history.push({ role: 'assistant', content: full || '（网络异常）' });
    } finally {
      streaming = false;
      sendBtn.disabled = false;
      bubble.div.classList.remove('streaming');
      clearThinking(bubble);
      scrollToBottom();
      /* 回答完成 → 异步刷新动态追问 chips（不阻塞阅读，失败静默） */
      updateFollowups();
    }
  }

  sendBtn.addEventListener('click', () => send(inputEl.value));
  inputEl.addEventListener('keydown', e => {
    if (e.key === 'Enter') send(inputEl.value);
  });

  /* 快捷问题 chips：初始为固定清单；每轮回答完成后被动态追问替换（renderChips 重渲染+重绑定） */
  renderChips(QUICK_PROMPTS);

  /** 渲染快捷 chips 并绑定点击发送（动态追问更新入口） */
  function renderChips(list) {
    const chipsEl = container.querySelector('#ai-chips');
    if (!chipsEl || !Array.isArray(list) || !list.length) return;
    chipsEl.innerHTML = list.map(p =>
      '<button class="ai-chip" data-q="' + escapeHtml(p) + '">' + escapeHtml(p) + '</button>'
    ).join('');
    chipsEl.querySelectorAll('.ai-chip').forEach(chip => {
      chip.addEventListener('click', () => send(chip.dataset.q));
    });
  }

  /** 【Agent架构升级】动态追问：回答完成后按会话记忆生成 3 个上下文相关的追问 chips。
   *  竞态保护（只应用最新一次请求）；失败/超时静默保留当前 chips，不打扰阅读。 */
  let followupSeq = 0;
  async function updateFollowups() {
    const sid = getSessionId();
    if (!sid) return;
    const seq = ++followupSeq;
    try {
      const resp = await fetch(API_BASE + '/ai/followups', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(getToken() ? { authorization: getToken() } : {})
        },
        body: JSON.stringify({ sessionId: sid })
      });
      if (!resp.ok) return;
      const res = await resp.json();
      const list = res && res.data;
      if (seq === followupSeq && Array.isArray(list) && list.length) {
        renderChips(list);
      }
    } catch { /* 静默保留旧 chips */ }
  }

  /* 会话续连：本地无展示副本但持有 sessionId → 从服务端记忆拉回历史完整还原 */
  const sid = getSessionId();
  if (history.length === 0 && sid) {
    fetch(API_BASE + '/ai/history?sessionId=' + encodeURIComponent(sid), {
      headers: getToken() ? { authorization: getToken() } : {}
    })
      .then(r => r.ok ? r.json() : null)
      .then(res => {
        const msgs = res && res.data;
        if (Array.isArray(msgs) && msgs.length && history.length === 0) {
          msgs.forEach(m => {
            history.push({ role: m.role, content: m.content });
            appendHistoryBubble(m.role, m.content);
          });
          scrollToBottom();
        }
      })
      .catch(() => { /* 拉取失败保持欢迎态，不影响新对话 */ });
  } else if (history.length > 0) {
    /* 本地展示副本回显（切页返回场景，无需请求） */
    messagesEl.innerHTML = '';
    history.forEach(m => appendHistoryBubble(m.role, m.content));
    scrollToBottom();
  }
}

/** 欢迎态 HTML（清空/首屏复用） */
function welcomeHtml() {
  return `
    <div class="ai-welcome">
      <div class="ai-welcome-icon">
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#0E9F8A" stroke-width="1.8"><path d="M12 2l2.4 5.8L20 9l-4.2 3.8L17 19l-5-2.8L7 19l1.2-6.2L4 9l5.6-1.2L12 2z"/></svg>
      </div>
      <div class="ai-welcome-title">我是你的旅游攻略助手</div>
      <div class="ai-welcome-hint">问我任何旅行问题：行程安排、雨天备选、预算玩法、亲子路线……</div>
    </div>`;
}
