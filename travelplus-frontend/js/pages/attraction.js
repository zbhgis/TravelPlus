/**
 * 【景点详情的实现】#/attraction/:id 页面渲染模块——轮播、信息展示、门票列表、秒杀、评论、评分、打卡、赞、收藏
 * 职责：景点图片轮播（自动+触摸滑动）、详情信息展示、门票列表渲染、秒杀按钮交互、评论区、动态评分、打卡、赞/收藏
 * 依赖：api.js (get/post/put/del)、ui.js (imgUrl/imgError/scoreDisplay/fenToYuan/formatDate/showLoading/showEmpty/toast)、store.js (isLoggedIn/getUser)
 * 核心链路：
 *   renderAttraction(container, {id})
 *     → 并行加载：GET /attraction/{id}（景点详情）+ GET /ticket/list/{id}（门票列表）
 *     → 渲染区：
 *       - 轮播：images 逗号分隔 → 3 秒自动滚动 + touchstart/touchend 手势切换
 *       - 信息：名称、评分（avgScore ÷10）、地区、地址、均价（0元=免费）、营业时间
 *       - 动态评分：avgScore（AVG，存储 ×10，显示 ÷10）+ ratingCount
 *       - 操作区：赞/收藏/打分/打卡（一行图标按钮）
 *       - 门票：ticket-card 列表（标题/副标题/原价/实付价/库存/秒杀时间段）
 *     → 评论区：仿游记评论区（加载/发布/空态/头像可点）
 *     → 秒杀按钮：见下方【秒杀的实现】
 *     → cleanup：清除 carouselTimer 轮播定时器
 *
 * 【评论功能的实现】景点评论区
 * - GET /attraction-comment/of/{id}?current= 拉取评论列表
 * - POST /attraction-comment {attractionId, content} 发布评论
 * - 评论头像可点击跳转用户主页
 * - 空态显示"暂无评论，来说点什么吧"
 *
 * 【动态评分的实现】星级打分
 * - 详情显示 avgScore（动态分 AVG）+ ratingCount（评分人数）
 * - 5 颗星点击打分 → POST /attraction/rate {attractionId, score}
 * - myRating 高亮已打分值，可改分（乐观更新 + 失败回滚）
 * - 【重要】avgScore 总评分不立即更新——用户明确要求"评分完不立即更新，刷新后更新总评分"，前端仅 toast"评分成功"不修改总评 DOM
 *
 * 【打卡的实现】景点打卡
 * - 打卡按钮 POST /attraction/checkin/{id}
 * - checkedIn 状态 → 已打卡 UI
 * - checkinCount → "N 人来过"
 *
 * 【赞的实现】景点点赞
 * - attraction.isLike 初始态 → PUT /attraction/like/{id} 切换
 * - 乐观更新 + 失败回滚
 *
 * 【收藏功能的实现】收藏按钮（景点详情）
 * - 书签图标 + 乐观更新 + 失败回滚
 * - targetType: 'attraction', targetId: attraction.id
 */
import { get, post, put, del } from '../api.js';
import { imgUrl, imgError, scoreDisplay, fenToYuan, formatDate, showLoading, showEmpty, toast } from '../ui.js';
import { isLoggedIn, getUser } from '../store.js';

let carouselTimer = null;

/** 【评论功能的实现】防 XSS 转义工具 */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str || '';
  return div.innerHTML;
}

/** 【评论功能的实现】加载并渲染景点评论区
 *  - GET /attraction-comment/of/{id}?current= 拉取评论列表
 *  - 空列表显示"暂无评论，来说点什么吧"占位
 *  - 头像可点击跳转用户主页
 */
