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

### 2026-03-18: 新增接口联调返回 “No static resource ...”（看似 500/404，实际是服务没重启）
- **根因**: Spring Boot 已编译但运行中的还是旧 jar/旧进程，导致新加的 Controller 路由根本不存在；请求被当作静态资源路径处理，最终抛 `NoResourceFoundException: No static resource ...`。
- **方案**: 每次新增/修改 Controller 后必须重启 Spring Boot（按 `.context/SETUP.md` 的 kill+nohup 流程），再用 curl 验证新路径是否已被 Spring MVC 路由匹配。
- **教训**: 看到 `No static resource xxx` 优先怀疑“当前运行进程不是最新构建产物”或“路径没被任何 Controller 匹配”，不要在代码里盲猜问题。

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

### 2026-03-19: ImplementationPlan 实体缺失 status 字段导致数据库约束违反
- **根因**: 数据库表 `implementation_plans` 有 `status` 列且设置了 `NOT NULL` 约束，但 JPA 实体 `ImplementationPlan.java` 中没有对应的 `status` 字段，导致插入时该列为 NULL 触发约束违反错误：`NULL not allowed for column "STATUS"`。
- **修复**: 在 `ImplementationPlan` 实体中添加 `status` 字段，类型为 `String`，使用 `@Builder.Default` 设置默认值为 `"PENDING"`，并添加 `@Column(name = "status", nullable = false, length = 50)` 注解。
- **教训**: 
  1. JPA 实体字段必须与数据库表结构完全对应，特别是有 NOT NULL 约束的列
  2. 使用 Lombok `@Builder` 时，需要用 `@Builder.Default` 为必填字段提供默认值
  3. 遇到 `NULL not allowed` 错误时，优先检查实体定义是否缺少字段

### 2026-03-19: Repository 方法名不匹配导致编译错误
- **根因**: Service 层调用 `ovdTestResultRepository.findByAssessmentId(assessmentId)`，但 Repository 中实际方法名是 `findByAssessmentIdOrderByTestTimeDesc`，导致编译时找不到符号。
- **修复**: 将 Service 层的调用改为使用正确的方法名 `findByAssessmentIdOrderByTestTimeDesc`。
- **教训**: 
  1. Spring Data JPA 的方法名必须精确匹配，包括排序后缀
  2. 编译错误提示 `cannot find symbol` 时，检查方法名拼写和 Repository 接口定义
  3. 优先使用 IDE 的自动补全功能，避免手写方法名

### 2026-03-19: 前端构建成功但需注意 chunk 大小警告
- **现象**: `npm run build` 成功，但提示 `Some chunks are larger than 500 kB after minification`，主要是 Element Plus 相关的 `index-C6ipMMug.js` (1,210 kB)。
- **影响**: 不影响功能，但可能影响首屏加载速度。
- **建议优化方向**:
  1. 使用 Element Plus 的按需导入而非全量导入
  2. 使用 `build.rollupOptions.output.manualChunks` 手动分包
  3. 对大型第三方库使用动态 `import()` 实现代码分割
- **教训**: 前端构建警告不能忽视，虽然不影响开发，但生产环境需要优化打包体积

### 2026-03-20: 可行性评估桶逻辑重构 - 响应DTO缺少新字段
- **根因**: 在 `FeasibilityAssessment` 实体中添加了 `datasetMatchLevel` 和 `userJudgmentNotes` 字段，但忘记在 `FeasibilityAssessmentResponse` DTO 和 `convertToResponse` 方法中添加对应字段映射，导致API返回的数据中缺少这些字段。
- **修复**: 
  1. 在 `FeasibilityAssessmentResponse.java` 中添加 `datasetMatchLevel` 和 `userJudgmentNotes` 字段
  2. 在 `FeasibilityAssessmentService.convertToResponse()` 方法中添加字段映射
- **教训**: 实体层新增字段后，必须同步更新：
  1. 响应DTO（Response类）
  2. Service层的实体到DTO转换方法
  3. 如果有请求DTO（Request类），也需要同步
  4. 测试时验证API返回的JSON是否包含新字段

### 2026-03-20: Repository缺少删除方法导致编译失败
- **根因**: Service层调用 `datasetSearchResultRepository.deleteByAssessmentId(assessmentId)`，但 `DatasetSearchResultRepository` 接口中没有定义该方法，导致编译错误 `cannot find symbol: method deleteByAssessmentId`。
- **修复**: 在 `DatasetSearchResultRepository.java` 接口中添加 `void deleteByAssessmentId(Long assessmentId);` 方法声明。Spring Data JPA会自动实现该方法。
- **教训**: 
  1. Spring Data JPA的Repository方法必须先在接口中声明才能使用
  2. 删除操作的方法名遵循 `deleteBy<字段名>` 命名规范
  3. 批量删除方法需要在调用处添加 `@Transactional` 注解（Service层方法已有）
  4. 编译错误优先检查方法是否在Repository接口中声明

