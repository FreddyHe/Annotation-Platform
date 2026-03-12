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

### 6.9 用户在 LS Web UI 中看不到自己组织下的项目（已修复）
- **现象**：用户注册并创建项目后，在 LS Web UI 中登录时只能看到组织 1 下的项目，看不到自己组织下的项目
- **原因**：
  1. LS 创建用户时，默认将用户添加到组织 1 的成员表中
  2. 用户通过浏览器访问 LS Web UI 时，LS 可能会将用户的 `active_organization_id` 改为 1
  3. LS Web UI 的项目列表 API（`GET /api/projects`）根据用户当前的 `active_organization_id` 过滤项目
  4. 即使用户被添加到了新组织，但如果 `active_organization_id` 仍然是 1，就只能看到组织 1 下的项目
- **修复方案**：
  1. 在 `syncUserToLS` 中，添加用户到目标组织后，从组织 1 的成员表中移除该用户（仅当目标组织不是组织 1 时）
  2. 这样用户只属于一个组织，LS Web UI 就能正确显示该组织下的项目
- **修复代码**：
  ```java
  // 新增方法：从 LS 组织成员表移除用户
  private void removeUserFromOrganizationInLSDB(Long lsOrgId, Long lsUserId) {
      String sql = "DELETE FROM organizations_organizationmember WHERE organization_id = ? AND user_id = ?";
      try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
           PreparedStatement stmt = conn.prepareStatement(sql)) {
          stmt.setLong(1, lsOrgId);
          stmt.setLong(2, lsUserId);
          int deleted = stmt.executeUpdate();
          log.info("从 LS 组织成员表移除用户: lsOrgId={}, lsUserId={}, deletedRows={}", lsOrgId, lsUserId, deleted);
      } catch (SQLException e) {
          log.error("从 LS 组织成员表移除用户失败: lsOrgId={}, lsUserId={}, error={}", lsOrgId, lsUserId, e.getMessage(), e);
      }
  }

  // 在 syncUserToLS 中调用：添加用户到目标组织后，从组织 1 移除
  addUserToOrganizationInLSDB(user.getOrganization().getLsOrgId(), user.getLsUserId());

  if (user.getOrganization().getLsOrgId() != null && user.getOrganization().getLsOrgId() != 1L) {
      removeUserFromOrganizationInLSDB(1L, user.getLsUserId());
      log.info("已将用户从 LS 默认组织中移除: lsUserId={}", user.getLsUserId());
  }
  ```
- **历史数据修复**：对于已存在的用户，需要手动执行 SQL 修复：
  ```sql
  -- 从组织 1 的成员表中移除用户
  DELETE FROM organizations_organizationmember WHERE organization_id = 1 AND user_id = 17;
  
  -- 更新用户的 active_organization_id
  UPDATE htx_user SET active_organization_id = 17 WHERE id = 17;
  ```

### 6.10 概览页面最近项目显示问题（已修复）
- **现象**：在"概览"页面看不到"最近项目"，或者显示的项目数量不足
- **原因**：
  1. 前端请求的项目数量为 `size=3`，但需求是显示 5 个项目
  2. 前端先请求 `status=PROCESSING` 状态的项目，如果没有再 fallback 到全部项目，导致如果恰好没有 PROCESSING 项目，或者最近创建的项目还不是 PROCESSING 状态，就会显示异常
- **修复**：
  1. 将 `size=3` 改为 `size=5`
  2. 移除先请求 PROCESSING 状态项目的逻辑，直接请求所有项目
  3. 后端已支持按 `createdAt` 降序排序，直接请求 `page=0&size=5` 即可拿到最新的 5 个项目
