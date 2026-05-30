/**
 * 【登录页的实现】#/login 页面渲染模块
 * 职责：手机号+验证码/密码双模式登录、验证码倒计时、密码可见性切换、登录成功回跳
 * 依赖：api.js (post/get)、store.js (setToken/setUser)、ui.js (toast/getUrlParam)
 * 核心链路：
 *   - main.js → register('/login', renderLogin) → hashchange 触发 renderLogin(app)
 *   - renderLogin(app) 渲染 HTML → 绑定事件 → 返回 cleanup 函数（清理倒计时定时器）
 * 页面结构：
 *   - login-tabs：验证码登录 / 密码登录 pill 切换（默认验证码模式）
 *   - 两种模式共用手机号输入框；验证码模式显示 code-row + 获取验证码按钮；密码模式显示 password-row + 眼睛图标
 *   - 底部 register-link：没有账号？→ #/register
 * 交互细节：
 *   - 验证码发送：POST /user/code?phone= → 60s 倒计时禁用按钮
 *   - 登录提交：验证码模式 POST /user/login {phone, code}；密码模式 POST /user/login {phone, password}
 *   - 成功 → handleLoginSuccess: setToken → GET /user/me → setUser → 回跳 redirect 或跳 #/home
 *   - 失败 → api.js 统一 toast errorMsg（catch 块静默）
 *   - cleanup：清除 countdownTimer 定时器，防止内存泄漏
 */
import { post, get } from '../api.js';
import { setToken, setUser, getToken } from '../store.js';
import { toast, getUrlParam } from '../ui.js';

let countdownTimer = null;
let currentMode = 'code';

const eyeOpenSvg = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
const eyeClosedSvg = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

export function renderLogin(container) {
  container.innerHTML = `
    <div class="login-page">
      <div class="login-logo">TravelPlus</div>
      <div class="login-sub">探索世界，从这里开始</div>
      <div class="login-tabs">
        <button class="login-tab active" data-tab="code">验证码登录</button>
        <button class="login-tab" data-tab="password">密码登录</button>
      </div>
      <div class="login-form">
        <div class="phone-row">
          <input type="tel" id="login-phone" placeholder="请输入手机号" maxlength="11" value="13686869696"/>
        </div>
        <div class="phone-row" id="code-row">
          <input type="text" id="login-code" placeholder="请输入验证码" maxlength="6"/>
          <button class="code-btn" id="login-send-code">获取验证码</button>
        </div>
        <div class="phone-row hidden" id="password-row">
          <div class="password-wrap">
            <input type="password" id="login-password" placeholder="请输入密码"/>
            <button class="pwd-toggle" id="pwd-toggle">${eyeOpenSvg}</button>
          </div>
        </div>
        <button class="btn btn-primary btn-block" id="login-submit">登录</button>
      </div>
      <div class="login-hint" id="code-hint">演示环境：验证码发送在后端控制台日志/Redis，可向管理员索取</div>
      <div class="login-register-link">
        没有账号？<a href="#/register">立即注册</a>
      </div>
    </div>
  `;

  const tabBtns = container.querySelectorAll('.login-tab');
  const codeRow = container.querySelector('#code-row');
  const passwordRow = container.querySelector('#password-row');
  const codeHint = container.querySelector('#code-hint');
  const phoneInput = container.querySelector('#login-phone');
  const codeInput = container.querySelector('#login-code');
  const passwordInput = container.querySelector('#login-password');
  const pwdToggle = container.querySelector('#pwd-toggle');
  const sendBtn = container.querySelector('#login-send-code');
  const submitBtn = container.querySelector('#login-submit');

  /** 【密码登录的实现】双 tab 切换：验证码模式与密码模式互斥显示
   *  - 通过 `hidden` 类控制 code-row / password-row 的显示与隐藏
   *  - 验证码提示文字仅验证码模式可见
   *  - 两种模式共用同一个手机号输入框
   */
  tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      currentMode = btn.dataset.tab;
      tabBtns.forEach(b => b.classList.toggle('active', b.dataset.tab === currentMode));
      codeRow.classList.toggle('hidden', currentMode !== 'code');
      passwordRow.classList.toggle('hidden', currentMode !== 'password');
      codeHint.classList.toggle('hidden', currentMode !== 'code');
    });
  });

  // password visibility toggle
  pwdToggle.addEventListener('click', () => {
    const isPassword = passwordInput.type === 'password';
    passwordInput.type = isPassword ? 'text' : 'password';
    pwdToggle.innerHTML = isPassword ? eyeClosedSvg : eyeOpenSvg;
  });

  // send verification code
  sendBtn.addEventListener('click', async () => {
    const phone = phoneInput.value.trim();
    if (!/^1\d{10}$/.test(phone)) {
      toast('请输入正确的手机号');
      return;
    }
    try {
      await post('/user/code?phone=' + phone);
      toast('验证码已发送');
      let sec = 60;
      sendBtn.disabled = true;
      sendBtn.textContent = sec + 's';
      countdownTimer = setInterval(() => {
        sec--;
        if (sec <= 0) {
          clearInterval(countdownTimer);
          sendBtn.disabled = false;
          sendBtn.textContent = '获取验证码';
        } else {
          sendBtn.textContent = sec + 's';
        }
      }, 1000);
    } catch {}
  });

  /** 【密码登录的实现】公共登录成功流程（验证码/密码两种模式共用）
   *  - 存 token（setToken）→ 获取用户信息（GET /user/me）→ 存用户（setUser）
   *  - 支持回跳：有 redirect 参数则跳回来源页，无则跳首页 #/home
   */
  async function handleLoginSuccess(token) {
    setToken(token);
    const user = await get('/user/me');
    setUser(user);
    toast('登录成功');
    const redirect = getUrlParam('redirect');
    if (redirect) {
      const r = decodeURIComponent(redirect);
      window.location.hash = '#' + (r.startsWith('/') ? r : '/' + r);
    } else {
      window.location.hash = '#/home';
    }
  }

  // submit
  submitBtn.addEventListener('click', async () => {
    const phone = phoneInput.value.trim();
    if (!phone) {
      toast('请输入手机号');
      return;
    }
    if (!/^1\d{10}$/.test(phone)) {
      toast('请输入正确的手机号');
      return;
    }

    if (currentMode === 'code') {
      const code = codeInput.value.trim();
      if (!code) {
        toast('请输入验证码');
        return;
      }
      try {
        const token = await post('/user/login', { phone, code });
        await handleLoginSuccess(token);
      } catch {}
    } else {
      /** 【密码登录的实现】密码模式提交分支
       *  - 前端预校验：非空、≥6 位（拦截无效请求，减少服务端压力）
       *  - 请求体 `{phone, password}`，不带 code 字段；后端据此判断走密码登录分支
       *  - 成功后复用 handleLoginSuccess 公共流程
       */
      const password = passwordInput.value.trim();
      if (!password) {
        toast('请输入密码');
        return;
      }
      if (password.length < 6) {
        toast('密码不能少于6位');
        return;
      }
      try {
        const token = await post('/user/login', { phone, password });
        await handleLoginSuccess(token);
      } catch {}
    }
  });

  return () => {
    if (countdownTimer) {
      clearInterval(countdownTimer);
      countdownTimer = null;
    }
  };
}
