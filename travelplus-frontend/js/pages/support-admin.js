/**
 * 【企业级客服Agent的实现】#/support-admin 客服工作台（人工客服）
 * 能力：工单列表（待接入/人工中/已结束）、查看完整会话、接入、回复、结束并恢复 AI。
 * 权限：后端 /support/admin/** 校验 travelplus.support.admin-user-ids 白名单；
 *       非管理员进入页面会看到无权限提示。
 */
import { get, post } from '../api.js';
import { toast, confirmDialog } from '../ui.js';
import { renderMd } from '../markdown.js';

function fmtTime(value) {
  if (!value) return '';
  return String(value).replace('T', ' ').slice(0, 16);
}

/** 等待时长文案（待接入/人工中工单的紧迫感提示） */
function fmtWait(value) {
  if (!value) return '';
  const ms = Date.now() - new Date(value).getTime();
  if (isNaN(ms) || ms < 0) return '';
  const min = Math.floor(ms / 60000);
  if (min < 1) return '已等待不到 1 分钟';
  if (min < 60) return '已等待 ' + min + ' 分钟';
  return '已等待 ' + Math.floor(min / 60) + ' 小时 ' + (min % 60) + ' 分钟';
}

export async function renderSupportAdmin(container) {
  container.innerHTML = `
    <div id="support-admin-root" class="admin-page">
      <div class="page-header">
        <button class="header-back" id="admin-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>客服工作台</h2>
        <div class="admin-header-actions">
          <button class="admin-refresh" id="admin-kb-reload">重载知识库</button>
          <button class="admin-refresh" id="admin-refresh">刷新</button>
        </div>
      </div>
      <div class="admin-tabs" id="admin-tabs">
        <button data-status="0" class="active">待接入</button>
        <button data-status="1">人工中</button>
        <button data-status="2">已结束</button>
        <button data-view="feedback">差评反馈</button>
      </div>
      <div class="admin-list" id="admin-list"></div>
      <div class="admin-detail hidden" id="admin-detail">
        <div class="admin-detail-head">
          <span id="admin-detail-title">会话</span>
          <button id="admin-detail-close">收起</button>
        </div>
        <div class="admin-chat" id="admin-chat"></div>
        <div class="admin-reply">
          <input id="admin-reply-input" placeholder="输入回复内容..."/>
          <button class="btn btn-primary btn-sm" id="admin-reply-send">回复</button>
        </div>
        <button class="admin-finish" id="admin-finish">结束会话并恢复 AI</button>
      </div>
    </div>
  `;

  const root = container.querySelector('#support-admin-root');
  const listEl = root.querySelector('#admin-list');
  const detailEl = root.querySelector('#admin-detail');
  const chatEl = root.querySelector('#admin-chat');
  const replyInput = root.querySelector('#admin-reply-input');
  const detailTitle = root.querySelector('#admin-detail-title');

  root.querySelector('#admin-back').addEventListener('click', () => window.history.back());

  /* 权限校验 */
  try {
    const me = await get('/support/admin/me');
    if (!me || !me.admin) {
      listEl.innerHTML = '<div class="admin-empty">无权访问客服工作台</div>';
      return;
    }
  } catch {
    listEl.innerHTML = '<div class="admin-empty">无权访问客服工作台</div>';
    return;
  }

  let currentStatus = 0;
  let currentView = 'tickets';
  let selectedId = null;
  let msgTimer = null;

  function stopMsgTimer() {
    if (msgTimer) {
      clearInterval(msgTimer);
      msgTimer = null;
    }
  }

  function renderTicketCard(t) {
    const actions = [];
    actions.push('<button data-act="open" data-id="' + t.id + '">查看会话</button>');
    if (t.status === 0) {
      actions.push('<button class="primary" data-act="claim" data-id="' + t.id + '">接入</button>');
    }
    return `
      <div class="admin-card">
        <div class="admin-card-top">
          <span class="admin-user">${escape(t.nickName || ('用户' + t.userId))}</span>
          <span class="admin-status">${escape(t.statusText || '')}</span>
        </div>
        <div class="admin-reason">${escape(t.reason || '（未填写原因）')}</div>
        <div class="admin-meta">#${t.id} · ${t.source === 'agent' ? 'Agent 转接' : '用户转接'} · ${fmtTime(t.createTime)}${t.status !== 2 ? ' · ' + fmtWait(t.createTime) : ''}</div>
        <div class="admin-actions">${actions.join('')}</div>
      </div>`;
  }

  function escape(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  async function loadTickets() {
    try {
      const list = await get('/support/admin/tickets?status=' + currentStatus);
      if (!Array.isArray(list) || list.length === 0) {
        listEl.innerHTML = '<div class="admin-empty">暂无工单</div>';
        return;
      }
      listEl.innerHTML = list.map(renderTicketCard).join('');
    } catch {
      listEl.innerHTML = '<div class="admin-empty">工单加载失败</div>';
    }
  }

  /** 【客服Agent完善】差评/bad case 列表：原因 + 原始回复片段，便于归因优化 */
  async function loadFeedback() {
    try {
      const list = await get('/support/admin/feedback?helpful=0&limit=50');
      if (!Array.isArray(list) || list.length === 0) {
        listEl.innerHTML = '<div class="admin-empty">暂无差评反馈</div>';
        return;
      }
      listEl.innerHTML = list.map(f => `
        <div class="admin-card">
          <div class="admin-card-top">
            <span class="admin-user">${escape(f.nickName || ('用户' + f.userId))}</span>
            <span class="admin-status">差评${f.intent ? ' · ' + escape(f.intent) : ''}</span>
          </div>
          <div class="admin-reason">原因：${escape(f.reason || '用户未填写')}</div>
          ${f.messageContent ? '<div class="admin-quote">“' + escape(f.messageContent) + '”</div>' : ''}
          <div class="admin-meta">反馈 #${f.id}${f.messageId ? ' · 消息 #' + f.messageId : ''} · ${fmtTime(f.createTime)}</div>
        </div>`).join('');
    } catch {
      listEl.innerHTML = '<div class="admin-empty">差评加载失败</div>';
    }
  }

  function appendMsg(fromSupport, content) {
    const bubble = document.createElement('div');
    bubble.className = 'chat-bubble ' + (fromSupport ? 'chat-bubble-support' : 'chat-bubble-user');
    const span = document.createElement('span');
    if (fromSupport) {
      span.innerHTML = renderMd(content || '');
    } else {
      span.textContent = content || '';
    }
    bubble.appendChild(span);
    chatEl.appendChild(bubble);
  }

  async function loadMessages(ticketId) {
    try {
      const list = await get('/support/admin/tickets/' + ticketId + '/messages');
      chatEl.innerHTML = '';
      if (!Array.isArray(list) || list.length === 0) {
        chatEl.innerHTML = '<div class="admin-empty">暂无消息</div>';
        return;
      }
      list.forEach(m => appendMsg(!!m.fromSupport, m.content));
      chatEl.scrollTop = chatEl.scrollHeight;
    } catch {
      // 轮询失败静默
    }
  }

  async function selectTicket(ticketId) {
    selectedId = ticketId;
    detailEl.classList.remove('hidden');
    detailTitle.textContent = '工单 #' + ticketId;
    await loadMessages(ticketId);
    stopMsgTimer();
    msgTimer = setInterval(() => {
      if (!document.contains(root)) {
        stopMsgTimer();
        return;
      }
      if (selectedId) loadMessages(selectedId);
    }, 5000);
  }

  async function claim(ticketId) {
    try {
      await post('/support/admin/tickets/' + ticketId + '/claim', {});
      toast('已接入工单');
      await loadTickets();
      await selectTicket(ticketId);
    } catch {
      // api.js 已 toast
    }
  }

  listEl.addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const ticketId = Number(btn.dataset.id);
    if (btn.dataset.act === 'open') {
      await selectTicket(ticketId);
    } else if (btn.dataset.act === 'claim') {
      await claim(ticketId);
    }
  });

  root.querySelector('#admin-detail-close').addEventListener('click', () => {
    selectedId = null;
    stopMsgTimer();
    detailEl.classList.add('hidden');
  });

  root.querySelector('#admin-refresh').addEventListener('click', async () => {
    if (currentView === 'feedback') {
      await loadFeedback();
    } else {
      await loadTickets();
      if (selectedId) await loadMessages(selectedId);
    }
  });

  /** 【客服Agent完善】知识库热重载：运营改完外部 Markdown 后一键生效 */
  root.querySelector('#admin-kb-reload').addEventListener('click', async () => {
    try {
      const stats = await post('/support/admin/kb/reload', {});
      toast('知识库已重载：' + (stats && stats.docCount ? stats.docCount : 0) + ' 篇');
    } catch {
      // api.js 已 toast
    }
  });

  root.querySelector('#admin-reply-send').addEventListener('click', async () => {
    const content = replyInput.value.trim();
    if (!content || !selectedId) return;
    try {
      await post('/support/admin/tickets/' + selectedId + '/reply', { content });
      replyInput.value = '';
      toast('已回复用户');
      await loadMessages(selectedId);
      await loadTickets();
    } catch {
      // api.js 已 toast
    }
  });

  replyInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      root.querySelector('#admin-reply-send').click();
    }
  });

  root.querySelector('#admin-finish').addEventListener('click', async () => {
    if (!selectedId) return;
    const ok = await confirmDialog('结束会话', '结束后将恢复 AI 客服，确定吗？');
    if (!ok) return;
    try {
      await post('/support/admin/tickets/' + selectedId + '/close', {});
      toast('已结束人工会话');
      selectedId = null;
      stopMsgTimer();
      detailEl.classList.add('hidden');
      await loadTickets();
    } catch {
      // api.js 已 toast
    }
  });

  root.querySelector('#admin-tabs').addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-status], button[data-view]');
    if (!btn) return;
    root.querySelectorAll('#admin-tabs button').forEach(b => b.classList.toggle('active', b === btn));
    selectedId = null;
    stopMsgTimer();
    detailEl.classList.add('hidden');
    if (btn.dataset.view === 'feedback') {
      currentView = 'feedback';
      await loadFeedback();
      return;
    }
    currentView = 'tickets';
    currentStatus = Number(btn.dataset.status);
    await loadTickets();
  });

  /**
   * 【企业级客服Agent的实现】待接入/人工中工单与差评反馈自动刷新：20 秒一次。
   * 页面销毁（document.contains 守卫）自动停止；已结束 tab 不刷新，避免无谓请求。
   */
  setInterval(async () => {
    if (!document.contains(root)) return;
    if (currentView === 'feedback') {
      await loadFeedback();
      return;
    }
    if (currentStatus === 2) return;
    await loadTickets();
    if (selectedId) await loadMessages(selectedId);
  }, 20000);

  await loadTickets();
}
