/**
 * 【用户注册的实现】#/register 页面渲染模块
 * - 表单字段：手机号（必填，11 位）、昵称（选填，最多 20 字）、密码（≥6 位）、确认密码
 * - 前端校验链：11 位手机号 → 密码非空 → 密码 ≥6 位 → 两次密码一致 → 拦截无效请求
 * - 提交 POST /user/register {phone, password, nickName?}
 * - 成功返回 token → 自动登录（setToken → GET /user/me → setUser）→ toast → 跳 #/home
 * - 失败由 api.js 统一 toast errorMsg（catch 块静默）
 */
import { post, get } from '../api.js';
import { setToken, setUser } from '../store.js';
import { toast } from '../ui.js';

export function renderRegister(container) {
  container.innerHTML = `
    <div class="page-header">
      <button class="header-back" id="reg-back">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
      </button>
      <h2>注册账号</h2>
      <div class="header-right"></div>
    </div>
    <div class="register-page">
      <div class="register-form">
        <input type="tel" id="reg-phone" placeholder="请输入手机号" maxlength="11"/>
        <input type="text" id="reg-nickname" placeholder="昵称（选填）" maxlength="20"/>
        <input type="password" id="reg-password" placeholder="请设置密码（不少于6位）"/>
        <input type="password" id="reg-password2" placeholder="请再次输入密码"/>
        <button class="btn btn-primary btn-block" id="reg-submit">注册</button>
      </div>
    </div>
  `;

  const backBtn = container.querySelector('#reg-back');
  const phoneInput = container.querySelector('#reg-phone');
  const nicknameInput = container.querySelector('#reg-nickname');
  const passwordInput = container.querySelector('#reg-password');
  const password2Input = container.querySelector('#reg-password2');
  const submitBtn = container.querySelector('#reg-submit');

  backBtn.addEventListener('click', () => {
    history.back();
  });

  /** 【用户注册的实现】提交逻辑
   *  - 前端校验链：11 位手机号 → 密码非空 → 密码 ≥6 位 → 两次密码一致
   *  - 任一校验不通过则 toast 提示并 return，拦截无效请求，减少服务端压力
   *  - 昵称为选填字段，仅非空时才加入请求体 data.nickName
   *  - POST /user/register → 成功返回 token → setToken 存本地 → GET /user/me 获取用户 → setUser
   *  - 实现注册即登录：注册成功后自动以 token 身份进入首页 #/home，无需二次登录
   *  - 失败由 api.js 统一 toast errorMsg，catch 块静默
   */
  submitBtn.addEventListener('click', async () => {
    const phone = phoneInput.value.trim();
    const nickname = nicknameInput.value.trim();
    const password = passwordInput.value;
    const password2 = password2Input.value;

    // 校验链：按顺序逐项检查，不通过立即拦截
    if (!phone) {
      toast('请输入手机号');
      return;
    }
    if (!/^1\d{10}$/.test(phone)) {
      toast('请输入正确的手机号');
      return;
    }
    if (!password) {
      toast('请设置密码');
      return;
    }
    if (password.length < 6) {
      toast('密码不能少于6位');
      return;
    }
    if (password !== password2) {
      toast('两次输入的密码不一致');
      return;
    }

    const data = { phone, password };
    if (nickname) {
      data.nickName = nickname;
    }

    try {
      const token = await post('/user/register', data);
      setToken(token);
      const user = await get('/user/me');
      setUser(user);
      toast('注册成功');
      window.location.hash = '#/home';
    } catch {
      // api.js 已统一处理失败 toast，此处静默
    }
  });
}
