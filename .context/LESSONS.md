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

### 2026-04-23: 项目详情训练进度和指标未回显、测试模型仍是模拟结果
- **根因**: 项目详情页 `/projects/{id}/training/status` 只读 `ModelTrainingRecord` 数据库记录，没有实时查询算法服务的 `processed_images/total_images`，也没有在训练完成后把 `results.csv`/算法结果中的 mAP、precision、recall、模型路径写回；`/projects/{id}/training/detect` 返回硬编码模拟检测框，没有调用训练出的 `best.pt`；算法测试上传路由还存在 `/api/v1` 前缀重复和 multipart 表单参数未声明为 `Form` 的问题。
- **修复**: 后端状态接口主动同步算法服务任务状态，完成后保存/回填 `best.pt`、mAP、precision、recall；算法训练结果返回 `metrics/results_csv`；项目检测接口改为上传图片并调用真实 YOLO 测试服务；修正算法测试路由前缀、上传表单参数和 CPU 回退。
- **教训**: 长任务前端展示不能只依赖启动时写入的数据库状态，必须有“算法任务状态 → 后端记录 → 前端轮询”的字段映射；所有测试按钮必须走真实推理路径，不能保留 mock 返回。

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

### 2026-03-25: 导出功能下载文件无效/空文件
- **根因**: 后端返回 `data:` URL 格式，前端直接使用 `a.href` 赋值，但未正确解析 base64 数据和设置正确的 MIME 类型，导致下载的文件无法打开或内容为空。
- **修复**: 前端从 `data:` URL 中提取 base64 数据，使用 `decodeURIComponent` 解码，创建 `Blob` 对象并设置正确的 MIME 类型（`application/json`、`text/plain`、`application/xml`、`text/csv`），通过 `URL.createObjectURL` 生成下载链接。
- **教训**: 处理 `data:` URL 时必须正确解析和创建 Blob，不能直接赋值给 `a.href`。不同格式需要设置对应的 MIME 类型和文件扩展名。

### 2026-03-25: 训练功能缺少状态持久化
- **根因**: 当前训练接口返回模拟数据，没有真实的训练服务集成，训练状态无法持久化，进度监控和指标展示都是静态数据。
- **修复**: 需要集成真实的训练服务（如 Python FastAPI 训练脚本），建立训练任务表存储状态，通过 WebSocket 或轮询机制实时更新训练进度。
- **教训**: 训练功能涉及长时间运行任务，必须设计状态持久化和实时更新机制，不能仅返回模拟数据。

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

### 2026-03-25: Label Studio本地文件挂载失败 + 自动标注进度未显示
- **问题1 - LS挂载失败**: 后端日志显示"挂载本地存储失败，跳过预测同步"，导致自动标注结果无法同步到Label Studio。
- **根因**: Label Studio启动时未设置必需的环境变量 `LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true` 和 `LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs`，导致本地文件存储功能不可用。
- **修复**: 
  1. 停止Label Studio进程：`pkill -f "label-studio"`
  2. 使用正确的启动命令（必须包含两个环境变量）：
     ```bash
     source $(conda info --base)/etc/profile.d/conda.sh
     conda activate web_annotation
     export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
     export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs
     nohup label-studio start --port 5001 --no-browser --log-level INFO > /tmp/labelstudio.log 2>&1 &
     ```
- **问题2 - 前端进度未显示**: 自动标注页面点击启动后，进度条一直停留在5%"任务已启动..."，无法显示实际进度（DETECTING、CLEANING、SYNCING等状态）。
- **根因**: `AutoAnnotationController.getTaskStatus()` 接口返回"Task status query not implemented yet"，前端轮询无法获取真实任务状态。
- **修复**:
  1. 在 `AutoAnnotationController` 中注入 `ProjectRepository`
  2. 修改 `startAutoAnnotation()` 返回包含 `taskId: "project-{projectId}"` 的Map
  3. 重写 `getTaskStatus()` 方法：
     - 从taskId提取projectId
     - 查询Project实体获取当前状态
     - 将Project.ProjectStatus映射到前端期望的状态字符串（DRAFT→PENDING, DETECTING→DETECTING, CLEANING→CLEANING, SYNCING→SYNCING, COMPLETED→COMPLETED, FAILED→FAILED）
     - 返回包含status字段的Map
  4. 重新编译并重启Spring Boot
