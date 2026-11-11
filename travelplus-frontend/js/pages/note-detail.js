/**
 * 【游记详情页的实现】#/note/:id 页面渲染模块
 * 职责：游记图片轮播、点赞（乐观更新）、关注切换、评论列表（回复/删除/置顶）与发布、游记删除（作者可见）、头像跳转用户主页
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
 *   - 评论：参见 loadComments（含回复/删除/置顶）和 comment-submit 监听器
 *   - 删除：仅作者可见 → confirm → DELETE /note/{id} → toast → 跳 #/home?tab=notes
 */
import { get, put, post, del } from '../api.js';
import { imgUrl, imgError, formatDate, showLoading, showEmpty, toast, confirmDialog } from '../ui.js';
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

/** 【评论功能的实现】当前笔记信息缓存（供评论交互使用：判断删除/置顶权限）
 *  - 实现细节：评论区的删除和置顶按钮需要判断评论作者和游记作者身份
 *  - noteData 在 renderNoteDetail 中设置，loadComments 中引用
 */
let noteData = null;

/** 【评论功能的实现】加载并渲染评论列表（支持回复/删除/置顶）
 *  - GET /note-comments/of/{noteId}?current=1 拉取评论列表
 *  - 响应格式：数组，每项包含 {id, parentId, answerId, isTop, name, icon, userId, content, createTime}
 *  - 渲染结构：
 *    - 置顶评论（isTop=1）加「置顶」徽标，排在列表最前（后端已排序）
 *    - 一级评论（parentId 为空/null）：显示回复/删除/置顶按钮
 *    - 回复评论（parentId 非空）：缩进显示在该一级评论下方，显示"回复 @xx"
 *  - 按钮权限：
 *    - 回复按钮：所有登录用户可见
 *    - 删除按钮：评论作者（comment.userId===当前用户）或游记作者（noteData.userId===当前用户）可见
 *    - 置顶按钮：仅游记作者可见
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

    const me = getUser();
    const meId = me ? me.id : null;
    const isNoteAuthor = noteData && meId != null && String(meId) === String(noteData.userId);

    // 分离一级评论和回复
    const tops = list.filter(c => !c.parentId);
    const replies = list.filter(c => c.parentId);

    // 构建一级评论 HTML（后端已按 isTop+createTime 排序）
    let html = '';
    tops.forEach(c => {
      const isTop = c.isTop === 1 || c.isTop === true;
      const isCommentAuthor = meId != null && String(meId) === String(c.userId);
      const canDelete = isCommentAuthor || isNoteAuthor;
      const canPin = isNoteAuthor;

      // 一级评论条目
      html += `
        <div class="comment-item${isTop ? ' comment-pinned' : ''}" data-comment-id="${c.id}" data-user-id="${c.userId}">
          <img class="avatar avatar-sm comment-avatar-clickable" src="${imgUrl(c.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${c.userId || ''}" data-avatar-icon="${imgUrl(c.icon)}" data-avatar-nick="${escapeHtml(c.name || '匿名')}"/>
          <div class="comment-body">
            <div class="comment-meta">
              <span class="comment-name comment-name-clickable" data-uid="${c.userId}">${escapeHtml(c.name || '匿名')}</span>
              ${isTop ? '<span class="comment-pin-badge">置顶</span>' : ''}
              <span class="comment-time">${formatDate(c.createTime)}</span>
            </div>
            <div class="comment-text">${escapeHtml(c.content || '')}</div>
            <div class="comment-actions">
              ${meId != null ? '<button class="comment-action-btn comment-reply-btn" data-parent-id="' + c.id + '" data-answer-id="' + c.id + '" data-reply-name="' + escapeHtml(c.name || '匿名') + '">回复</button>' : ''}
              ${canDelete ? '<button class="comment-action-btn comment-delete-btn" data-comment-id="' + c.id + '">删除</button>' : ''}
              ${canPin ? '<button class="comment-action-btn comment-pin-btn" data-comment-id="' + c.id + '" data-is-top="' + (isTop ? '1' : '0') + '">' + (isTop ? '取消置顶' : '置顶') + '</button>' : ''}
            </div>
          </div>
        </div>`;

      // 该一级评论下的回复（缩进显示）
      const childReplies = replies.filter(r => String(r.parentId) === String(c.id));
      if (childReplies.length > 0) {
        html += '<div class="comment-reply-list">';
        childReplies.forEach(r => {
          const rIsCommentAuthor = meId != null && String(meId) === String(r.userId);
          const rCanDelete = rIsCommentAuthor || isNoteAuthor;

          // 查找被回复的用户名（answerId 指向的具体评论作者）
          let replyToName = '';
          if (r.answerId) {
            const answerComment = list.find(a => String(a.id) === String(r.answerId));
            if (answerComment) {
              replyToName = answerComment.name || '匿名';
            }
          }

          html += `
            <div class="comment-reply-item" data-comment-id="${r.id}" data-user-id="${r.userId}">
              <img class="avatar avatar-sm comment-avatar-clickable" src="${imgUrl(r.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${r.userId || ''}" data-avatar-icon="${imgUrl(r.icon)}" data-avatar-nick="${escapeHtml(r.name || '匿名')}"/>
              <div class="comment-body">
                <div class="comment-meta">
                  <span class="comment-name comment-name-clickable" data-uid="${r.userId}">${escapeHtml(r.name || '匿名')}</span>
                  ${replyToName ? '<span class="comment-reply-to">回复 @' + escapeHtml(replyToName) + '</span>' : ''}
                  <span class="comment-time">${formatDate(r.createTime)}</span>
                </div>
                <div class="comment-text">${escapeHtml(r.content || '')}</div>
                <div class="comment-actions">
                  ${meId != null ? '<button class="comment-action-btn comment-reply-btn" data-parent-id="' + c.id + '" data-answer-id="' + r.id + '" data-reply-name="' + escapeHtml(r.name || '匿名') + '">回复</button>' : ''}
                  ${rCanDelete ? '<button class="comment-action-btn comment-delete-btn" data-comment-id="' + r.id + '">删除</button>' : ''}
                </div>
              </div>
            </div>`;
        });
        html += '</div>';
      }
    });

    container.innerHTML = html;

    /** 【头像交互的实现】评论区头像点击改由全局委托 initAvatarDelegate 统一处理
     * - data-avatar-uid 已加在 HTML 模板中，无需再单独绑定
     * - 昵称点击保留（非头像交互，仍跳用户主页）
     */
    // 昵称点击 → 用户主页（保留：非头像元素，由各自 data-uid + 独立绑定处理）

    // 昵称点击 → 用户主页
    container.querySelectorAll('.comment-name-clickable').forEach(nameEl => {
      nameEl.addEventListener('click', (e) => {
        e.stopPropagation();
        const uid = nameEl.dataset.uid;
        if (uid) window.location.hash = '#/user/' + uid;
      });
    });

    // 回复按钮 → 设置输入框的回复目标
    container.querySelectorAll('.comment-reply-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const commentBox = document.querySelector('.comment-box');
        if (!commentBox) return;
        const input = commentBox.querySelector('#comment-input');
        if (!input) return;

        const parentId = btn.dataset.parentId;
        const answerId = btn.dataset.answerId;
        const replyName = btn.dataset.replyName || '匿名';

        // 保存回复上下文
        commentBox.dataset.replyParentId = parentId;
        commentBox.dataset.replyAnswerId = answerId;

        // 显示回复提示
        let hint = commentBox.querySelector('.comment-reply-hint');
        if (!hint) {
          hint = document.createElement('div');
          hint.className = 'comment-reply-hint';
          commentBox.insertBefore(hint, commentBox.firstChild);
        }
        hint.innerHTML = '回复 @' + escapeHtml(replyName) + ' <button class="comment-reply-cancel">×</button>';
        hint.querySelector('.comment-reply-cancel').addEventListener('click', () => {
          cancelReply(commentBox);
        });

        input.placeholder = '回复 @' + replyName + '...';
        input.focus();
      });
    });

    /** 【弹窗组件的实现】删除评论二次确认
     *  - 使用 confirmDialog 替代原生 confirm()，与界面主题一致
     *  - await 等待用户选择：确认→DELETE 请求，取消→return
     */
    // 删除按钮
    container.querySelectorAll('.comment-delete-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        if (!await confirmDialog('删除评论', '确认删除该评论？')) return;
        try {
          await del('/note-comments/' + btn.dataset.commentId);
          toast('删除成功');
          // 重新加载评论列表（评论数会变化）
          loadComments(container, noteId);
        } catch {
          // api.js 已 toast errorMsg
        }
      });
    });

    // 置顶/取消置顶按钮
    container.querySelectorAll('.comment-pin-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        const isTop = btn.dataset.isTop === '1';
        const newTop = isTop ? 0 : 1;
        try {
          await put('/note-comments/top/' + btn.dataset.commentId, { top: newTop });
          toast(newTop ? '已置顶' : '已取消置顶');
          loadComments(container, noteId);
        } catch {
          // api.js 已 toast errorMsg
        }
      });
    });
  } catch {
    // Silent failure — keep placeholder（不阻断详情展示）
    if (!container.innerHTML.includes('comment-item')) {
      container.innerHTML = '<div class="comment-placeholder">暂无评论，来说点什么吧</div>';
    }
  }
}

