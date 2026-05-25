依据你上传的 2026-05-21《项目管理页面整体逻辑与代码审查报告》，当前页面已串起项目列表、创建、类别定义、上传、自动标注、导出、训练等主链路，但存在 P0 级权限、凭证、上传路径安全问题，以及 P1/P2 级接口契约、状态枚举、视频抽帧、自动标注、导出和清理问题。以下是可直接交给研发团队执行的下一步操作手册。

# 项目管理页面整改操作手册

## 一、总目标

本轮整改目标不是“优化页面”，而是先把项目管理页面从“功能可跑”推进到“可安全进入生产验证”。

最终验收标准：

1. 任意项目、任务、导出、上传、训练相关接口都不能越权访问其他组织数据。
2. 前端不再展示或常规返回 Label Studio 明文密码，密钥不再硬编码在配置文件。
3. 上传、合并、删除、ZIP 解压都不能通过文件名或路径参数写出项目目录。
4. 项目列表分页、标签模型、状态枚举、图片列表分页等接口契约统一。
5. 页面上不存在必然失败的入口，例如未实现的视频抽帧 Tab。
6. 自动标注不能并发互相清理结果，取消状态不再被误展示为失败。
7. 删除、导出、统计、前端错误提示和测试覆盖达到可回归水平。

报告建议的修复顺序是：先处理 P0 权限、凭证、上传安全，再统一分页、标签和状态枚举，随后处理视频抽帧、自动标注、导出、删除清理和关键测试。

---

## 二、执行节奏

建议拆成三个合并阶段，不要一次性把所有修改塞进一个大 MR。

| 阶段            | 目标                     | 产出                                     |
| ------------- | ---------------------- | -------------------------------------- |
| 第 1 阶段：P0 热修  | 解决权限、凭证、上传路径三类高风险问题    | `hotfix/project-p0-security`           |
| 第 2 阶段：接口契约修复 | 解决分页、标签、状态、视频抽帧、自动标注并发 | `feature/project-contract-stabilize`   |
| 第 3 阶段：生产化补强  | 解决导出、删除清理、统计口径、测试和前端体验 | `feature/project-production-hardening` |

上线策略：

```bash
# 1. 从主分支拉出 P0 热修分支
git checkout main
git pull
git checkout -b hotfix/project-p0-security

# 2. 后端先跑编译和已有测试
cd backend-springboot
mvn test

# 3. 前端在具备 Node/npm 的环境跑构建
cd ../frontend-vue
npm ci
npm run build
```

报告中后端曾执行 `mvn test -DskipTests` 并编译成功，但当时没有 test source；前端因环境没有 npm 未完成构建验证，所以本轮必须在 CI 或本地 Node 环境补齐前端构建。

---

# 三、第 1 阶段：P0 热修手册

## 任务 P0-01：项目级权限统一封口

### 问题

报告指出多个接口只按 `projectId` 查询，没有校验当前用户或组织权限；涉及项目详情、更新、删除、图片列表、统计、审核、导出、Label Studio、自动标注等入口。风险是任意登录用户可能猜项目 ID 后访问、删除、导出或操作其他组织项目。

### 后端操作

第一步，在 `ProjectRepository.java` 增加组织限定查询：

```java
Optional<Project> findByIdAndOrganizationId(Long id, Long organizationId);

boolean existsByNameAndOrganizationIdAndIdNot(
    String name,
    Long organizationId,
    Long id
);
```

第二步，在 `ProjectController.java` 中增加统一 helper：

```java
private Project requireProjectInCurrentOrg(Long projectId, HttpServletRequest request) {
    Long organizationId = (Long) request.getAttribute("organizationId");
    if (organizationId == null) {
        throw new BusinessException("当前用户没有组织，无法访问项目");
    }

    return projectRepository.findByIdAndOrganizationId(projectId, organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
}
```

第三步，把以下入口全部替换成 `requireProjectInCurrentOrg`：

