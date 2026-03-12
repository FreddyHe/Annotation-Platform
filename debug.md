智能标注平台 Debug 手册

本文件是跨窗口持久化的 debug 上下文。每次新开 Agent 窗口时，请先阅读本文件。
路径：/root/autodl-fs/Annotation-Platform/debug.md


一、项目架构概览
智能标注平台，用于图像目标检测的自动化标注。核心流程：DINO 检测 → VLM 清洗 → 同步预测到 Label Studio。
组件技术栈端口启动方式后端Spring Boot 3.2 + JPA + H28080见下方前端Vue 3 + Element Plus + Vite5173cd frontend-vue && npm run devLabel StudioPython (conda: web_annotation)5001见下方算法服务 FastAPIPython (conda: algo_service)8001见下方DINO 模型服务Python (conda: groundingdino310)5003见下方

二、关键路径和配置
项目根目录: /root/autodl-fs/Annotation-Platform/
后端代码:   /root/autodl-fs/Annotation-Platform/backend-springboot/
前端代码:   /root/autodl-fs/Annotation-Platform/frontend-vue/
算法服务:   /root/autodl-fs/Annotation-Platform/algorithm-service/

Spring Boot 配置: backend-springboot/src/main/resources/application.yml
  - context-path: /api/v1  （所有 API 路径前缀是 /api/v1，不是 /api）
  - 数据库: H2, 文件路径 backend-springboot/data/testdb
  - LS URL: http://localhost:5001
  - LS public URL: http://122.51.47.91:28450
  - LS admin-token: 3dd84879dff6fd5949dc1dd76edbecccac3f8524
  - LS SQLite DB: /root/.local/share/label-studio/label_studio.sqlite3
  - 文件上传路径: /root/autodl-fs/uploads

Label Studio admin 用户:
  - LS user id: 1
  - email: datian@tongji.edu.cn
  - token: 3dd84879dff6fd5949dc1dd76edbecccac3f8524
  - active_organization_id: 1

三、服务启动方法
3.1 Spring Boot 后端
bashcd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests
kill $(lsof -ti:8080) 2>/dev/null; sleep 3
nohup java -jar target/platform-backend-1.0.0.jar --server.port=8080 > /tmp/springboot.log 2>&1 &
# 等待 15 秒后验证
sleep 15 && grep "Started" /tmp/springboot.log
⚠️ 日志必须输出到 /tmp/springboot.log，绝对不能输出到 /dev/null！
### 3.2 Label Studio
```bash
# 确认是否已在运行
ps aux | grep label-studio | grep -v grep
# 如果没有运行：
source $(conda info --base)/etc/profile.d/conda.sh
conda activate web_annotation

# ⚠️ 必须设置这两个环境变量，否则本地文件存储功能不可用
export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs

nohup label-studio start --port 5001 --no-browser --log-level INFO > /tmp/labelstudio.log 2>&1 &
```
3.3 算法服务（两个进程，两个 conda 环境）
bashsource $(conda info --base)/etc/profile.d/conda.sh

# DINO 服务 (端口 5003)
conda activate groundingdino310
export CUDA_VISIBLE_DEVICES=""
cd /root/autodl-fs/Annotation-Platform/algorithm-service
nohup python dino_model_server.py > /tmp/dino.log 2>&1 &

# FastAPI 算法服务 (端口 8001)
conda activate algo_service
export CUDA_VISIBLE_DEVICES=""
cd /root/autodl-fs/Annotation-Platform/algorithm-service
nohup uvicorn main:app --host 0.0.0.0 --port 8001 > /tmp/algorithm.log 2>&1 &
⚠️ 不要用系统 Python 直接 python main.py，必须先 conda activate！
3.4 快速检查所有服务状态
bashecho "=== 端口检查 ==="
for port in 5001 5003 8001 8080 5173; do
  if lsof -ti:$port > /dev/null 2>&1; then
    echo "  端口 $port: ✅ 运行中"
  else
    echo "  端口 $port: ❌ 未运行"
  fi
done

四、LS 数据库快速查询
bash# 查看所有用户
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT id, email, active_organization_id FROM htx_user ORDER BY id;"

# 查看所有组织
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT id, title, created_by_id FROM organization ORDER BY id;"

# 查看组织成员
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT organization_id, user_id FROM organizations_organizationmember ORDER BY organization_id, user_id;"

# 查看所有项目
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT id, title, organization_id, created_by_id, substr(label_config, 1, 60) FROM project ORDER BY id;"

# 查看 Token
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT t.key, t.user_id, u.email, u.active_organization_id FROM authtoken_token t JOIN htx_user u ON t.user_id = u.id;"

