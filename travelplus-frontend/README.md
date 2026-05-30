# TravelPlus 移动端 H5 SPA

纯静态、零构建、零依赖的移动端单页应用，对接 TravelPlus 后端 API。基于原生 HTML/CSS/JS（ES Module），hash 路由，响应式适配手机与桌面浏览器。

> **monorepo 说明**：本项目现为 TravelPlus 单一仓库（monorepo）的一部分，位于
> `E:\AAAproject\travelplus\travelplus-frontend`，与后端 `travelplus-backend`、根目录
> `docker-compose.yml` 一同管理。

## 启动方式

### 方式 1：双击 `start-frontend.bat`
直接双击 `start-frontend.bat`，会自动静默启动 Python HTTP 服务（无控制台窗口，日志写入 `logs/frontend.log`）。

### 方式 2：命令行
```bash
cd E:\AAAproject\travelplus\travelplus-frontend
python -m http.server 19608
```
然后浏览器打开 http://localhost:19608

### 方式 3：任意静态服务
将 `travelplus-frontend/` 目录作为静态文件根目录即可，如 nginx、live-server 等。

## 配置

**依赖后端**：页面所有数据均来自后端 API，需先启动后端（端口 8081）与根 `docker-compose.yml` 中的依赖服务。

编辑 `js/config.js`：

```js
export const API_BASE = 'http://localhost:8081';  // 后端地址
export const IMG_BASE = API_BASE;                   // 图片资源基础路径
```

## 功能清单

### 路由与页面

底部 TabBar 共 5 个：**首页 / 旅行 / 发布 / 消息 / 我的**；详情类页面自动隐藏 TabBar。

| 页面 | Hash 路由 | 功能 |
|------|----------|------|
| 登录 | `#/login` | 手机号+验证码或密码登录，支持回跳 |
| 注册 | `#/register` | 手机号+密码注册，注册成功自动登录 |
| 首页 | `#/home` | 三频道（关注/游记/景点），景点分类加载、全局搜索入口、热门游记瀑布流 |
| 景点详情 | `#/attraction/:id` | 图片轮播、动态评分、打卡、赞/收藏、足迹记录、门票列表、秒杀、评论区 |
| 游记详情 | `#/note/:id` | 图片轮播、点赞、收藏、关注、评论（回复/置顶）、关联景点 |
| 发布/编辑游记 | `#/edit`（编辑 `#/edit?id=`） | 标题+正文+图片上传（最多9张），发布或编辑，删除 |
| 关注动态 | `#/follow`（即 `#/home?tab=follow`） | 关注用户游记时间线 Feed |
| 热门游记 | `#/notes`（即 `#/home?tab=notes`） | 双列瀑布流、无限滚动 |
| 旅行地图 | `#/travel` | Leaflet 地图、附近景点定位 |
| 新建旅行计划 | `#/trip/new` | 多日行程规划 |
| 旅行计划详情 | `#/trip/:id` | 行程查看 |
| 全局搜索 | `#/search` | ES 搜索（景点/游记/用户三类）、热门词、输入联想 |
| 消息中心 | `#/messages` | 三分类通知（赞和收藏/新增关注/评论和@）+ 分类未读角标 |
| 消息分类页 | `#/messages/likes`、`#/messages/follows`、`#/messages/comments` | 各分类通知列表 |
| AI 客服 | `#/support` | DeepSeek 异步客服对话（轮询回复） |
| 私信会话列表 | `#/chats` | 微信式会话列表，左滑置顶/删除，未读红点 |
| 私信聊天 | `#/chat/:peerId` | 一对一聊天，发送/未读红点 |
| 我的主页 | `#/profile` | 签到、我的游记、获赞/关注/粉丝、钱包/订单/收藏/足迹/浏览记录入口 |
| 他人主页 | `#/user/:id` | 小红书风格主页（主页/笔记/获赞） |
| 编辑资料 | `#/user/edit` | 昵称/头像/背景/简介 |
| 关注列表 | `#/user/:id/follows` | 关注/粉丝列表 |
| 浏览记录 | `#/user/browse` | 历史浏览景点 |
| 钱包 | `#/wallet` | 余额/积分/秒杀券三资产总览 |
| 余额 | `#/balance` | 余额明细、充值（模拟微信/支付宝） |
| 积分 | `#/points` | 积分明细、积分抵扣支付（1积分=0.01元） |
| 秒杀券 | `#/coupons` | 秒杀券列表（未使用/已核销）、条形码展示 |
| 订单 | `#/orders` | 秒杀券订单列表（按状态区分） |
| 订单详情 | `#/order/:id` | 订单详情、支付、超时关单状态 |
| 我的收藏 | `#/favorites` | 收藏的景点/游记 |
| 隐私设置 | `#/privacy` | 隐私选项 |

### 触屏体验

- **鼠标模拟触屏**：会话左滑置顶/删除等手势基于 Pointer Events 实现，桌面浏览器用鼠标拖拽即可模拟手机滑动手势，无需触屏设备。
- **响应式布局**：`≤480px` 全屏手机布局；`≥768px` 桌面浏览器居中显示手机框，方便桌面演示。

## 接口对应表