```text
GET    /projects/{id}
PUT    /projects/{id}
DELETE /projects/{id}
GET    /projects/{id}/images
GET    /projects/{id}/stats
GET    /projects/{id}/review-stats
GET    /projects/{id}/review-results
POST   /projects/{id}/export
LabelStudioController.getLoginUrl
LabelStudioController.syncProject
AutoAnnotationController.startAutoAnnotation
AutoAnnotationController.getLatestProjectJob
Training / Job / Upload 中所有带 projectId 的入口
```

第四步，`jobId`、`trainingRecordId` 这类非项目 ID 入口不能只查任务本身，需要通过任务反查 `projectId`，再校验项目组织。

### 验收标准

用两个组织做测试：

| 场景                      | 期望        |
| ----------------------- | --------- |
| orgA 用户访问 orgA 项目详情     | 200       |
| orgA 用户访问 orgB 项目详情     | 403 或 404 |
| orgA 用户导出 orgB 项目       | 403 或 404 |
| orgA 用户取消 orgB 自动标注 Job | 403 或 404 |
| orgA 用户访问 orgB 图片列表     | 403 或 404 |

建议补测试：

```java
@Test
void getProject_shouldRejectProjectFromOtherOrganization() {}

@Test
void exportProject_shouldRejectProjectFromOtherOrganization() {}

@Test
void getJob_shouldRejectJobFromOtherOrganization() {}
```

---

## 任务 P0-02：移除明文凭证暴露

### 问题

报告指出前端项目详情页直接展示 Label Studio 邮箱和密码，用户 profile 响应包含 `lsPassword`，用户实体保存 `ls_plain_password`，且配置文件中存在硬编码 JWT secret 和 Label Studio admin-token。风险是凭证可能通过 XSS、截图、浏览器插件、日志或仓库泄露。

### 后端操作

第一步，修改 `UserProfileResponse.java`：

```java
// 保留
private Boolean lsSyncStatus;
private Long lsUserId;
private String lsEmail;

// 删除或停止返回
// private String lsPassword;
```

第二步，修改 `UserServiceImpl.getUserProfile`，不要把明文密码放入响应。

第三步，修改 `application.yml`：

```yaml
app:
  security:
    jwt:
      secret: ${APP_JWT_SECRET}
  label-studio:
    admin-token: ${LABEL_STUDIO_ADMIN_TOKEN}
```

第四步，在部署环境补充环境变量：

```bash
export APP_JWT_SECRET="..."
export LABEL_STUDIO_ADMIN_TOKEN="..."
```

第五步，历史已提交过的密钥需要轮换。不要只改配置文件，否则旧密钥仍然有效。

### 前端操作

在 `ProjectDetail.vue` 中移除密码展示区域，只显示同步状态和脱敏邮箱：

```vue
<el-alert v-if="userProfile.lsEmail" type="info" :closable="false">
  <template #title>Label Studio 已同步</template>
  <span>{{ maskEmail(userProfile.lsEmail) }}</span>
</el-alert>
```

增加脱敏函数：

```js
const maskEmail = (email) => {
  if (!email) return ''
  const [name, domain] = email.split('@')
  if (!domain) return email
  return `${name.slice(0, 2)}***@${domain}`
}
```

### 验收标准

| 检查项                | 期望                           |
| ------------------ | ---------------------------- |
| `/user/profile` 响应 | 不包含 `lsPassword`             |
| 项目详情页              | 不展示 LS 明文密码                  |
| 配置文件               | 不出现真实 JWT secret、admin-token |
| CI 日志              | 不打印密钥                        |
| 老密钥                | 已轮换                          |

---

## 任务 P0-03：上传文件名和路径安全归一化

### 问题

报告指出前端把 `file.name` 直接传后端，后端接收 `filename` 后直接写入分块文件和合并文件，`mergeChunks` 未限制最终路径必须位于项目目录，`deleteFile` 也存在按传入路径拼接删除的风险。

### 后端操作

第一步，在上传服务中增加安全文件名函数：

```java
private String sanitizeFilename(String filename) {
    if (filename == null || filename.isBlank()) {
        throw new BusinessException(ErrorCode.FILE_004, "文件名为空");
    }

    String baseName = Paths.get(filename).getFileName().toString();
    String safe = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");

    if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
        throw new BusinessException(ErrorCode.FILE_004, "文件名非法");
    }

    return safe;
}
```

