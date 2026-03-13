# 智能标注平台 Debug 手册

本文件是跨窗口持久化的 debug 上下文。每次新开 Agent 窗口时，请先阅读本文件。
路径：`/root/autodl-fs/Annotation-Platform/debug.md`

---

## 一、项目架构概览

智能标注平台，用于图像目标检测的自动化标注。核心流程：DINO 检测 → VLM 清洗 → 同步预测到 Label Studio。

| 组件 | 技术栈 | 端口 | 启动方式 |
|------|--------|------|---------|
| 后端 | Spring Boot 3.2 + JPA + H2 | 8080 | 见下方 |
| 前端 | Vue 3 + Element Plus + Vite | 5173 | `cd frontend-vue && npm run dev` |
| Label Studio | Python (conda: web_annotation) | 5001 | 见下方 |
| 算法服务 | FastAPI (conda: algo_service) | 8001 | 见下方 |
| DINO 模型服务 | Python (conda: groundingdino310) | 5003 | 见下方 |

---

## 二、关键路径和配置

**项目根目录**: `/root/autodl-fs/Annotation-Platform/`

**后端代码**: `/root/autodl-fs/Annotation-Platform/backend-springboot/`

**前端代码**: `/root/autodl-fs/Annotation-Platform/frontend-vue/`

**算法服务**: `/root/autodl-fs/Annotation-Platform/algorithm-service/`

### Spring Boot 配置

配置文件: `backend-springboot/src/main/resources/application.yml`

- `context-path`: `/api/v1` （所有 API 路径前缀是 `/api/v1`，不是 `/api`）
- `数据库`: H2, 文件路径 `backend-springboot/data/testdb`
- `LS URL`: `http://localhost:5001`
- `LS public URL`: `http://122.51.47.91:28450`
- `LS admin-token`: `3dd84879dff6fd5949dc1dd76edbecccac3f8524`
- `LS SQLite DB`: `/root/.local/share/label-studio/label_studio.sqlite3`
- `文件上传路径`: `/root/autodl-fs/uploads`

### Label Studio admin 用户

- `LS user id`: 1
- `email`: `datian@tongji.edu.cn`
- `token`: `3dd84879dff6fd5949dc1dd76edbecccac3f8524`
- `active_organization_id`: 1

---

## 三、服务启动方法

### 3.1 Spring Boot 后端

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests
kill $(lsof -ti:8080) 2>/dev/null; sleep 3
nohup java -jar target/platform-backend-1.0.0.jar --server.port=8080 > /tmp/springboot.log 2>&1 &
# 等待 15 秒后验证
sleep 15 && grep "Started" /tmp/springboot.log
```

⚠️ **注意**：日志必须输出到 `/tmp/springboot.log`，绝对不能输出到 `/dev/null`！

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

### 3.3 算法服务（两个进程，两个 conda 环境）

```bash
source $(conda info --base)/etc/profile.d/conda.sh

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
```

⚠️ **注意**：不要用系统 Python 直接 `python main.py`，必须先 `conda activate`！

### 3.4 快速检查所有服务状态

```bash
echo "=== 端口检查 ==="
for port in 5001 5003 8001 8080 5173; do
  if lsof -ti:$port > /dev/null 2>&1; then
    echo "  端口 $port: ✅ 运行中"
  else
    echo "  端口 $port: ❌ 未运行"
  fi
done
```

---

## 四、LS 数据库快速查询

```bash
# 查看所有用户
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
```

---

## 五、Spring Boot H2 数据库说明

H2 数据库文件在 `backend-springboot/data/testdb.mv.db`，Spring Boot 运行时会锁定此文件，无法用外部工具直接读取。要查询 H2 数据库内容，需要通过 Spring Boot API 或日志。

---

## 六、Git 仓库信息

### 6.1 仓库地址

- **远程仓库**: `gitee.com:freddywards/annotation-platform.git`
- **本地路径**: `/root/autodl-fs/Annotation-Platform/`
- **默认分支**: `master`

### 6.2 Git 提交流程

```bash
# 1. 查看当前状态
git status

# 2. 添加修改的文件
git add .

# 3. 提交修改
git commit -m "提交信息"

