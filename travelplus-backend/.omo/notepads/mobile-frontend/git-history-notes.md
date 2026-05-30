# Git 历史整理记录（2026-08-07）

## 操作目标
将 TravelPlus 后端仓库全部未提交更改入库，并重写整个 git 历史：
- 所有 commit 作者/提交者统一为 `zbhgis <2935624171@qq.com>`
- 提交日期按 commit 时间顺序分布在 **2026-02-10 ~ 2026-05-30**（+08:00，严格递增）

## 执行内容

### 1. 提交当前工作（后端 travelplus）
- `.gitignore` 新增：`.omo/`、`.playwright-mcp/`、`logs/`、`home.png`（工具产物/运行时日志不入库，`uploads/` 此前已忽略）
- `git add -A` 后一次提交，共 **87 个文件**（3354 增 / 250 删）：
  - `feat: 移动端前端、密码注册、Kafka消费、评论、社交功能、主页编辑、数据丰富与全量注释`
- 提交后工作区干净

### 2. 历史重写（filter-branch）
- 重写前备份分支：`backup-before-rewrite`（指向重写前 HEAD `da05e05`）
- 脚本 `rewrite-history.sh`：`git rev-list --reverse` 取旧→新顺序，为每个 commit 分配 `2026-02-10 + round(i*109/(N-1))` 天的日期（哈希→日期映射文件，与 filter-branch 处理顺序无关），时区 +0800
- `git filter-branch --force --env-filter ... -- master`（仅重写 master 分支）
- 清理：删除 `refs/original/*`、`git reflog expire --expire=now --all`、`git gc --prune=now`

### 3. 前端仓库初始化（travelplus-frontend）
- 原非 git 仓库 → `git init` + 单次提交（25 文件，4588 行）
- `feat: TravelPlus 移动端前端（登录/注册/景点/游记/社交/个人中心）`
- 作者 `zbhgis`，日期 `2026-05-30T10:00:00+08:00`
- 新增 `.gitignore` 忽略 `logs/`

## 验证结果
- commit 总数：**57**（原 56 + 新增 1）
- 唯一作者：`zbhgis <2935624171@qq.com>`（57/57）
- 日期范围：`2026-02-10 09:00:00` → `2026-05-30 15:08:32`，`git log --reverse` 逐条递增校验通过（MONOTONIC_INCREASING=TRUE）
- `git status` 干净；关键文件存在（TravelPlusApplication.java / application.yaml / travelplus.sql / 移动端前端设计与规划.md）

## 备注
- 原始历史保留于 `backup-before-rewrite` 与 `travelplus-migration` 分支，可随时回滚
- 无远程仓库，未推送；未修改 git 全局配置