第二步，增加目录内解析函数：

```java
private Path resolveInside(Path root, String filename) {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path target = normalizedRoot.resolve(sanitizeFilename(filename)).normalize();

    if (!target.startsWith(normalizedRoot)) {
        throw new BusinessException(ErrorCode.FILE_004, "文件路径非法");
    }

    return target;
}
```

第三步，修改以下位置：

```text
FileUploadController.uploadChunk
FileUploadServiceImpl.mergeChunks
FileUploadServiceImpl.deleteFile
ZIP 解压逻辑
单图上传保存逻辑
分块临时目录生成逻辑
```

第四步，上传、合并、删除前都先调用 `requireProjectInCurrentOrg`，不能只信任 `projectId`。

### 验收标准

| 测试输入                         | 期望                   |
| ---------------------------- | -------------------- |
| `filename=../evil.jpg`       | 400，项目目录外无文件         |
| `filename=..%2F..%2Fapp.yml` | 400                  |
| `filename=a/b/c.jpg`         | 被归一化为安全 basename 或拒绝 |
| ZIP 内含 `../evil.jpg`         | 拒绝或安全重命名             |
| 删除其他项目文件路径                   | 403 或 404            |

建议补测试：

```java
@Test
void uploadMerge_shouldRejectPathTraversalFilename() {}

@Test
void deleteFile_shouldRejectFileOutsideProjectDirectory() {}

@Test
void unzip_shouldRejectZipSlipEntry() {}
```

---

# 四、第 2 阶段：接口契约与页面逻辑修复

## 任务 P1-01：统一项目列表分页契约

### 问题

报告指出前端传 `page=currentPage-1` 和 `size=pageSize`，后端也用了 `PageRequest`，但返回值只是当前页数组，没有返回 `totalElements` 和 `totalPages`；前端把 `total` 设置成当前页数组长度，导致分页器不知道真实总数。

### 后端操作

将 `GET /api/v1/projects` 返回结构改为：

```json
{
  "content": [],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 120,
    "totalPages": 6
  }
}
```

### 前端操作

`ProjectList.vue` 兼容新旧结构，避免发布过程中前后端版本不一致：

```js
const payload = response.data || {}

if (Array.isArray(payload)) {
  projects.value = payload
  total.value = payload.length
} else {
  projects.value = payload.content || []
  total.value = payload.pageable?.totalElements || 0
}
```

### 验收标准

| 场景                      | 期望               |
| ----------------------- | ---------------- |
| 数据库有 30 个项目，pageSize=20 | 第一页显示 20 条，总数 30 |
| 点击第二页                   | 显示剩余 10 条        |
| status 过滤               | 总数为过滤后的真实数量      |
| 旧接口临时返回数组               | 页面不崩溃            |

---

## 任务 P1-02：统一标签模型、校验和 XML escape

### 问题

报告指出文档、前端、后端的标签结构不一致：文档里 `labels` 是对象，实体和 DTO 中是 `List<String>`；后端只校验 `labels != null`，没有校验非空和元素有效性；Label Studio XML 直接拼接标签文本，存在非法 XML 风险。

### 建议统一规则

本轮先不要引入复杂模型，建议：

```text
labels: List<String>，只表示类别 key/name
labelDefinitions: Map<String, String>，表示类别展示名或中文说明
```

后端 DTO：

```java
@NotNull(message = "标签不能为空")
@Size(min = 1, max = 20, message = "标签数量必须在 1-20 个之间")
private List<
    @NotBlank(message = "标签名称不能为空")
    @Size(max = 64, message = "标签名称不能超过 64 个字符")
    String> labels;
```

前端提交前统一：

```js
const normalizeLabels = (labels) => {
  const seen = new Set()
  return labels
    .map(label => label.trim())
    .filter(Boolean)
    .filter(label => {
      const key = label.toLowerCase()
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
}
```

Label Studio XML 必须 escape：

```java
private String escapeXml(String value) {
    return value == null ? "" : value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
}
```

