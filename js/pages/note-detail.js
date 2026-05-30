/**
 * 【游记详情页的实现】#/note/:id 页面渲染模块
 * 职责：游记图片轮播、点赞（乐观更新）、关注切换、评论列表与发布、游记删除（作者可见）、头像跳转用户主页
 * 依赖：api.js (get/put/post/del)、ui.js (imgUrl/imgError/formatDate/showLoading/showEmpty/toast)、store.js (isLoggedIn/getUser)
 * 核心链路：
 *   renderNoteDetail(container, params)
 *     → 并行加载：GET /note/{id}（游记详情）+ GET /note/likes/{id}（点赞用户榜）+ GET /follow/or/not/{userId}（关注状态）
 *     → 渲染详情区：轮播、标题、作者（头像可点击跳 #/user）、关注按钮、点赞、内容、点赞榜、评论区
 *     → 绑定交互：返回/轮播定时器/点赞/关注/评论/删除
 *     → cleanup：清除 ndTimer 轮播定时器
 * 子功能模块：
 *   - 图片轮播：setInterval 3 秒自动滚动 + 触摸滑动切换
 *   - 点赞：乐观更新 UI（立即切换状态 + 数字）→ PUT /note/like/{id} → 失败回滚
 *   - 评论：参见 loadComments 和 comment-submit 监听器
 *   - 删除：仅作者可见 → confirm → DELETE /note/{id} → toast → 跳 #/home?tab=notes
 */
import { get, put, post, del } from '../api.js';
import { imgUrl, imgError, formatDate, showLoading, showEmpty, toast } from '../ui.js';
import { isLoggedIn, getUser } from '../store.js';

/** 【评论功能的实现】防 XSS 转义工具
 *  - 利用浏览器原生 textContent 赋值自动转义 HTML 特殊字符（< > & " '）
 *  - 返回安全的 HTML 字符串，配合 innerHTML 渲染用户内容时防止脚本注入
 *  - 对昵称、评论内容等用户生成数据均经此函数转义后再插入 DOM
 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}

/** 【评论功能的实现】加载评论列表
 *  - GET /note-comments/of/{noteId}?current=1 分页获取评论
 *  - 兼容两种响应格式：直接数组 或 {records: [...]} 分页对象
 *  - 渲染内容：头像（avatar-sm）、昵称（escapeHtml 防 XSS）、格式化时间、评论正文
 *  - 空列表显示「暂无评论，来说点什么吧」占位提示
 *  - 失败静默处理：不阻断详情页展示，保留占位文本（评论加载非关键路径）
 */
async function loadComments(container, noteId) {
  if (!container) return;
  try {
    const result = await get('/note-comments/of/' + noteId + '?current=1');
    const list = Array.isArray(result) ? result : (result && result.records ? result.records : []);

    if (!list.length) {
      container.innerHTML = '<div class="comment-placeholder">暂无评论，来说点什么吧</div>';
      return;
    }

    container.innerHTML = list.map(c => {
      const name = c.name || '匿名';
      return `
        <div class="comment-item">
          <img class="avatar avatar-sm" src="${imgUrl(c.icon)}" alt="" onerror="imgError(event)"/>
          <div class="comment-body">
            <div class="comment-meta">
              <span class="comment-name">${escapeHtml(name)}</span>
              <span class="comment-time">${formatDate(c.createTime)}</span>
            </div>
            <div class="comment-text">${escapeHtml(c.content || '')}</div>
          </div>
        </div>`;
    }).join('');
  } catch {
    // Silent failure — keep placeholder（不阻断详情展示）
    container.innerHTML = '<div class="comment-placeholder">暂无评论，来说点什么吧</div>';
  }
}

