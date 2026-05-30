# TravelPlus 移动端前端 — 后端对接事实（orchestrator 实地探索，勿臆造）

## 运行环境
- 后端端口 8081；鉴权头 authorization（小写）= 登录返回 token；未登录访问受保护路径 → HTTP 401
- 统一返回 Result: {success, errorMsg, data, total}（non_null，null 字段不输出）
- 后端当前无 CORS 配置 → Task A 在 MvcConfig 补 addCorsMappings + /imgs/** 静态映射
- MySQL(3307)/Redis(6379)/Kafka(9092) Docker 均 Up

## 放行路径（无需 token）
/attraction/**, /ticket/**, /attraction-type/**, /upload/**, /note/hot, /user/code, /user/login
其余需登录（/user/me, /user/sign, /note POST, /note/like/**, /note/of/me, /note/of/follow, /follow/**, /ticket-order/**）

## 接口清单
用户: POST /user/code?phone= | POST /user/login {phone,code}→data=token | POST /user/logout(恒fail未实现) | GET /user/me→UserDTO | GET /user/info/{id} | GET /user/{id} | POST /user/sign | GET /user/sign/count→int
景点: GET /attraction/{id} | GET /attraction/of/type?typeId&current&x&y（5秒限5次，超限fail"查询次数过多"） | GET /attraction/of/name?name&current
类型: GET /attraction-type/list → [{id,name,icon,sort}]
门票: GET /ticket/list/{attractionId} → List<Ticket>（stock/beginTime/endTime 非空=秒杀券）
订单: POST /ticket-order/seckill/{ticketId}（需登录）成功data=订单id；失败文案：库存不足/一人一单/未开始/已结束
游记: POST /note {title,images,content,attractionId?}→data=noteId | PUT /note/like/{id} 切换 | GET /note/hot?current（含name/icon/isLike） | GET /note/{id} | GET /note/likes/{id}→Top5 UserDTO | GET /note/of/me?current | GET /note/of/user?id&current | GET /note/of/follow?lastId&offset→{list,offset,minTime}
关注: PUT /follow/{id}/{true|false} | GET /follow/or/not/{id}→boolean | GET /follow/common/{id}
上传: POST /upload/note multipart字段file→data=相对名(blogs/d1/d2/uuid.jpg，不带/imgs前缀)
评论: /note-comments 空壳控制器，无任何接口（未实现）

## 实体渲染字段
Attraction: id,name,typeId,images(逗号分隔,/imgs/开头),area,address,avgPrice,sold,comments,score(×10),openHours
Ticket: id,title,subTitle,rules,payValue(分),actualValue(分),status,stock,beginTime,endTime
TravelNote: id,userId,icon,name,isLike,title,images(逗号分隔),content,liked,comments,createTime
UserDTO: id,nickName,icon
金额单位=分(/100展示)；score/10 展示

## 图片
DB 图片路径形如 /imgs/blogs/blog1.jpg。上传返回相对名，展示拼 IMG_BASE+/imgs/+相对名。
上传目录 D:\lesson\nginx-1.18.0\html\travelplus\imgs\（D盘存在；目录不存在时上传会失败）
Task A 让后端 /imgs/** 映射该目录 → IMG_BASE 可直接用 http://localhost:8081

## 验证码 E2E 技巧
验证码仅打印到后端日志，也存 Redis key login:code:{phone}（无密码）：docker exec company-agent-redis redis-cli GET login:code:13686869696
种子用户: 13686869696 小鱼同学(id=1), 13838411438(id=2)

## Task A 改动（已完成，mvn -q compile 通过）
- MvcConfig 新增 addCorsMappings: /** + allowedOriginPatterns(*) + GET/POST/PUT/DELETE/OPTIONS + allowedHeaders(*) + maxAge 3600（不启用 credentials，前端不带 cookie）
- MvcConfig 新增 addResourceHandlers: /imgs/** -> file:D:\lesson\nginx-1.18.0\html\travelplus\imgs\（启动时 mkdirs 确保目录存在，无需 nginx）
- addInterceptors 原样保留（LoginInterceptor exclude 列表与 RefreshTokenInterceptor 注册未动）

## Task A 修复（E2E 401 问题，mvn -q compile 通过）
- LoginInterceptor excludePathPatterns 追加 /imgs/**（静态图片匿名可访问，避免未登录 401）
- 其余不动：addCorsMappings / addResourceHandlers / RefreshTokenInterceptor 注册保持原样

## Task A 修复2（/error 转发 401 问题，mvn -q compile 通过）
- LoginInterceptor excludePathPatterns 追加 /error（NoResourceFoundException 等转发到 /error 必须匿名，否则 404/500 被吞成 401）
- 其余不动

## Task A 修复3（CORS 预检 401 问题，mvn -q compile 通过）
- LoginInterceptor.preHandle 开头新增：请求方法为 OPTIONS 时直接 return true 放行（CORS 预检不携带业务凭证）
- RefreshTokenInterceptor 无需改（无 token 时本就放行）
