# 踩坑记录与经验教训

> 改代码前必读。每条记录格式：日期、摘要、根因、教训。需要看完整修复代码的去查对应的 git commit。

---

## 高频踩坑点（置顶）

1. **context-path 是 `/api/v1`**。新写 Controller 的 `@RequestMapping` 不要再加 `/api/v1` 前缀，否则实际路径会变成 `/api/v1/api/v1/xxx`。测试接口时 curl 的 URL 必须带 `/api/v1` 前缀。
2. **SecurityConfig 白名单**。新增 Controller 路径后，必须检查 `SecurityConfig.java` 是否已放行该路径，否则会被 JWT 过滤器拦截返回 401/403。
3. **H2 数据库运行时锁定**。Spring Boot 运行时不能用外部工具读写 `testdb.mv.db`。要查数据只能通过 API 或重启后用 H2 console。
4. **Lombok 需要 maven-compiler-plugin 配置**。pom.xml 必须有 `maven-compiler-plugin` 并配置 `annotationProcessorPaths` 包含 Lombok，否则编译时找不到 `log`、`builder()`、getter/setter。
5. **LS 环境变量每次重启都要设**。`LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true` 和 `LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs`，否则本地文件存储 400。
6. **conda 环境不能混用**。DINO 服务用 `groundingdino310`，FastAPI 算法服务用 `algo_service`，不要用系统 Python。
7. **日志输出到 `/tmp/xxx.log`**。绝对不能输出到 `/dev/null`，否则出了问题无法定位。

---

## 问题记录

### 2026-03-18: Spring Boot 启动失败（H2 文件锁 / 端口占用）
- **根因**: 后端使用文件型 H2 数据库，运行中的进程锁定 `testdb.mv.db`；同时端口被其他进程占用。
- **方案**: 采用启动参数覆盖进行隔离验证：临时改用内存 H2（`--spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1`）并切换端口（如 `--server.port=8090`）；确认功能通过后再在默认 8080 + 文件型 H2 上运行。
- **教训**: 碰到数据库锁或端口冲突，优先使用运行参数进行环境隔离，避免影响现有服务。

### 2026-03-18: VLM/LLM 连接信息写死导致无法按用户配置切换
- **根因**: 算法服务 `auto_annotation.py` 在 VLM 清洗逻辑里硬编码了 `api_key/base_url/model`，Spring Boot 虽透传了字段但 Python 端没有使用；前端缺少设置入口，无法为不同用户保存模型配置。
- **修复**: 新增 Spring Boot `user_model_configs`（每用户一条，包含 VLM/LLM 的 key/url/model）；提供 `/api/user/model-config` 的 GET/PUT 和 test-vlm/test-llm；算法服务新增 `/api/v1/model-config/test-vlm|test-llm` 并改造 VLM 清洗优先使用 `vlm_*` 字段（为空回退旧字段与默认值）；Spring Boot 转发 VLM 清洗时从用户配置取值并附加到请求体；前端设置页新增“模型配置”区域与测试/保存。
- **教训**: 任何第三方模型连接信息都不能硬编码在算法侧；要么来自用户配置（DB），要么来自环境变量；并且需要提供连通性测试接口，避免上线后才发现 key/url/model 配错。

### 2026-03-17: /feasibility/ 路径全部 404
- **根因**: 测试时用了 `/feasibility/assessments` 而不是 `/api/v1/feasibility/assessments`。context-path 是 `/api/v1`，Controller 上 `@RequestMapping("/feasibility")` 的实际路径是 `/api/v1/feasibility`。
- **教训**: 新接口测试必须加 `/api/v1` 前缀。Tomcat 原生 404（不是 Spring JSON 错误）通常意味着路径根本没匹配到任何 Controller。

### 2026-03-17: CategoryAssessment 插入时 H2 Check constraint violation
- **根因**: 旧的 H2 数据库文件中有残留的约束，与新的实体定义冲突。
- **修复**: 删除旧的 `testdb.mv.db` 文件，让 JPA 重新建表。
- **教训**: 实体结构大改时（新增 @OneToMany、修改字段约束），如果遇到奇怪的约束错误，优先考虑清空 H2 重建。

