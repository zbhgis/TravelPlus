# TravelPlus 移动端 — 本轮新增/改造代码注释规范化摘要（mvn -q compile 通过，零逻辑改动）

## 背景
- 用户要求：本轮新增/改造代码统一补充 `【功能名】 + 实现细节` 注释，风格对齐 Kafka 部分已有的 `【kafka消息队列的实现】`（类/方法级 Javadoc 块，逐条解释"为什么这样实现、链路是什么、注意什么"）。
- 只加注释，不改任何方法体/条件/常量；Kafka 部分（KafkaConfig/SeckillTicketConsumer/TicketOrderServiceImpl 发送端/@EnableKafka）已达标未改动。

## A. 密码登录与注册（【密码登录与注册的实现】）
- `service/impl/UserServiceImpl.java`
  - `login()` 新增方法级注释：双分支设计（StrUtil.isNotBlank(password) 判分支，password 非空优先走密码登录，为空回退验证码登录）；密码分支链路 = 手机号校验→查用户（未注册提示）→BCrypt.checkpw→saveLoginToken；BCrypt 校验失败统一提示"手机号或密码错误"（不区分未设密码/密码错误，避免泄露注册状态）；两条分支收敛到 saveLoginToken。
  - `register()` 新增方法级注释：校验链（手机号格式→密码≥6位→查重）、BCrypt.hashpw 加密存储、昵称可选自动生成、注册成功自动登录复用 saveLoginToken 返回 token 的体验设计。
  - `saveLoginToken()` 注释升级：token 用 UUID.toString(true) 生成；User→UserDTO→Redis Hash（BeanUtil 只复制非敏感字段、setIgnoreNullValue + toString 值处理）；key=login:token:{token} 与 RefreshTokenInterceptor/logout 共用约定；TTL 由 RefreshTokenInterceptor 随活跃请求滚动续期；三处调用共用。
- `controller/UserController.java` `register()` 注释升级：接口用途 + 注册成功自动登录返回 token + /user/register 已在拦截器 exclude 放行匿名可访问。
- `dto/LoginFormDTO.java` `nickName` 字段补注释：注册可选传，为空时服务端自动生成 "user_"+随机串。

## B. 登出（【登出功能的实现】）
- `controller/UserController.java` `logout()` 注释升级：从 authorization 头取 token；非空删除 Redis login:token:{token}（LOGIN_USER_KEY）服务端即刻失效；之后 RefreshTokenInterceptor 不再写入 UserHolder；前后端配合（先服务端失效、前端再清本地）；删除幂等直接 ok。
- import 自查：HttpServletRequest / StringRedisTemplate / StrUtil / LOGIN_USER_KEY 均已引入，无需新增。

## C. 评论（【评论功能的实现】）
- `controller/TravelNoteCommentController.java`：两个端点注释统一为【评论功能的实现】格式（POST 发表需登录、userId 服务端取不信任前端；GET /of/{noteId} 分页升序）。
- `service/impl/TravelNoteCommentServiceImpl.java`：
  - `saveComment()` 注释升级：UserHolder 取 userId→参数校验→parentId/answerId 默认0、liked 0、status true→落库→游记 comments+1 同步（失败不更新计数）。
  - `queryComments()` 注释升级：createTime 升序分页→IUserService.getById 补 name/icon（@TableField(exist=false) 不落库）→用户不存在兜底"匿名"。
- `entity/TravelNoteComment.java`：icon/name 的 exist=false 字段注释补全（只用于响应传输、不映射表列、由 queryComments 补充，风格同 TravelNote）。

## D. 其他入口
- `config/MvcConfig.java`：`/user/register` exclude 行加内联注释（注册接口匿名可访问，密码/验证码登录接口本就在放行列表）。

## 验证
- `mvn -q compile` 通过（EXIT=0，仅 JDK24 sun.misc.Unsafe 警告，无害）。
- grep 自查 `【.*的实现】`：UserServiceImpl(3)、UserController(2)、TravelNoteCommentController(2)、TravelNoteCommentServiceImpl(2)、TravelNoteComment(2) 全部覆盖；Kafka 部分标记未变。
- 零逻辑改动：仅增补注释；未重启后端；未改前端/DB；未加依赖；未 git 操作。
