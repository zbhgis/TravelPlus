/**
 * 【接口请求封装的实现】fetch 统一封装层——所有 HTTP 请求的唯一出口
 * 职责：自动注入 authorization、统一解析 Result{success,data,errorMsg}、401 跳登录、FormData 适配与错误 toast
 * 依赖：config.js (API_BASE)、store.js (getToken/clearToken)、ui.js (toast)
 * 核心链路：
 *   request(url, options)
 *     → 1. 注入 authorization（从 getToken 取，非空时携带）
 *     → 2. 自动设置 Content-Type（FormData 不加；普通请求 application/json）
 *     → 3. fetch → 401 则 clearToken + 构造 redirect 参数跳 #/login
 *     → 4. 解析 JSON → body.success 为 false 时 toast errorMsg 并 throw Error
 *     → 5. 成功时返回 body.data（剥离统一包装，调用方直接拿到业务数据）
 *     → 6. catch 块：非重复错误 toast 一次 + 继续 throw 给调用方
 * 思考：get/post/put/del 四个出口函数均复用 request 核心，保证统一行为
 */
import { API_BASE } from './config.js';
import { getToken, clearToken } from './store.js';
import { toast } from './ui.js';

/**
 * 【request】核心请求函数——所有 HTTP 请求的统一入口
 * - authorization 头：自动从 store.getToken() 注入；无 token 时不带
 * - Content-Type：FormData 时不设置（让浏览器自动 multipart/form-data + boundary），其余默认 application/json
 * - 401 处理：清 token + 构造 ?redirect= 参数跳登录页（保留当前 hash 路径便于登录后回跳）
 * - Result 解包：body.success===false → toast errorMsg + throw Error；true → 返回 body.data
 * - FormData 适配：post 检测 data 是否为 FormData 实例，避免 JSON.stringify 破坏文件内容
 */
async function request(url, options = {}) {
  const token = getToken();
  const headers = { ...options.headers };

  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }
  if (token) {
    headers['authorization'] = token;
  }

  try {
    const res = await fetch(API_BASE + url, { ...options, headers });
    if (res.status === 401) {
      clearToken();
      const hash = window.location.hash.slice(1) || '';
      window.location.hash = '#/login' + (hash ? '?redirect=' + encodeURIComponent(hash) : '');
      throw new Error('登录已过期，请重新登录');
    }
    const body = await res.json();
    if (body && typeof body.success === 'boolean') {
      if (!body.success) {
        toast(body.errorMsg || '操作失败');
        throw new Error(body.errorMsg || '操作失败');
      }
      return body.data;
    }
    return body;
  } catch (e) {
    if (e.message && e.message !== '操作失败' && !e.message.startsWith('登录已过期')) {
      toast(e.message);
    }
    throw e;
  }
}

/**
 * 【get】GET 请求封装
 * - 实现：调用 request(url, {method:'GET'})，返回 body.data
 */
export function get(url) {
  return request(url, { method: 'GET' });
}

/**
 * 【post】POST 请求封装
 * - 实现：body 根据类型处理——FormData 直接发送（文件上传），其余 JSON.stringify
 * - 注意：不手动设置 Content-Type（由 request 内根据 FormData 判断）
 */
export function post(url, data) {
  return request(url, {
    method: 'POST',
    body: data instanceof FormData ? data : JSON.stringify(data)
  });
}

/**
 * 【put】PUT 请求封装
 * - 实现：JSON.stringify(data)，data 为空时 body 为 undefined
 * - 注意：RESTful 更新语义，不发送 null/undefined body
 */
export function put(url, data) {
  return request(url, {
    method: 'PUT',
    body: data ? JSON.stringify(data) : undefined
  });
}

/**
 * 【del】DELETE 请求封装
 * - 实现：调用 request(url, {method:'DELETE'})，无 body
 */
export function del(url) {
  return request(url, { method: 'DELETE' });
}