### 用户模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 发送验证码 | `/user/code?phone=` | POST |
| 登录 | `/user/login` | POST（验证码或密码） |
| 注册 | `/user/register` | POST |
| 获取当前用户 | `/user/me` | GET |
| 用户主页信息 | `/user/home/{id}` | GET |
| 用户详情 | `/user/info/{id}`、`/user/{id}` | GET |
| 更新资料 | `/user/info` | PUT |
| 签到 | `/user/sign` | POST |
| 签到天数/记录 | `/user/sign/count`、`/user/sign/list` | GET |
| 退出登录 | `/user/logout` | POST |
| 浏览记录 | `/user/browse/list` | GET |
| 用户评论/获赞游记/获赞景点/打卡列表 | `/user/comments/{id}`、`/user/liked-notes/{id}`、`/user/liked-attractions/{id}`、`/user/checkins/{id}` | GET |

### 景点模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 类型列表 | `/attraction-type/list` | GET |
| 新增景点 | `/attraction` | POST |
| 景点详情 | `/attraction/{id}` | GET |
| 按类型查景点 | `/attraction/of/type?typeId=&current=` | GET |
| 按名称搜景点 | `/attraction/of/name?name=&current=` | GET |
| 目的地列表/按目的地查 | `/attraction/destinations`、`/attraction/of/destination` | GET |
| 点赞切换 | `/attraction/like/{id}` | PUT |
| 动态评分 | `/attraction/rate`、`/attraction/rate/status` | POST / GET |
| 打卡 | `/attraction/checkin/{id}` | POST |
| 景点评论 | `/attraction-comment/of/{attractionId}`、`/attraction-comment` | GET / POST |

### 门票模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 门票列表 | `/ticket/list/{attractionId}` | GET |
| 新增门票/秒杀券 | `/ticket`、`/ticket/seckill` | POST |
| 秒杀下单 | `/ticket-order/seckill/{ticketId}` | POST（需登录） |

### 订单模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 订单列表 | `/ticket-order/list` | GET（需登录） |
| 订单详情 | `/ticket-order/{id}` | GET（需登录） |
| 订单支付 | `/ticket-order/pay/{id}` | POST（需登录） |

### 游记模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 热门游记 | `/note/hot?current=` | GET |
| 游记详情 | `/note/{id}` | GET |
| 点赞切换 | `/note/like/{id}` | PUT（需登录） |
| 点赞用户榜 | `/note/likes/{id}` | GET |
| 发布/编辑/删除游记 | `/note`、`/note/{id}` | POST / PUT / DELETE（需登录） |
| 我的游记 | `/note/of/me?current=` | GET（需登录） |
| 用户游记 | `/note/of/user?userId=` | GET |
| 关注 Feed | `/note/of/follow?lastId=&offset=` | GET（需登录） |
| 评论/回复/置顶/删除 | `/note-comments`、`/note-comments/{id}`、`/note-comments/top/{id}` | POST / DELETE / PUT |

### 关注模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 关注状态 | `/follow/or/not/{id}` | GET（需登录） |
| 关注/取关 | `/follow/{id}/{true\|false}` | PUT（需登录） |
| 共同关注/关注列表 | `/follow/common/{id}`、`/follow/list/{id}` | GET |

### 收藏模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 收藏/取消 | `/favorite`、`/favorite/{targetType}/{targetId}` | POST / DELETE（需登录） |
| 收藏列表/状态/计数 | `/favorite/list`、`/favorite/status`、`/favorite/count` | GET（需登录） |

### 私信模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 会话列表 | `/chat/sessions` | GET（需登录） |
| 聊天记录 | `/chat/list?peerId=` | GET（需登录） |
| 发送消息 | `/chat/send` | POST（需登录） |
| 未读数/互关状态 | `/chat/unread-count`、`/chat/mutual-status` | GET（需登录） |
| 左滑置顶/取消置顶/删除会话 | `/chat/pin/{peerId}`、`/chat/session/{peerId}` | PUT / DELETE（需登录） |

### 消息中心（通知）
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 通知列表 | `/notify/list?category=` | GET（需登录） |
| 总未读数 | `/notify/unread-count` | GET（需登录） |
| 分类未读数 | `/notify/category-unread-count` | GET（需登录） |
| 标记已读 | `/notify/read` | PUT（需登录） |

### 钱包/积分模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 钱包总览 | `/user/wallet` | GET（需登录） |
| 充值 | `/user/recharge` | POST（需登录，模拟微信/支付宝） |
| 积分明细 | `/user/points/records` | GET（需登录） |
| 余额明细 | `/user/balance/records` | GET（需登录） |

### AI 客服
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 发送消息（异步回复） | `/support/chat` | POST（需登录） |

### 旅行模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 地图点位 | `/map/points` | GET |
| 新建计划 | `/trip` | POST（需登录） |
| 计划列表/详情/删除 | `/trip/list`、`/trip/{id}` | GET / DELETE（需登录） |

### 搜索模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 全局搜索 | `/search?keyword=` | GET |
| 热门词 | `/search/hot` | GET |
| 输入联想 | `/search/suggest?keyword=` | GET |
| ES 索引重建 | `/search/index/rebuild` | POST |

### 上传模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 上传游记图片 | `/upload/note` | POST（multipart，字段名 file） |
| 上传头像 | `/upload/user` | POST（multipart，字段名 file） |
| 删除图片 | `/upload/note/delete?name=` | GET |

## 已知限制

1. **验证码**：仅打印到后端控制台日志，也存 Redis `login:code:{phone}`。演示环境需查看后端日志或 Redis 获取，或直接使用密码登录。
2. **图片上传**：图片由后端项目内 uploads/imgs 目录提供，随项目迁移。
3. **CORS**：已配置完成（`MvcConfig` `allowedOriginPatterns("*")`，允许 `authorization` 头），前端独立端口可正常跨域访问后端 8081，无需额外配置。
4. **路由**：基于 hash 路由（`#/`），不支持 HTML5 History API，刷新页面会重新加载。