- **教训**:
  1. **Label Studio环境变量是必需的**：每次重启LS都必须设置这两个环境变量，否则本地文件存储不可用。这是置顶第5条的核心原因。
  2. **接口实现不能留空**：Controller中的"not implemented yet"会导致前端功能完全不可用，必须在开发时立即实现或至少返回有意义的默认值。
  3. **状态轮询依赖后端真实数据**：前端进度条依赖后端返回的状态字段，后端必须查询数据库获取真实状态，不能返回mock数据。
  4. **枚举值映射要准确**：Project.ProjectStatus的枚举值是DRAFT/UPLOADING/DETECTING/CLEANING/SYNCING/COMPLETED/FAILED，不是CREATED，映射时要使用正确的枚举值。
  5. **修改Controller后必须重启**：新增或修改Controller方法后，必须重新编译（mvn clean package）并重启Spring Boot，否则运行的还是旧代码。

### 2026-03-25: 自动标注重复数据 + Label Studio图片加载失败 + 审核结果字段名不匹配
- **问题1 - 重新执行自动标注时清洗结果显示重复数据**：
  - **根因**: `AutoAnnotationService.startAutoAnnotation()` 方法没有清理旧数据逻辑，每次执行都会创建新的 `AnnotationTask` 和 `DetectionResult` 记录，导致同一张图片的清洗结果出现多次。前端传递的 `processRange` 参数虽然被接收，但方法体内没有使用。
  - **修复**:
    1. 在 `DetectionResultRepository` 添加 `deleteByTaskIdIn(List<Long>)` 和 `@Modifying @Query deleteByProjectId(Long)` 方法
    2. 在 `AnnotationTaskRepository` 添加 `findIdsByProjectId(Long)` 和 `@Modifying @Query deleteByProjectId(Long)` 方法
    3. 在 `AutoAnnotationService.startAutoAnnotation()` 方法中，获取项目信息后、获取图片列表前，添加清理逻辑：如果 `processRange = "all"`，先删除 `DetectionResult`（外键依赖），再删除 `AnnotationTask`
    4. 删除顺序很重要：必须先删 `DetectionResult` 再删 `AnnotationTask`，否则会报外键约束错误
- **问题2 - Label Studio 图片 URL 加载失败（500错误）**：
  - **根因**: Label Studio 数据库中存在孤立的 storage 记录（`io_storages_localfilesimportstorage` 表中的记录关联的 `project_id` 对应的项目已被删除）。当访问 `/data/local-files/?d=...` 时，Label Studio 查找所有路径匹配的 storage，在权限检查时 `storage.project` 查询失败导致 500 错误。
  - **临时修复**: 直接操作 Label Studio SQLite 数据库，删除孤立的 storage 记录：
    ```sql
    DELETE FROM io_storages_localfilesimportstorage WHERE localfilesmixin_ptr_id IN (1, 2, 12, 13, 14);
    DELETE FROM io_storages_localfilesmixin WHERE id IN (1, 2, 12, 13, 14);
    ```
  - **预防性修复**: 在后端删除项目时同步清理 Label Studio 的 local storage，避免产生孤立记录：
    1. 在 `LabelStudioProxyService` 接口添加 `deleteLocalStorageByProject(Long lsProjectId, Long userId)` 方法
    2. 在 `LabelStudioProxyServiceImpl` 实现该方法：先调用 `/api/storages/localfiles?project={id}` 获取项目的所有 storage，再逐个调用 `DELETE /api/storages/localfiles/{storageId}` 删除
    3. 在 `ProjectController.deleteProject()` 中，**必须先删除 storage 再删除 project**（删除顺序：DetectionResult → ProjectImage → Local Storage → LS Project → Project 实体）
  - **验证**: 环境变量 `LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true` 和 `LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs` 已正确设置，文件权限正常。
- **问题3 - 审核结果显示"项目尚未同步到 Label Studio"**：
  - **根因**: `ProjectDetailResponse` DTO 缺少 `lsProjectId` 字段，前端检查 `props.project.labelStudioProjectId` 时始终为 `undefined`，导致误判为未同步。
  - **修复**:
    1. 在 `ProjectDetailResponse.java` 添加字段 `@JsonProperty("labelStudioProjectId") private Long lsProjectId;`（使用 `@JsonProperty` 注解将后端字段名 `lsProjectId` 映射为前端期望的 `labelStudioProjectId`）
    2. 在 `ProjectController.convertToDetailResponse()` 方法中添加 `.lsProjectId(project.getLsProjectId())` 映射
