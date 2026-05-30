# data-x3-notes — 数据 3 倍扩容报告（2026-08-08）

范围：TravelPlus 数据扩容至 3 倍——新增 32 用户 + 42 游记 + 48 关注 + 41 评论 + 28 收藏 + 21 打卡 + 325 通知，全部落地运行库并同步种子脚本 travelplus.sql。
执行结果：全部成功，失败项 0。

## A. 新增用户（tb_user + tb_user_info，32 个，id=1022~1053）

总用户 16 → 48（目标 ≥48 ✓）。

- 昵称旅游主题且不与既有重复：湖光山色、鹭岛漫步、山河远阔、云端行者、赶路人、山海之间、江南烟雨、海风信子、环岛路的夏天、小巷深处、白鹭洲头、拾光旅人、向阳而生、山海有约、浪迹天涯、慢游日记、追风少年、见山见海、一路向南、曾厝垵姑娘、千岛之恋、龙井问茶、断桥残雪、苏堤春晓、灵隐听钟、满陇桂雨、太子湾的风、乌镇枕水、良渚拾光、沙坡尾青年、云深不知处、海边的咖啡馆。
- 手机号新号段 133/135/136/137/138/139/150/151/152/153/155/156/157/158/159/180/181/182/183/185/186/187/188/189（如 13310000000、13510000001…），11 位，与既有 16 个零冲突。
- password 统一 $2a/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K（123456）；icon 用默认头像 /imgs/user/avatar/default.png；create_time 分布 2026-06 ~ 2026-08。
- tb_user_info 32 条：city=杭州/厦门/全国，introduce 贴合人设，fans/followee 初始 0（后按真实关注全量重算）。

## B. 新增游记（tb_travel_note，42 篇，id=28~69）

总游记 21 → 63（目标 ≥63 ✓；运行库原有多余测试笔记 id=29「你好」已删除，id=29 现为本次新增灵隐主题游记）。

- 主题覆盖：杭州（西湖/灵隐/西溪/龙井/太子湾/宋城/清河坊/雷峰塔/六和塔/浙博/动物园/乐园/最忆是杭州/满觉陇/断桥/苏堤）+ 厦门（鼓浪屿/厦大/环岛路/曾厝垵/南普陀/沙坡尾）+ 周边（千岛湖/乌镇/湘湖/良渚）。
- 标题小红书风格、正文 130~260 字、含 emoji、多段换行（CHAR(10)）；attraction_id 全部关联真实景点。
- 图片策略（零新增下载）：42/42 全部复用关联景点首图 /imgs/attraction/{attractionId}/0.jpg（文件已存在，HTTP 抽查 200）。
- 作者分散：42 篇 42 个不同作者（新用户为主 + 老用户 1010~1020/1/2/4/5 混合），厦门主题 11 篇。
- visible=0 公开；liked 与 Redis 一致（见 C）；comments 与评论表实际行数一致；create_time 2026 年内。

## C. 点赞（Redis ZADD + DB 同步）

- 每篇新游记 Redis 
ote:liked:{id} ZADD 3~8 个不同用户（新老用户池，作者除外），score=近期时间戳。
- 逐篇 ZCARD → UPDATE tb_travel_note SET liked={zcard}，42/42 篇 DB liked == Redis ZCARD 全对齐（含既有 4~25 篇复查，0 失配）。

## D. 关注 / 评论 / 收藏 / 打卡 / 通知

- 关注 tb_follow：新增 48 条（id=47~94），新用户互关 16 + 新关注老 16 + 老回关 16，与既有 28 条零重复（uk_user_follow 兜底）。
- fans/followee：全量重算 UPDATE tb_user_info ... (SELECT COUNT(*) FROM tb_follow ...)，与真实关注表一致（如 1010 fans=5/followee=6、1022 fans=2/followee=1）。
- 评论 tb_travel_note_comment：新增 41 条（id=22~62），覆盖新游记 35 篇 + 老游记 9/11/13/21/22；comments 计数 UPDATE 为评论表实际行数（新老游记均对齐）。
- 收藏 tb_favorite：新增 28 条（16 景点 + 12 游记，id=16~43，INSERT IGNORE + uk_user_target 幂等）。
- 打卡 tb_attraction_checkin：新增 21 条（id=29~49，杭州/厦门 21 个不同景点，INSERT IGNORE + uk_ac 幂等）。
- 通知 tb_notify：新增 325 条（id=218~542），按新关注(48)/点赞(216)/评论(34)/收藏(11) 关系生成；content 纯动作文案（「关注了你」「赞了你的游记《…》」等），displayText 由展示层实时拼装昵称（小红书方案）。

## E. 种子脚本同步（travelplus.sql，UTF-8 无 BOM）

- 追加：32 用户（含 password 哈希 + 默认头像）+ 32 user_info + 42 游记（含 attraction_id/images/visible）+ 48 关注 + 41 评论 + 21 打卡 + 说明注释；收藏/通知为运行时业务数据按既有约定不写入种子。
- 对齐 AUTO_INCREMENT：tb_user=1054、tb_travel_note=70、tb_follow=95、tb_travel_note_comment=63（运行库同步 ALTER）。
- 所有 INSERT 使用显式列名（兼容运行库与种子的列顺序差异，规避 live 库列序与种子不同的坑）。

## F. 验证

- 运行库行数：用户 48（新 32）、游记 63（新 42）、关注 76（新 48）、评论 60（新 41）、收藏 33（新 28）、打卡 49（新 21）、通知 485（新 325）、user_info 45（新 32）。
- 图片 HTTP：抽查 6 个 /imgs/attraction/{id}/0.jpg + 默认头像全部 200。
- 登录：POST /user/login（13310000000/123456、13510000001/123456）均 success 返回 token；/user/me 正常返回昵称/默认头像。
- API 回显：GET /note/hot 第 1 页含新游记 69/62/50 等；GET /notify/unread-count、/notify/list 正常（user2 未读 10、总数 27，首条 follow 来自 1013）。
- 临时库 seed_verify：CREATE → 导入 travelplus.sql → 用户 48 / 游记 63 / 关注 72 / 评论 59 / 打卡 48 / user_info 44 全符合预期 → 已 DROP。

## 失败项 / 备注（坑）

- 失败项：0。
- 坑 1：运行库 	b_user/	b_travel_note 列顺序与种子 CREATE TABLE 不同（bg_image/visible 在末尾）——位置 VALUES 会错位，改用显式列名 INSERT 全兼容。
- 坑 2：PowerShell 5.1 读 .ps1 中文需 UTF-8 带 BOM，否则解析乱码。
- 坑 3：Redis --pipe 模式不认 # 注释行（ERR unknown command '#'），去注释后重放即可（ZADD 幂等）。
- 坑 4：live 库 AUTO_INCREMENT 滞后（tb_user=1023 < max 1053），不 ALTER 会导致新注册撞已有 id；已 ALTER 对齐。
- 未重启后端、未 git 操作、未改代码/前端。