async function loadAttractionComments(container, attractionId) {
  if (!container) return;
  try {
    const result = await get('/attraction-comment/of/' + attractionId + '?current=1');
    const list = Array.isArray(result) ? result : (result && result.records ? result.records : []);

    if (!list.length) {
      container.innerHTML = '<div class="comment-placeholder">暂无评论，来说点什么吧</div>';
      return;
    }

    const me = getUser();
    const meId = me ? me.id : null;

    let html = '';
    list.forEach(c => {
      html += `
        <div class="comment-item" data-user-id="${c.userId || ''}">
          <img class="avatar avatar-sm comment-avatar-clickable" src="${imgUrl(c.icon)}" alt="" onerror="imgError(event)" data-avatar-uid="${c.userId || ''}" data-avatar-icon="${imgUrl(c.icon)}" data-avatar-nick="${escapeHtml(c.name || '匿名')}"/>
          <div class="comment-body">
            <div class="comment-meta">
              <span class="comment-name comment-name-clickable" data-uid="${c.userId || ''}">${escapeHtml(c.name || '匿名')}</span>
              <span class="comment-time">${formatDate(c.createTime)}</span>
            </div>
            <div class="comment-text">${escapeHtml(c.content || '')}</div>
          </div>
        </div>`;
    });

    container.innerHTML = html;

    /** 【头像交互的实现】景点评论区头像点击改由全局委托 initAvatarDelegate 统一处理
     * - data-avatar-uid 已加在 HTML 模板中，无需单独绑定
     * - 昵称点击保留（非头像交互，仍跳用户主页）
     */
    // 昵称点击 → 用户主页（保留：非头像元素，由各自 data-uid + 独立绑定处理）
    container.querySelectorAll('.comment-name-clickable').forEach(nameEl => {
      nameEl.addEventListener('click', (e) => {
        e.stopPropagation();
        const uid = nameEl.dataset.uid;
        if (uid) window.location.hash = '#/user/' + uid;
      });
    });
  } catch {
    if (!container.innerHTML.includes('comment-item')) {
      container.innerHTML = '<div class="comment-placeholder">暂无评论，来说点什么吧</div>';
    }
  }
}