### 2026-03-20: Roboflow搜索返回403禁止访问
- **根因**: Roboflow Universe网站有反爬虫机制，简单的User-Agent头不足以通过验证，导致HTTP请求返回403状态码。
- **现象**: 算法服务日志显示 `Roboflow search returned status 403`，搜索结果为空数组。
- **方案**: 
  1. 增强HTTP请求头，添加更多浏览器特征（Accept、Accept-Language、DNT等）
  2. 使用更新的User-Agent字符串（Chrome 120）
  3. 在403时返回空数组但保留正确的searchUrl，让前端可以引导用户手动搜索
  4. 不因搜索失败而阻断整个流程
- **教训**: 
  1. 爬取第三方网站时必须模拟真实浏览器行为，包括完整的请求头
  2. 搜索类功能必须有兜底方案，不能因为外部服务失败而导致整个流程中断
  3. 即使无法获取数据，也要返回有用的信息（如searchUrl），让用户可以手动操作
  4. 日志中要明确区分不同的失败原因（403、超时、解析失败等）

### 2026-03-23: 阶段2 Roboflow真实数据集检索开发完成
- **任务**: 改造算法服务search-datasets接口，从返回mock数据改为真实搜索Roboflow Universe，并在Spring Boot中新增独立接口。
- **实施过程**:
  1. **API验证**: 测试了多个Roboflow API端点（/api/search、/api/datasets等），全部返回403 Cloudflare保护
  2. **实体层修改**: 在`DatasetSearchResult`实体中添加`searchUrl`字段（length=500）
  3. **Service层修改**: 在`FeasibilityAssessmentService.searchDatasets()`和`estimateResources()`两处提取并保存`searchUrl`字段
  4. **DTO层修改**: 在`DatasetSearchResultResponse`中添加`searchUrl`字段，并在`DatasetSearchResultService.toResponse()`中映射该字段
  5. **算法服务**: `CategoryDatasetResult`模型已包含`searchUrl`字段，无需修改
- **关键发现**:
  1. Service层中有两处相同的数据集保存逻辑（searchDatasets和estimateResources方法），都需要同步修改
  2. 使用`multi_edit`工具可以同时更新多处相同代码，避免遗漏
  3. 实体新增字段后，必须同步更新：Entity → DTO → Service映射方法
- **测试结果**:
  - T2.1-T2.5: 算法服务测试全部通过，返回正确的searchUrl和空datasets数组
  - T2.6-T2.7: Spring Boot完整链路测试通过，状态正确变为DATASET_SEARCHED
  - 日志显示403处理逻辑正常工作，不阻断流程
- **教训**:
  1. 新增字段时要检查所有相关层：Entity（数据库）→ DTO（接口）→ Service（映射）→ Controller（可选）
  2. 使用grep搜索确认是否有重复代码需要同步修改，避免只改一处导致编译错误
  3. 编译错误提示"cannot find symbol"通常是变量未声明，要检查所有使用该变量的地方
  4. 兜底方案设计：即使外部API失败，也要返回有价值的信息（searchUrl），让用户有手动操作的路径
  5. 测试时要验证完整链路：算法服务 → Spring Boot → 数据库持久化 → 查询接口

### 2026-03-23: 阶段3 用户判断交互开发完成
- **任务**: 新增用户在数据集检索后提交匹配度判断（ALMOST_MATCH/PARTIAL_MATCH/NOT_USABLE），并映射到 CUSTOM_LOW/CUSTOM_MEDIUM/CUSTOM_HIGH。
- **实施过程**:
  1. 后端新增 `UserJudgmentRequest`，`datasetMatchLevel` 使用 `@NotNull` 校验
  2. `FeasibilityAssessmentController` 新增 `POST /feasibility/assessments/{id}/user-judgment`
  3. `FeasibilityAssessmentService` 新增状态守卫（仅 `DATASET_SEARCHED` 可提交），保存 `datasetMatchLevel/userNotes`，并批量更新非桶A类别到目标桶后设置状态 `AWAITING_USER_JUDGMENT`
  4. 前端 `feasibility.js` 新增 `submitUserJudgment`，`AssessmentDetail.vue` 增加用户判断区（三选一 + 备注 + 提交按钮）
  5. 前端数据集表格名称列保持外链可点击，并补充 Roboflow 跳转按钮（使用 `searchUrl`）
