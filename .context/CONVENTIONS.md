# 开发规范

## Git 规范

### 仓库信息

- **远程仓库**: `gitee.com:freddywards/annotation-platform.git`
- **本地路径**: `/root/autodl-fs/Annotation-Platform/`
- **默认分支**: `master`

### 提交格式

```
<type>: <中文描述>
```

| type | 用途 |
|------|------|
| `fix` | 修复 bug |
| `feat` | 新增功能 |
| `refactor` | 重构代码（不改功能） |
| `docs` | 文档更新 |
| `style` | 代码格式调整 |
| `test` | 测试相关 |
| `chore` | 构建/工具相关 |

示例：
- `fix: 修复概览页面最近项目显示问题`
- `feat: 新增可行性评估类别管理 CRUD 接口`
- `refactor: 项目名称唯一性从全局唯一改为组织内唯一`

### 提交流程

```bash
cd /root/autodl-fs/Annotation-Platform
git add .
git commit -m "<type>: <描述>"
git push origin master
```

## 代码风格

### Java（Spring Boot）

- **Lombok**: 所有实体类用 `@Data`、`@Builder`、`@NoArgsConstructor`、`@AllArgsConstructor`；Service 类用 `@Slf4j` + `@RequiredArgsConstructor`
- **Controller**: 用 `@RestController` + `@RequestMapping`，不要在 RequestMapping 里写 `/api/v1` 前缀（context-path 已配置）
- **响应格式**: 统一使用 `Result<T>` 包装类，`Result.success(data)` 或 `Result.error(message)`
- **异常处理**: 资源不存在抛 `ResourceNotFoundException`，业务错误抛 `BusinessException`
- **事务**: Service 层写操作加 `@Transactional`
- **DTO 分离**: 创建用 `CreateXxxRequest`（有 `@NotBlank` 等校验），更新用 `UpdateXxxRequest`（字段全部可选），响应用 `XxxResponse`
- **权限**: 从 `HttpServletRequest` 的 attribute 中获取 `userId` 和 `organizationId`（JWT 过滤器设置的）
- **白名单**: 新增需要匿名访问的接口时，同步更新 `SecurityConfig` 的 `requestMatchers(...).permitAll()`

### 匿名访问白名单（当前）

- `/feasibility/assessments/*/ovd-results/**`
- `/feasibility/ovd-results/*/quality-scores/**`
- `/feasibility/assessments/*/datasets/**`
- `/feasibility/assessments/*/resource-estimations/**`
- `/feasibility/assessments/*/implementation-plans/**`

### Vue（前端）

- **组件风格**: 使用 Vue 3 Composition API（`<script setup>`），不使用 Options API
- **API 封装**: 
  - 按模块分文件：`src/api/feasibility.js`、`src/api/index.js` 等
  - 统一使用 `import request from '@/utils/request'`
  - 导出对象形式：`export const feasibilityAPI = { method1, method2 }`
- **路由规范**: 
  - 在 `src/router/index.js` 注册，所有路由都是 Layout 的 children
  - 添加 `meta: { title, icon, requiresAuth: true }`
  - 侧边栏菜单在 `src/layout/index.vue` 的 `<el-menu>` 中添加 `<el-menu-item>`
- **请求配置**: 
  - baseURL 统一为 `/api/v1`（在 `utils/request.js` 配置）
  - Token 自动从 localStorage 读取并添加到 Authorization 头
  - 响应拦截器统一处理错误（401跳转登录，其他显示 ElMessage）
- **状态管理**: 使用 Pinia store，不使用 Vuex
- **样式规范**: 
  - 使用 `<style scoped>` 避免样式污染
  - 优先使用 Element Plus 组件，保持 UI 一致性
  - 响应式布局使用 CSS Grid 或 Flexbox
- **命名规范**:
  - 组件文件：PascalCase（如 `AssessmentList.vue`）
  - 变量/函数：camelCase（如 `loadAssessments`）
  - 常量：UPPER_SNAKE_CASE（如 `API_BASE_URL`）

### Python（算法服务）

- **路由模块化**: 新功能在 `routers/` 下新建文件，在 `main.py` 中 `include_router` 注册
- **参数传递**: MultipartFile 转发时数值参数必须用 `String.valueOf()` 转字符串，不能直接传 Integer

## 可行性评估模块约定

### 状态流转规则（2026-03-20更新）

评估状态（`AssessmentStatus`）有两条分支路径：

