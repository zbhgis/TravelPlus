import { get, post, put } from '../api.js';
import { imgUrl, imgError, toast } from '../ui.js';
import { isLoggedIn, getUser } from '../store.js';

/** 【编辑资料页的实现】用户资料编辑页面渲染
 * 实现细节：
 * - 路由 #/user/edit，需登录，否则跳 #/login?redirect=user/edit
 * - 初始数据来源：GET /user/info/{自己id}（含 followVisible/fansVisible/bgImage/introduce/city/gender/birthday）
 *   + getUser()（nickName/icon）
 * - 表单字段：昵称（≤20字非空）、简介、城市、性别（radio 男=0 女=1）、生日（date yyyy-MM-dd）
 * - 头像更换：点击当前头像 → 选文件 → POST /upload/user（FormData "file"）→ 返回相对路径 → 更新预览
 * - 背景图更换：点击当前背景预览格 → 选文件 → POST /upload/user → 返回相对路径 → 更新预览
 * - 隐私开关：followVisible（关注列表可见）、fansVisible（粉丝列表可见），toggle 切换 true/false
 * - 保存：PUT /user/info {nickName,icon,bgImage,introduce,city,gender,birthday,followVisible,fansVisible}
 *   仅传有值的字段；成功 toast + 跳回 #/user/{自己id}；失败 api.js 自动 toast errorMsg
 * - 上传模式参考 note-edit.js：FormData + post('/upload/user', formData)，不加 Content-Type
 * - imgUrl 处理上传返回的相对路径（如 user/d1/d2/x.jpg）→ 拼 /imgs/ 前缀 ✓
 */
