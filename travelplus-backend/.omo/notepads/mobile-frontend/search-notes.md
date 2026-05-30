# 全局搜索（跨景点/游记/用户 + 热门词条 + 输入联想）实现摘要
# mvn -q compile 通过（EXITCODE=0，仅 Maven 自身 sun.misc.Unsafe JDK24 告警）；未重启后端；未 git 操作；未改前端；未加新依赖；未建表
# Java LSP（jdtls）未安装，编译通过为权威验证（沿用先前任务约定）

## ① 接口（全部公开，无需 token）
- `GET /search?keyword=&current=`（公开）→ data={attractions:[{id,name,image,score,avgPrice,destination}],
  notes:[{id,title,image,userId,name}], users:[{id,nickName,icon}]}
  - keyword 非空才检索；空 keyword 返回三类空列表（不查库）
  - 三类 like 模糊匹配：tb_attraction.name / tb_travel_note.title / tb_user.nick_name（LambdaQueryWrapper.like）
  - 每类最多 SystemConstants.MAX_PAGE_SIZE(10) 条（.last("LIMIT 10")，分页语义简化，current 参数位保留）
  - 首图：images 逗号 split 取第 1 项（与 UserServiceImpl.firstImage 同约定）；score 原值返回（×10，前端 /10 展示）
  - 游记作者昵称：按 userId 去重后 selectBatchIds 一次查齐（避免 N+1），缺省"匿名"
  - 用户结果：BeanUtil.copyProperties(User, UserDTO.class)（仅暴露 id/nickName/icon，不泄露 phone/password）
- `GET /search/hot`（公开）→ data=List<String> 10 个固定热门词：杭州/西湖/断桥/灵隐寺/鼓浪屿/乌镇/千岛湖/厦门/龙井/雷峰塔
  - 演示用常量数组（不建搜索词表）；后续可扩展为 Redis ZSet 按搜索频次统计取 Top N
- `GET /search/suggest?keyword=`（公开）→ data=List<String> 输入联想
  - 景点名/游记标题/用户昵称 各取 top3（like + LIMIT 3），LinkedHashSet 混合去重（保序，处理重名），最多 6 条
  - 返回扁平 List<String> 简单约定：前端输入框联想直接渲染字符串即可

## ② 匿名放行
- MvcConfig LoginInterceptor excludePathPatterns 追加 `/search/**`（搜索页先于登录展示，三个接口均无需登录）

## 改动文件（新增 3 + 修改 1）
- 新增 service/ISearchService.java（search/hotKeywords/suggest 三方法，注释含返回结构约定）
- 新增 service/impl/SearchServiceImpl.java（三类 like 查询 + 首图 split + 批量昵称 + 固定热门词 + 联想去重）
- 新增 controller/SearchController.java（@RequestMapping("/search")，@GetMapping /、/hot、/suggest）
- 修改 config/MvcConfig.java（/search/** 加入 excludePathPatterns）

## 前端对接契约
- 全局搜索页调 `GET /search?keyword=西湖` → data 三类数组；attractions 每项含 id/name/image/score(×10)/avgPrice/destination，
  notes 含 id/title/image/userId/name(作者昵称)，users 含 id/nickName/icon
- 热门词条 `GET /search/hot` → 10 个词直接渲染标签
- 搜索框联想 `GET /search/suggest?keyword=西` → 最多 6 个字符串直接下拉渲染
- 图片路径 /imgs/ 开头，展示拼 http://localhost:8081（后端 IMG_BASE）