**桶A路径（OVD_AVAILABLE）**：
```
CREATED → PARSING → PARSED → OVD_TESTING → OVD_TESTED → 
EVALUATING → EVALUATED → COMPLETED
```

**非桶A路径（CUSTOM_LOW/MEDIUM/HIGH）**：
```
CREATED → PARSING → PARSED → OVD_TESTING → OVD_TESTED → 
EVALUATING → EVALUATED → DATASET_SEARCHED → AWAITING_USER_JUDGMENT → 
ESTIMATING → COMPLETED
```

任何阶段失败都应设置为 `FAILED`，不允许跳过中间状态。

### 四桶分类标准（2026-03-20更新）

从3桶改为4桶系统：

- **桶A (OVD_AVAILABLE)**: Precision > 0.7（OVD可直接使用）
- **桶B (CUSTOM_LOW)**: 数据集几乎一致，需少量微调（500张图片）
- **桶B+ (CUSTOM_MEDIUM)**: 数据集部分相关，需中等标注（1000张图片）
- **桶C (CUSTOM_HIGH)**: 数据集几乎不可用，需大量标注（2000张图片）
- **PENDING**: 初始状态，等待VLM评估后确定

**桶判定逻辑**：
- VLM评估后：`avgPrecision > 0.7` → `OVD_AVAILABLE`，否则 → `PENDING`
- 用户判断数据集匹配度后：
  - `ALMOST_MATCH` → `CUSTOM_LOW`
  - `PARTIAL_MATCH` → `CUSTOM_MEDIUM`
  - `NOT_USABLE` → `CUSTOM_HIGH`

### 实施计划生成规则（2026-03-20更新）

根据四桶分类动态生成阶段：
- **定制训练桶存在（CUSTOM_LOW/MEDIUM/HIGH）**: 生成"数据采集方案设计"、"数据标注"、"模型训练与调优"三个阶段
  - 数据标注天数 = max(各类别人天) + 7天缓冲
  - 模型训练天数 = max(各类别GPU时) / 8 + 5天缓冲
- **桶A存在**: 生成"OVD配置与校验"阶段（固定4天）
- **始终生成**: "系统集成测试"（7天）、"部署上线"（4天）、"持续运维"（0天）

### 数据存储约定

- **tasks字段**: 存储为JSON字符串数组，如 `["任务1", "任务2"]`
- **dependencies字段**: 存储为逗号分隔的阶段名称，如 `"数据采集方案设计,数据标注"`
- **bboxJson字段**: 存储为JSON字符串，如 `"[{\"x\":10,\"y\":20,\"width\":100,\"height\":50}]"`

### 前端步骤映射

前端详情页步骤条（el-steps）的 active 状态根据后端 status 映射：
```javascript
const stepMap = {
  CREATED: 0, PARSING: 0, PARSED: 1,
  OVD_TESTING: 1, OVD_TESTED: 2,
  EVALUATING: 2, EVALUATED: 3,
  ESTIMATING: 3, COMPLETED: 5, FAILED: -1
}
```

### 按钮禁用逻辑

前端操作按钮的禁用状态必须严格按照状态流转：
- 解析按钮：仅在 `CREATED` 时可用
- OVD测试按钮：仅在 `PARSED` 时可用
- 评估按钮：仅在 `OVD_TESTED` 时可用
- 估算按钮：仅在 `EVALUATED` 时可用
- 生成计划按钮：在 `EVALUATED` 或 `ESTIMATING` 时可用

## 单类别模型训练模块约定（2026-03-24新增）

### API 封装规范

- 模块API文件：`src/api/customModel.js`
- 导出函数形式（不使用对象包装）：
  ```javascript
  export function createTrainingTask(data) { ... }
  export function listTrainingTasks() { ... }
  ```

### 页面组件规范

- **ModelTraining.vue**: 左右分栏布局（el-row + el-col）
  - 左栏（span=10）：新建训练任务表单
  - 右栏（span=14）：训练任务列表表格 + 选中行详情
- **SingleClassDetection.vue**: 改造为支持多模型
  - 新增"检测模型"下拉框（分组显示：内置模型 + 自定义训练模型）
  - 原"模型选择"改名为"检测类别"
  - 使用 `computed` 动态计算当前模型的类别列表和模型路径
  - 使用 `watch` 监听模型切换，自动选中第一个类别

### 状态轮询规范