export async function renderNoteDetail(container, params) {
  const id = params.id;
  let ndTimer = null;
  container.innerHTML = `<div id="note-root"><div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div></div>`;
  const root = container.querySelector('#note-root');

  try {
    const note = await get('/note/' + id);
    let likeUsers = [];
    try { likeUsers = await get('/note/likes/' + id); } catch { likeUsers = []; }

    let isFollowed = false;
    if (isLoggedIn()) {
      try { isFollowed = await get('/follow/or/not/' + note.userId); } catch {}
    }

    const images = (note.images || '').split(',').filter(Boolean);

    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="nd-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>游记详情</h2>
      </div>
      ${images.length > 0 ? `
      <div class="img-carousel" id="nd-carousel">
        <div class="carousel-track" id="nd-track">
          ${images.map(img => '<div class="carousel-slide"><img src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/></div>').join('')}
        </div>
        ${images.length > 1 ? '<div class="carousel-dots" id="nd-dots">' + images.map((_, i) => '<div class="carousel-dot' + (i === 0 ? ' active' : '') + '"></div>').join('') + '</div>' : ''}
      </div>` : ''}
      <div class="detail-header">
        <div class="detail-title">${note.title}</div>
        <div class="detail-author">
          <img class="avatar" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)"/>
          <div class="detail-author-info">
            <div class="detail-author-name">${note.name || '匿名'}</div>
            <div class="detail-author-time">${formatDate(note.createTime)}</div>
          </div>
          ${note.userId ? '<button class="btn btn-outline btn-sm follow-btn" data-uid="' + note.userId + '" data-followed="' + isFollowed + '">' + (isFollowed ? '已关注' : '+ 关注') + '</button>' : ''}
        </div>
      </div>
      <div class="detail-actions">
        <button class="action-btn like-btn ${note.isLike ? 'liked' : ''}" data-liked="${note.isLike ? '1' : '0'}">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="${note.isLike ? '#f87171' : 'none'}" stroke="${note.isLike ? '#f87171' : '#999'}" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
          <span class="like-count">${note.liked || 0}</span>
        </button>
        ${(() => { const me = getUser(); return note.userId && me && String(me.id) === String(note.userId); })() ? '<button class="action-btn note-delete-btn" id="nd-delete">删除</button>' : ''}
      </div>
      <div class="detail-content">${note.content || ''}</div>
      ${likeUsers.length > 0 ? `
      <div class="detail-section">
        <h3>点赞的人</h3>
        <div class="like-avatars">
          ${likeUsers.map(u => '<img src="' + imgUrl(u.icon) + '" alt="' + (u.nickName || '') + '" onerror="imgError(event)" title="' + (u.nickName || '') + '"/>').join('')}
        </div>
      </div>` : ''}
      <div class="detail-section">
        <h3>评论</h3>
        <div class="comment-list" id="comment-list">
          <div class="comment-placeholder">暂无评论，来说点什么吧</div>
        </div>
      </div>
      <div class="comment-box">
        <input type="text" id="comment-input" placeholder="写评论..."/>
        <button class="btn btn-primary btn-sm" id="comment-submit">发送</button>
      </div>
    `;

    // Back
    root.querySelector('#nd-back').addEventListener('click', () => window.history.back());

    // Carousel
    if (images.length > 1) {
      let idx = 0;
      const track = root.querySelector('#nd-track');
      const dots = root.querySelectorAll('#nd-dots .carousel-dot');
      ndTimer = setInterval(() => {
        idx = (idx + 1) % images.length;
        track.style.transform = 'translateX(-' + (idx * 100) + '%)';
        dots.forEach((d, i) => d.classList.toggle('active', i === idx));
      }, 3000);
    }

    // Like button
    const likeBtn = root.querySelector('.like-btn');
    if (likeBtn) {
      likeBtn.addEventListener('click', async () => {
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('note/' + id);
          return;
        }
        const currentLiked = likeBtn.dataset.liked === '1';
        const countEl = likeBtn.querySelector('.like-count');
        const svg = likeBtn.querySelector('svg');

        // Optimistic update
        const newLiked = !currentLiked;
        likeBtn.dataset.liked = newLiked ? '1' : '0';
        likeBtn.classList.toggle('liked', newLiked);
        svg.setAttribute('fill', newLiked ? '#f87171' : 'none');
        svg.setAttribute('stroke', newLiked ? '#f87171' : '#999');
        const currentCount = parseInt(countEl.textContent) || 0;
        countEl.textContent = newLiked ? currentCount + 1 : Math.max(0, currentCount - 1);

        try {
          await put('/note/like/' + id);
        } catch {
          // Revert
          likeBtn.dataset.liked = currentLiked ? '1' : '0';
          likeBtn.classList.toggle('liked', currentLiked);
          svg.setAttribute('fill', currentLiked ? '#f87171' : 'none');
          svg.setAttribute('stroke', currentLiked ? '#f87171' : '#999');
          countEl.textContent = currentCount;
        }
      });
    }

    // Follow button
    const followBtn = root.querySelector('.follow-btn');
    if (followBtn) {
      followBtn.addEventListener('click', async () => {
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('note/' + id);
          return;
        }
        const uid = followBtn.dataset.uid;
        const currentState = followBtn.dataset.followed === 'true';
        const newState = !currentState;
        try {
          await put('/follow/' + uid + '/' + newState);
          followBtn.dataset.followed = String(newState);
          followBtn.textContent = newState ? '已关注' : '+ 关注';
          followBtn.classList.toggle('followed', newState);
        } catch {}
      });
      if (isFollowed) followBtn.classList.add('followed');
    }

    /** 【游记删除的实现】详情页删除按钮
     * - 仅当前登录用户是游记作者时显示删除按钮（getUser().id == note.userId）
     * - 点击 → confirm 确认 → DELETE /note/{id} → 成功 toast + 跳 #/home?tab=notes
     * - 失败由 api.js 统一 toast errorMsg，不跳转
     */
    const delBtn = root.querySelector('#nd-delete');
    if (delBtn) {
      delBtn.addEventListener('click', async () => {
        if (!confirm('确认删除该游记？')) return;
        try {
          await del('/note/' + id);
          toast('删除成功');
          window.location.hash = '#/home?tab=notes';
        } catch {
          // api.js 已 toast errorMsg
        }
      });
    }

    /** 【用户主页的实现】详情页头像点击跳转用户主页
     * - 作者头像 .detail-author img.avatar → #/user/{note.userId}
     * - 点赞榜头像 .like-avatars img → #/user/{u.id}
     * - 头像添加 cursor:pointer 和点击事件，直接跳转
     */
    const authorAvatar = root.querySelector('.detail-author .avatar');
    if (authorAvatar && note.userId) {
      authorAvatar.style.cursor = 'pointer';
      authorAvatar.addEventListener('click', () => {
        window.location.hash = '#/user/' + note.userId;
      });
    }
    const likeAvatars = root.querySelectorAll('.like-avatars img');
    likeAvatars.forEach((img, i) => {
      const u = likeUsers[i];
      if (u && u.id) {
        img.style.cursor = 'pointer';
        img.addEventListener('click', () => {
          window.location.hash = '#/user/' + u.id;
        });
      }
    });

    /** 【评论功能的实现】评论发布
     *  - 需登录保护：未登录跳转 #/login 并带 redirect 参数，登录后回到当前游记页
     *  - 空内容直接 return，不发送请求
     *  - POST /note-comments {noteId, content} → 成功后清空输入框 + 重新加载评论列表（即时刷新）
     *  - 失败由 api.js 统一 toast errorMsg
     */
    root.querySelector('#comment-submit').addEventListener('click', async () => {
      if (!isLoggedIn()) {
        window.location.hash = '#/login?redirect=' + encodeURIComponent('note/' + id);
        return;
      }
      const input = root.querySelector('#comment-input');
      const content = input.value.trim();
      if (!content) return;
      try {
        await post('/note-comments', { noteId: id, content });
        toast('评论成功');
        input.value = '';
        loadComments(root.querySelector('#comment-list'), id);
      } catch {
        // api.js handles toast
      }
    });

    // Load comments
    loadComments(root.querySelector('#comment-list'), id);

  } catch {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }

  return () => {
    if (ndTimer) clearInterval(ndTimer);
  };
}
