
## 图片下载结果摘要（2026-08-05）
任务：下载真实图片替换缺失/外链图片，更新 MySQL 图片路径。图片根目录 D:\lesson\nginx-1.18.0\html\travelplus\imgs\，base=http://localhost:8081（/imgs/** 静态映射，匿名放行）。

### 各类数量
- 景点图片 tb_attraction：14 个景点共 39 张（每景点 2~3 张），存 imgs/attraction/{id}/{n}.{jpg|png}（3 号景点含 2 张 .png、12 号含 1 张 .png，其余 .jpg，按真实内容扩展名保存）
- 类型图标 tb_attraction_type：10 张，存 imgs/types/{原文件名}.jpg（ms/KTV/lrmf/jsyd/amzl/spa/qzyl/jiuba/hpg/mjmj）
- 用户头像 tb_user：id=1 → imgs/blogs/blog1.jpg；id=2 → imgs/icons/kkjtbcr.jpg（icons 目录已新建）
- 游记图片 tb_travel_note：13 张（12 张唯一 uuid 图片 + blog1.jpg 复用），存 imgs/blogs/{d1}/{d2}/{uuid}.jpg
- 合计 63 张新下载图片（另保留原有 70B 的 test.png 未动）

### 下载源
- 景点：优先复用 DB 既有美团/腾讯云 CDN 外链（多数返回 200 直接复用），失败的用 picsum.photos/seed/... 兜底
- 类型/头像/游记：picsum.photos 稳定 seed 下载（无版权风险占位图）
- 失败项：0（63/63 全部下载成功且 >5KB）

### 数据库更新（共 24 条 UPDATE，全部执行成功并查回验证）
- tb_attraction：14 条 UPDATE（images 改为 /imgs/attraction/{id}/... 逗号分隔）
- tb_attraction_type：10 条 UPDATE（icon 由 /types/*.png 改为 /imgs/types/*.jpg）
- tb_user / tb_travel_note：无需 UPDATE（路径未变，文件已补齐）

### 验证
- 全部 63 张 >5KB（无 404/占位页）
- 抽查 10 个 URL 经 curl 均返回 HTTP 200（含 jpg 与 png，覆盖景点/类型/头像/游记）
- DB 查回验证通过：SELECT id,images FROM tb_attraction LIMIT 3 等

## 图片目录迁移（2026-08-05）
- 解除对 `D:\lesson\nginx-1.18.0\html\travelplus\imgs\` 外部硬编码目录的依赖
- `SystemConstants.IMAGE_UPLOAD_DIR` 改为 `System.getProperty("user.dir") + "\\uploads\\imgs\\"`，随项目位置自动解析（mvn spring-boot:run / java -jar 下 user.dir = 项目根 E:\AAAproject\travelplus → `E:\AAAproject\travelplus\uploads\imgs\`）
- 已复制 D 盘 imgs 全部 65 个文件至项目内 uploads/imgs（attraction/、types/、blogs/、icons/、test.png），源/目标文件数一致（65=65），关键样本 attraction/1/0.jpg、types/ms.jpg、blogs/blog1.jpg、icons/kkjtbcr.jpg、test.png 均存在；D 盘源目录保留未删（待验证后由用户决定清理）
- .gitignore 末尾追加 `uploads/`；docs/travelplus/05-移动端前端设计与规划.md（第 4 节 MvcConfig 说明 + 第 9 节已知限制）与 travelplus-frontend/README.md（已知限制第 4 条）D 盘表述已更新；docs/travelplus/03-过程记录.md 图片资源小节加注记（保留原文）
- `mvn -q compile` 通过（exit 0，仅 JDK/Maven 无害警告）
- DB 与前端存的 `/imgs/...` 路径不变，上传与 `/imgs/**` 静态映射零改动