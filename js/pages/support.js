/**
 * 【AI客服的实现】#/support 客服对话页面渲染模块（异步客服——后端线程池生成回复，前端轮询 chat/list 获取）
 * 职责：聊天 UI（用户气泡/客服气泡）、发送消息（fire-and-forget）、"思考中…"占位气泡、轮询获取 AI 回复、超时兜底、滚动到底部
 * 依赖：api.js (get/post/del)、ui.js (toast/confirmDialog)、router.js (refreshMessagesBadge)
 * 核心链路：
 *   renderSupport(container)
 *     → 渲染聊天 UI：顶部返回 + 标题 + 清空按钮、消息列表区、底部输入框+发送按钮
 *     → GET /chat/list?peerId={CUSTOMER_ID}&current=1 加载后端历史消息并渲染（含欢迎语若空）
 *     → 用户发送消息 → 追加用户气泡 → POST /support/chat {message}（fire-and-forget，不等待回复）
 *     → 追加「思考中...」占位气泡 → 启动轮询（每 2s）GET /chat/list?peerId={CUSTOMER_ID}&current=1
 *     → 检测到新的 AI 回复（assistant 侧消息，按 fromUserId 最大消息 id 判断新增，兼容后端分页）→ 移除占位 + 追加 AI 回复气泡 → 停止轮询
 *     → 30 秒超时兜底：移除占位 → toast"客服响应超时，请稍后重试" → 停止轮询
 *     → 页面离开时 clearInterval 清理定时器（通过 document.contains 守卫）
 * - 发送防重复：发送中 + 等待回复期间按钮 disabled（收到回复或超时后恢复）
 * - 客服对话走后端消息表：消息栏「私信」会话列表可自然出现客服会话（无需前端额外处理）
 * - 客服 id 常量：CUSTOMER_ID=1021（与后端 tb_chat_message 的客服虚拟用户 id 一致）
 * - 空历史时显示欢迎语（不写库，仅前端渲染）
 * - 清空对话：保留（DELETE /chat/session/{CUSTOMER_ID} + 重置欢迎语）
 * - 滚动到底部：每次消息更新后 scrollTop = scrollHeight
 */
import { get, post, del } from '../api.js';
import { toast, confirmDialog } from '../ui.js';
import { refreshMessagesBadge } from '../router.js';

/** 【AI客服的实现】客服虚拟用户 ID（与后端 tb_chat_message 一致） */
const CUSTOMER_ID = 1021;

/**
 * 【AI客服的实现】欢迎语文案（空历史时前端显示，不写后端消息表）
 * 实现细节：
 * - GET /chat/list 返回空数组时，chatHistory 仅含此欢迎语
 * - 用户发送第一条消息后才真正写入后端消息表（POST /support/chat）
 */
const WELCOME_MSG = '你好，我是 TravelPlus 智能客服，请问有什么可以帮你？';