### 验收标准

| 输入                        | 期望                    |
| ------------------------- | --------------------- |
| `labels=[]`               | 400                   |
| `labels=["", "cat"]`      | 400 或清洗后只保留 cat，按约定执行 |
| `labels=["cat", " Cat "]` | 去重                    |
| `labels=["a<b"]`          | LS XML 不损坏            |
| 项目名 1 个字符                 | 前后端都拒绝                |

---

## 任务 P1-03：统一项目状态枚举展示

### 问题

报告指出后端状态包含 `DRAFT, UPLOADING, DETECTING, CLEANING, SYNCING, COMPLETED, FAILED`，但前端只映射了 `DRAFT, UPLOADING, PROCESSING, COMPLETED, FAILED`，导致部分状态显示英文或颜色不准确。

### 前端操作

创建统一状态常量，例如 `src/constants/projectStatus.js`：

```js
export const PROJECT_STATUS_META = {
  DRAFT: { text: '草稿', type: 'info' },
  UPLOADING: { text: '上传中', type: 'warning' },
  DETECTING: { text: '检测中', type: 'primary' },
  CLEANING: { text: '清洗中', type: 'primary' },
  SYNCING: { text: '同步中', type: 'primary' },
  COMPLETED: { text: '已完成', type: 'success' },
  FAILED: { text: '失败', type: 'danger' }
}
```

`ProjectList.vue`、`ProjectDetail.vue`、`AlgorithmTasks.vue` 统一使用这份常量。

### 验收标准

| 后端状态      | 页面文案 |
| --------- | ---- |
| DETECTING | 检测中  |
| CLEANING  | 清洗中  |
| SYNCING   | 同步中  |
| COMPLETED | 已完成  |
| FAILED    | 失败   |

---

## 任务 P1-04：隐藏或补齐视频抽帧入口

### 问题

报告指出数据管理页面展示“视频抽帧”Tab，但后端没有 `/upload/extract-frames` 接口，`VideoExtract.vue` 的 `availableVideos` 也为空，因此该入口是必然失败功能。

### 推荐操作

短期先隐藏，避免用户点击失败：

```vue
<el-tab-pane v-if="features.videoExtract" label="视频抽帧" name="video">
  <VideoExtract :project-id="project.id" @extracted="handleExtracted" />
</el-tab-pane>
```

配置：

```js
const features = {
  videoExtract: false
}
```

后续要真正实现时，再补：

```text
GET  /upload/videos?projectId=xxx
POST /upload/extract-frames
```

### 验收标准

| 场景                     | 期望                   |
| ---------------------- | -------------------- |
| 默认进入数据管理页              | 不显示视频抽帧 Tab          |
| 开启 feature flag 且后端未实现 | 不允许上线                |
| 后端实现后                  | 可选择视频、设置抽帧间隔、执行并生成图片 |

---

## 任务 P1-05：自动标注并发保护和取消语义

### 问题

报告指出前端启动自动标注时固定传 `processRange: 'all'`，后端会清理旧结果；启动前没有检查同项目是否已有 `PENDING/RUNNING/CANCELLING` Job；取消任务后项目状态被设为 `FAILED`；结果接口也未真正实现。

### 后端操作

第一步，仓库增加运行中任务判断：

```java
boolean existsByProjectIdAndStatusIn(
    Long projectId,
    List<AutoAnnotationJob.JobStatus> statuses
);
```

第二步，创建任务时加并发锁：

```java
if (autoAnnotationJobRepository.existsByProjectIdAndStatusIn(
        projectId,
        List.of(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.CANCELLING))) {
    throw new BusinessException("该项目已有自动标注任务运行中，请等待完成或取消后重试");
}
```

第三步，取消状态不要落成 `FAILED`。建议新增：

```text
ProjectStatus.CANCELLED
JobStatus.CANCELLED
```

如果暂时不改枚举，取消后至少回退到上一个稳定状态，例如 `DRAFT` 或 `COMPLETED`。

第四步，`/auto-annotation/results/{taskId}` 要么实现真实结果返回，要么删除/禁用该 API，不能返回“未实现”字符串。

