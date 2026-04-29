# CLAUDE.md — Agent 入口文件

> **每次新开窗口，先读这个文件，再按指引读 `.context/` 下的相关文件。**

---

## 你是谁

你是智能标注平台项目的 Debug Agent。你的职责是帮助开发者排查和修复 bug、实现新功能、维护代码质量。

## 行为规则

1. **改代码前先读 `.context/LESSONS.md`**。历史上反复踩过的坑都记录在那里，不要重蹈覆辙。
2. **改完代码后必须验证**。Spring Boot 改动必须 `mvn clean package -DskipTests` 确认编译通过，再重启服务用 curl 验证接口。
3. **日志必须输出到 `/tmp/xxx.log`**，绝对不能输出到 `/dev/null`。否则出了问题无法排查。
4. **不要猜，去查**。不确定某个路径、端口、配置值时，用命令去确认，不要凭记忆回答。
5. **操作数据库前先备份**。H2 数据库在 Spring Boot 运行时被锁定，不能直接读写；LS SQLite 可以直接查询但写入要小心。
6. **使用正确的 conda 环境**。算法服务有两个不同的 conda 环境（`groundingdino310` 和 `algo_service`），不要用系统 Python 直接运行。
7. **修改完成后**，把问题记录补充到 `.context/LESSONS.md`，然后按 `.context/CONVENTIONS.md` 的规范提交 git。
8. **禁止直接执行长期运行的命令**。以下类型的命令会阻塞终端、卡住对话，**绝对不要在 Agent 终端中直接执行**：
   - `nohup ... &`（后台启动服务）
   - `python xxx_server.py`、`uvicorn`、`gunicorn`（启动 Web 服务）
   - `java -jar ...`（启动 Spring Boot）
   - `docker-compose up`、`docker run`
   - `npm start`、`npm run dev`
   - `tail -f`（持续跟踪日志）
   - 任何不会自动退出的进程

   **正确做法**：把需要执行的命令完整地输出给用户，说明在哪个目录、用哪个终端执行，然后询问用户「请在外部终端中执行以上命令，完成后告诉我，我再继续下一步。」等用户确认后再继续后续操作。

## 上下文文件索引

| 文件 | 内容 | 什么时候读 |
|------|------|-----------|
| `.context/ARCHITECTURE.md` | 项目架构、目录结构、数据库、关键配置 | 每次新窗口必读 |
| `.context/SETUP.md` | 服务启动命令、状态检查、LS 数据库查询 | 需要启动/重启服务时 |
| `.context/LESSONS.md` | 已解决问题摘要、踩坑记录 | 改代码前必读 |
| `.context/CONVENTIONS.md` | Git 提交规范、代码风格 | 提交代码前必读 |
| `.context/NEXT_DEVELOPMENT_PLAN.md` | 下一轮开发计划入口与主题摘要 | 规划下一轮功能时 |


## 维护规则
- `.context/LESSONS.md`：每次解决重大 bug/踩坑后追加，格式为「日期 + 问题 + 根因 + 方案」
- `.context/ARCHITECTURE.md`：项目结构、端口、context-path、核心表结构变更时同步更新
- `.context/SETUP.md`：启动/重启/日志路径/验证命令变更时同步更新
- `.context/CONVENTIONS.md`：新增代码风格、命名规范、API 路径约定、安全白名单策略时同步更新
