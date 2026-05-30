/**
 * 【路由注册表】应用入口——导入所有页面模块、注册路由、绑定全局错误处理、启动路由引擎
 * 职责：集中管理路由注册（map pattern→handler），确定匹配优先级，兜底全局异常
 * 依赖：router.js (register/start/navigate)、pages/ 下全部 14 个页面模块
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
 */
import { register, start, navigate } from './router.js';
import { renderLogin } from './pages/login.js';
import { renderHome } from './pages/home.js';
import { renderAttraction } from './pages/attraction.js';
import { renderNoteDetail } from './pages/note-detail.js';
import { renderNoteEdit } from './pages/note-edit.js';
import { renderProfile } from './pages/profile.js';
import { renderRegister } from './pages/register.js';
import { renderUser } from './pages/user.js';
import { renderUserEdit } from './pages/user-edit.js';
import { renderUserBrowse } from './pages/user-browse.js';
import { renderFollows } from './pages/follows.js';

// 路由注册——按匹配优先级排序：精确路由在前，参数路由在后
register('/login', renderLogin);
register('/register', renderRegister);
register('/home', renderHome);
register('/attraction/:id', renderAttraction);
register('/notes', () => { navigate('/home?tab=notes'); });
register('/note/:id', renderNoteDetail);
register('/edit', renderNoteEdit);
register('/follow', () => { navigate('/home?tab=follow'); });
register('/profile', renderProfile);
register('/user/edit', renderUserEdit);
register('/user/browse', renderUserBrowse);
register('/user/:id/follows', renderFollows);
register('/user/:id', renderUser);

// 兜底路由：/ → 跳首页
register('/', (app) => {
  navigate('/home');
});

// 全局错误兜底：未捕获异常记录到 console（不影响用户体验）
window.addEventListener('error', (e) => {
  console.error('Global error:', e.error);
});

window.addEventListener('unhandledrejection', (e) => {
  console.error('Unhandled rejection:', e.reason);
});

start();
