# 私信限制升级 + 隐私扩展 + 笔记可见性 改动摘要（mvn compile BUILD SUCCESS 未重启后端，未 git 操作）

## A. 表结构（DDL 已执行 + travelplus.sql 种子已同步）
- `ALTER TABLE tb_user_info`（UTF-8 无 BOM 临时 sql 管道 docker exec 已执行，SHOW COLUMNS 已验证）：
  - `likes_visible` tinyint NOT NULL DEFAULT 1（赞过列表可见，1=可见 0=不可见）
  - `favorites_visible` tinyint NOT NULL DEFAULT 1（收藏列表可见）
  - `comments_visible` tinyint NOT NULL DEFAULT 1（评论列表可见）
  - `footprints_visible` tinyint NOT NULL DEFAULT 1（足迹/打卡列表可见）
  - `dm_mode` tinyint NOT NULL DEFAULT 0 COMMENT '私信模式 0=开放 1=仅互关 2=关闭'
- `ALTER TABLE tb_travel_note`：`visible` tinyint NOT NULL DEFAULT 0 COMMENT '0=公开 1=仅自己可见'
- 实体：UserInfo +likesVisible/favoritesVisible/commentsVisible/footprintsVisible/dmMode；TravelNote +visible；`dto/UserEditDTO` 同加 5 字段
- 种子同步：travelplus.sql 两表 CREATE TABLE 加新列（放 fans_visible 后 / comments 后）+ INSERT 行补默认
  （tb_travel_note 21 行各补 `0`；tb_user_info 12 行各补 `1,1,1,1,0`），已用脚本批量替换并抽查确认

## B. 私信限制升级（【私信限制的实现】，替换原"非互关每日 3 条"）
- `ChatServiceImpl.send` 新逻辑（按接收方 dm_mode 三档分流，UserInfo 不存在按 dm_mode=0 开放）：
  1. dm_mode=2 → fail"对方已关闭私信功能"（含互关，彻底关闭）
  2. dm_mode=1 且非互关 → fail"对方仅允许互关好友私信"（互关放行）
  3. dm_mode=0：互关 → 不限；非互关 → `checkStrangerLimit`：
     - 对方回复过我（`tb_chat_message WHERE from=对方 AND to=我` count>0）→ 不限（回复后当天可继续对话）
     - 未回复 → Redis key `chat:stranger:{from}:{to}:{yyyyMMdd}` INCR，>1 → fail"对方尚未回复你，今天最多发送一条消息"；首次=1 通过（EXPIRE 当日剩余秒数，自然日自动重置）
- RedisConstants：删 `CHAT_LIMIT_KEY`/`CHAT_NON_MUTUAL_DAILY_LIMIT(3)`，新增 `CHAT_STRANGER_KEY="chat:stranger:"`
- 互关豁免保持：isMutual（tb_follow 双向 count==2）不计数不限制
- IChatService.send/mutualStatus 文档同步更新

## C. 隐私可见性校验（【隐私设置的实现】，他人视角；本人始终可见）
- 公共语义：查看他人数据时校验对应 tb_user_info 可见性开关，==0 → fail"对方设置了隐私，该内容不可见"；
  UserInfo 不存在或开关为 null = 默认公开（不误屏蔽老数据）
- `UserServiceImpl.checkPrivacyVisible(targetUserId, Function<UserInfo,Integer> getter)` 私有复用方法（本人 id 相等直接放行）
- 接入点：
  - `GET /user/liked-notes/{id}`、`/user/liked-attractions/{id}` → `likesVisible`（UserInfo::getLikesVisible）
  - `GET /user/comments/{id}` → `commentsVisible`
  - `GET /user/checkins/{id}` → `footprintsVisible`
  - `GET /favorite/list?userId=`（FavoriteServiceImpl.list，他人查看时）→ `favoritesVisible`（注入 UserInfoMapper 直查）
- 编辑资料 `PUT /user/info`（UserServiceImpl.updateUserInfo）：新 5 字段校验（四个 0/1、dmMode 0/1/2），
  建 user_info 时补默认 1,1,1,1,0，非空才更新