- **修复文件**：`frontend-vue/src/views/Dashboard.vue`
- **修复代码**：
  ```javascript
  const loadRecentProjects = async () => {
    try {
      const response = await fetch('/api/v1/projects?page=0&size=5', {
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('token')}`
        }
      })
      if (response.ok) {
        const data = await response.json()
        if (data.data && data.data.length > 0) {
          recentProjects.value = data.data.map(project => ({
            id: project.id,
            name: project.name,
            status: project.status,
            totalImages: project.totalImages || 0,
            processedImages: project.processedImages || 0,
            createdAt: project.createdAt
          }))
        }
      }
    } catch (error) {
      console.error('加载最近项目失败:', error)
    }
  }
  ```
- **Git 提交**：`dab50d8` - "fix: 修复概览页面最近项目显示问题，改为显示5个最新项目"

### 6.11 项目名称唯一性从全局唯一改为组织内唯一（已修复）
- **现象**：创建项目时，如果项目名称已存在（即使是在不同组织内），会报错：`Unique index or primary key violation`
- **原因**：
  1. `Project.name` 字段设置了 `unique = true` 约束，导致项目名称在全局范围内必须唯一
  2. 实际需求是：同一个组织内的项目名称不能重复，但不同组织可以有同名项目
  3. 数据库中的唯一索引 `UK_1E447B96PEDRVTXW44OT4QXEM` 是基于 `name` 字段的单一索引
- **修复方案**：
  1. 移除 `Project.name` 字段的 `unique = true` 约束
  2. 替换 `ProjectRepository` 的方法：
     - `existsByName(String name)` → `existsByNameAndOrganizationId(String name, Long organizationId)`
     - `findByName(String name)` → `findByNameAndOrganizationId(String name, Long organizationId)`
  3. 在 `ProjectController.createProject` 中添加应用层检查：
     - 创建项目前检查同一组织内是否已存在同名项目
     - 添加 `try-catch` 兜底处理并发场景，捕获 `DataIntegrityViolationException`
  4. 数据库迁移：
     - 删除旧的全局唯一约束 `UK_1E447B96PEDRVTXW44OT4QXEM`
     - 创建组合唯一索引 `uk_project_name_org (organization_id, name)`
- **修复文件**：
  - `backend-springboot/src/main/java/com/annotation/platform/entity/Project.java`
  - `backend-springboot/src/main/java/com/annotation/platform/repository/ProjectRepository.java`
  - `backend-springboot/src/main/java/com/annotation/platform/controller/ProjectController.java`
- **修复代码**：
  ```java
  // Project.java
  @Column(name = "name", nullable = false, length = 100)
  private String name;

  // ProjectRepository.java
  boolean existsByNameAndOrganizationId(String name, Long organizationId);
  Optional<Project> findByNameAndOrganizationId(String name, Long organizationId);

  // ProjectController.java
  if (projectRepository.existsByNameAndOrganizationId(request.getName(), organizationId)) {
      throw new com.annotation.platform.exception.BusinessException("项目名称已存在");
  }

  Project savedProject;
  try {
      savedProject = projectRepository.save(project);
  } catch (DataIntegrityViolationException e) {
      throw new com.annotation.platform.exception.BusinessException("项目名称已存在");
  }
  ```
- **数据库迁移 SQL**：
  ```sql
  -- 删除旧的全局唯一约束
  ALTER TABLE PUBLIC.PROJECTS DROP CONSTRAINT IF EXISTS UK_1E447B96PEDRVTXW44OT4QXEM;

  -- 创建组织+名称组合唯一索引
  CREATE UNIQUE INDEX IF NOT EXISTS uk_project_name_org ON PUBLIC.PROJECTS(organization_id, name);
  ```
- **Git 提交**：`6781cca` - "refactor: 项目名称唯一性从全局唯一改为组织内唯一"
- **验证结果**：
  - ✅ 同一组织内创建同名项目 → 返回"项目名称已存在"
  - ✅ 不同组织创建同名项目 → 成功创建
  - ✅ 并发创建同名项目 → 被 try-catch 捕获并返回友好提示

七、当前待解决的问题
（无）

---

## 八、期望的架构设计

1. 用户注册时在 LS 中创建对应账户
2. 每个组织在 LS 中有对应组织，第一个创建者是管理员
3. **所有 LS 项目操作使用组织管理员的 token**（不是全局 admin token）
4. 项目自动归属到管理员的 active_organization