/** 【评论功能的实现】取消回复状态
 *  - 实现：清除 comment-box 的 data-reply-* 属性，移除回复提示 UI，恢复默认 placeholder
 */
function cancelReply(commentBox) {
  delete commentBox.dataset.replyParentId;
  delete commentBox.dataset.replyAnswerId;
  const hint = commentBox.querySelector('.comment-reply-hint');
  if (hint) hint.remove();
  const input = commentBox.querySelector('#comment-input');
  if (input) input.placeholder = '写评论...';
}

export async function renderNoteDetail(container, params) {
  const id = params.id;
  let ndTimer = null;
  container.innerHTML = `<div id="note-root"><div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div></div>`;
  const root = container.querySelector('#note-root');

  try {
    const note = await get('/note/' + id);
    /** 【评论功能的实现】缓存笔记信息，供评论交互判断作者权限 */
    noteData = note;
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
      ${/** 【游记关联景点的实现】如果 note.attractionId 非空，显示关联景点卡片
         *  - 卡片显示景点名 + 图，点击跳 #/attraction/{id}
         *  - attraction 对象由后端内联返回或单独请求
         */
      (() => {
        if (note.attractionId && note.attraction) {
          const attrImg = note.attraction.images ? note.attraction.images.split(',')[0] : '';
          return '<div class="linked-attraction-banner" data-attr-id="' + note.attractionId + '">' +
            (attrImg ? '<img class="linked-attraction-banner-img" src="' + imgUrl(attrImg) + '" alt="" onerror="imgError(event)"/>' : '') +
            '<div class="linked-attraction-banner-info">' +
              '<span class="linked-attraction-banner-label">相关景点</span>' +
              '<span class="linked-attraction-banner-name">' + escapeHtml(note.attraction.name || '查看景点') + '</span>' +
            '</div>' +
            '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>' +
            '</div>';
        }
        return '';
      })()}
      ${images.length > 0 ? `
      <div class="img-carousel" id="nd-carousel">
        <div class="carousel-track" id="nd-track">
          ${images.map(img => '<div class="carousel-slide"><img src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/></div>').join('')}
        </div>
        ${images.length > 1 ? '<div class="carousel-dots" id="nd-dots">' + images.map((_, i) => '<div class="carousel-dot' + (i === 0 ? ' active' : '') + '"></div>').join('') + '</div>' : ''}
      </div>` : ''}
      <div class="detail-header">
        <div class="detail-title">${escapeHtml(note.title || '')}</div>
        <div class="detail-author">
          <img class="avatar" src="${imgUrl(note.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${note.userId || ''}" data-avatar-icon="${imgUrl(note.icon)}" data-avatar-nick="${escapeHtml(note.name || '')}"/>
          <div class="detail-author-info">
            <div class="detail-author-name">${escapeHtml(note.name || '匿名')}</div>
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
        ${/** 【收藏功能的实现】收藏按钮：仅登录用户可见，isFavorite 决定初始态
           *  - 后端并行实现中，note.isFavorite 不返回时默认 false 不报错
           *  - 图标：未收藏=书签轮廓、已收藏=书签填充（主题色）
           *  - 显示收藏数 favoriteCount
           *  - targetType: 'note'
           */
        (() => {
          const initialFav = note.isFavorite ? '1' : '0';
          return '<button class="action-btn fav-btn' + (note.isFavorite ? ' favorited' : '') + '" data-fav="' + initialFav + '">' +
            '<span class="fav-icon-outline"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2"><path d="m19 21-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg></span>' +
            '<span class="fav-icon-filled"><svg width="20" height="20" viewBox="0 0 24 24" fill="#0E9F8A" stroke="#0E9F8A" stroke-width="2"><path d="m19 21-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg></span>' +
            '<span class="fav-count">' + (note.favoriteCount || 0) + '</span>' +
            '</button>';
        })()}
        ${/** 【游记编辑的实现】编辑按钮：仅作者可见，点击跳 #/edit?id={noteId}
           * 实现细节：
           * - 与删除按钮同条件：note.userId === 当前用户 id
           * - 点击 → window.location.hash = '#/edit?id=' + noteId
           */
        (() => { const me = getUser(); return note.userId && me && String(me.id) === String(note.userId); })() ? '<button class="action-btn note-edit-btn" id="nd-edit">编辑</button><button class="action-btn note-delete-btn" id="nd-delete">删除</button>' : ''}
      </div>
      <div class="detail-content">${escapeHtml(note.content || '')}</div>
      ${likeUsers.length > 0 ? `
      <div class="detail-section">
        <h3>点赞的人</h3>
        <div class="like-avatars">
          ${likeUsers.map(u => '<img src="' + imgUrl(u.icon) + '" alt="' + escapeHtml(u.nickName || '') + '" onerror="imgError(event)" data-avatar-uid="' + (u.id || '') + '" data-avatar-icon="' + imgUrl(u.icon) + '" data-avatar-nick="' + escapeHtml(u.nickName || '') + '" title="' + escapeHtml(u.nickName || '') + '"/>').join('')}
        </div>
      </div>` : ''}
      <div class="detail-section">
        <h3>评论</h3>
        <div class="comment-list" id="comment-list">
          <div class="comment-placeholder">暂无评论，来说点什么吧</div>
        </div>
      </div>
      <div class="comment-box" id="comment-box">
        <input type="text" id="comment-input" placeholder="写评论..."/>
        <button class="btn btn-primary btn-sm" id="comment-submit">发送</button>
      </div>
    `;

    // Back
    root.querySelector('#nd-back').addEventListener('click', () => window.history.back());

    /** 【游记关联景点的实现】关联景点卡片点击 → 跳景点详情 */
    const attrBanner = root.querySelector('.linked-attraction-banner');
    if (attrBanner) {
      attrBanner.addEventListener('click', () => {
        window.location.hash = '#/attraction/' + attrBanner.dataset.attrId;
      });
    }

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

    /** 【收藏功能的实现】收藏按钮点击处理（游记详情页）
     * - 登录保护：未登录跳 #/login?redirect=note/{id}
     * - 乐观更新：立即切换 UI 状态（图标切换 + 收藏数 ±1）
     * - POST /favorite {targetType:'note', targetId} → 失败回滚
     * - DELETE /favorite/note/{id} → 取消失败回滚
     * - targetType='note'，targetId=游记id
     */
    const favBtn = root.querySelector('.fav-btn');
    if (favBtn) {
      favBtn.addEventListener('click', async () => {
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('note/' + id);
          return;
        }
        const currentFav = favBtn.dataset.fav === '1';
        const countEl = favBtn.querySelector('.fav-count');

        // Optimistic update
        const newFav = !currentFav;
        favBtn.dataset.fav = newFav ? '1' : '0';
        favBtn.classList.toggle('favorited', newFav);
        const currentCount = parseInt(countEl.textContent) || 0;
        countEl.textContent = newFav ? currentCount + 1 : Math.max(0, currentCount - 1);

        try {
          if (newFav) {
            await post('/favorite', { targetType: 'note', targetId: id });
          } else {
            await del('/favorite/note/' + id);
          }
        } catch {
          // Revert on failure
          favBtn.dataset.fav = currentFav ? '1' : '0';
          favBtn.classList.toggle('favorited', currentFav);
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

    /** 【游记编辑的实现】详情页编辑按钮
     * - 仅当前登录用户是游记作者时显示编辑按钮（getUser().id == note.userId）
     * - 点击 → 跳 #/edit?id={noteId}（note-edit.js 通过 query id 识别编辑模式）
     */
    const editBtn = root.querySelector('#nd-edit');
    if (editBtn) {
      editBtn.addEventListener('click', () => {
        window.location.hash = '#/edit?id=' + id;
      });
    }

    /** 【游记删除的实现】详情页删除按钮
     * - 仅当前登录用户是游记作者时显示删除按钮（getUser().id == note.userId）
     * - 点击 → confirmDialog 确认 → DELETE /note/{id} → 成功 toast + 跳 #/home?tab=notes
     * - 失败由 api.js 统一 toast errorMsg，不跳转
     * - 使用 await confirmDialog 替代原生 confirm()，与界面主题一致
     */
    const delBtn = root.querySelector('#nd-delete');
    if (delBtn) {
      delBtn.addEventListener('click', async () => {
        if (!await confirmDialog('删除游记', '确认删除该游记？')) return;
        try {
          await del('/note/' + id);
          toast('删除成功');
          window.location.hash = '#/home?tab=notes';
        } catch {
          // api.js 已 toast errorMsg
        }
      });
    }

    /** 【头像交互的实现】作者头像点击改由全局委托 initAvatarDelegate 统一处理（data-avatar-uid 已加在 HTML 模板）
     * - 自己头像→预览弹窗（含"这是您自己"标签），他人头像→跳主页
     * - 旧独立绑定已移除，避免与全局委托冲突
     */

    /** 【评论功能的实现】评论发布（支持普通评论与回复）
     *  - 需登录保护：未登录跳转 #/login 并带 redirect 参数，登录后回到当前游记页
     *  - 空内容直接 return，不发送请求
     *  - 若处于回复模式（comment-box.dataset.replyParentId 非空），POST 携带 parentId 和 answerId
     *  - POST /note-comments {noteId, content, parentId?, answerId?}
     *  - 成功后清空输入框 + 取消回复状态 + 重新加载评论列表（即时刷新）
     *  - 失败由 api.js 统一 toast errorMsg
     */
    const commentBox = root.querySelector('#comment-box');
    root.querySelector('#comment-submit').addEventListener('click', async () => {
      if (!isLoggedIn()) {
        window.location.hash = '#/login?redirect=' + encodeURIComponent('note/' + id);
        return;
      }
      const input = root.querySelector('#comment-input');
      const content = input.value.trim();
      if (!content) return;
      try {
        const body = { noteId: id, content };
        // 回复模式：附加 parentId 和 answerId
        if (commentBox.dataset.replyParentId) {
          body.parentId = commentBox.dataset.replyParentId;
          body.answerId = commentBox.dataset.replyAnswerId;
        }
        await post('/note-comments', body);
        toast('评论成功');
        input.value = '';
        cancelReply(commentBox);
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