- **教训**:
  1. **数据清理逻辑必须完整实现**：接收参数后必须在方法体内使用，不能只改方法签名让编译通过
  2. **外键删除顺序**：删除有外键依赖的数据时，必须先删子表（`DetectionResult`）再删父表（`AnnotationTask`）
  3. **@Modifying 注解必需**：JPQL 的 `DELETE`/`UPDATE` 语句必须配合 `@Modifying` 注解，否则 Spring Data JPA 会报错
  4. **Label Studio 数据库清理**：当 Label Studio 出现 500 错误且日志显示 "Project matching query does not exist" 时，检查是否有孤立的 storage 记录（关联的项目已删除）
  5. **Label Studio 删除顺序**：删除 LS 项目时，必须先删除 local storage 再删除 project，否则删除 project 后 API 查不到关联的 storage，会再次产生孤立记录
  6. **预防性编程**：在删除操作中添加完整的清理逻辑，避免产生孤立数据。删除项目的正确顺序：DetectionResult → ProjectImage → LS Local Storage → LS Project → Project 实体
  7. **DTO 字段映射**：实体新增字段后，必须同步更新 Response DTO 和转换方法，使用 `@JsonProperty` 可以解决前后端字段名不一致问题
  8. **前后端字段名统一**：优先使用 `@JsonProperty` 注解映射字段名，而不是要求前端修改代码，减少联调成本

### 2026-03-25: 进入项目详情页时后端报404错误（Label Studio空项目tasks API）
- **根因**: Label Studio 1.22.0 对**没有任何 task 的空项目**调用 `GET /api/projects/{id}/tasks` 返回 404 而不是空列表。新建项目同步到 LS 后还没执行自动标注，项目为空就会触发此错误。
- **排查过程**:
  1. 用 curl 验证：`GET /api/projects/41/` 返回 200（项目存在），`GET /api/projects/41/tasks` 返回 404（无 tasks）
  2. 对已有 tasks 的项目 39 测试，`GET /api/projects/39/tasks` 返回 200
  3. Service 层有 `catch (Exception e)` 但异常被 CGLIB 代理拦截，绕过了 catch 块
  4. Controller 层的 catch 也被 CGLIB 代理绕过（Controller 上有其他方法带 `@Transactional`，导致整个类被代理）
- **修复**: 在 `LabelStudioProxyServiceImpl.java` 的 `getProjectReviewStats` 和 `getProjectReviewResults` 两个方法中，**直接在 `restTemplate.exchange()` 调用处**捕获 404：
  ```java
  ResponseEntity<String> response;
  try {
      response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
  } catch (org.springframework.web.client.HttpClientErrorException.NotFound e404) {
      log.warn("LS项目尚无任务(404): lsProjectId={}", lsProjectId);
      return createEmptyReviewStats(); // 或 createEmptyReviewResults()
  }
  ```
- **教训**:
  1. **CGLIB 代理会干扰异常捕获**：当类中有方法被 `@Transactional` 标注时，整个类会被 CGLIB 代理，异常可能在代理层被包装后绕过内部 catch。这导致 Service 层和 Controller 层的 catch 块都不起作用
  2. **异常捕获要在离抛出点最近的地方做**：不要依赖外层 catch 兜底，直接在 `restTemplate.exchange()` 调用处捕获具体异常类型（如 `HttpClientErrorException.NotFound`）
  3. Label Studio 不同版本对空项目的 API 行为不一致（有的返回空列表，有的返回 404），需要做防御性编码

### 2026-03-25: 审核结果解析失败（fastjson2 JSONException: character [）
- **根因**: Label Studio `GET /api/projects/{id}/tasks` API 返回的 JSON 格式不固定：无 tasks 时返回 404；有 tasks 时直接返回 **JSON 数组**（以 `[` 开头），而不是 `{"tasks": [...]}`。代码中使用 `JSON.parseObject()` 只能解析 JSON 对象（以 `{` 开头），遇到数组就报 `JSONException: offset 1, character [`。
- **修复**: 在 `LabelStudioProxyServiceImpl.java` 的 `getProjectReviewStats` 和 `getProjectReviewResults` 两个方法中，解析响应时增加格式判断：
  ```java
  String body = response.getBody();
  com.alibaba.fastjson2.JSONArray tasks;
  if (body != null && body.trim().startsWith("[")) {
      tasks = JSON.parseArray(body);
  } else {
      JSONObject responseData = JSON.parseObject(body);
      tasks = responseData.getJSONArray("tasks");
  }
  ```
- **教训**:
  1. **不要假设第三方 API 的返回格式是固定的**，需要做格式判断或兼容处理
  2. `JSON.parseObject()` 只能解析对象，`JSON.parseArray()` 只能解析数组，要根据实际响应内容选择
  3. 调用第三方 API 前，先用 curl 手动测试返回格式，确认是对象还是数组

