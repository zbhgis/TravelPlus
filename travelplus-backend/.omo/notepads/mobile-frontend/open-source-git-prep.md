# 开源前 Git 历史安全处理记录

> 执行日期：2026-08-15
> 目标：唯一可见作者 `zbhgis <2935624171@qq.com>`、提交时间 `2026-02 ~ 2026-05`，清除原始作者/旧项目残留。

## 1. 执行内容

### A. 备份
- ✅ `backup-pre-open-source` 分支已创建，指向 master（回滚安全网，**勿删**）

### B. 处理含原始作者的本地分支
- ✅ 删除 `travelplus-migration`（含虎哥 huyi0612@163.com、张虎翼 zhanghuyi@itcast.cn 及 2021 提交）
- ✅ 删除 `backup-before-rewrite`（重写前备份，同样含原始作者）
- ✅ `git reflog expire --expire=now --all` + `git gc --prune=now --aggressive` 彻底清除被删分支对象
- ✅ `git fsck --unreachable` 返回 **0 个不可达对象**（原始作者提交无法找回）

### C. 处理远程
- ✅ 移除 `origin`（原指向旧项目 `https://gitee.com/yueruf/yueruf-hmdp.git`，开源时需配置新 TravelPlus 远程）
- ✅ `git remote -v` 为空

### D. master 提交时间范围验证
- ✅ 最早 `2026-02-10`、最晚 `2026-05-30`，所有提交日期均在范围内（57 提交）
- ✅ `git log --all --format="%an <%ae>"` 仅 `zbhgis <2935624171@qq.com>`

### E. 配置检查
- ✅ `user.name = zbhgis`、`user.email = 2935624171@qq.com`（已正确，无需修改）

## 2. 遗留隐患（需用户决策，本次未改动）

### ⚠️ DeepSeek API Key 明文
- `src/main/resources/application.yaml:79` 含 `api-key: sk-***（脱敏）`（DeepSeek 平台测试密钥）
- **未进入 git 历史**（`git log -S` 无命中），仅存在于当前工作区未提交改动中
- 开源前建议改为占位符（如 `${DEEPSEEK_API_KEY}` 或假值），否则下次提交即入库泄露
- 另：`password: 123456`（MySQL/Redis 演示密码）虽弱，属 demo 级别，风险低

### ⚠️ 工作区存在大量未提交改动
- 当前 `git status` 不干净：数十个修改/新增文件（Chat/Search/Trip/Favorite 等功能、ES、docker/、scripts/）
- 这些是新功能代码，尚未提交；开源发布前需决定是否提交、以及提交前的密钥脱敏

## 3. 当前状态
- 分支：`master` + `backup-pre-open-source`（均只有 zbhgis 作者、2026 时间）
- 无远程；无不可达对象；历史已完全清洗

## 4. 开源发布 Checklist（待办）
- [ ] 配置新 TravelPlus 远程（`git remote add origin <新地址>`）
- [ ] 脱敏 application.yaml 中 DeepSeek api-key 后再提交工作区改动
- [ ] 决定未提交新功能代码的处理方式