export async function renderAttraction(container, params) {
  const id = params.id;
  container.innerHTML = `<div id="attr-root"><div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div></div>`;

  const root = container.querySelector('#attr-root');

  try {
    const attraction = await get('/attraction/' + id);
    const tickets = await get('/ticket/list/' + id).catch(() => []);

    const images = (attraction.images || '').split(',').filter(Boolean);

    /** 【动态评分的实现】评分数据准备
     *  - avgScore：动态评分平均值（AVG），后端返回数字，前端直接显示
     *  - ratingCount：评分人数
     *  - myRating：当前用户已打分数（0=未打分，1-5=已打分）
     *  - 实现细节：后端并行实现，未返回时默认 0/0/0
     */
    const avgScore = attraction.avgScore != null ? attraction.avgScore : 0;
    const ratingCount = attraction.ratingCount || 0;
    const myRating = attraction.myRating || 0;

    root.innerHTML = `
      <div class="page-header">
        <button class="header-back" id="attr-back">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <h2>景点详情</h2>
      </div>
      <div class="attraction-detail">
        ${images.length > 0 ? `
        <div class="img-carousel" id="attr-carousel">
          <div class="carousel-track" id="carousel-track">
            ${images.map(img => '<div class="carousel-slide"><img src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/></div>').join('')}
          </div>
          ${images.length > 1 ? '<div class="carousel-dots" id="carousel-dots">' + images.map((_, i) => '<div class="carousel-dot' + (i === 0 ? ' active' : '') + '"></div>').join('') + '</div>' : ''}
        </div>` : ''}
        <div class="detail-header">
          <div class="detail-title">${attraction.name}</div>
          <div class="detail-meta" style="padding:0;margin-top:10px;flex-wrap:wrap">
            ${/** 【动态评分的实现】评分合一显示——有 ratingCount 时显示动态评分 avgScore/10，无评分时回退原种子分 score/10
             * - 实现细节：前端只呈现一个「评分」值，不再区分"原分"和"动态分"；avgScore 是用户动态评分 AVG，存储 ×10 同量纲，显示需 ÷10 */
            ratingCount > 0
              ? '<span class="detail-score">★ ' + (avgScore ? (avgScore / 10).toFixed(1) : '暂无') + (ratingCount > 0 ? '（' + ratingCount + '人评）' : '') + '</span>'
              : (attraction.score != null ? '<span class="detail-score">★ ' + scoreDisplay(attraction.score) + '</span>' : '')}
            ${attraction.area ? '<span>' + attraction.area + '</span>' : ''}
            ${attraction.address ? '<span>' + attraction.address + '</span>' : ''}
            ${/** 【景点详情的实现】价格显示：avgPrice>0 显示 ¥X起，avgPrice=0 显示"免费"，无 avgPrice 不显示
             * - 实现细节：与主页卡片一致：0 元 / 无票 = 免费 */
            attraction.avgPrice != null && attraction.avgPrice > 0 ? '<span class="detail-price">¥' + fenToYuan(attraction.avgPrice) + '起</span>' : (attraction.avgPrice != null && attraction.avgPrice <= 0 ? '<span class="card-free">免费</span>' : '')}
            ${/** 【景点详情的实现】营业时间（已移除"已售 xxx"，用户不显示已售数量） */
            attraction.openHours ? '<span>' + attraction.openHours + '</span>' : ''}
          </div>
        </div>
        ${/** 【赞/收藏/打分/打卡的实现】操作区：一行图标按钮 */
        '<div class="detail-actions">' +
          /** 【赞的实现】赞按钮（heart 图标） */
          '<button class="action-btn like-btn' + (attraction.isLike ? ' liked' : '') + '" data-liked="' + (attraction.isLike ? '1' : '0') + '">' +
            '<svg width="20" height="20" viewBox="0 0 24 24" fill="' + (attraction.isLike ? '#f87171' : 'none') + '" stroke="' + (attraction.isLike ? '#f87171' : '#999') + '" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>' +
            '<span class="like-count">' + (attraction.liked || 0) + '</span>' +
          '</button>' +
          /** 【收藏功能的实现】收藏按钮（书签图标） */
          '<button class="action-btn fav-btn' + (attraction.isFavorite ? ' favorited' : '') + '" data-fav="' + (attraction.isFavorite ? '1' : '0') + '">' +
            '<span class="fav-icon-outline"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2"><path d="m19 21-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg></span>' +
            '<span class="fav-icon-filled"><svg width="20" height="20" viewBox="0 0 24 24" fill="#0E9F8A" stroke="#0E9F8A" stroke-width="2"><path d="m19 21-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg></span>' +
            '<span class="fav-count">' + (attraction.favoriteCount || 0) + '</span>' +
          '</button>' +
          /** 【打卡的实现】打卡按钮 */
          '<button class="action-btn checkin-btn' + (attraction.checkedIn ? ' checked-in' : '') + '" id="attr-checkin-btn" data-checked="' + (attraction.checkedIn ? '1' : '0') + '">' +
            '<svg width="20" height="20" viewBox="0 0 24 24" fill="' + (attraction.checkedIn ? '#0E9F8A' : 'none') + '" stroke="' + (attraction.checkedIn ? '#0E9F8A' : '#999') + '" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>' +
            '<span class="checkin-text">' + (attraction.checkedIn ? '已打卡' : '打卡') + '</span>' +
          '</button>' +
          /** 【打卡的实现】打卡人数提示 */
          '<span class="checkin-count">' + (attraction.checkinCount > 0 ? attraction.checkinCount + '人来过' : '') + '</span>' +
        '</div>'}
        ${/** 【动态评分的实现】星级打分行 */
        '<div class="star-rating-section" id="star-rating-section">' +
          '<span class="star-label">评分：</span>' +
          '<div class="star-rating" id="star-rating">' +
            [1,2,3,4,5].map(s => '<span class="star' + (s <= myRating ? ' active' : '') + '" data-score="' + s + '">★</span>').join('') +
          '</div>' +
          (myRating > 0 ? '<span class="my-rating-hint">我的评分：' + myRating + '分</span>' : '<span class="my-rating-hint">点击星星打分</span>') +
        '</div>'}
        <div class="section-title">门票</div>
        <div class="page-content" id="ticket-list">
          ${tickets.length ? '' : '<div class="empty-state"><p>暂无门票</p></div>'}
        </div>
        ${/** 【评论功能的实现】景点评论区 */
        '<div class="detail-section">' +
          '<h3>评论</h3>' +
          '<div class="comment-list" id="attr-comment-list">' +
            '<div class="comment-placeholder">暂无评论，来说点什么吧</div>' +
          '</div>' +
        '</div>' +
        '<div class="comment-box" id="attr-comment-box">' +
          '<input type="text" id="attr-comment-input" placeholder="写评论..."/>' +
          '<button class="btn btn-primary btn-sm" id="attr-comment-submit">发送</button>' +
        '</div>'}
      </div>
    `;

    // Back button
    root.querySelector('#attr-back').addEventListener('click', () => {
      window.history.back();
    });

    /** 【赞的实现】赞按钮点击处理
     * - 登录保护：未登录跳 #/login?redirect=attraction/{id}
     * - 乐观更新：立即切换 UI 状态（图标 + 数字）
     * - PUT /attraction/like/{id} → 失败回滚
     */
    const likeBtn = root.querySelector('.like-btn');
    if (likeBtn) {
      likeBtn.addEventListener('click', async () => {
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('attraction/' + id);
          return;
        }
        const currentLiked = likeBtn.dataset.liked === '1';
        const countEl = likeBtn.querySelector('.like-count');
        const svg = likeBtn.querySelector('svg');

        const newLiked = !currentLiked;
        likeBtn.dataset.liked = newLiked ? '1' : '0';
        likeBtn.classList.toggle('liked', newLiked);
        svg.setAttribute('fill', newLiked ? '#f87171' : 'none');
        svg.setAttribute('stroke', newLiked ? '#f87171' : '#999');
        const currentCount = parseInt(countEl.textContent) || 0;
        countEl.textContent = newLiked ? currentCount + 1 : Math.max(0, currentCount - 1);

        try {
          await put('/attraction/like/' + id);
        } catch {
          likeBtn.dataset.liked = currentLiked ? '1' : '0';
          likeBtn.classList.toggle('liked', currentLiked);
          svg.setAttribute('fill', currentLiked ? '#f87171' : 'none');
          svg.setAttribute('stroke', currentLiked ? '#f87171' : '#999');
          countEl.textContent = currentCount;
        }
      });
    }

    /** 【收藏功能的实现】收藏按钮点击处理（景点详情页）
     * - 登录保护：未登录跳 #/login?redirect=attraction/{id}
     * - 乐观更新：立即切换 UI 状态（图标切换 + 收藏数 ±1）
     * - POST /favorite {targetType:'attraction', targetId} → 失败回滚
     * - DELETE /favorite/attraction/{id} → 取消失败回滚
     */
    const favBtn = root.querySelector('.fav-btn');
    if (favBtn) {
      favBtn.addEventListener('click', async () => {
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('attraction/' + id);
          return;
        }
        const currentFav = favBtn.dataset.fav === '1';
        const countEl = favBtn.querySelector('.fav-count');

        const newFav = !currentFav;
        favBtn.dataset.fav = newFav ? '1' : '0';
        favBtn.classList.toggle('favorited', newFav);
        const currentCount = parseInt(countEl.textContent) || 0;
        countEl.textContent = newFav ? currentCount + 1 : Math.max(0, currentCount - 1);

        try {
          if (newFav) {
            await post('/favorite', { targetType: 'attraction', targetId: id });
          } else {
            await del('/favorite/attraction/' + id);
          }
        } catch {
          favBtn.dataset.fav = currentFav ? '1' : '0';
          favBtn.classList.toggle('favorited', currentFav);
          countEl.textContent = currentCount;
        }
      });
    }

    /** 【打卡的实现】打卡按钮点击处理
     * - 登录保护：未登录跳 #/login?redirect=attraction/{id}
     * - POST /attraction/checkin/{id} → 成功切换已打卡状态
     * - 乐观更新：立即切换 UI（图标 + 文字 + checkinCount）
     * - 不可取消打卡（后端一人一次）
     */
    const checkinBtn = root.querySelector('#attr-checkin-btn');
    if (checkinBtn) {
      checkinBtn.addEventListener('click', async () => {
        if (!isLoggedIn()) {
          window.location.hash = '#/login?redirect=' + encodeURIComponent('attraction/' + id);
          return;
        }
        const currentChecked = checkinBtn.dataset.checked === '1';
        if (currentChecked) {
          toast('已打卡'); return;
        }
        const svg = checkinBtn.querySelector('svg');
        const textEl = checkinBtn.querySelector('.checkin-text');
        const countEl = root.querySelector('.checkin-count');

        // 乐观更新
        checkinBtn.dataset.checked = '1';
        checkinBtn.classList.add('checked-in');
        svg.setAttribute('fill', '#0E9F8A');
        svg.setAttribute('stroke', '#0E9F8A');
        textEl.textContent = '已打卡';
        const currentCount = parseInt((countEl.textContent || '').replace('人来过', '')) || 0;
        countEl.textContent = (currentCount + 1) + '人来过';

        try {
          await post('/attraction/checkin/' + id);
        } catch {
          // 失败回滚
          checkinBtn.dataset.checked = '0';
          checkinBtn.classList.remove('checked-in');
          svg.setAttribute('fill', 'none');
          svg.setAttribute('stroke', '#999');
          textEl.textContent = '打卡';
          countEl.textContent = (currentCount > 0 ? currentCount + '人来过' : '');
        }
      });
    }

    /** 【动态评分的实现】星级打分点击处理
     * - 登录保护：未登录跳 #/login?redirect=attraction/{id}
     * - 5 颗星点击 → POST /attraction/rate {attractionId, score}
     * - 乐观更新星标高亮 + 我的评分提示文字
     * - 失败回滚到之前评分状态
     */
    const starContainer = root.querySelector('#star-rating');
    const hintEl = root.querySelector('.my-rating-hint');
    if (starContainer) {
      let previousRating = myRating;
      starContainer.querySelectorAll('.star').forEach(star => {
        star.addEventListener('click', async () => {
          if (!isLoggedIn()) {
            window.location.hash = '#/login?redirect=' + encodeURIComponent('attraction/' + id);
            return;
          }
          const score = parseInt(star.dataset.score);
          // 乐观更新
          starContainer.querySelectorAll('.star').forEach(s => {
            s.classList.toggle('active', parseInt(s.dataset.score) <= score);
          });
          if (hintEl) hintEl.textContent = '我的评分：' + score + '分';

          try {
            await post('/attraction/rate', { attractionId: parseInt(id), score: score });
            /** 【动态评分的实现】评分成功后只 toast 提示 + 更新 myRating 本地状态
             *  - 不乐观修改 avgScore 总评 DOM（用户明确要求"评分完不立即更新，刷新后更新总评分"）
             *  - 实现细节：avgScore 在初始渲染时写入 HTML，此后不做任何修改，用户刷新页面重新 GET 后显示新总评分
             */
            previousRating = score;
            toast('评分成功');
            /** 【景点列表的实现】评分成功后设置脏标记，主页下次渲染时失效缓存重新拉取
             * 实现细节：
             * - localStorage.setItem('travelplus_attraction_dirty','1') 通知 home.js 评分已变
             * - home.js 检测到脏标记 → 清空 cache 对象并移除标记 → 重新 GET /attraction/of/type 获取新评分
             * - 脏标记只清一次缓存（不每次都清，兼顾 /attraction/of/type 限流 5秒5次）
             */
            localStorage.setItem('travelplus_attraction_dirty', '1');
          } catch {
            // 失败回滚
            starContainer.querySelectorAll('.star').forEach(s => {
              s.classList.toggle('active', parseInt(s.dataset.score) <= previousRating);
            });
            if (hintEl) hintEl.textContent = previousRating > 0 ? '我的评分：' + previousRating + '分' : '点击星星打分';
          }
        });
      });
    }

    // Carousel
    if (images.length > 1) {
      let carouselIdx = 0;
      const track = root.querySelector('#carousel-track');
      const dots = root.querySelectorAll('#carousel-dots .carousel-dot');
      const startCarousel = () => {
        carouselTimer = setInterval(() => {
          carouselIdx = (carouselIdx + 1) % images.length;
          track.style.transform = 'translateX(-' + (carouselIdx * 100) + '%)';
          dots.forEach((d, i) => d.classList.toggle('active', i === carouselIdx));
        }, 3000);
      };
      startCarousel();

      // Touch swipe
      let touchStartX = 0;
      track.parentElement.addEventListener('touchstart', (e) => {
        touchStartX = e.touches[0].clientX;
        clearInterval(carouselTimer);
      });
      track.parentElement.addEventListener('touchend', (e) => {
        const diff = touchStartX - e.changedTouches[0].clientX;
        if (Math.abs(diff) > 50) {
          carouselIdx = diff > 0
            ? Math.min(carouselIdx + 1, images.length - 1)
            : Math.max(carouselIdx - 1, 0);
        }
        track.style.transform = 'translateX(-' + (carouselIdx * 100) + '%)';
        dots.forEach((d, i) => d.classList.toggle('active', i === carouselIdx));
        startCarousel();
      });
    }

    // Render tickets
    const ticketContainer = root.querySelector('#ticket-list');
    if (tickets && tickets.length) {
      ticketContainer.innerHTML = tickets.map(t => {
        const isSeckill = t.beginTime && t.endTime;
        /* 秒杀时间段格式化：后端返回 ISO（2026-08-01T00:00:00），转 "YYYY-MM-DD HH:mm:ss" 展示 */
        const timeStr = isSeckill ? (formatDate(t.beginTime) + ' ~ ' + formatDate(t.endTime)) : '';
        return `
          <div class="ticket-card">
            <div class="ticket-info">
              <div class="ticket-title">${t.title}</div>
              ${t.subTitle ? '<div class="ticket-sub">' + t.subTitle + '</div>' : ''}
              <div class="ticket-price">
                <span class="ticket-pay">¥${fenToYuan(t.payValue)}</span>
                ${t.actualValue > t.payValue ? '<span class="ticket-original">¥' + fenToYuan(t.actualValue) + '</span>' : ''}
              </div>
              ${t.stock != null ? '<div class="ticket-stock">库存：' + t.stock + '</div>' : ''}
              ${timeStr ? '<div class="ticket-time">' + timeStr + '</div>' : ''}
            </div>
            ${isSeckill ? '<button class="btn btn-primary btn-sm seckill-btn" data-tid="' + t.id + '">秒杀</button>' : ''}
          </div>`;
      }).join('');

      /** 【秒杀的实现】秒杀按钮点击处理 */
      ticketContainer.querySelectorAll('.seckill-btn').forEach(btn => {
        btn.addEventListener('click', async () => {
          if (!isLoggedIn()) {
            window.location.hash = '#/login?redirect=' + encodeURIComponent('attraction/' + id);
            return;
          }
          const tid = btn.dataset.tid;
          btn.disabled = true;
          try {
            const orderId = await post('/ticket-order/seckill/' + tid);
            toast('秒杀成功，订单号 ' + orderId);
            /** 【秒杀的实现】乐观更新库存显示
             * 实现细节：
             * - Lua 预检通过即已扣 Redis 库存（秒杀瞬时完成），DB 库存由 Kafka 消费端异步扣减
             * - 前端乐观减 1 即时反馈，刷新后与后端一致
             * - 找到对应门票卡片的 .ticket-stock 元素 → 本地 stock-1（防负 Math.max(0,n-1)）
             */
            const ticketCard = btn.closest('.ticket-card');
            if (ticketCard) {
              const stockEl = ticketCard.querySelector('.ticket-stock');
              if (stockEl) {
                const curStock = parseInt(stockEl.textContent.replace('库存：', '')) || 0;
                stockEl.textContent = '库存：' + Math.max(0, curStock - 1);
              }
            }
          } catch (e) {
            toast(e.message || '秒杀失败');
          } finally {
            btn.disabled = false;
          }
        });
      });
    } else {
      ticketContainer.innerHTML = '<div class="empty-state"><p>暂无门票</p></div>';
    }

    /** 【评论功能的实现】加载景点评论列表 */
    loadAttractionComments(root.querySelector('#attr-comment-list'), id);

    /** 【评论功能的实现】景点评论发布
     * - 需登录保护：未登录跳 #/login 带 redirect
     * - 空内容 return
     * - POST /attraction-comment {attractionId, content}
     * - 成功后清空输入框 + 重新加载评论
     */
    root.querySelector('#attr-comment-submit').addEventListener('click', async () => {
      if (!isLoggedIn()) {
        window.location.hash = '#/login?redirect=' + encodeURIComponent('attraction/' + id);
        return;
      }
      const input = root.querySelector('#attr-comment-input');
      const content = input.value.trim();
      if (!content) return;
      try {
        await post('/attraction-comment', { attractionId: parseInt(id), content });
        toast('评论成功');
        input.value = '';
        loadAttractionComments(root.querySelector('#attr-comment-list'), id);
      } catch {
        // api.js 已 toast errorMsg
      }
    });

  } catch (e) {
    root.innerHTML = '<div class="empty-state"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg><p>加载失败</p></div>';
  }

  return () => {
    if (carouselTimer) {
      clearInterval(carouselTimer);
      carouselTimer = null;
    }
  };
}