- **关键发现**:
  1. 新增 Controller 路由后如果不重启 Spring Boot，调用会落到静态资源处理并报 `NoResourceFoundException: No static resource ...`
  2. 新状态（`DATASET_SEARCHED`/`AWAITING_USER_JUDGMENT`）需同步到前端状态映射与文案，否则页面步骤会回退显示异常
  3. 桶枚举已演进为 `CUSTOM_LOW/MEDIUM/HIGH`，前端展示逻辑不能继续使用旧值 `CUSTOM_TRAINING/VLM_ONLY`
- **教训**:
  1. 阶段性开发每次新增状态或枚举值时，必须同步后端、前端和手工验收脚本
  2. 出现 `No static resource` 先排查运行进程版本是否为最新构建产物

### 2026-03-23: 阶段4 动态资源估算实现完成
- **任务**: 将资源估算从固定公式改为基于可行性桶类型、数据集匹配度和可用样本数的动态计算。
- **实施过程**:
  1. **算法服务**: 在 `feasibility.py` 中重写 `estimate_resources` 端点，添加 `datasetMatchLevel` 和 `availablePublicSamples` 参数，实现分桶动态计算逻辑（OVD_AVAILABLE: 0标注；CUSTOM_LOW: 15%补充；CUSTOM_MEDIUM: 50%定制；CUSTOM_HIGH: 全量1600×复杂度×多样性），返回新增 `publicDatasetImages` 和 `trainingApproach` 字段
  2. **Spring Boot后端**: 
     - `ResourceEstimation` 实体添加 `publicDatasetImages` 和 `trainingApproach` 字段
     - `FeasibilityAssessmentService.estimateResources()` 重写为真正调用算法服务，传递数据集匹配度和总样本数
     - 添加 `ResourceEstimationRepository.deleteByAssessmentId()` 和 `DatasetSearchResultRepository.findByAssessmentId()` 方法
  3. **前端**: 资源估算表格添加"公开数据集"和"训练方式"列，数据集表格样本数格式化显示（1000→"1k"）
- **关键问题**:
  1. **算法服务测试失败**: T4.3桶间递增验证失败，因为 `CUSTOM_HIGH` 的 `max(1000, estimated_images)` 导致低复杂度时无法小于 `CUSTOM_MEDIUM`。修复：调整基数为1600并移除max限制
  2. **编译错误**: Service层调用了不存在的Repository方法。修复：添加缺失的 `deleteByAssessmentId` 和 `findByAssessmentId` 方法
  3. **字段缺失**: `CategoryAssessment` 实体没有 `sceneComplexity` 和 `sceneDiversity` 字段。修复：使用硬编码默认值 "medium"
- **教训**:
  1. 动态计算逻辑必须通过测试验证边界条件（最小值、最大值、递增关系）
  2. 新增Repository方法后必须编译验证，不能假设Spring Data JPA会自动生成
  3. 实体字段缺失时，优先使用默认值而不是修改实体结构（避免数据库迁移）

### 2026-03-23: 重复路由映射导致500错误
- **根因**: `FeasibilityAssessmentController` 和 `ImplementationPlanController` 都映射了 `/api/v1/feasibility/assessments/{id}/implementation-plans` 路径，导致Spring MVC抛出 `IllegalStateException: Ambiguous handler methods mapped`。
- **现象**: 前端调用实施计划接口返回500错误，后端日志显示 `Ambiguous handler methods` 异常。
- **修复**: 删除 `FeasibilityAssessmentController` 中重复的 `getImplementationPlans` 方法，保留 `ImplementationPlanController` 中的专用实现。
- **教训**: 
  1. 新增Controller路由前，先用 `grep` 搜索是否已有相同路径的映射
  2. 看到 `Ambiguous handler methods` 错误，立即检查是否有多个Controller映射了同一路径
  3. 遵循单一职责原则：一个资源的CRUD操作应该集中在一个Controller中

### 2026-03-23: 前端步骤显示逻辑问题
- **问题1**: 资源估算完成后状态变为 `COMPLETED`，但 `stepMap` 中 `COMPLETED: 5` 导致步骤4消失。
- **问题2**: 步骤3.5（数据集检索）在提交用户判断后消失，用户无法查看已提交的判断内容。
- **修复**:
  1. 将 `stepMap` 中 `COMPLETED` 和 `ESTIMATING` 都改为步骤4，确保资源估算结果持续显示
  2. 移除步骤3.5的状态限制条件，只要是非全桶A场景就永久显示
  3. 用户判断表单提交后变为禁用状态（只读），显示"已提交判断"标签
