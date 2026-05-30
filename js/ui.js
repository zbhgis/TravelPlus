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
 * 【formatDate】ISO 时间 → 友好相对时间字符串
 * - 实现：计算与当前时间差值，分级返回"刚刚"/"x分钟前"/"x小时前"/"x天前"/完整日期 yyyy-MM-dd
 * - 边界：iso 为空返回 ''；Date 解析失败返回原字符串
 */
export function formatDate(iso) {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    const now = new Date();
    const diff = now - d;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前';
    if (diff < 604800000) return Math.floor(diff / 86400000) + '天前';
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return y + '-' + m + '-' + day;
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