- 训练任务列表需要轮询更新进行中的任务状态
- 轮询间隔：5秒
- 轮询条件：存在 `PENDING/DOWNLOADING/CONVERTING/TRAINING` 状态的任务
- 自动停止：所有任务都不在进行中时清除定时器
- 组件卸载时必须清除定时器（`onUnmounted`）

### 路由跳转规范

- 训练完成后点击"去检测"按钮跳转：
  ```javascript
  router.push({ path: '/single-class-detection', query: { modelId: model.id } })
  ```
- 检测页面根据 `route.query.modelId` 自动选中对应模型：
  ```javascript
  if (route.query.modelId) {
    const targetId = `custom_${route.query.modelId}`
    if (allModels.value.find(m => m.id === targetId)) {
      selectedModelId.value = targetId
    }
  }
  ```

### 数据结构约定

统一模型数据格式（内置 + 自定义）：
```javascript
{
  id: 'builtin' | `custom_${modelId}`,
  modelName: string,
  modelPath: string,
  classes: [
    { classId: number, className: string, cnName: string }
  ]
}
```



## 2026-03-25 新增规范

### CGLIB 代理与异常捕获（重要）

当 Spring 类被 CGLIB 代理（例如类中有方法带 `@Transactional`）时，异常可能在代理层被拦截，绕过 Service/Controller 内部的 catch 块。

**规则：调用外部 API 时，在 `restTemplate.exchange()` 调用处直接捕获具体异常，不依赖外层 catch 兜底。**

```java
// ✅ 正确：在调用点直接捕获
ResponseEntity<String> response;
try {
    response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
} catch (HttpClientErrorException.NotFound e404) {
    log.warn("资源不存在: {}", url);
    return createEmptyResult();
}

// ❌ 错误：依赖外层 catch（可能被 CGLIB 代理绕过）
try {
    ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
    // ... 处理 response
} catch (Exception e) {
    // 这个 catch 可能永远执行不到
    return createEmptyResult();
}
```

### 第三方 API JSON 响应解析

不要假设第三方 API 返回格式固定。Label Studio 的 tasks API 在有数据时返回数组 `[...]`，无数据时返回 404。

**规则：解析前判断响应体是数组还是对象。**

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

### 外键删除顺序

删除有外键依赖的数据时，必须按子表→父表顺序。

**删除项目的完整顺序：**
```
1. DetectionResult      (外键依赖 ProjectImage + AnnotationTask)
2. ProjectImage         (外键依赖 Project)
3. LS Local Storage     (外部系统，通过 API 删除)
4. LS Project           (外部系统，通过 API 删除)
5. Project 实体          (JPA delete，级联删除 AnnotationTask)
```

**重新执行自动标注的清理顺序：**
```
1. DetectionResult      (deleteByProjectId)
2. AnnotationTask       (deleteByProjectId)
```

### JPQL DELETE/UPDATE 必须加 @Modifying

```java
// ✅ 正确
@Modifying
@Query("DELETE FROM DetectionResult dr WHERE dr.image.project.id = :projectId")
void deleteByProjectId(@Param("projectId") Long projectId);

// ❌ 错误：缺少 @Modifying，运行时会报错
@Query("DELETE FROM DetectionResult dr WHERE dr.image.project.id = :projectId")
void deleteByProjectId(@Param("projectId") Long projectId);
```

### DTO 字段映射

实体新增字段后，必须同步更新 Response DTO 和转换方法。前后端字段名不一致时用 `@JsonProperty`：

```java
// 后端实体字段: lsProjectId
// 前端期望字段: labelStudioProjectId
@JsonProperty("labelStudioProjectId")
private Long lsProjectId;
```

### 前端 URL 拼接空值防护

```javascript
// ✅ 正确：空值判断
url: img.filePath ? `/api/v1/files/${img.filePath}` : ''

// ❌ 错误：可能拼出 /api/v1/files/undefined
url: `/api/v1/files/${img.filePath}`
```

### Label Studio API 注意事项

| API | 行为 |
|-----|------|
| `GET /api/projects/{id}/tasks` | 空项目返回 **404**，有 tasks 返回 **JSON 数组**（非对象） |
| `DELETE /api/projects/{id}` | **不会**自动删除关联的 local storage |
| `GET /api/storages/localfiles?project={id}` | 获取项目 local storage 列表 |
| `DELETE /api/storages/localfiles/{id}` | 删除单个 local storage |