export async function renderSupport(container) {
  container.innerHTML = `
    <div id="support-root">
      <div class="chat-page">
        <div class="page-header">
          <button class="header-back" id="support-back">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
          </button>
          <h2>TravelPlus 客服</h2>
          <!-- 【AI客服的实现】清空对话按钮：页面右上角，点击后 confirmDialog → DELETE /chat/session/{CUSTOMER_ID} + 重置欢迎语 -->
          <button class="header-right" id="support-clear" style="font-size:13px;color:var(--danger);padding:4px 8px">清空</button>
        </div>
        <div class="chat-messages" id="chat-messages">
        </div>
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

  /** 【AI客服的实现】聊天历史数组（内存中的运行副本，与后端消息表同步） */
  let chatHistory = [];

  /**
   * 【AI客服的实现】后端当前已有的 AI 回复最大消息 id（不含欢迎语）
   * 实现细节：
   * - 初始加载时由 loadHistoryFromBackend() 设置
   * - 每次轮询检测到新增 AI 回复后更新
   * - 用于判断 GET /chat/list 是否返回了新回复（后端按 create_time 倒序分页，每页最多 10 条；
   *   用数量比较会在消息超过一页时失效——新回复挤入首页的同时把最旧一条客服消息挤出首页，计数不变。
   *   改用最大 id 比较：新回复 id 必大于历史最大 id 且必在首页，不受分页截断影响）
   */
  let backendMaxAssistantId = 0;

  /**
   * 【AI客服的实现】轮询定时器引用（用于页面离开时清理）
   * 实现细节：
   * - sendMessage 启动轮询时赋值 setInterval 返回值
   * - 收到回复 / 超时 / 页面离开时 clearInterval
   */
  let pollTimer = null;

  /**
   * 【AI客服的实现】超时定时器引用（30 秒兜底）
   * 实现细节：
   * - sendMessage 启动 30 秒 setTimeout
   * - 收到回复或手动停止时 clearTimeout
   */
  let timeoutTimer = null;

  // 返回按钮
  root.querySelector('#support-back').addEventListener('click', () => window.history.back());

  // 滚动到底部辅助函数
  function scrollToBottom() {
    chatMessages.scrollTop = chatMessages.scrollHeight;
  }

  /**
   * 【AI客服的实现】追加消息气泡到聊天区域
   * - 实现：根据 type 创建不同样式的气泡（user=右对齐/assistant=左对齐）
   * - 返回气泡 DOM 元素，用于后续移除（如"思考中…"loading 气泡）
   */
  function appendBubble(type, text) {
    const bubble = document.createElement('div');
    // 兼容旧 type='support' → 统一用 assistant 样式
    const cssType = type === 'user' ? 'user' : 'support';
    bubble.className = 'chat-bubble chat-bubble-' + cssType;
    bubble.innerHTML = '<span>' + text + '</span>';
    chatMessages.appendChild(bubble);
    scrollToBottom();
    return bubble;
  }

  /**
   * 【AI客服的实现】从后端加载聊天历史并渲染全部气泡
   * 实现细节：
   * - GET /chat/list?peerId={CUSTOMER_ID}&current=1 → 历史消息数组
   * - 后端返回数组格式：[{fromUserId, content, createTime}, ...]
   * - fromUserId === CUSTOMER_ID 为客服气泡（左对齐），否则为用户气泡（右对齐）
   * - 空历史时填充欢迎语（chatHistory=[{role:'assistant', content:WELCOME_MSG}]）
 * - 加载失败静默降级：显示欢迎语（chatHistory 仅含欢迎语）
 * - 同时记录后端 AI 回复最大消息 id 到 backendMaxAssistantId（用于后续轮询检测新增）
 */
  async function loadHistoryFromBackend() {
    try {
      const data = await get('/chat/list?peerId=' + CUSTOMER_ID + '&current=1');
      // 后端可能返回 {records: [...]} 包裹或直接返回数组
      const records = Array.isArray(data) ? data : (data && data.records ? data.records : []);
      if (records.length > 0) {
        // 后端消息按时间戳排序（通常倒序），前端需要旧→新展示；若后端已升序则无需 reverse
        // 假设后端返回升序（最旧先），直接映射
        chatHistory = records.map(msg => ({
          role: String(msg.fromUserId) === String(CUSTOMER_ID) ? 'assistant' : 'user',
          content: msg.content || '',
          time: msg.createTime || ''
        }));
        // 检查是否需要反转：若第一条时间 > 最后一条时间（倒序），则 reverse
        if (chatHistory.length >= 2 && chatHistory[0].time > chatHistory[chatHistory.length - 1].time) {
          chatHistory.reverse();
        }
        // 【AI客服的实现】记录后端 AI 回复最大消息 id，用于轮询检测新增回复
        // 实现细节：取 fromUserId === CUSTOMER_ID 的消息中最大 id（Number 归一化处理 number/string），
        // 不含前端欢迎语。后端倒序返回时首页第一条即最新，最大 id 必在首页，不受分页影响
        const maxId = records
          .filter(r => String(r.fromUserId) === String(CUSTOMER_ID))
          .reduce((acc, r) => Math.max(acc, Number(r.id) || 0), 0);
        backendMaxAssistantId = maxId;
      } else {
        // 空历史 → 仅含欢迎语
        chatHistory = [{
          role: 'assistant',
          content: WELCOME_MSG,
          time: new Date().toISOString()
        }];
        backendMaxAssistantId = 0;
      }
    } catch {
      // 加载失败静默降级：仅含欢迎语
      chatHistory = [{
        role: 'assistant',
        content: WELCOME_MSG,
        time: new Date().toISOString()
      }];
      backendMaxAssistantId = 0;
    }
  }

  /**
   * 【AI客服的实现】渲染全部历史聊天记录到 DOM
   * 实现细节：
   * - 清空 chatMessages → 遍历 chatHistory 按 role 渲染对应气泡
   * - 欢迎语已在 loadHistoryFromBackend() 中自动补入（空历史时）
   */
  function renderAllHistory() {
    chatMessages.innerHTML = '';
    chatHistory.forEach(msg => {
      appendBubble(msg.role, msg.content);
    });
  }

  // 初始加载历史并渲染
  await loadHistoryFromBackend();
  renderAllHistory();

  /**
   * 【AI客服的实现】停止所有轮询与超时定时器，恢复发送按钮
   * 实现细节：
   * - 清空 pollTimer / timeoutTimer 引用
   * - 恢复发送按钮可用状态
   * - 页面离开时由 document.contains 守卫自动调用
   */
  function stopPollingAndReset() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    if (timeoutTimer) { clearTimeout(timeoutTimer); timeoutTimer = null; }
    chatSend.disabled = false;
    chatSend.textContent = '发送';
  }

  /**
   * 【AI客服的实现】发送消息主流程（异步客服——后端线程池生成回复，前端轮询获取）
   * - 实现：
   *   1. 空内容直接 return
   *   2. 发送中防重复：按钮 disabled + 文字"发送中…"
   *   3. 追加用户气泡 + 追加到 chatHistory（本地即时显示）+ 清空输入框
 *   4. 记录当前后端 AI 回复最大消息 id（maxAssistantIdBefore）→ 用于轮询检测新增
 *   5. 追加"思考中…"loading 占位气泡（.chat-bubble-loading，三点脉动动画）
 *   6. POST /support/chat {message} → fire-and-forget（不等待回复，后端异步线程池生成）
 *   7. 启动轮询（每 2s）GET /chat/list?peerId={CUSTOMER_ID}&current=1：
 *      - 比较后端返回的 AI 回复最大消息 id 与 maxAssistantIdBefore
 *      - id 变大 → 检测到新回复 → 移除 loading 占位 + 追加 AI 回复气泡 → 更新 chatHistory → 停止轮询
   *      - 页面已离开（!document.contains(chatMessages)）→ 自动停止轮询 + 清理定时器
   *   8. 启动 30 秒超时兜底：到时间后移除占位 → toast"客服响应超时，请稍后重试" → 停止轮询
   *   9. 恢复按钮状态（收到回复或超时后）
   * - 客服对话走后端消息表：后端 /support/chat 会将对话写入 tb_chat_message，
   *   消息页「私信」会话列表 GET /chat/sessions 可自然出现客服会话
   */
  async function sendMessage() {
    const text = chatInput.value.trim();
    if (!text) return;

    // 【AI客服的实现】防并发发送：按钮禁用期间（发送中/等待回复中）直接忽略
    // 实现细节：回车快捷键不检查按钮 disabled 状态，需在此兜底，
    // 否则会追加第二个"思考中…"占位气泡并启动第二个轮询，导致 AI 回复后占位残留（用户→AI回复→思考中）
    if (chatSend.disabled) return;

    // 防重复发送：按钮 disabled + 文字变为"发送中…"（收到回复或超时后恢复）
    chatSend.disabled = true;
    chatSend.textContent = '发送中…';

    // 本地追加用户气泡（即时显示）
    appendBubble('user', text);
    chatHistory.push({ role: 'user', content: text, time: new Date().toISOString() });
    chatInput.value = '';

    /**
     * 【AI客服的实现】记录发送前后端已有的 AI 回复最大消息 id
     * 实现细节：
     * - 从 backendMaxAssistantId（初始加载时设置）取值
     * - 轮询时比较后端返回的 AI 回复最大 id 是否 > 此值，判断是否有新回复到达
     * - 不含前端欢迎语（欢迎语不在后端消息表，不影响比较）
     */
    const maxAssistantIdBefore = backendMaxAssistantId;

    // 追加"思考中…"占位气泡（客服侧，三点脉动动画）
    const loadingBubble = document.createElement('div');
    loadingBubble.className = 'chat-bubble chat-bubble-support chat-bubble-loading';
    loadingBubble.innerHTML = '<span class="chat-loading-dots">思考中<span class="dot1">.</span><span class="dot2">.</span><span class="dot3">.</span></span>';
    chatMessages.appendChild(loadingBubble);
    scrollToBottom();

    /**
     * 【AI客服的实现】发送请求（fire-and-forget——不等待 AI 回复）
     * 实现细节：
     * - POST /support/chat {message} 仅负责把用户消息写入后端消息表
     * - AI 回复由后端线程池异步生成并写入 tb_chat_message
     * - 前端通过轮询 GET /chat/list 获取 AI 回复
     * - 发送失败静默处理：轮询仍继续（后端可能已收到但返回失败）
     */
    post('/support/chat', { message: text }).catch(() => {
      // 发送失败静默处理：不阻断轮询（后端可能已写入消息表但 HTTP 响应异常）
    });

    /**
     * 【AI客服的实现】启动轮询获取 AI 回复
     * 实现细节：
     * - 每 2 秒 GET /chat/list?peerId={CUSTOMER_ID}&current=1
     * - 页面离开守卫：document.contains(chatMessages) 为 false 时自动停止（DOM 已被销毁）
     * - 检测逻辑：统计后端返回中 fromUserId === CUSTOMER_ID 的消息最大 id，若 > maxAssistantIdBefore 则新回复到达
     *   （后端按 create_time 倒序分页、每页最多 10 条；新回复 id 必最大且必在首页，故不受分页截断影响）
     * - 新回复到达后：移除占位气泡 → 追加 AI 回复气泡 → 更新 chatHistory + backendMaxAssistantId → 停止轮询
     * - 轮询失败静默继续（不 toast，避免每 2 秒弹一次错误）
     */
    pollTimer = setInterval(async () => {
      // 【AI客服的实现】页面离开守卫：DOM 已销毁则自动停止轮询并清理
      if (!document.contains(chatMessages)) {
        stopPollingAndReset();
        return;
      }

      try {
        const data = await get('/chat/list?peerId=' + CUSTOMER_ID + '&current=1');
        const records = Array.isArray(data) ? data : (data && data.records ? data.records : []);

        // 统计后端当前返回的 AI 回复最大消息 id（fromUserId === CUSTOMER_ID）
        const assistantMsgs = records.filter(r => String(r.fromUserId) === String(CUSTOMER_ID));
        const currentMaxAssistantId = assistantMsgs
          .reduce((acc, r) => Math.max(acc, Number(r.id) || 0), 0);

        /**
         * 【AI客服的实现】检测到新的 AI 回复
         * 实现细节：
         * - currentMaxAssistantId > maxAssistantIdBefore → 后端已生成新回复
         * - 提取新增的 AI 消息（过滤出 id 大于历史最大 id 的部分，不再按数量 slice——
         *   分页后首页可能同时被挤出最旧消息，数量比较会失效）
         */
        if (currentMaxAssistantId > maxAssistantIdBefore) {
          // 移除"思考中…"占位气泡（全量清理 .chat-bubble-loading，含历史遗留多个占位一并移除，保证不残留）
          chatMessages.querySelectorAll('.chat-bubble-loading').forEach(b => b.remove());

          // 提取新增的 AI 回复（id 大于发送前最大 id 的消息）
          const newAssistantMsgs = assistantMsgs.filter(r => Number(r.id) > maxAssistantIdBefore);

          // 追加新增的 AI 回复气泡
          newAssistantMsgs.forEach(msg => {
            appendBubble('assistant', msg.content || '');
            chatHistory.push({
              role: 'assistant',
              content: msg.content || '',
              time: msg.createTime || ''
            });
          });

          // 更新后端 AI 回复最大消息 id（用于下次发送时的基线比较）
          backendMaxAssistantId = currentMaxAssistantId;

          scrollToBottom();
          stopPollingAndReset();

          /** 【消息中心的实现】收到 AI 回复后刷新 tabbar 消息红点
           * 实现细节：
           * - AI 回复写入聊天消息表后，消息红点可能变化
           * - 异步刷新不阻塞 UI
           */
          refreshMessagesBadge();
        }
      } catch {
        // 轮询失败静默继续（不 toast，避免每 2 秒弹一次错误提示）
      }
    }, 2000);

    /**
     * 【AI客服的实现】30 秒超时兜底
     * 实现细节：
     * - 到时间后：移除"思考中…"占位 → toast 提示"客服响应超时，请稍后重试"
     * - 停止轮询 + 恢复按钮可用状态
     * - 使用 loadingBubble.parentNode 检查占位是否已被移除（收到回复时已移除，无需重复操作）
     */
    timeoutTimer = setTimeout(() => {
      // 移除所有"思考中…"占位气泡（与轮询成功路径一致的清理方式，避免超时后占位残留）
      chatMessages.querySelectorAll('.chat-bubble-loading').forEach(b => b.remove());
      stopPollingAndReset();
      toast('客服响应超时，请稍后重试');
    }, 30000);
  }

  // 发送按钮点击事件
  chatSend.addEventListener('click', sendMessage);

  // 回车发送
  chatInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      sendMessage();
    }
  });

  /**
   * 【AI客服的实现】清空对话按钮（改用后端会话删除）
   * 实现细节：
   * - confirmDialog 二次确认 → DELETE /chat/session/{CUSTOMER_ID} 清后端消息表会话
   * - 成功后重置 chatHistory 为仅含欢迎语 + 重新渲染气泡
   * - 同时重置 backendMaxAssistantId = 0（清空后后端无 AI 回复）
   * - 失败 toast errorMsg（api.js 自动处理）
   * - 清空后消息栏私信会话列表也会消失（后端消息表已清空）
   */
  root.querySelector('#support-clear').addEventListener('click', async () => {
    const ok = await confirmDialog('清空对话', '确定要清空所有聊天记录吗？');
    if (!ok) return;
    try {
      await del('/chat/session/' + CUSTOMER_ID);
      chatHistory = [{
        role: 'assistant',
        content: WELCOME_MSG,
        time: new Date().toISOString()
      }];
      backendMaxAssistantId = 0;
      renderAllHistory();
      toast('对话已清空');
    } catch {
      // api.js 已 toast errorMsg
    }
  });
}