### 2026-03-17: 更新接口要求所有字段都不为空
- **根因**: 更新接口复用了创建接口的 DTO（`CreateCategoryRequest`），其中 `categoryName` 有 `@NotBlank` 校验，导致只想更新一个字段时也必须传所有必填字段。
- **修复**: 创建独立的 `UpdateCategoryRequest` DTO，所有字段都是可选的。
- **教训**: 创建和更新必须使用不同的 DTO。创建 DTO 有必填校验，更新 DTO 字段全部可选，Service 层做 null 检查后选择性更新。

### 2026-03-18: 创建 OVD 测试结果返回 SYSTEM_ERROR
- **根因**: `bboxJson` 是字符串字段，curl 里直接塞未转义的 JSON（例如 `"[{\"x\":10,\"y\":20}]"` 写错转义）会导致 Jackson 解析请求体失败。
- **教训**: `bboxJson` 必须是合法 JSON 字符串。联调时先用 `"{}"` 验证通路，再逐步替换为正确转义后的 bbox JSON。

### 2026-03-12: 项目名称全局唯一导致不同组织不能同名
- **根因**: `Project.name` 设了 `unique = true`，是全局唯一而不是组织内唯一。
- **修复**: 移除全局唯一约束，改为 `(organization_id, name)` 组合唯一索引，Repository 方法改为 `existsByNameAndOrganizationId`。
- **教训**: 唯一性约束要考虑多租户场景。`@Column(unique=true)` 是全局的，多租户必须用组合索引。
- **Commit**: `6781cca`

### 2026-03-12: 概览页最近项目显示异常
- **根因**: 前端先请求 `status=PROCESSING` 的项目，没有再 fallback；请求数量是 3 不是 5。
- **修复**: 直接请求 `page=0&size=5` 不过滤状态。
- **Commit**: `dab50d8`

### LS 同步系列问题（已全部修复）

这组问题的核心教训是：**LS 的组织和用户关系管理非常脆弱，必须通过直接操作 SQLite 来保证一致性。**

- **LS 项目 organization_id 为 NULL**: `syncProjectToLS` 传了 `"organization": null`，改为用管理员 token 创建。
- **LS 组织 created_by_id 为 NULL**: 创建组织后没设置 `created_by_id`，在 `syncUserToLS` 中补设。
- **LS 用户 active_organization_id 为 NULL**: `syncUserToLS` 没设置，补调 `updateUserActiveOrganizationInLSDB`。
- **LS 用户没加入新组织**: 通过 admin token 创建的用户默认只在组织 1，需要手动调 `addUserToOrganizationInLSDB`。
- **Hibernate "no Session" 懒加载**: `syncProjectToLS` 中访问 `organization.getCreatedBy()` 时 Session 已关闭，改为用 Repository 重新查询。
- **LS label_config 为空**: 没传 label_config 字段，改为根据 `project.getLabels()` 动态生成 XML。
- **项目创建后 organization_id 仍为 1**: 用户浏览器访问 LS 后 `active_organization_id` 被改，在创建项目前强制更新 `active_organization_id`。
- **用户在 LS Web UI 看不到自己项目**: 用户同时属于组织 1 和新组织，LS 默认显示组织 1。修复：加入新组织后从组织 1 移除。

### Lombok 编译错误
- **根因**: pom.xml 缺少 `maven-compiler-plugin` 的 `annotationProcessorPaths` 配置。
- **教训**: 见置顶第 4 条。

### LS 免密直达不工作
- **根因**: 前端依赖 `lsEmail` 和 `lsPassword`，但后端存的是 API Token 不是密码，Django 密码哈希无法还原。
- **修复**: 前端改用 `labelStudioAPI.getLoginUrl()` 获取带 token 的登录链接，用 `window.open` 打开。