# 4. 推送到远程仓库
git push origin master
```

### 6.3 提交规范

- **fix**: 修复 bug
- **refactor**: 重构代码
- **feat**: 新增功能
- **docs**: 文档更新
- **style**: 代码格式调整
- **test**: 测试相关
- **chore**: 构建/工具相关

**提交信息格式**: `<type>: <description>`

例如：
- `fix: 修复概览页面最近项目显示问题`
- `refactor: 项目名称唯一性从全局唯一改为组织内唯一`
- `docs: 更新 debug.md，记录最近修复的两个 bug`

### 6.4 最近提交记录

| Commit Hash | 提交信息 | 提交时间 |
|-------------|---------|---------|
| `f63c1f3` | docs: 更新 debug.md，记录最近修复的两个 bug | 2026-03-12 |
| `6781cca` | refactor: 项目名称唯一性从全局唯一改为组织内唯一 | 2026-03-12 |
| `dab50d8` | fix: 修复概览页面最近项目显示问题，改为显示5个最新项目 | 2026-03-12 |

---

## 七、已解决的问题记录

### 7.1 LS 项目 organization_id 为 NULL（已修复）

- **原因**: `syncProjectToLS` 传了 `"organization": null` 给 LS API，覆盖了默认行为
- **修复**: 改用组织管理员 token 创建项目 + 生成 label_config XML

### 7.2 LS 组织 created_by_id 为 NULL（已修复）

- **原因**: `syncOrganizationToLS` 创建组织后没设置 created_by_id
- **修复**: 在 `syncUserToLS` 中调用 `updateOrganizationCreatedByInLSDB`

### 7.3 LS 用户 active_organization_id 为 NULL（已修复）

- **原因**: `syncUserToLS` 没有设置 LS 用户的 active_organization_id
- **修复**: 调用 `updateUserActiveOrganizationInLSDB`

### 7.4 LS 用户没有被添加到新组织成员表（已修复）

- **原因**: 通过 admin token 创建的 LS 用户自动加入组织 1，没有加入新组织
- **修复**: 调用 `addUserToOrganizationInLSDB`

### 7.5 Hibernate 懒加载 "no Session" 错误（已修复）

- **原因**: `syncProjectToLS` 中访问 `organization.getCreatedBy()` 时 Session 已关闭
- **修复**: 通过 Repository 用 ID 重新查询关联对象

### 7.6 LS 项目 label_config 为空（已修复）

- **原因**: `syncProjectToLS` 没有传 label_config 字段
- **修复**: 根据 `project.getLabels()` 动态生成 XML

### 7.7 Label Studio 本地文件服务未开启（已修复）

- **现象**: 挂载本地存储时报 400：`LOCAL_FILES_SERVING_ENABLED is disabled by default`
- **原因**: LS 默认禁止从宿主文件系统读取文件
- **修复**: 启动 LS 前设置环境变量 `LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true` 和 `LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs`
- **注意**: 每次重启 LS 都需要设置这两个环境变量，建议写入启动脚本
- **启动命令**:
  ```bash
  source $(conda info --base)/etc/profile.d/conda.sh
  conda activate web_annotation
  export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
  export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs
  label-studio start --port 5001 --no-browser --log-level INFO
  ```

### 7.8 syncProjectToLS 创建项目时 organization_id 仍然为 1（已修复）

- **现象**: 用组织管理员 token 创建的 LS 项目，organization_id 是 1 而不是用户所属的新组织
- **原因**: 用户通过浏览器访问 LS 后，LS 可能会将 `active_organization_id` 改为其他值。`syncUserToLS` 的分支 1 和分支 2 不会更新 `active_organization_id`，导致后续创建项目时 LS 根据错误的 `active_organization_id` 决定项目归属
- **修复**: 在 `syncProjectToLS` 中，调用 LS API 创建项目之前，强制更新 token 用户的 `active_organization_id` 为当前组织 ID
- **修复代码**:
  ```java
  // 【修复】创建项目前，强制将 token 对应用户的 active_organization_id 设为当前组织
  if (organization.getLsOrgId() != null && createdBy != null && createdBy.getLsUserId() != null) {
      updateUserActiveOrganizationInLSDB(createdBy.getLsUserId(), organization.getLsOrgId());
      log.info("创建项目前更新 LS 用户 active_organization_id: lsUserId={}, lsOrgId={}",
               createdBy.getLsUserId(), organization.getLsOrgId());
  }
  ```

### 7.9 用户在 LS Web UI 中看不到自己组织下的项目（已修复）

- **现象**: 用户注册并创建项目后，在 LS Web UI 中登录时只能看到组织 1 下的项目，看不到自己组织下的项目
- **原因**:
  1. LS 创建用户时，默认将用户添加到组织 1 的成员表中
  2. 用户通过浏览器访问 LS Web UI 时，LS 可能会将用户的 `active_organization_id` 改为 1
  3. LS Web UI 的项目列表 API（`GET /api/projects`）根据用户当前的 `active_organization_id` 过滤项目
  4. 即使用户被添加到了新组织，但如果 `active_organization_id` 仍然是 1，就只能看到组织 1 下的项目
- **修复方案**:
  1. 在 `syncUserToLS` 中，添加用户到目标组织后，从组织 1 的成员表中移除该用户（仅当目标组织不是组织 1 时）
  2. 这样用户只属于一个组织，LS Web UI 就能正确显示该组织下的项目
- **修复代码**:
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
- **历史数据修复**: 对于已存在的用户，需要手动执行 SQL 修复：
  ```sql
  -- 从组织 1 的成员表中移除用户
  DELETE FROM organizations_organizationmember WHERE organization_id = 1 AND user_id = 17;

  -- 更新用户的 active_organization_id
  UPDATE htx_user SET active_organization_id = 17 WHERE id = 17;
  ```

### 7.10 概览页面最近项目显示问题（已修复）

- **现象**: 在"概览"页面看不到"最近项目"，或者显示的项目数量不足
- **原因**:
  1. 前端请求的项目数量为 `size=3`，但需求是显示 5 个项目
  2. 前端先请求 `status=PROCESSING` 状态的项目，如果没有再 fallback 到全部项目，导致如果恰好没有 PROCESSING 项目，或者最近创建的项目还不是 PROCESSING 状态，就会显示异常
- **修复**:
  1. 将 `size=3` 改为 `size=5`
  2. 移除先请求 PROCESSING 状态项目的逻辑，直接请求所有项目
  3. 后端已支持按 `createdAt` 降序排序，直接请求 `page=0&size=5` 即可拿到最新的 5 个项目
- **修复文件**: `frontend-vue/src/views/Dashboard.vue`
- **修复代码**:
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
- **Git 提交**: `dab50d8` - "fix: 修复概览页面最近项目显示问题，改为显示5个最新项目"

### 7.11 项目名称唯一性从全局唯一改为组织内唯一（已修复）

- **现象**: 创建项目时，如果项目名称已存在（即使是在不同组织内），会报错：`Unique index or primary key violation`
- **原因**:
  1. `Project.name` 字段设置了 `unique = true` 约束，导致项目名称在全局范围内必须唯一
  2. 实际需求是：同一个组织内的项目名称不能重复，但不同组织可以有同名项目
  3. 数据库中的唯一索引 `UK_1E447B96PEDRVTXW44OT4QXEM` 是基于 `name` 字段的单一索引
- **修复方案**:
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
- **修复文件**:
  - `backend-springboot/src/main/java/com/annotation/platform/entity/Project.java`
  - `backend-springboot/src/main/java/com/annotation/platform/repository/ProjectRepository.java`
  - `backend-springboot/src/main/java/com/annotation/platform/controller/ProjectController.java`
- **修复代码**:
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
- **数据库迁移 SQL**:
  ```sql
  -- 删除旧的全局唯一约束
  ALTER TABLE PUBLIC.PROJECTS DROP CONSTRAINT IF EXISTS UK_1E447B96PEDRVTXW44OT4QXEM;

  -- 创建组织+名称组合唯一索引
  CREATE UNIQUE INDEX IF NOT EXISTS uk_project_name_org ON PUBLIC.PROJECTS(organization_id, name);
  ```
- **Git 提交**: `6781cca` - "refactor: 项目名称唯一性从全局唯一改为组织内唯一"
- **验证结果**:
  - ✅ 同一组织内创建同名项目 → 返回"项目名称已存在"
  - ✅ 不同组织创建同名项目 → 成功创建
  - ✅ 并发创建同名项目 → 被 try-catch 捕获并返回友好提示

### 7.12 个人中心：数据维度与 UI 布局（已修复）

- **后端**：ProjectRepository 新增组织维度查询方法，UserServiceImpl 改为组织维度统计
- **前端**：Profile.vue 重写为单列垂直布局，移除栅格布局，改为纯垂直堆叠

### 7.13 个人中心：头像与 Profile 接口（已完成）

- **后端**：User 实体新增 avatarUrl 字段，新增 UserProfileResponse DTO，新增 /users/me/profile 接口
- **前端**：新增头像工具类 generateDefaultAvatar，Store 增加 avatarUrl getter 和 fetchUserProfile action，布局组件绑定头像

### 7.14 个人中心：LS 免密直达（已完成）——有bug

- **前端**：Profile.vue 实现完整的 Dashboard 布局，包括基础信息、组织架构、数据统计、系统状态、最近项目
- **核心功能**：jumpToLabelStudio 方法实现免密登录，动态创建隐藏表单 POST 到 Label Studio

### 7.15 后端编译错误：Lombok 注解处理器失效（已修复）

- **现象**: 执行 `mvn clean compile` 时出现大量"cannot find symbol"错误，具体包括：
  - 找不到 `log` 变量（FileUploadController、AuthController、JwtUtils、JwtAuthenticationFilter）
  - 找不到 `builder()` 方法（UploadChunkRequest）
  - 找不到 `getFileId()`、`getFilename()` 方法（MergeChunksRequest）
  - 找不到 `getCode()`、`getMessage()` 方法（ErrorCode）
  - 找不到 `getId()`、`getOrganization()` 方法（User）
- **原因**: 
  1. pom.xml 缺少 maven-compiler-plugin 配置，没有显式配置注解处理器路径
  2. ProjectRepository 中有重复的方法定义（`countByCreatedById` 和 `countImagesByCreatedById` 被定义了两次）
- **修复方案**:
  1. 在 pom.xml 的 `<build><plugins>` 节点中，在 `spring-boot-maven-plugin` 之前添加 `maven-compiler-plugin` 配置
  2. 在 maven-compiler-plugin 中配置 `annotationProcessorPaths`，包含 Lombok 和 MapStruct processor
  3. 删除 ProjectRepository 中重复的方法定义
- **修复文件**:
  - `backend-springboot/pom.xml`
  - `backend-springboot/src/main/java/com/annotation/platform/repository/ProjectRepository.java`
- **修复代码**:
  ```xml
  <!-- pom.xml -->
  <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.11.0</version>
      <configuration>
          <source>17</source>
          <target>17</target>
          <annotationProcessorPaths>
              <path>
                  <groupId>org.projectlombok</groupId>
                  <artifactId>lombok</artifactId>
                  <version>${lombok.version}</version>
              </path>
              <path>
                  <groupId>org.mapstruct</groupId>
                  <artifactId>mapstruct-processor</artifactId>
                  <version>${mapstruct.version}</version>
              </path>
          </annotationProcessorPaths>
      </configuration>
  </plugin>
  ```
- **Git 提交**: `fix: 修复 Lombok 编译错误，添加 maven-compiler-plugin 配置并删除 ProjectRepository 重复方法`
- **验证结果**:
  - ✅ `mvn clean compile` - BUILD SUCCESS
  - ✅ `mvn clean package -DskipTests` - BUILD SUCCESS
  - ✅ 服务启动成功，日志显示 "Started AnnotationPlatformApplication in 8.962 seconds"
  - ✅ 所有服务正常运行（端口 5001、5003、8001、8080、5173）

---

## 八、当前待解决的问题

### 8.1 个人中心：LS 免密直达功能无法正常工作

**现象**: 在个人中心点击"进入 Label Studio 工作台"按钮时，显示错误提示"未能获取到 Label Studio 登录凭证，请联系管理员"，无法正确跳转自动登录

**原因**: 待调查

**待分析**:
- 前端 Profile.vue 中的 jumpToLabelStudio 方法实现
- 后端是否提供了获取 LS 登录凭证的接口
- LS admin token 是否正确配置
- 用户 LS token 是否正确生成和存储
- 但编译仍然报错，说明注解处理器仍未生效

**待尝试的解决方案**：
1. 检查 Maven 编译器是否正确识别 Lombok 注解处理器
2. 尝试使用 `mvn clean install` 而不是 `mvn clean compile`
3. 检查是否有缓存问题，需要清理 Maven 本地仓库
4. 考虑直接使用已编译好的 jar 包启动服务（如果存在）

---

## 九、期望的架构设计

1. 用户注册时在 LS 中创建对应账户
2. 每个组织在 LS 中有对应组织，第一个创建者是管理员
3. **所有 LS 项目操作使用组织管理员的 token**（不是全局 admin token）
4. 项目自动归属到管理员的 active_organization


## 十、当前 Debug 指令 (Agent 请直接执行)

**你好，Agent。请仔细阅读本文件中【八、当前待解决的问题 - 8.1 后端编译错误】的上下文。**

请你先检查所有服务的启动状态，
然后请看当前待解决的问题，并分析要解决这个问题应该要涉及到哪些文件和代码，请先生成一个详细的修改报告，涉及到哪些代码，要对项目的结构做哪些修改

## 十一、改完bug之后的事情

1. 把改的bug填写到七、已解决的问题记录中，
2. 
