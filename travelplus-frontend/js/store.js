/**
 * 【本地存储的实现】token 与用户信息的读写封装
 * 职责：对 localStorage 进行统一 key 管理、安全读写与登出清理
 * 依赖：无外部模块依赖（纯浏览器 API）
 * - TOKEN_KEY='travelplus_token' / USER_KEY='travelplus_user'：避免 key 冲突
 * - getUser：JSON.parse 加 try/catch 防损坏数据导致崩溃
 * - isLoggedIn：基于 token 存在性判定，轻量无网络开销
 * - logout：同时清除 token 与用户信息，保证状态一致
 */

const TOKEN_KEY = 'travelplus_token';
const USER_KEY = 'travelplus_user';

/**
 * 【getToken】读取本地存储的登录 token
 * - 实现：localStorage.getItem，无 token 时返回空字符串 ''
 * - 注意：空字符串利于 falsy 判断，且传给 authorization 头时不会发送 "null"
 */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

/**
 * 【setToken】写入登录 token 到本地存储
 * - 实现：localStorage.setItem 直接覆盖
 */
export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

/**
 * 【clearToken】清除本地存储的登录 token
 * - 实现：localStorage.removeItem
 */
export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

/**
 * 【getUser】读取本地存储的用户信息对象
 * - 实现：JSON.parse 反序列化，try/catch 防损坏数据；无数据返回 null
 * - 注意：localStorage 可能被手动篡改，返回 null 而非崩溃
 */
export function getUser() {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/**
 * 【setUser】将用户对象序列化后写入本地存储
 * - 实现：JSON.stringify → localStorage.setItem
 */
export function setUser(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

/**
 * 【clearUser】清除本地存储的用户信息
 * - 实现：localStorage.removeItem
 */
export function clearUser() {
  localStorage.removeItem(USER_KEY);
}

/**
 * 【isLoggedIn】判断当前是否已登录
 * - 实现：检查 token 是否存在（!!getToken()）
 * - 注意：不验证 token 有效性（由 api.js 401 处理兜底），仅本地快速判断
 */
export function isLoggedIn() {
  return !!getToken();
}

/**
 * 【logout】登出：同时清除 token 和用户信息
 * - 实现：clearToken + clearUser 双清，确保状态一致
 * - 注意：不发送网络请求（由调用方按需配合 POST /user/logout）
 */
export function logout() {
  clearToken();
  clearUser();
}
