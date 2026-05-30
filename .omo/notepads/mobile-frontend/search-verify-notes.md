# 全局搜索接口验证笔记（search-verify）

> 验证日期：2026-08-07
> 环境：重启后后端（PID 21292，端口 8081）正常运行；JDK 24 + Spring Boot 3.5.9
> 验证方式：curl.exe 匿名请求（无登录态），全部走 /search/** 公开放行

## 重启过程
- 杀 8081 监听 PID（原 28528）后端口释放；start-backend.bat 后台启动成功
- 启动耗时约 2.9s，Started TravelPlusApplication，日志无 ERROR

## 接口验证结果

### GET /search/hot（匿名）OK
- HTTP 200，success:true
- 返回 10 个热门词（顺序）：杭州、西湖、断桥、灵隐寺、鼓浪屿、乌镇、千岛湖、厦门、龙井、雷峰塔
- 与 SearchServiceImpl.HOT_KEYWORDS 常量一致（固定演示词条，不落库）

### GET /search?keyword=西湖（匿名）OK
- attractions（1 条）：id=1 西湖风景名胜区（destination=杭州）
- notes（3 条，标题含"西湖"）：id=4 西湖断桥｜白堤漫步…、id=9 雷峰塔｜登塔俯瞰西湖全景…、id=16 湘湖｜比西湖清静…（标题含"西湖"均命中）
- users（2 条）：id=1011 西湖边的猫、id=1013 西湖听雨人（昵称含"西湖"）

### GET /search?keyword=杭州（匿名）WARN users 为空（符合实现，非缺陷）
- attractions（3 条，name 含"杭州"）：杭州动物园、最忆是杭州演出、杭州乐园
- notes（10 条，标题含"杭州"）：灵隐寺/西溪湿地/龙井村/苏堤春晓/清河坊/满觉陇/钱塘江夜游/省博物馆/动物园等
- users（0 条）：实现按 tb_user.nick_name LIKE '%杭州%' 匹配；库中无昵称含"杭州"的用户
  - 任务预期"杭城小筑/杭漂摄影狮"实为昵称含"杭"不含"杭州"，属预期清单偏差
  - keyword=杭 时 users 正确命中：1010 杭城小筑、1016 杭漂摄影狮 OK

### GET /search?keyword=小（匿名）OK
- users（3 条，昵称含"小"）：id=1 小鱼同学、id=1010 杭城小筑、id=1015 茶园小鹿
- attractions/notes 均为空

### GET /search?keyword=（空，匿名）OK
- 三类均返回空数组（实现空关键字短路，不查库）

### GET /search/suggest?keyword=西（匿名）OK
- 返回 6 条（<=6 去重）：西湖风景名胜区、西溪国家湿地公园、西湖断桥｜…、杭州西溪湿地｜…、雷峰塔｜登塔俯瞰西湖全景…、西湖边的猫
- 三类（景点/游记/昵称）各取 top3 混合 LinkedHashSet 去重截断

### GET /search/suggest?keyword=杭（匿名）OK
- 返回 6 条：杭州动物园、最忆是杭州演出、杭州乐园、西湖断桥｜…、杭州灵隐寺｜…、杭州西溪湿地｜…
- 均为含"杭"匹配；联想词正常

## 结论
- 全部接口匿名可访问（/search/** 已放行 LoginInterceptor）
- 8 项用例中 7 项完全符合预期；1 项（keyword=杭州 users）与任务预期清单有出入，
  但经查库确认是预期清单把"昵称含杭"误写为"含杭州"，实现行为正确
- 后端保持运行，供浏览器 E2E 使用