### 2026-03-25: 前端 LabelDefinition.vue 警告 + /api/v1/files/undefined 请求
- **问题1**: Vue warn: Property "form" was accessed during render but is not defined on instance（`LabelDefinition.vue`）
- **问题2**: 浏览器请求 `GET /api/v1/files/undefined` 返回 500（`ImageList.vue`）
- **根因**:
  1. `LabelDefinition.vue` 模板中 `<el-form :model="form">` 引用了 `form` 变量但 script 中未定义
  2. `ImageList.vue` 中 `` url: `/api/v1/files/${img.filePath}` `` 当图片的 `filePath` 为 null/undefined 时拼接出无效 URL
- **修复**:
  1. `LabelDefinition.vue`：`<el-form :model="form">` 改为 `<el-form>`（去掉 `:model="form"`）
  2. `ImageList.vue`：加空值判断 `` url: img.filePath ? `/api/v1/files/${img.filePath}` : '' ``
- **教训**:
  1. 模板中引用的变量必须在 setup/data 中定义，否则 Vue 3 会报 warn
  2. 拼接 URL 时必须对变量做空值判断，避免拼出 `/api/v1/files/undefined`

### 2026-04-13: 项目旧代码大清理

- **背景**: 项目从 Streamlit 单体迁移到 Vue + Spring Boot + FastAPI 微服务架构后，根目录残留大量旧版文件，新旧代码混杂，影响可维护性。
- **操作清单**:
  1. **修复 `application.yml`**: `app.training.script-path` 从旧路径 `/root/autodl-fs/web_biaozhupingtai/scripts/train_yolo.py` 改为 `/root/autodl-fs/Annotation-Platform/scripts/train_yolo.py`
  2. **删除 Streamlit 旧版文件（13个）**: `main.py`、`main.py.bak`、`config.py`、`login_page.py`、`user_auth.py`、`annotation_editor.py`、`training_manager.py`、`file_upload_server.py`、`org_project_manager.py`、`user_project_manager.py`、`label_studio_integration_enhanced.py`、`ls_login_proxy.py`、`requirements_web.txt`
  3. **删除旧目录**: `backend/`（旧 Python 后端）、`.streamlit/`、`__pycache__/`、`.pytest_cache/`
  4. **删除过期启动脚本（6个）**: `start.sh`、`start_new.sh`、`start_all_services.sh`、`run.sh`、`install_env.sh`、`restructure_project.sh`（均引用旧路径 `web_biaozhupingtai` 或启动 Streamlit）
  5. **删除一次性修复/诊断脚本（8个）**: `nuclear_fix.py`、`repair_user.py`、`fix_ls_auth.py`、`find_ls_api_key.py`、`diagnose_prediction_score.py`、`diagnose_specific_project.py`、`predicti_demo.py`、`train_freeze.py`
  6. **删除 `scripts/` 下已替代文件（4个）**: `detect_yolo.py`、`manage_vlm_service.sh`、`run_dino_detection.py`、`run_vlm_clean.py`（功能已由 `algorithm-service/` 替代），保留 `train_yolo.py`（被 `application.yml` 引用）
  7. **删除过期文档（17个）**: 根目录 `ARCHITECTURE.md`（描述 Streamlit 技术栈）、`QUICKSTART.md`、`REFACTORING_SUMMARY.md`、`CHANGES.md`、`INTERFACE_VERIFICATION.md`、`AUTO_ANNOTATION_ARCHITECTURE.md`、`debug.md`、`debug_dino.sh`、`test_dino.sh`、`frontend-vue-restyled.zip`、`import_to_label_studio.json`、`projects.json`、`test_label_studio_integration.py`、`test_ls_only.py`、`pytest.ini`、`.context/STAGE_16_17_18_SUMMARY.md`、`.context/RESULT_DISPLAY_FIX.md`
  8. **更新 `.gitignore`**: 补充 `.pytest_cache/`、`*.log`、`.env`/`.env.*`、`.idea/`、`frontend-vue/node_modules/`、`*.zip`、`*.tar.gz`
  9. **更新 `vite.config.js`**: 默认端口从 `5173` 改为 `6006`
- **教训**:
  1. 架构迁移后应及时清理旧代码，避免新旧混杂导致路径引用混乱
  2. 配置文件中的路径引用（如 `script-path`）必须随项目迁移同步更新
  3. 项目根目录只保留唯一启动脚本（`startup.sh`），多个启动脚本并存会造成维护混乱
  4. `.gitignore` 应覆盖所有构建产物、日志、环境变量文件，避免敏感信息或大文件误提交
