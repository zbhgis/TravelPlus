/**
 * 【隐私设置的实现】#/privacy 隐私设置独立页面渲染模块（从编辑资料页抽离）
 * 职责：关注列表可见 / 粉丝列表可见 / 赞过可见 / 收藏可见 / 评论可见 / 足迹可见 六开关 toggle + 私信模式三选
 * 依赖：api.js (get/put)、ui.js (toast)、store.js (isLoggedIn/getUser)
 * 核心链路：
 *   renderPrivacy(container)
 *     → 未登录 → 跳 #/login?redirect=privacy
 *     → 已登录 → GET /user/info/{自己id} 回显全部隐私字段
 *     → 渲染：标题"隐私设置" + 返回按钮 + 六个 iOS toggle 开关 + 私信模式 segmented
 *     → 用户切换开关/模式并点击保存 → PUT /user/info {followVisible,fansVisible,likesVisible,favoritesVisible,commentsVisible,footprintsVisible,dmMode}
 *     → 成功 toast "保存成功" + history.back()
 *     → 失败 api.js 自动 toast errorMsg
 * 设计决策：
 * - 隐私设置为菜单栏独立选项（侧边栏 → #/privacy），不嵌套在编辑资料页内
 * - 开关样式复用编辑页 toggle-switch（checkbox + .toggle-slider，与 user-edit.js 一致）
 * - 回显兼容后端 Integer(0/1) 与 Boolean 双重类型（info.xxxVisible === 1 || info.xxxVisible === true）
 * - 保存字段类型转换：checkbox.checked (Boolean) → Integer 0/1，dmMode 保持整数 0/1/2
 * - 私信模式三选：开放(0)、仅互关好友(1)、关闭(2)，使用 segmented pill 控件
 */
import { get, put } from '../api.js';
import { toast } from '../ui.js';
import { isLoggedIn, getUser } from '../store.js';

