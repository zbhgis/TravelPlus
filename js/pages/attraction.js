/**
 * 【景点详情的实现】#/attraction/:id 页面渲染模块——轮播、信息展示、门票列表、秒杀
 * 职责：景点图片轮播（自动+触摸滑动）、详情信息展示、门票列表渲染、秒杀按钮交互
 * 依赖：api.js (get/post)、ui.js (imgUrl/imgError/scoreDisplay/fenToYuan/showLoading/showEmpty/toast)、store.js (isLoggedIn)
 * 核心链路：
 *   renderAttraction(container, {id})
 *     → 并行加载：GET /attraction/{id}（景点详情）+ GET /ticket/list/{id}（门票列表）
 *     → 渲染区：
 *       - 轮播：images 逗号分隔 → 3 秒自动滚动 + touchstart/touchend 手势切换
 *       - 信息：名称、评分、地区、地址、均价、已售数、营业时间
 *       - 门票：ticket-card 列表（标题/副标题/原价/实付价/库存/秒杀时间段）
 *     → 秒杀按钮：见下方【秒杀的实现】
 *     → 返回按钮：history.back()
 *     → cleanup：清除 carouselTimer 轮播定时器
 *
 * 【秒杀的实现】门票秒杀按钮交互
 * - 判断依据：门票有 beginTime 且 endTime 则显示"秒杀"按钮（时间区间字符串显示在 ticket-time）
 * - 登录保护：未登录点击 → 跳 #/login?redirect=attraction/{id}（登录后回到当前景点页）
 * - 一人一单提示：POST /ticket-order/seckill/{ticketId} → 成功 toast "秒杀成功，订单号 xxx" → 失败 toast errorMsg
 *   （后端秒杀链路 Redis Lua 原子扣库存 + Kafka 异步消费生成订单，重复秒杀返回失败）
 * - 按钮禁用：请求期间 btn.disabled=true 防重复点击；finally 恢复
 */
import { get, post } from '../api.js';
import { imgUrl, imgError, scoreDisplay, fenToYuan, showLoading, showEmpty, toast } from '../ui.js';
import { isLoggedIn } from '../store.js';

let carouselTimer = null;

export async function renderAttraction(container, params) {
  const id = params.id;
  container.innerHTML = `<div id="attr-root"><div class="loading-spinner"><div class="spinner"></div><p>加载中...</p></div></div>`;

  const root = container.querySelector('#attr-root');

  try {
    const attraction = await get('/attraction/' + id);
    const tickets = await get('/ticket/list/' + id).catch(() => []);

    const images = (attraction.images || '').split(',').filter(Boolean);

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
            ${attraction.score != null ? '<span class="detail-score">★ ' + scoreDisplay(attraction.score) + '</span>' : ''}
            ${attraction.area ? '<span>' + attraction.area + '</span>' : ''}
            ${attraction.address ? '<span>' + attraction.address + '</span>' : ''}
            ${attraction.avgPrice != null ? '<span class="detail-price">¥' + fenToYuan(attraction.avgPrice) + '起</span>' : ''}
            ${attraction.sold ? '<span>已售 ' + attraction.sold + '</span>' : ''}
            ${attraction.openHours ? '<span>' + attraction.openHours + '</span>' : ''}
          </div>
        </div>
        <div class="section-title">门票</div>
        <div class="page-content" id="ticket-list">
          ${tickets.length ? '' : '<div class="empty-state"><p>暂无门票</p></div>'}
        </div>
      </div>
    `;

    // Back button
    root.querySelector('#attr-back').addEventListener('click', () => {
      window.history.back();
    });

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
        const timeStr = isSeckill ? (t.beginTime + ' ~ ' + t.endTime) : '';
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

      /** 【秒杀的实现】秒杀按钮点击处理
       * - 登录保护：未登录 → 跳 #/login 带 redirect 参数
       * - POST /ticket-order/seckill/{ticketId} → 成功 toast 订单号；失败 toast errorMsg
       * - btn.disabled 防重复点击：请求期间禁用，finally 恢复
       * - 后端一人一单：Redis Lua 原子扣库存 + Kafka 异步生成订单，重复秒杀提示失败
       */
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
