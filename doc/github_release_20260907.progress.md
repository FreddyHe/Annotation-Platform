# GitHub 发布进度 2026-09-07

目标：提交当前平台代码，排除模型/运行数据/凭据，提供可复现的新机器部署与模型准备说明。

- 已读取既有验收记录；该记录为 2026-08-25，不代替本轮新环境验收。
- 已确认 main 与 origin/main 基线 96f78b1。
- 已整理：前后端、智能预标注适配器、模型注册表、标注主题代理、脚本与部署指南；不提交机器专属 AGENTS、客户验收材料和运行产物。
- 24 个已跟踪临时上传图片从索引移除，磁盘文件保留；旧 Git 历史未重写。
- 已验证：`git diff --cached --check` 无错误；暂存树扫描常见令牌格式及 .env.local 敏感值，未匹配；无模型权重/视频/数据库后缀文件。
- 已验证：`npm run build` 通过（1719 modules，16.52s）；有大 chunk 告警，不影响构建。首次 npm 未加载 NVM，加载 `/root/.nvm/nvm.sh` 后通过。
- 已验证：暂存源码导出到 `/tmp/annotation-release-tucc7q17`，`mvn -o -DskipTests package` 重新编译 198 个 Java 源文件并成功打包（10.175s）；这是构建检查，不是后端业务测试。
- 已验证：75 个 Python 文件 AST 语法检查；当前 Python 环境 `import main` 成功，76 routes，CPU fallback；存在旧 requests 依赖版本警告。
- 已验证：`python -m pytest scripts/test_label_studio_proxy.py -q` 为 2 passed。首次误用 unittest 发现 0 项，未将该结果作为测试通过依据。
- 已验证：经 redcloud fetch，HEAD 与 origin/main ahead/behind 为 0/0；GitHub 仓库当前为 PUBLIC。
- 未验证：独立新机器依赖安装、完整模型下载、全流程 UI/训练/边端复现。部署依然约定 Linux 固定目录，README 与部署文档已明确说明。
- 下一步：提交本次代码与部署文档、正常推送 main（不 force），再核对远端提交哈希。
- 发布前补充：开发说明采用 CRLF 导致 diff whitespace 检查告警，已统一换行并清理行尾空格；补充文件后重新扫描常见令牌格式，未匹配。代码已形成发布提交，等待远端确认。