export async function renderUserEdit(container) {
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=user/edit';
    return;
  }

  const me = getUser();
  if (!me || !me.id) {
    toast('请先登录');
    window.location.hash = '#/login';
    return;
  }

  const myId = me.id;

  container.innerHTML = `
    <div id="user-edit-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#user-edit-root');

  try {
    // 并行获取用户信息详情 + 当前用户基础数据
    const infoData = await get('/user/info/' + myId);
    const currentUser = getUser();

    // infoData 可能为 null（新用户无 info 记录）
    const info = infoData || {};
    const nickName = currentUser.nickName || '';
    const icon = currentUser.icon || '';

    // 状态变量：头像路径、背景图路径
    let iconPath = icon;
    let bgImagePath = info.bgImage || '';

    /** 【编辑资料页的实现】表单 HTML 构建
     * - 头像上传区：圆形预览 + 点击更换提示，隐藏 file input
     * - 背景图上传区：宽条预览 + 点击更换提示，隐藏 file input
     * - 表单：昵称、简介、城市、性别 radio（男/女）、生日 date
     * - 隐私开关：followVisible、fansVisible toggle
     * - 保存按钮：btn-primary btn-block
     */
    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="edit-info-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>编辑资料</h2>
      </div>
      <div class="edit-profile-form">
        <!-- 头像更换 -->
        <div class="form-group">
          <label>头像</label>
          <div class="avatar-upload-wrap">
            <img class="avatar-upload-preview" id="avatar-preview" src="${imgUrl(iconPath)}" alt="头像" onerror="imgError(event)"/>
            <span class="avatar-upload-hint">点击更换头像</span>
          </div>
          <input type="file" id="avatar-input" accept="image/*" hidden/>
        </div>
        <!-- 背景图更换 -->
        <div class="form-group">
          <label>个人主页背景</label>
          <div class="bg-upload-wrap" id="bg-upload-wrap">
            ${bgImagePath
              ? '<img class="bg-upload-preview" id="bg-preview" src="' + imgUrl(bgImagePath) + '" alt="背景图" onerror="imgError(event)"/>'
              : '<div class="bg-upload-placeholder" id="bg-preview">点击更换背景图</div>'}
          </div>
          <input type="file" id="bg-input" accept="image/*" hidden/>
        </div>
        <!-- 昵称 -->
        <div class="form-group">
          <label>昵称</label>
          <input type="text" id="edit-nickname" value="${escapeHtml(nickName)}" placeholder="请输入昵称（最多20字）" maxlength="20"/>
        </div>
        <!-- 简介 -->
        <div class="form-group">
          <label>简介</label>
          <textarea id="edit-introduce" placeholder="介绍一下自己..." rows="3">${escapeHtml(info.introduce || '')}</textarea>
        </div>
        <!-- 城市 -->
        <div class="form-group">
          <label>城市</label>
          <input type="text" id="edit-city" value="${escapeHtml(info.city || '')}" placeholder="所在城市"/>
        </div>
        <!-- 性别 -->
        <div class="form-group">
          <label>性别</label>
          <div class="gender-radio-group">
            <label class="gender-radio">
              <!-- 【用户主页编辑的实现】性别回显：后端 UserInfo.gender 可能是 Integer(0/1) 或 Boolean(false/true)，需双重判断兼容 -->
              <input type="radio" name="gender" value="0" ${info.gender === 0 || info.gender === false ? 'checked' : ''}/>
              <span>男</span>
            </label>
            <label class="gender-radio">
              <input type="radio" name="gender" value="1" ${info.gender === 1 || info.gender === true ? 'checked' : ''}/>
              <span>女</span>
            </label>
          </div>
        </div>
        <!-- 生日 -->
        <div class="form-group">
          <label>生日</label>
          <input type="date" id="edit-birthday" value="${info.birthday || ''}"/>
        </div>
        <!-- 隐私开关 -->
        <div class="form-group">
          <label>隐私设置</label>
          <div class="privacy-toggles">
            <div class="privacy-toggle-item">
              <span>关注列表可见</span>
              <label class="toggle-switch">
                <!-- 【用户主页编辑的实现】隐私开关回显：后端返回 Integer(0/1) 或 Boolean，双重判断兼容；0/false=不可见 -->
                <input type="checkbox" id="toggle-follow-visible" ${info.followVisible === 1 || info.followVisible === true ? 'checked' : ''}/>
                <span class="toggle-slider"></span>
              </label>
            </div>
            <div class="privacy-toggle-item">
              <span>粉丝列表可见</span>
              <label class="toggle-switch">
                <input type="checkbox" id="toggle-fans-visible" ${info.fansVisible === 1 || info.fansVisible === true ? 'checked' : ''}/>
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>
        <!-- 保存 -->
        <button class="btn btn-primary btn-block mt-16" id="edit-info-submit">保存</button>
      </div>
    `;

    // 返回按钮
    root.querySelector('#edit-info-back').addEventListener('click', () => window.history.back());

    /** 【编辑资料页的实现】头像上传交互
     * - 点击头像预览区域触发 file input
     * - 选文件后 FormData POST /upload/user → 返回相对路径
     * - 更新预览（imgUrl 生成完整 URL）+ 更新 iconPath 变量
     * - 上传失败 toast errorMsg（api.js 自动处理）
     */
    const avatarPreview = root.querySelector('#avatar-preview');
    const avatarUploadWrap = root.querySelector('.avatar-upload-wrap');
    const avatarInput = root.querySelector('#avatar-input');
    avatarUploadWrap.addEventListener('click', () => avatarInput.click());
    avatarInput.addEventListener('change', async () => {
      const file = avatarInput.files[0];
      if (!file) return;
      try {
        const formData = new FormData();
        formData.append('file', file);
        const name = await post('/upload/user', formData);
        iconPath = name;
        avatarPreview.src = imgUrl(name);
      } catch {
        // api.js 已 toast errorMsg
      }
      avatarInput.value = '';
    });

    /** 【编辑资料页的实现】背景图上传交互
     * - 点击背景图预览区域触发 file input
     * - 选文件后 FormData POST /upload/user → 返回相对路径
     * - 更新预览（替换占位文本为 img 标签）+ 更新 bgImagePath 变量
     * - 无背景图时显示占位文字「点击更换背景图」
     */
    const bgWrap = root.querySelector('#bg-upload-wrap');
    const bgInput = root.querySelector('#bg-input');
    bgWrap.addEventListener('click', () => bgInput.click());
    bgInput.addEventListener('change', async () => {
      const file = bgInput.files[0];
      if (!file) return;
      try {
        const formData = new FormData();
        formData.append('file', file);
        const name = await post('/upload/user', formData);
        bgImagePath = name;
        bgWrap.innerHTML = '<img class="bg-upload-preview" id="bg-preview" src="' + imgUrl(name) + '" alt="背景图" onerror="imgError(event)"/>';
      } catch {
        // api.js 已 toast errorMsg
      }
      bgInput.value = '';
    });

    /** 【编辑资料页的实现】保存按钮逻辑
     * - 收集表单字段 + 隐私开关状态
     * - 前端校验：昵称非空且 ≤20 字
     * - 构建 body，仅传有值的字段（后端按非空更新）
     * - PUT /user/info → 成功 toast"保存成功" + 跳 #/user/{自己id} → 失败 api.js 自动 toast
     * - 【用户主页编辑的实现】字段类型转换：
     *   gender radio value 是字符串 "0"/"1"，需 parseInt 转 Integer
     *   followVisible/fansVisible checkbox.checked 是 Boolean，后端 UserEditDTO 用 Integer(0/1)，需显式转换 ? 1 : 0
     */
    const submitBtn = root.querySelector('#edit-info-submit');
    submitBtn.addEventListener('click', async () => {
      const nick = root.querySelector('#edit-nickname').value.trim();
      const introduce = root.querySelector('#edit-introduce').value.trim();
      const city = root.querySelector('#edit-city').value.trim();
      const genderRadio = root.querySelector('input[name="gender"]:checked');
      const birthday = root.querySelector('#edit-birthday').value;
      // 隐私开关：checkbox.checked 是 Boolean，转为 Integer 0/1 匹配后端 UserEditDTO
      const followVisible = root.querySelector('#toggle-follow-visible').checked ? 1 : 0;
      const fansVisible = root.querySelector('#toggle-fans-visible').checked ? 1 : 0;

      // 前端校验：昵称非空且 ≤20 字
      if (!nick) {
        toast('请输入昵称');
        return;
      }
      if (nick.length > 20) {
        toast('昵称最多20个字');
        return;
      }

      // 构建 body：仅传有值的字段（后端按非空更新）
      const body = {};
      if (nick) body.nickName = nick;
      if (iconPath) body.icon = iconPath;
      if (bgImagePath) body.bgImage = bgImagePath;
      if (introduce) body.introduce = introduce;
      if (city) body.city = city;
      // genderRadio.value 是字符串 "0"/"1"，parseInt 转 Integer 后发送
      if (genderRadio) body.gender = parseInt(genderRadio.value);
      if (birthday) body.birthday = birthday;
      body.followVisible = followVisible;
      body.fansVisible = fansVisible;

      submitBtn.disabled = true;
      submitBtn.textContent = '保存中...';
      try {
        await put('/user/info', body);
        toast('保存成功');
        window.location.hash = '#/user/' + myId;
      } catch {
        // api.js 已 toast errorMsg
        submitBtn.disabled = false;
        submitBtn.textContent = '保存';
      }
    });

    return () => {};
  } catch {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }
}

/** 【编辑资料页的实现】XSS 防护：文本内容 HTML 转义
 * 实现细节：
 * - 表单回显值使用 escapeHtml 处理，防止昵称/简介/城市中特殊字符破坏 HTML
 * - 参考 note-detail.js 的 escapeHtml 实现
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