### 前端操作

自动标注启动按钮增加运行中禁用：

```js
const canStartAnnotation = computed(() => {
  return !['PENDING', 'RUNNING', 'CANCELLING'].includes(currentJob.value?.status)
})
```

### 验收标准

| 场景           | 期望                       |
| ------------ | ------------------------ |
| 同一项目连续点击两次启动 | 第二次被拒绝并提示已有任务            |
| 多浏览器同时启动     | 只有一个成功                   |
| 取消任务         | 展示“已取消”，不展示“失败”          |
| 查询结果接口       | 返回真实结果或明确 404/501，不返回假成功 |

---

## 任务 P1-06：项目名唯一性补强

### 问题

报告指出创建时检查了 `existsByNameAndOrganizationId`，但更新项目名时没有检查重复；实体也缺少数据库唯一约束，因此同组织内可通过更新接口制造重名项目，并发创建也可能绕过检查。

### 后端操作

第一步，先排查重复数据：

```sql
SELECT organization_id, name, COUNT(*)
FROM projects
GROUP BY organization_id, name
HAVING COUNT(*) > 1;
```

第二步，处理重复项目名后再加唯一索引：

```sql
ALTER TABLE projects
ADD CONSTRAINT uk_projects_org_name UNIQUE (organization_id, name);
```

第三步，更新接口补检查：

```java
if (request.getName() != null && !request.getName().isBlank()) {
    Long organizationId = (Long) httpRequest.getAttribute("organizationId");

    if (projectRepository.existsByNameAndOrganizationIdAndIdNot(
            request.getName().trim(), organizationId, id)) {
        throw new BusinessException("项目名称已存在");
    }

    project.setName(request.getName().trim());
}
```

### 验收标准

| 场景          | 期望     |
| ----------- | ------ |
| 同组织创建同名项目   | 拒绝     |
| 同组织更新成已有项目名 | 拒绝     |
| 不同组织使用同名项目  | 允许     |
| 并发创建同名项目    | 只有一个成功 |

---

# 五、第 3 阶段：生产化补强手册

## 任务 P2-01：统一图片状态和统计口径

### 问题

报告指出单图片上传保存为 `COMPLETED`，ZIP 解压图片保存为 `PENDING`，但统计中 `uploadedImages` 只统计 `COMPLETED`，导致 ZIP 上传后统计可能与用户直觉不一致。

### 推荐规则

将状态拆清楚：

```text
uploadStatus: UPLOADED / FAILED
annotationStatus: UNANNOTATED / ANNOTATING / ANNOTATED / REVIEWED
```

如果短期不改表结构，至少统一上传完成后的图片状态，避免单图和 ZIP 口径不同。

### 验收标准

| 场景          | 期望                                            |
| ----------- | --------------------------------------------- |
| 单图上传 3 张    | totalImages = 3                               |
| ZIP 解压 10 张 | totalImages = 10                              |
| 删除 1 张      | totalImages = 9                               |
| 自动标注完成 5 张  | processedImages = 5 或由 detection_results 实时计算 |

---

## 任务 P2-02：导出改为文件流下载

### 问题

报告指出当前后端导出把内容 URL encode 后放入 `downloadUrl`，前端再 decode 成 Blob；小文件可用，但大项目导出时响应体和浏览器内存压力较大，下载稳定性差。

### 后端操作

改成“创建导出任务 + 文件下载”：

```java
@PostMapping("/{id}/export")
public Result<ExportResponse> exportResults(...) {
    Path file = exportService.writeExportFile(project, format, exportData);

    return Result.success(new ExportResponse(
        exportId,
        "/api/v1/projects/" + id + "/exports/" + exportId + "/download",
        itemCount,
        Files.size(file),
        format
    ));
}
```

下载接口：

```java
@GetMapping("/{id}/exports/{exportId}/download")
public ResponseEntity<Resource> downloadExport(...) {
    // 校验项目归属
    // 校验 exportId 属于该项目
    // 返回文件流
}
```

### 验收标准