export async function renderPrivacy(container) {
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=privacy';
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
    <div id="privacy-root">
      <div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div>
    </div>
  `;
  const root = container.querySelector('#privacy-root');

  try {
    /** 【隐私设置的实现】GET /user/info/{自己id} 获取当前隐私状态
     * 实现细节：
     * - 后端 UserInfo 含 followVisible/fansVisible/likesVisible/favoritesVisible/commentsVisible/footprintsVisible（Integer 0/1）
     * - dmMode（Integer 0/1/2：开放/仅互关好友/关闭）
     * - 新用户无 info 记录时 infoData 为 null → 默认全部开关开启（checked），dmMode 默认 0（开放）
     */
    const infoData = await get('/user/info/' + myId);
    const info = infoData || {};

    /**
     * 【隐私设置的实现】渲染隐私设置页面 HTML
     * 实现细节：
     * - 标题"隐私设置" + 返回按钮（history.back()）
     * - 六个 iOS toggle 开关：关注列表可见、粉丝列表可见、赞过可见、收藏可见、评论可见、足迹可见
     * - 私信模式 segmented 三选：开放 / 仅互关好友 / 关闭
     * - 回显兼容 Integer(0/1) 与 Boolean：双重判断确保正确初始化 checked 状态
     * - dmMode 回显：0=开放、1=仅互关好友、2=关闭（默认 0）
     * - 保存按钮：收集全部开关状态 + dmMode → PUT /user/info
     */
    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="privacy-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>隐私设置</h2>
      </div>
      <div class="edit-profile-form">
        <!-- 【隐私设置的实现】隐私开关：关注列表可见 -->
        <div class="form-group">
          <label>谁可以看我的关注列表</label>
          <div class="privacy-toggles">
            <div class="privacy-toggle-item">
              <span>关注列表可见</span>
              <label class="toggle-switch">
                <input type="checkbox" id="toggle-follow-visible" ${info.followVisible === 1 || info.followVisible === true ? 'checked' : ''}/>
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>
        <!-- 【隐私设置的实现】隐私开关：粉丝列表可见 -->
        <div class="form-group">
          <label>谁可以看我的粉丝列表</label>
          <div class="privacy-toggles">
            <div class="privacy-toggle-item">
              <span>粉丝列表可见</span>
              <label class="toggle-switch">
                <input type="checkbox" id="toggle-fans-visible" ${info.fansVisible === 1 || info.fansVisible === true ? 'checked' : ''}/>
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>
        <!-- 【隐私设置的实现】隐私开关：赞过可见 -->
        <div class="form-group">
          <label>谁可以看我的赞过</label>
          <div class="privacy-toggles">
            <div class="privacy-toggle-item">
              <span>赞过可见</span>
              <label class="toggle-switch">
                <input type="checkbox" id="toggle-likes-visible" ${info.likesVisible === 1 || info.likesVisible === true ? 'checked' : ''}/>
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>
        <!-- 【隐私设置的实现】隐私开关：收藏可见 -->
        <div class="form-group">
          <label>谁可以看我的收藏</label>
          <div class="privacy-toggles">
            <div class="privacy-toggle-item">
              <span>收藏可见</span>
              <label class="toggle-switch">
                <input type="checkbox" id="toggle-favorites-visible" ${info.favoritesVisible === 1 || info.favoritesVisible === true ? 'checked' : ''}/>
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>
        <!-- 【隐私设置的实现】隐私开关：评论可见 -->
        <div class="form-group">
          <label>谁可以看我的评论</label>
          <div class="privacy-toggles">
            <div class="privacy-toggle-item">
              <span>评论可见</span>
              <label class="toggle-switch">
                <input type="checkbox" id="toggle-comments-visible" ${info.commentsVisible === 1 || info.commentsVisible === true ? 'checked' : ''}/>
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>
        <!-- 【隐私设置的实现】隐私开关：足迹可见 -->
        <div class="form-group">
          <label>谁可以看我的足迹</label>
          <div class="privacy-toggles">
            <div class="privacy-toggle-item">
              <span>足迹可见</span>
              <label class="toggle-switch">
                <input type="checkbox" id="toggle-footprints-visible" ${info.footprintsVisible === 1 || info.footprintsVisible === true ? 'checked' : ''}/>
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>
        <!-- 【隐私设置的实现】私信模式三选：开放(0) / 仅互关好友(1) / 关闭(2) -->
        <div class="form-group">
          <label>谁可以给我发私信</label>
          <div class="dm-mode-segmented" id="dm-mode-group">
            <button class="dm-mode-seg${(info.dmMode === 0 || info.dmMode == null) ? ' active' : ''}" data-dm="0">开放</button>
            <button class="dm-mode-seg${info.dmMode === 1 ? ' active' : ''}" data-dm="1">仅互关好友</button>
            <button class="dm-mode-seg${info.dmMode === 2 ? ' active' : ''}" data-dm="2">关闭</button>
          </div>
        </div>
        <!-- 【隐私设置的实现】保存按钮 -->
        <button class="btn btn-primary btn-block mt-16" id="privacy-submit">保存</button>
      </div>
    `;

    // 返回按钮
    root.querySelector('#privacy-back').addEventListener('click', () => window.history.back());

    /** 【隐私设置的实现】私信模式 segmented 控件交互
     * 实现细节：
     * - 三个按钮，点击切换 active 类（互斥）
     * - data-dm 存储模式值 0/1/2
     * - 保存时读取 active 按钮的 data-dm 值
     */
    let dmModeValue = info.dmMode != null ? info.dmMode : 0;
    const dmGroup = root.querySelector('#dm-mode-group');
    dmGroup.addEventListener('click', (e) => {
      const seg = e.target.closest('.dm-mode-seg');
      if (!seg) return;
      dmModeValue = parseInt(seg.dataset.dm);
      dmGroup.querySelectorAll('.dm-mode-seg').forEach(b => b.classList.remove('active'));
      seg.classList.add('active');
    });

    /**
     * 【隐私设置的实现】保存按钮逻辑
     * 实现细节：
     * - 收集六个开关 checkbox.checked（Boolean）→ 转为 Integer 0/1 匹配后端 UserEditDTO
     * - dmMode 保持 Integer 0/1/2
     * - body 含全部七个字段
     * - PUT /user/info → 成功 toast"保存成功" + history.back() → 失败 api.js 自动 toast errorMsg
     */
    const submitBtn = root.querySelector('#privacy-submit');
    submitBtn.addEventListener('click', async () => {
      /** 【隐私设置的实现】checkbox.checked 是 Boolean → 转为 Integer 0/1 匹配后端 */
      const followVisible = root.querySelector('#toggle-follow-visible').checked ? 1 : 0;
      const fansVisible = root.querySelector('#toggle-fans-visible').checked ? 1 : 0;
      const likesVisible = root.querySelector('#toggle-likes-visible').checked ? 1 : 0;
      const favoritesVisible = root.querySelector('#toggle-favorites-visible').checked ? 1 : 0;
      const commentsVisible = root.querySelector('#toggle-comments-visible').checked ? 1 : 0;
      const footprintsVisible = root.querySelector('#toggle-footprints-visible').checked ? 1 : 0;

      submitBtn.disabled = true;
      submitBtn.textContent = '保存中...';
      try {
        await put('/user/info', {
          followVisible, fansVisible,
          likesVisible, favoritesVisible, commentsVisible, footprintsVisible,
          dmMode: dmModeValue
        });
        toast('保存成功');
        window.history.back();
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