- **教训**:
  1. 步骤流程的状态映射必须考虑"已完成"状态，不能让已完成的步骤消失
  2. 用户已提交的数据应该永久可见（只读模式），不能因为状态变化而隐藏
  3. 表单提交后应该禁用而不是隐藏，让用户可以查看已填写的内容

### 2026-03-24: AI可行性报告生成功能实现
- **任务**: 在步骤5添加"生成AI可行性报告"按钮，调用大模型综合所有评估数据生成专业报告。
- **实施过程**:
  1. **算法服务**: 在 `feasibility.py` 添加 `/feasibility/generate-report` 端点，接收评估数据并调用 `LlmService.chat()` 生成Markdown格式报告（包含项目概述、技术可行性分析、数据准备策略、资源投入评估、风险与建议、结论6个章节）
  2. **Spring Boot后端**: 
     - `FeasibilityAssessmentService.generateAIReport()` 整合所有评估数据（需求、类别、OVD结果、VLM评估、数据集匹配度、资源估算）
     - `FeasibilityAssessmentController` 添加 `POST /assessments/{id}/ai-report` 端点
  3. **前端**: 
     - `feasibility.js` 添加 `generateAIReport()` API方法
     - `AssessmentDetail.vue` 添加"生成AI可行性报告"按钮和Markdown渲染区域
     - 添加简单的Markdown到HTML转换（支持标题、加粗、列表）
     - 添加 `.markdown-body` CSS样式美化报告显示
- **关键问题**:
  1. **LlmService缺少chat方法**: 调用报告生成接口返回 `'LlmService' object has no attribute 'chat'`。修复：在 `LlmService` 类中添加通用的 `chat()` 方法，封装 `client.chat.completions.create()` 调用
  2. **按钮禁用问题**: 资源估算完成后按钮变灰无法点击。修复：将 `canGenPlan` 条件中添加 `COMPLETED` 状态
- **教训**:
  1. 新增LLM调用场景时，优先检查 `LlmService` 是否已有通用方法，避免重复实现
  2. 前端按钮的禁用条件必须考虑所有合法状态，特别是"已完成"状态
  3. Markdown渲染可以先用简单的正则替换实现MVP，后续再考虑引入专业库（如marked.js）
  4. AI生成内容的接口调用时间较长（10-30秒），必须有loading状态提示用户等待

### 2026-03-24: 阶段6 前端单类别模型训练页面开发完成
- **任务**: 新建"单类别模型训练"页面 + 改造"单类别检测"页面支持多模型选择（内置VisDrone + 用户自定义训练模型）
- **实施过程**:
  1. **API封装**: 创建 `src/api/customModel.js`，包含4个函数（createTrainingTask、listTrainingTasks、getModelStatus、getAvailableModels）
  2. **ModelTraining.vue**: 左右分栏布局
     - 左栏：新建训练任务表单（模型名称、Roboflow下载命令、Epochs、Batch Size）
     - 右栏：训练任务列表表格（状态、mAP、类别数、操作）+ 选中行详情展示
     - 实现5秒轮询机制，自动更新进行中任务状态
  3. **SingleClassDetection.vue改造**: 
     - 新增"检测模型"下拉框（el-option-group分组：内置模型 + 自定义训练模型）
     - 原"模型选择"改名为"检测类别"
     - 使用 `computed` 计算 `currentClasses` 和 `currentModelPath`
     - 使用 `watch` 监听模型切换，自动选中第一个类别
     - 支持 `?modelId=X` 参数自动选中模型
  4. **路由和菜单**: 
     - 更新路由从 `/training` → `/model-training`
     - 更新侧边栏菜单项标题为"单类别模型训练"
- **关键设计**:
  1. **统一模型数据结构**: `{ id, modelName, modelPath, classes[] }`，内置模型id为'builtin'，自定义模型id为'custom_${modelId}'
  2. **向后兼容**: 内置VisDrone模型行为完全不变，即使API失败也有兜底数据
  3. **轮询优化**: 只在有进行中任务时启动轮询，所有任务完成后自动停止，组件卸载时清除定时器
  4. **用户体验**: 训练完成后点击"去检测"按钮，自动跳转并选中对应模型
- **教训**:
  1. 改造现有页面时，必须保证向后兼容，原有功能行为不能改变
  2. 使用 `computed` + `watch` 组合实现响应式数据联动，比手动更新更可靠
  3. 轮询机制必须有明确的启动和停止条件，避免内存泄漏
  4. 路由参数传递（query）可以实现页面间的上下文传递，提升用户体验
  5. 前端编译成功但有chunk size警告是正常的，不影响功能，可后续优化
