# TravelPlus 移动端 — 认证（登录/注册）改动记录

## 背景
- 接口契约（backend-contract.md）：`POST /user/login {phone,code} → data=token`；Redis key `login:token:{token}`；UserDTO = {id, nickName, icon}
- 本任务在保持验证码登录不变的前提下，新增密码登录与用户注册；旧用户统一初始密码 123456

## 实现摘要（mvn -q compile 通过）

### LoginFormDTO（新增字段）
- 新增 `nickName` 字段（注册传昵称用），phone/code/password 保持原有

### IUserService / UserServiceImpl
- `login(LoginFormDTO, HttpSession)` 扩展：
  - password 非空 → **密码登录**：手机号格式校验 → `query().eq("phone", phone).one()` 查用户（不存在 fail "该手机号未注册，请先注册"）→ `BCrypt.checkpw(password, user.getPassword())`（不匹配 fail "手机号或密码错误"）→ `saveLoginToken(user)` 返回 token
  - 无 password → 原验证码登录逻辑原样保留（验证码校验 → 查/建用户 → saveLoginToken）
- 新增 `register(LoginFormDTO)`：手机号校验 → 密码非空且 ≥6 位（否则 fail "密码长度不能少于6位"）→ 查重（已存在 fail "该手机号已注册"）→ 建 User（nickName 空则 `USER_NICK_NAME_PREFIX + RandomUtil.randomString(10)`，password=`BCrypt.hashpw`，icon=""）→ save → `saveLoginToken(user)` 自动登录返回 token
- 抽取私有方法 `String saveLoginToken(User user)`：原 login 中「User→UserDTO→Redis Hash(login:token:{token}, TTL 36000min)→返回 token」的代码，密码登录/验证码登录/注册三处共用
- 密码加密：`cn.hutool.crypto.digest.BCrypt`（hutool-all 5.8.36，零新依赖）

### UserController
- 新增 `@PostMapping("/register")` → `userService.register(loginForm)`
- `@PostMapping("/login")` 无需改动（body 含 password 时自动走密码分支）

### MvcConfig（登录拦截器放行）
- `LoginInterceptor.excludePathPatterns` 追加 `/user/register`（注册匿名可访问，否则 401）

## 数据更新
- 确认 `tb_user.password` 已是 `varchar(128)`（≥64），无需 ALTER
- 123456 的 BCrypt 哈希（hutool BCrypt.hashpw 生成）：
  `$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K`
  （jshell + hutool-all jar 验证 checkpw=true）
- 运行库：7 个现有用户 (id 1,2,4,5,1010,1011,1012) 统一 `UPDATE tb_user SET password=<上哈希>`，通过 SQL 文件管道执行
- 种子脚本 `src/main/resources/db/travelplus.sql`：tb_user 的 4 条 INSERT（id 1,2,4,5）password 列填入同一哈希；新环境种子用户可用 123456 登录

## 验证结果（本任务可自证范围）
- `mvn -q compile` 通过（EXIT=0，仅 JDK24 Unsafe 警告，无害）
- 运行中的后端进程（pid 44696，启动于改动前）为旧字节码，curl 自测 `/user/register` 返回 405 —— **需 orchestrator 重启后端后验证**，预期：
  - `POST /user/register {"phone":"13600000000","password":"abc123","nickName":"测试注册"}` → success=true data=token
  - `POST /user/login {"phone":"13600000000","password":"abc123"}` → success=true data=token
  - `POST /user/login {"phone":"13686869696","password":"123456"}`（旧用户）→ success=true
  - `POST /user/login {"phone":"13686869696","password":"wrong"}` → success=false（手机号或密码错误）

## 历史经验（踩坑）
- 新增匿名接口必须同步加进 LoginInterceptor excludePathPatterns（/user/register 一开始漏了，会 401）
- SQL 中文/哈希值一律写临时 .sql 文件（UTF-8 无 BOM）再 `cmd /c "docker exec -i travelplus-mysql mysql ... < file.sql"`，禁止 PowerShell 直接传 SQL
- 密码列长度：BCrypt 哈希 60 字符，列需 ≥64（本项目 varchar(128) 已满足）