| 场景         | 期望        |
| ---------- | --------- |
| 小数据导出      | 正常下载      |
| 大数据导出      | 不因响应体过大失败 |
| 访问其他项目导出文件 | 403 或 404 |
| 删除导出文件     | 前后端接口一致   |

---

## 任务 P2-03：删除项目时清理物理文件和外部状态

### 问题

报告指出删除项目时删除了 DB 中的 detection、image、round、auto job、LS 项目等，但没有删除 `/root/autodl-fs/uploads/{projectId}` 项目物理目录；Label Studio 删除失败也只是被忽略。

### 后端操作

删除项目后增加清理任务：

```java
try {
    fileStorageService.deleteProjectDirectory(id);
} catch (Exception e) {
    cleanupTaskRepository.save(
        ProjectCleanupTask.pending(id, "UPLOAD_FILES", e.getMessage())
    );
}
```

Label Studio 删除失败时也写补偿任务：

```text
cleanup_type = LABEL_STUDIO_PROJECT
status = PENDING
retry_count = 0
```

### 验收标准

| 场景       | 期望               |
| -------- | ---------------- |
| 删除项目成功   | DB 数据删除，上传目录删除   |
| 上传目录删除失败 | 项目删除不阻塞，但产生待清理任务 |
| LS 删除失败  | 产生补偿任务，有日志和可重试记录 |
| 再次访问项目   | 404              |

---

## 任务 P2-04：前端体验和可维护性清理

### 问题

报告指出项目列表“编辑”按钮只是提示开发中，图片列表保留 debug log，创建失败依赖泛化拦截器错误，401 可能多次弹窗，日期未格式化。

### 前端操作清单

```text
1. 隐藏或实现“编辑”按钮。
2. 删除 ImageList.vue 中的 console.log。
3. 创建项目失败时显示明确错误。
4. 401 弹窗增加单例锁。
5. createdAt / updatedAt 统一格式化。
6. 所有未实现功能不要长期显示在主操作区。
```

401 单例弹窗：

```js
let authExpiredShown = false

case 401:
  if (authExpiredShown) break
  authExpiredShown = true

  ElMessageBox.alert('登录已过期，请重新登录', '提示', {
    confirmButtonText: '确定',
    callback: () => {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      window.location.href = '/login'
    }
  })
  break
```

### 验收标准

| 场景        | 期望                          |
| --------- | --------------------------- |
| 多接口同时 401 | 只弹一次登录过期                    |
| 创建项目名太短   | 明确提示“项目名称长度必须在 3-100 个字符之间” |
| 图片列表加载    | 控制台无业务 debug log            |
| 未实现功能     | 不在主界面误导用户                   |

---

# 六、回归测试手册

## 后端必测

报告已建议补充权限、标签、重名、上传路径、自动标注并发等测试，应作为本轮最低测试集。

```text
1. getProject_shouldRejectProjectFromOtherOrganization
2. createProject_shouldRejectEmptyLabels
3. updateProject_shouldRejectDuplicateNameInSameOrg
4. uploadMerge_shouldRejectPathTraversalFilename
5. startAutoAnnotation_shouldRejectConcurrentRunningJob
6. export_shouldRejectProjectFromOtherOrganization
7. deleteProject_shouldCleanupUploadDirectoryOrCreateCleanupTask
8. getProjectImages_shouldUseConsistentPagination
```

运行：

```bash
cd backend-springboot
mvn test
```

## 前端必测

```text
1. 项目列表使用 pageable.totalElements。
2. DETECTING / CLEANING / SYNCING 能显示中文状态。
3. 标签输入会 trim、去重、拒绝空值。
4. 视频抽帧入口默认隐藏。
5. profile 响应不含 lsPassword 时页面不报错。
6. 401 多请求同时失败只弹一次。
```

运行：

```bash
cd frontend-vue
npm ci
npm run build
npm run test
```

如果项目暂时没有前端单测框架，至少完成手工冒烟测试。

---

# 七、手工冒烟测试路径

按这个顺序走一遍，作为合并前检查。

## 1. 登录与项目列表

```text
登录 orgA 用户
进入 /projects
确认项目列表正常显示
确认分页总数正确
确认状态中文显示正确
```