## D. 笔记可见性 + 编辑（【笔记可见性的实现】【笔记编辑的实现】）
- `saveTravelNote`：支持请求体 visible（null 默认 0=公开；仅 0/1 合法，越界 fail"可见性取值只能为0或1"）；可见性不影响 Feed 推流
- 可见性过滤（统一条件 `(visible = 0 OR user_id = 当前用户)`，未登录游客只出公开）：
  - `queryHotTravelNote`（/note/hot，公开接口，游客 currentUserId=null 只出公开）
  - `queryTravelNoteByUserId`（/note/of/user，Controller 内联查询加过滤；看自己 id 时全部可见）
  - `queryTravelNoteOfFollow`（/note/of/follow，Feed IN 查询加 `AND (visible=0 OR user_id=拉取者)`，他人隐藏笔记从关注流剔除）
  - `SearchServiceImpl.search`（/search，游客只出公开，登录含自己的隐藏笔记）
  - `GET /note/{id}`：visible=1 且访问者非作者 → fail"该笔记仅作者可见"（含游客）
- 新增 `updateNote(id, note)`（【笔记编辑的实现】）：PUT /note/{id} 需登录；
  作者校验（exist.getUserId() != 当前登录用户 → fail"无权编辑该游记"；不存在 → fail"游记不存在"）；
  非空更新 title/content/images/attractionId/visible（updateById 默认只更新非 null 字段，null=不修改）；
  visible 仅 0/1、attractionId 非空时校验景点存在；liked/comments 计数不可编辑；update_time 走 DB ON UPDATE
- ITravelNoteService 增加 updateNote 声明 + 文档；TravelNoteController 增加 `@PutMapping("/{id}")`

## E. 验证
- `mvn compile` → BUILD SUCCESS，exit 0，无 [ERROR]/[WARNING]（仅 JDK24 sun.misc.Unsafe 自身告警，无害）
- DDL 已验证：SHOW COLUMNS 确认 tb_user_info 5 新列（默认 1/1/1/1/0）与 tb_travel_note.visible（默认 0）
- 未重启后端；未 git 操作；未改前端；未加新依赖

## 改动文件
- entity/UserInfo.java、entity/TravelNote.java、dto/UserEditDTO.java（+字段与文档）
- utils/RedisConstants.java（-CHAT_LIMIT_KEY/-CHAT_NON_MUTUAL_DAILY_LIMIT，+CHAT_STRANGER_KEY）
- service/impl/ChatServiceImpl.java、service/IChatService.java（私信三档 + 陌生人单条）
- service/impl/UserServiceImpl.java（checkPrivacyVisible + 4 查询校验 + updateUserInfo 扩展）
- service/impl/FavoriteServiceImpl.java（favorites_visible 校验）
- service/impl/TravelNoteServiceImpl.java、service/ITravelNoteService.java（visible 过滤 + updateNote）
- controller/TravelNoteController.java（/of/user 过滤 + PUT /note/{id}）
- service/impl/SearchServiceImpl.java（笔记搜索过滤）
- src/main/resources/db/travelplus.sql（两表 DDL + INSERT 补默认）

## 契约要点（供前端对接）
- 编辑资料页隐私设置新增 5 项：赞过可见/收藏可见/评论可见/足迹可见（0/1）+ 私信模式 dm_mode（0=开放 1=仅互关 2=关闭），`PUT /user/info` 提交同名字段
- 他人主页 tab 不可见时后端 fail"对方设置了隐私，该内容不可见"（前端展示该文案即可）；本人始终可见
- 发私信失败文案：关闭="对方已关闭私信功能"；仅互关非互关="对方仅允许互关好友私信"；开放且对方未回复第二条起="对方尚未回复你，今天最多发送一条消息"
- 笔记发布 body 可带 `visible`（0=公开 1=仅自己可见，缺省 0）；编辑 `PUT /note/{id}` 同字段名，
  title/content/images/attractionId/visible 均可选提交（null=不修改）
- 仅自己可见笔记：详情/热门/他人主页/关注 Feed/全局搜索对非作者不可见（详情 fail"该笔记仅作者可见"），
  本人"我的笔记"（/note/of/me）与本人视角列表均可见