五、Spring Boot H2 数据库说明
H2 数据库文件在 backend-springboot/data/testdb.mv.db，Spring Boot 运行时会锁定此文件，无法用外部工具直接读取。要查询 H2 数据库内容，需要通过 Spring Boot API 或日志。

六、已解决的问题记录
6.1 LS 项目 organization_id 为 NULL（已修复）

原因：syncProjectToLS 传了 "organization": null 给 LS API，覆盖了默认行为
修复：改用组织管理员 token 创建项目 + 生成 label_config XML

6.2 LS 组织 created_by_id 为 NULL（已修复）

原因：syncOrganizationToLS 创建组织后没设置 created_by_id
修复：在 syncUserToLS 中调用 updateOrganizationCreatedByInLSDB

6.3 LS 用户 active_organization_id 为 NULL（已修复）

原因：syncUserToLS 没有设置 LS 用户的 active_organization_id
修复：调用 updateUserActiveOrganizationInLSDB

6.4 LS 用户没有被添加到新组织成员表（已修复）

原因：通过 admin token 创建的 LS 用户自动加入组织 1，没有加入新组织
修复：调用 addUserToOrganizationInLSDB

6.5 Hibernate 懒加载 "no Session" 错误（已修复）

原因：syncProjectToLS 中访问 organization.getCreatedBy() 时 Session 已关闭
修复：通过 Repository 用 ID 重新查询关联对象

6.6 LS 项目 label_config 为空（已修复）

原因：syncProjectToLS 没有传 label_config 字段
修复：根据 project.getLabels() 动态生成 XML

### 6.7 Label Studio 本地文件服务未开启（已修复）
- **现象**：挂载本地存储时报 400：`LOCAL_FILES_SERVING_ENABLED is disabled by default`
- **原因**：LS 默认禁止从宿主文件系统读取文件
- **修复**：启动 LS 前设置环境变量 `LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true` 和 `LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs`
- **注意**：每次重启 LS 都需要设置这两个环境变量，建议写入启动脚本
- **启动命令**：
  ```bash
  source $(conda info --base)/etc/profile.d/conda.sh
  conda activate web_annotation
  export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
  export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs
  label-studio start --port 5001 --no-browser --log-level INFO
  ```

### 6.8 syncProjectToLS 创建项目时 organization_id 仍然为 1（已修复）
- **现象**：用组织管理员 token 创建的 LS 项目，organization_id 是 1 而不是用户所属的新组织
- **原因**：用户通过浏览器访问 LS 后，LS 可能会将 `active_organization_id` 改为其他值。`syncUserToLS` 的分支 1 和分支 2 不会更新 `active_organization_id`，导致后续创建项目时 LS 根据错误的 `active_organization_id` 决定项目归属
- **修复**：在 `syncProjectToLS` 中，调用 LS API 创建项目之前，强制更新 token 用户的 `active_organization_id` 为当前组织 ID
- **修复代码**：
  ```java
  // 【修复】创建项目前，强制将 token 对应用户的 active_organization_id 设为当前组织
  if (organization.getLsOrgId() != null && createdBy != null && createdBy.getLsUserId() != null) {
      updateUserActiveOrganizationInLSDB(createdBy.getLsUserId(), organization.getLsOrgId());
      log.info("创建项目前更新 LS 用户 active_organization_id: lsUserId={}, lsOrgId={}",
               createdBy.getLsUserId(), organization.getLsOrgId());
  }
  ```

七、当前待解决的问题
（无）

现象：用组织管理员 token 创建的 LS 项目，organization_id 是 1 而不是用户所属的新组织
原因分析：LS 可能根据 token 用户的 active_organization_id 来决定项目归属。但新用户同时是组织 1 和新组织的成员。LS 的 GET /api/projects 返回的是用户当前 active_organization 下的项目。项目创建时 LS 可能用的是创建者当前的 active_organization。
关键线索：数据库显示新用户 active_organization_id=14，但创建的项目 organization_id=1。这说明 syncProjectToLS 中获取到的 token 可能不是新用户的 token，或者 LS API 创建项目时有特殊行为。
需要排查：syncProjectToLS 实际用的是哪个 token？该 token 对应用户的 active_organization_id 是多少？

---

## 八、期望的架构设计

1. 用户注册时在 LS 中创建对应账户
2. 每个组织在 LS 中有对应组织，第一个创建者是管理员
3. **所有 LS 项目操作使用组织管理员的 token**（不是全局 admin token）
4. 项目自动归属到管理员的 active_organization



