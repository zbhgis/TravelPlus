/**
 * 【AI 对话渲染的公共实现】受限 Markdown 渲染工具（AI 助手与智能客服共用）
 * 安全约定：先整体 HTML 转义，再做结构替换，所有动态文本不可注入标签/事件。
 * 支持：标题（#~####）、无序/有序列表、空行分段、行内 **加粗**。
 */

export function escapeHtml(str) {
  return String(str == null ? '' : str)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** 行内渲染：**加粗** → <b>（输入已转义） */
function inline(s) {
  return s.replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>');
}

/** Markdown → 受限 HTML（加粗 + 列表 + 段落 + 标题） */
export function renderMd(text) {
  const lines = escapeHtml(text).split(/\r?\n/);
  const out = [];
  let list = null;
  const closeList = () => { if (list) { out.push('</' + list + '>'); list = null; } };
  for (const raw of lines) {
    const line = raw.trim();
    if (/^#{1,4}\s+/.test(line)) {
      closeList();
      out.push('<p class="ai-md-h">' + inline(line.replace(/^#{1,4}\s+/, '')) + '</p>');
    } else if (/^[-•*]\s+/.test(line)) {
      if (list !== 'ul') { closeList(); out.push('<ul>'); list = 'ul'; }
      out.push('<li>' + inline(line.replace(/^[-•*]\s+/, '')) + '</li>');
    } else if (/^\d+[.、)]\s*/.test(line)) {
      if (list !== 'ol') { closeList(); out.push('<ol>'); list = 'ol'; }
      out.push('<li>' + inline(line.replace(/^\d+[.、)]\s*/, '')) + '</li>');
    } else if (line === '') {
      closeList();
    } else {
      closeList();
      out.push('<p>' + inline(line) + '</p>');
    }
  }
  closeList();
  return out.join('');
}