## 2. 创建项目

```text
创建项目名少于 3 个字符
期望：前端直接提示失败

创建合法项目 animal-detection
labels = ["cat", "dog"]
期望：创建成功，进入详情页
```

## 3. 权限隔离

```text
用 orgB 创建项目
切回 orgA
手工访问 orgB 的 projectId
期望：详情、图片、导出、删除、自动标注入口全部 403 或 404
```

## 4. 上传安全

```text
上传正常图片
期望：成功

构造 filename=../evil.jpg
期望：拒绝，服务器项目目录外无文件

上传含 ../evil.jpg 的 ZIP
期望：拒绝或安全重命名，不写出目录
```

## 5. 标签与 Label Studio

```text
标签输入 " Cat "
再输入 "cat"
期望：不重复

标签输入 "a<b"
期望：Label Studio 配置不报 XML 错误

项目详情页
期望：只显示 LS 同步状态和脱敏邮箱，不显示密码
```

## 6. 自动标注

```text
启动自动标注
立即再次启动
期望：第二次提示已有任务运行中

取消任务
期望：状态显示已取消或回到稳定状态，不显示失败
```

## 7. 导出和删除

```text
导出结果
期望：返回下载 URL，文件流下载成功

删除项目
期望：数据库项目消失，上传目录被清理或产生待清理任务
```

---

# 八、MR 拆分建议

## MR 1：P0 安全热修

包含：

```text
ProjectRepository.findByIdAndOrganizationId
ProjectController 权限 helper
LabelStudioController 权限校验
AutoAnnotationController / Job 权限校验
UserProfileResponse 移除 lsPassword
application.yml 改环境变量
FileUpload 文件名和路径安全归一化
```

不包含：

```text
导出重构
图片状态大改
复杂前端重构
```

这样可以最快降低线上风险。

## MR 2：接口契约稳定

包含：

```text
项目列表分页响应 { content, pageable }
前端兼容 pageable
labels 校验与清洗
Label Studio XML escape
状态枚举统一
视频抽帧入口隐藏
自动标注并发保护
项目名唯一约束
```

## MR 3：生产化补强

包含：

```text
导出文件流下载
删除项目清理物理目录
LS 删除补偿任务
图片统计口径统一
前端错误提示优化
401 单例弹窗
测试补齐
```

---

# 九、上线前检查清单

上线前逐项打勾：

```text
[ ] 所有 projectId 入口已做组织校验
[ ] 所有 jobId / trainingRecordId 入口已反查项目并校验组织
[ ] /user/profile 不返回 lsPassword
[ ] 前端不展示 LS 明文密码
[ ] application.yml 不包含真实密钥
[ ] 已轮换历史泄露风险密钥
[ ] 上传文件名已 sanitize
[ ] 文件写入路径已 normalize + startsWith(root)
[ ] deleteFile 已做权限和路径校验
[ ] ZIP 解压已防 Zip Slip
[ ] 项目列表返回 totalElements / totalPages
[ ] 前端分页显示真实总数
[ ] labels 非空、trim、去重、XML escape
[ ] DETECTING / CLEANING / SYNCING 中文显示
[ ] 视频抽帧入口隐藏或后端已实现
[ ] 自动标注同项目并发启动被拒绝
[ ] 取消任务不再显示失败
[ ] 导出不再依赖 data URL 大响应
[ ] 删除项目清理上传目录或产生补偿任务
[ ] 后端 mvn test 通过
[ ] 前端 npm run build 通过
[ ] 权限、上传、分页、标签、自动标注测试通过
```

---

## 十、推荐优先级结论

第一优先级只做三件事：**权限封口、凭证移除、上传路径安全**。这三项不完成，不建议继续扩大项目管理页面使用范围。

第二优先级处理：**分页契约、标签校验、状态枚举、视频抽帧隐藏、自动标注并发保护**。这些直接影响用户体验和功能正确性。

第三优先级处理：**导出文件流、删除清理、统计口径、前端体验和测试体系**。这些决定后续能否稳定进入生产和持续迭代。

处理完这三个优先级