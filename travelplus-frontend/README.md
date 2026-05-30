# TravelPlus 移动端 H5 SPA

纯静态、零构建、零依赖的移动端单页应用，对接 TravelPlus 后端 API。

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

| 页面 | Hash 路由 | 功能 |
|------|----------|------|
| 登录 | `#/login` | 手机号+验证码登录，支持回跳 |
| 首页 | `#/home` | 景点搜索、类型选项卡、景点卡片列表（分类加载+缓存） |
| 景点详情 | `#/attraction/:id` | 图片轮播、信息展示、门票列表、秒杀 |
| 热门游记 | `#/notes` | 双列瀑布流、无限滚动 |
| 游记详情 | `#/note/:id` | 图片轮播、点赞、关注、评论占位 |
| 发布游记 | `#/edit` | 标题+正文+图片上传（最多9张），发布 |
| 关注动态 | `#/follow` | 关注用户游记时间线 Feed |
| 我的 | `#/profile` | 签到、我的游记、退出登录 |

## 接口对应表

### 用户模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 发送验证码 | `/user/code?phone=` | POST |
| 登录 | `/user/login` | POST |
| 获取当前用户 | `/user/me` | GET |
| 签到 | `/user/sign` | POST |
| 签到天数 | `/user/sign/count` | GET |
| 退出登录 | `/user/logout` | POST（后端未实现，仅前端清 token） |

### 景点模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 类型列表 | `/attraction-type/list` | GET |
| 按类型查景点 | `/attraction/of/type?typeId=&current=` | GET（5秒限5次） |
| 按名称搜景点 | `/attraction/of/name?name=&current=` | GET |
| 景点详情 | `/attraction/{id}` | GET |

### 门票模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 门票列表 | `/ticket/list/{attractionId}` | GET |
| 秒杀 | `/ticket-order/seckill/{ticketId}` | POST（需登录） |

### 游记模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 热门游记 | `/note/hot?current=` | GET |
| 游记详情 | `/note/{id}` | GET |
| 点赞切换 | `/note/like/{id}` | PUT（需登录） |
| 点赞用户榜 | `/note/likes/{id}` | GET |
| 发布游记 | `/note` | POST（需登录） |
| 我的游记 | `/note/of/me?current=` | GET（需登录） |
| 关注 Feed | `/note/of/follow?lastId=&offset=` | GET（需登录） |

### 关注模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 关注状态 | `/follow/or/not/{userId}` | GET（需登录） |
| 关注/取关 | `/follow/{id}/{true\|false}` | PUT（需登录） |

### 上传模块
| 前端动作 | 接口 | 方法 |
|----------|------|------|
| 上传图片 | `/upload/note` | POST（multipart，字段名 file） |
| 删除图片 | `/upload/note/delete?name=` | GET |

## 已知限制

1. **评论功能**：后端 `/note-comments` 控制器为空壳，无任何接口。前端评论区点击"发送"会 toast 提示"评论功能开发中"。
2. **退出登录**：后端 `/user/logout` 恒返回 fail。前端仅清除本地 token，toast 提示"服务端登出接口待实现"。
3. **验证码**：仅打印到后端控制台日志，也存 Redis `login:code:{phone}`。演示环境需查看后端日志或 Redis 获取。
4. **图片上传**：图片由后端项目内 uploads/imgs 目录提供，随项目迁移。
5. **CORS**：已配置完成（`MvcConfig` `allowedOriginPatterns("*")`，允许 `authorization` 头），前端独立端口可正常跨域访问后端 8081，无需额外配置。
6. **路由**：基于 hash 路由（`#/`），不支持 HTML5 History API，刷新页面会重新加载。
