/**
 * 【路由注册表】应用入口——导入所有页面模块、注册路由、绑定全局错误处理、启动路由引擎
 * 职责：集中管理路由注册（map pattern→handler），确定匹配优先级，兜底全局异常
 * 依赖：router.js (register/start/navigate)、pages/ 下全部 19 个页面模块（含新增旅行 3 个）
 * 核心链路：
 *   1. 导入所有 render* 函数
 *   2. 按优先级顺序 register（精确路由先于参数路由；/user/edit > /user/browse > /user/:id/follows > /user/:id）
 *   3. register('/', ...) 兜底 → navigate('/home')
 *   4. 绑定 window.error / unhandledrejection 全局兜底日志
 *   5. start() 启动首次渲染 + hashchange 监听
 * 路由顺序说明：
 *   - /user/edit 和 /user/browse 注册在 /user/:id 之前，否则会被参数路由贪婪匹配吞掉
 *   - /notes → navigate('/home?tab=notes') 重定向到聚合首页游记频道
 *   - /follow → navigate('/home?tab=follow') 重定向到聚合首页关注频道
 *   - /messages 是独立 tab 页（消息中心）；/support 是隐藏页（AI客服）
 */
import { register, start, navigate } from './router.js';
import { initAvatarDelegate } from './ui.js';
import { renderLogin } from './pages/login.js';
import { renderHome } from './pages/home.js?v=type2';
import { renderAttraction } from './pages/attraction.js';
import { renderNoteDetail } from './pages/note-detail.js';
import { renderNoteEdit } from './pages/note-edit.js';
import { renderProfile } from './pages/profile.js?v=fp0712';
import { renderRegister } from './pages/register.js';
import { renderUserEdit } from './pages/user-edit.js';
import { renderUserBrowse } from './pages/user-browse.js';
import { renderFollows } from './pages/follows.js';
import { renderMessages, renderMessageCategory } from './pages/messages.js';
import { renderSupport } from './pages/support.js?v=support5';
/** 【企业级客服Agent的实现】客服工作台（人工客服）页面 */
import { renderSupportAdmin } from './pages/support-admin.js?v=sadmin3';
import { renderFavorites } from './pages/favorites.js';
import { renderChat } from './pages/chat.js?v=chat1';
import { renderChats } from './pages/chats.js';
/** 【隐私设置的实现】导入隐私设置页路由模块 */
import { renderPrivacy } from './pages/privacy.js';
/** 【旅行功能的实现】导入旅行相关页面模块——旅行页、创建计划页、计划详情页 */
/* 【旅行页 UI 改版】travel.js 带版本参数——python http.server 无 Cache-Control 头，
 * 浏览器启发式缓存会持有旧模块；改动静态资源后递增此版本号即可强制刷新 */
import { renderTravel } from './pages/travel.js?v=amap8';
import { renderUser } from './pages/user.js?v=amap2';
import { renderTripEdit } from './pages/trip-edit.js';
import { renderTripDetail } from './pages/trip-detail.js?v=amap4';
/** 【旅游AI助手的实现】导入旅游攻略 AI 助手页面模块（DeepSeek 流式对话） */
import { renderAiChat } from './pages/ai-chat.js?v=ai7';
/** 【全局搜索的实现】导入搜索页渲染模块 */
import { renderSearch } from './pages/search.js?v=srch2';
/** 【我的资产入口的实现】导入订单管理、我的钱包、积分记录页面模块 */
import { renderOrders } from './pages/orders.js';
import { renderOrderDetail } from './pages/order-detail.js';
import { renderWallet } from './pages/wallet.js';
import { renderPoints } from './pages/points.js';
/** 【钱包详情的实现】导入余额明细、秒杀券页面模块 */
import { renderBalance } from './pages/balance.js';
import { renderCoupons } from './pages/coupons.js';

// 路由注册——按匹配优先级排序：精确路由在前，参数路由在后
register('/login', renderLogin);
register('/register', renderRegister);
register('/home', renderHome);
/** 【旅行功能的实现】注册旅行相关路由——/trip/new 先于 /trip/:id 避免参数路由贪婪匹配 */
register('/travel', renderTravel);
register('/trip/new', renderTripEdit);
register('/trip/:id', renderTripDetail);
register('/attraction/:id', renderAttraction);
register('/notes', () => { navigate('/home?tab=notes'); });
register('/note/:id', renderNoteDetail);
/** 【订单详情的实现】注册订单详情路由——/order/:id 精确路由，点击订单卡片进入 */
register('/order/:id', renderOrderDetail);
register('/edit', renderNoteEdit);
register('/follow', () => { navigate('/home?tab=follow'); });
/** 【全局搜索的实现】注册全局搜索页路由 /search */
register('/search', renderSearch);
/** 【消息中心的实现】注册消息分类独立页路由——/messages/:category 先于 /messages 避免被精确匹配吞掉 */
register('/messages/likes', (app) => renderMessageCategory(app, { category: 'likes' }));
register('/messages/follows', (app) => renderMessageCategory(app, { category: 'follows' }));
register('/messages/comments', (app) => renderMessageCategory(app, { category: 'comments' }));
register('/messages', renderMessages);
register('/support', renderSupport);
/** 【企业级客服Agent的实现】客服工作台路由（管理员白名单访问） */
register('/support-admin', renderSupportAdmin);
/** 【旅游AI助手的实现】旅游攻略 AI 助手全屏对话页 */
register('/ai-chat', renderAiChat);
/** 【隐私设置的实现】注册隐私设置独立页路由 #/privacy */
register('/privacy', renderPrivacy);
register('/favorites', renderFavorites);
register('/chats', renderChats);
register('/chat/:peerId', renderChat);
register('/profile', renderProfile);
/** 【我的资产入口的实现】注册订单管理/我的钱包/积分记录路由——在 /user/:id 等参数路由前注册更安全 */
register('/orders', renderOrders);
register('/wallet', renderWallet);
register('/points', renderPoints);
/** 【钱包详情的实现】注册余额明细、秒杀券路由 */
register('/balance', renderBalance);
register('/coupons', renderCoupons);
register('/user/edit', renderUserEdit);
register('/user/browse', renderUserBrowse);
register('/user/:id/follows', renderFollows);
register('/user/:id', renderUser);

// 兜底路由：/ → 跳首页
register('/', (app) => {
  navigate('/home');
});

/** 【头像交互的实现】全局头像点击委托初始化——自己→预览、他人→跳主页，统一处理无需各页面独立绑定 */
initAvatarDelegate();

// 全局错误兜底：未捕获异常记录到 console（不影响用户体验）
window.addEventListener('error', (e) => {
  /* e.error 为 null = 资源加载失败/跨域脚本错误（无异常对象，与业务代码无关）——降级 debug，
   * 避免误报成"Global error: null"干扰排查（如地图插件脚本按需加载的跨域错误） */
  if (e.error == null) {
    console.debug('Resource error (non-business):', e.message || e.type, e.target && (e.target.src || e.target.href) || '');
    return;
  }
  console.error('Global error:', e.error);
});

window.addEventListener('unhandledrejection', (e) => {
  console.error('Unhandled rejection:', e.reason);
});

start();
