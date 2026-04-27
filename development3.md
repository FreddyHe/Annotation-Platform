# 动物检测 7 自动标注问题 - 修复方案

生成时间: 2026-04-24
适用范围: P0 + P1 全部修复，按优先级排序

---

## 0. 对原报告的修正

原报告 §3.1 把根因归为"`DetectionResultRepository.findByProjectIdAndType` 缺 `JOIN FETCH`"。

仔细看过代码后，**这只是表象的一半**。真正触发 `LazyInitializationException` 的链路是：

```
startAutoAnnotationJob()      <- @Async，无事务
  └─ syncPredictionsToLabelStudio()   <- 没有 @Transactional
       ├─ detectionResultRepository.findByProjectIdAndType(...)
       │     <- 这次查询内部有临时事务，结束后 Session 就关了
       └─ preparePredictions(keptDetections)
             └─ detection.getImage().getFilePath()   <- no Session 异常
```

所以只加 `JOIN FETCH` 是不够的——`preparePredictions` 里还有其他懒加载路径（比如
`image.getProject().getId()` 在 `getLocalImagePath` 里也被调用过）。正确做法是：

- **repository 层加 `JOIN FETCH`**（解决当前的 `image` 访问）
- **service 方法加 `@Transactional`**（防御所有未来新增的懒加载字段）

两者都要做。

---

## 1. P0-1：Label Studio predictions 同步失败

### 1.1 修改 `DetectionResultRepository.java`

新增一个带 `JOIN FETCH` 的查询方法。**不要**修改现有的 `findByProjectIdAndType`，因为其他地
方（比如 `ProjectController.getProjectImages` 里查 DINO/VLM 分组统计）并不需要 image 的全
量数据，改成 fetch join 会让那些查询变慢。

在文件末尾，`deleteByProjectId` 之前插入：

```java
/**
 * 与 findByProjectIdAndType 相同，但通过 JOIN FETCH 一次性把 image 和 image.project
 * 加载出来，避免在事务外访问触发 LazyInitializationException。
 * 专用于需要构造 Label Studio predictions payload 的场景。
 */
@Query("""
        SELECT dr
        FROM DetectionResult dr
        JOIN FETCH dr.image img
        JOIN FETCH img.project p
        WHERE p.id = :projectId AND dr.type = :type
        """)
List<DetectionResult> findByProjectIdAndTypeWithImage(
        @Param("projectId") Long projectId,
        @Param("type") DetectionResult.ResultType type);
```

> 说明：`img.project` 也 fetch 是因为 `getLocalImagePath(images)` 会访问
> `images.get(0).getProject().getId()`，不 fetch 会在另一条路径上再抛一次。

### 1.2 修改 `AutoAnnotationService.java`

#### 改动 A：把 `syncPredictionsToLabelStudio` 标成 `@Transactional(readOnly = true)`

理论上只读事务就够了（这个方法没有写 DB），但实际上它还要把结果传给 `preparePredictions`
去构造 LS payload，期间会持续访问懒加载字段。加 `@Transactional(readOnly = true)` 最合适。

把这一行：

```java
@SuppressWarnings("unchecked")
private void syncPredictionsToLabelStudio(Project project, List<ProjectImage> images, Long userId) {
```

改为：

```java
@Transactional(readOnly = true)
@SuppressWarnings("unchecked")
public void syncPredictionsToLabelStudio(Project project, List<ProjectImage> images, Long userId) {
```

> 注意两个关键点：
> - 必须改成 `public`，否则 Spring AOP 的事务代理不生效（Spring 只代理 public 方法）。
> - 因为调用方 `runThresholdMode` / `runVlmMode` 是在**同一个类内部**直接调用
>   `syncPredictionsToLabelStudio(...)`，这种 self-invocation 会**绕过 Spring 代理**，
>   `@Transactional` 不会生效。下面的改动 B 会解决这个问题。

#### 改动 B：解决 self-invocation 问题

最简单的办法是注入自己。在类的字段声明区加一个 self 引用：

```java
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;

// ... 在类内，已有的字段下面加：

@Autowired
@Lazy
private AutoAnnotationService self;
```

然后把 `runThresholdMode` 和 `runVlmMode` 里两处调用从：

```java
syncPredictionsToLabelStudio(project, images, job.getUserId());
```

改为：

```java
self.syncPredictionsToLabelStudio(project, images, job.getUserId());
```

一共两处。

#### 改动 C：把 `findByProjectIdAndType` 改为 `findByProjectIdAndTypeWithImage`

在 `syncPredictionsToLabelStudio` 方法体内，找到：

```java
List<DetectionResult> keptDetections = detectionResultRepository
        .findByProjectIdAndType(project.getId(), DetectionResult.ResultType.VLM_CLEANING);
```

改为：

```java
List<DetectionResult> keptDetections = detectionResultRepository
        .findByProjectIdAndTypeWithImage(project.getId(), DetectionResult.ResultType.VLM_CLEANING);
```

### 1.3 验证方式

改完重新跑一次自动标注，检查：

1. `/tmp/springboot.log` 不再出现 `could not initialize proxy ... no Session`
2. `sqlite3 label_studio.sqlite3 "SELECT COUNT(*) FROM prediction WHERE project_id=191"` > 0
3. 前端 Label Studio 项目 191 打开，每张图上应能看到预标注框

---

## 2. P0-2：项目删除接口没清理 `auto_annotation_jobs`

### 2.1 修改 `AutoAnnotationJobRepository.java`

在接口内添加：

```java
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// ...

@Modifying
@Transactional
@Query("DELETE FROM AutoAnnotationJob j WHERE j.project.id = :projectId")
void deleteByProjectId(@Param("projectId") Long projectId);

long countByProjectId(Long projectId);
```

> `countByProjectId` 方法名符合 Spring Data 约定，不用写 `@Query`。
> 这里显式写 `@Transactional` 是因为 `@Modifying` 查询必须在事务内。

### 2.2 修改 `ProjectController.java`

#### 改动 A：注入 repository

在字段声明区（`autoAnnotationJobRepository` 目前还没被注入，需要加）：

```java
private final com.annotation.platform.repository.AutoAnnotationJobRepository autoAnnotationJobRepository;
```

#### 改动 B：修正删除顺序

目前 `deleteProject` 的顺序是：

1. DetectionResult
2. ProjectImage
3. InferenceDataPoint / EdgeDeployment / ProjectConfig / IterationRound
4. LS 清理
5. Project（级联删 AnnotationTask、ModelTrainingRecord）

`auto_annotation_jobs` 外键指向 `projects`，所以必须在第 5 步**之前**删掉它。

找到这一段：

```java
// 2.5 清理迭代飞轮新增数据，避免 iteration_rounds 外键阻塞项目删除
inferenceDataPointRepository.deleteByProjectId(id);
edgeDeploymentRepository.deleteByProjectId(id);
projectConfigRepository.deleteById(id);
iterationRoundRepository.deleteByProjectId(id);
```

改为：

```java
// 2.5 清理迭代飞轮新增数据，避免 iteration_rounds 外键阻塞项目删除
inferenceDataPointRepository.deleteByProjectId(id);
edgeDeploymentRepository.deleteByProjectId(id);

// projectConfigRepository.deleteById 在记录不存在时会抛 EmptyResultDataAccessException
// 先判断存在再删
if (projectConfigRepository.existsById(id)) {
    projectConfigRepository.deleteById(id);
}

iterationRoundRepository.deleteByProjectId(id);

// 2.6 清理 auto_annotation_jobs，外键指向 projects，必须在 projectRepository.delete 之前
long autoJobCount = autoAnnotationJobRepository.countByProjectId(id);
if (autoJobCount > 0) {
    autoAnnotationJobRepository.deleteByProjectId(id);
    log.info("已删除项目自动标注任务: projectId={}, count={}", id, autoJobCount);
}
```

> 注意：我额外加了 `projectConfigRepository.existsById` 判断——目前这行代码如果项目没有
> config 记录会直接抛异常导致删除失败，是一个潜在隐患，一起修掉。

### 2.3 顺手补的回归测试（可选但强烈建议）

在 `backend-springboot/src/test/java/com/annotation/platform/controller/ProjectControllerDeleteTest.java` 新增：

```java
@Test
void deleteProject_shouldCascadeAutoAnnotationJobs() {
    // 1. 建 project
    // 2. 建 2 条 auto_annotation_jobs 指向该 project
    // 3. 调 DELETE /projects/{id}
    // 4. 断言 project 被删，auto_annotation_jobs 里没有残留
}
```

---

## 3. P1-1：前端进度条语义错误

### 3.1 后端：区分两种进度（可选，建议做）

目前 `AutoAnnotationJob.progressPercent` 是"流程阶段进度"，但前端日志行展示的是
`processedImages/totalImages`。两者混在一起用户分不清。

建议在 `AutoAnnotationService.toJobStatus` 里补两个字段，给前端留出表达空间：

```java
// 在 toJobStatus 方法里，现有 put 之后追加：
Integer processed = job.getProcessedImages() == null ? 0 : job.getProcessedImages();
Integer total = job.getTotalImages() == null ? 0 : job.getTotalImages();
double imageProgress = total > 0 ? (processed * 100.0 / total) : 0.0;
status.put("imageProgressPercent", imageProgress);
status.put("stageProgressPercent", job.getProgressPercent());  // 明确命名
```

保留 `progressPercent` 字段做兼容，含义仍等同 `stageProgressPercent`，前端渐进替换。

### 3.2 前端：拆分主进度条

修改 `frontend-vue/src/components/AlgorithmTasks.vue`。

#### 改动 A：新增 `imageProgressPercent` 计算属性

在 `progressPercent` computed 下方加：

```javascript
const imageProgressPercent = computed(() => {
  if (!jobStatus.value) return 0
  const processed = jobStatus.value.processedImages || 0
  const total = jobStatus.value.totalImages || props.project.totalImages || 0
  if (total <= 0) return 0
  return Math.min(100, Math.round(processed * 100 / total))
})

const stageLabel = computed(() => {
  const stage = jobStatus.value?.currentStage
  const map = {
    INIT: '初始化',
    DINO: 'DINO 目标检测',
    VLM: 'VLM 智能清洗',
    THRESHOLD_FILTER: '阈值过滤',
    SYNC: '同步到 Label Studio'
  }
  return map[stage] || '准备中'
})
```

#### 改动 B：替换进度区块

把当前这一段：

```vue
<div v-if="isProcessing" style="margin-top: 12px;">
  <div class="progress-info">
    <span class="progress-label">{{ currentStepText }}</span>
    <span class="progress-percent">{{ progressPercent }}%</span>
  </div>
  <div v-if="jobStatus" class="job-metrics">
    <span>{{ jobStatus.processedImages || 0 }}/{{ jobStatus.totalImages || 0 }} 张</span>
    <span>保留 {{ jobStatus.keptDetections || 0 }}</span>
    <span>舍弃 {{ jobStatus.discardedDetections || 0 }}</span>
  </div>
  <el-progress 
    :percentage="progressPercent" 
    :stroke-width="10"
  />
</div>
```

替换为：

```vue
<div v-if="isProcessing" style="margin-top: 12px;">
  <!-- 阶段指示器 -->
  <div class="stage-indicator">
    <el-tag size="small" type="info">当前阶段: {{ stageLabel }}</el-tag>
    <span class="stage-progress-hint">流程总进度 {{ progressPercent }}%</span>
  </div>

  <!-- 主进度条：图像处理进度 -->
  <div class="progress-info" style="margin-top: 12px;">
    <span class="progress-label">图像处理进度</span>
    <span class="progress-percent">
      {{ jobStatus?.processedImages || 0 }} / {{ jobStatus?.totalImages || 0 }}
      ({{ imageProgressPercent }}%)
    </span>
  </div>
  <el-progress
    :percentage="imageProgressPercent"
    :stroke-width="10"
    :status="jobStatus?.currentStage === 'DINO' ? '' : 'success'"
  />

  <!-- 副指标 -->
  <div v-if="jobStatus" class="job-metrics" style="margin-top: 8px;">
    <span>保留 {{ jobStatus.keptDetections || 0 }}</span>
    <span>舍弃 {{ jobStatus.discardedDetections || 0 }}</span>
  </div>
</div>
```

并在 `<style scoped>` 内追加：

```css
.stage-indicator {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--gray-50);
  border-radius: var(--radius-sm);
}
.stage-progress-hint {
  font-size: 12px;
  color: var(--gray-500);
}
```

这样用户看到的就是：
- 阶段徽标：明确当前在哪一步
- 主进度条：真实的图像处理进度（1004 张里处理到第几张）
- 小字提示：整体流程在 13% / 50% / 85%

### 3.3 兜底保障

如果你觉得后端改动先暂缓，只改前端 `imageProgressPercent` 仍然可用——因为
`jobStatus.processedImages/totalImages` 这两个字段后端已经在 `toJobStatus` 输出了。

---

## 4. P1-2：DINO 串行调用导致吞吐低

### 4.1 第一阶段：复用 `httpx.AsyncClient` + 有限并发（推荐先做）

修改 `algorithm-service/routers/dino.py`。

#### 改动 A：重写 `call_dino_service` 让它接收外部 client

```python
async def call_dino_service_single(
    client: httpx.AsyncClient,
    image_path: str,
    labels: List[str],
    box_threshold: float = 0.3,
    text_threshold: float = 0.25,
) -> Dict[str, Any]:
    """单图推理，复用外部 httpx client。"""
    dino_url = "http://127.0.0.1:5003/predict"
    resolved_image_path = resolve_image_path(image_path)

    if not os.path.exists(resolved_image_path):
        logger.error(f"Image file not found: {resolved_image_path}")
        return {
            "image_path": image_path,
            "detections": [],
            "labels": labels,
            "error": f"File not found: {resolved_image_path}",
        }

    try:
        with open(resolved_image_path, "rb") as f:
            image_data = f.read()

        text_prompt = " . ".join(labels)
        if not text_prompt.endswith("."):
            text_prompt += "."

        response = await client.post(
            dino_url,
            files={
                "image": (
                    resolved_image_path.split("/")[-1],
                    image_data,
                    "image/jpeg",
                )
            },
            data={
                "text_prompt": text_prompt,
                "box_threshold": str(box_threshold),
                "text_threshold": str(text_threshold),
            },
        )

        if response.status_code != 200:
            logger.error(f"DINO service error: {response.status_code} - {response.text}")
            return {
                "image_path": image_path,
                "detections": [],
                "labels": labels,
                "error": f"DINO service error: {response.status_code}",
            }

        data = response.json()
        detections_raw = data.get("detections", [])

        with Image.open(resolved_image_path) as img:
            img_w, img_h = img.size

        converted_detections = []
        for det in detections_raw:
            box = det.get("box", [])
            if len(box) == 4:
                cx, cy, w, h = box
                x_min = (cx - w / 2) * img_w
                y_min = (cy - h / 2) * img_h
                abs_w = w * img_w
                abs_h = h * img_h
                converted_detections.append({
                    "bbox": [x_min, y_min, abs_w, abs_h],
                    "label": det.get("label", "unknown"),
                    "score": det.get("logit_score", det.get("score", 0.0)),
                })

        return {
            "image_path": image_path,
            "detections": converted_detections,
            "labels": labels,
        }
    except Exception as e:
        logger.error(f"Error calling DINO service for {image_path}: {e}")
        return {
            "image_path": image_path,
            "detections": [],
            "labels": labels,
            "error": str(e),
        }
```

保留原来的 `call_dino_service` 做兼容，但内部改成转发新函数（避免其他 router 引用出错）。

#### 改动 B：重写 `run_dino_detection_task` 用 semaphore 并发

```python
DINO_CONCURRENCY = 4  # GPU 吃得住 4，从这里起步调优

async def run_dino_detection_task(
    task_id: str,
    project_id: int,
    image_paths: List[str],
    labels: List[str],
    box_threshold: float,
    text_threshold: float,
):
    try:
        await task_manager.set_task_running(task_id)

        total_images = len(image_paths)
        failed_images = 0
        last_error = None
        processed_counter = 0
        counter_lock = asyncio.Lock()

        semaphore = asyncio.Semaphore(DINO_CONCURRENCY)

        # 共享一个 client：连接池复用、避免反复握手
        async with httpx.AsyncClient(timeout=60.0, trust_env=False) as client:

            async def worker(image_path: str):
                nonlocal failed_images, last_error, processed_counter
                if await task_manager.is_task_cancelled(task_id):
                    return

                async with semaphore:
                    if await task_manager.is_task_cancelled(task_id):
                        return
                    result = await call_dino_service_single(
                        client, image_path, labels, box_threshold, text_threshold
                    )

                async with counter_lock:
                    if result.get("error"):
                        failed_images += 1
                        last_error = result["error"]
                    await task_manager.add_task_result(task_id, result)
                    processed_counter += 1
                    await task_manager.update_task_progress(task_id, processed_counter)

            await asyncio.gather(*(worker(p) for p in image_paths))

        if await task_manager.is_task_cancelled(task_id):
            await task_manager.set_task_cancelled(task_id)
            logger.info(f"Task {task_id}: Cancelled by user")
            return

        if total_images > 0 and failed_images == total_images:
            message = f"DINO service failed for all images: {last_error or 'unknown error'}"
            await task_manager.set_task_failed(task_id, message)
            logger.error(f"Task {task_id}: {message}")
            return

        await task_manager.set_task_completed(task_id)
        logger.info(
            f"Task {task_id}: DINO detection completed (failed={failed_images}/{total_images})"
        )
    except Exception as e:
        logger.error(f"Task {task_id}: DINO detection failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))
```

**两个关键细节：**

1. 进度计数用 `processed_counter` 而不是遍历下标 `idx`——并发下顺序不固定，前端看到的
   "已处理 N 张"必须是真实完成数。
2. `counter_lock` 里串行化 `add_task_result` + `update_task_progress`，避免 `task_manager`
   在多 worker 下丢数据。

#### 改动 C：结果顺序保持

原实现是"图片 → 结果"一一对应顺序。改成并发后顺序会乱。如果下游
（`AutoAnnotationService.saveDinoResults` / `extractDetectionsFromResults`）依赖顺序
的话要小心——我看了 Java 端代码，它是通过 `image_path` 和 `image_name` 去 `ProjectImage` 表
里反查的，**不依赖顺序**，所以并发安全。

### 4.2 第二阶段：DINO Flask 服务改成批量接口（长期，不在本次修复内）

`dino_model_server.py` 目前 `/predict` 只接一张图。真正把 GPU 吃满需要：

1. 新增 `/predict_batch` 接口
2. 用 `torch.stack` 把 N 张图拼成一个 batch tensor
3. 一次 forward 跑完，拆分结果返回

这个改动大，建议先做 4.1 观察 GPU 利用率，再决定是否有必要做批量化。

### 4.3 验证方式

```bash
# 改动前基线（你报告里的数字）
# 1004 张 DINO ≈ 178s，约 5.6 张/秒

# 改动后预期
# 并发 4 下，单张耗时应从 170ms 降到 ~50-80ms，吞吐应到 12-20 张/秒
# 1004 张应能在 60s 内完成

# 跑的时候另开一个终端观察：
nvidia-smi dmon -s u
# util.gpu 应该从 ~20% 提升到 ~60-80%
```

---

## 5. 顺手修的 bug（本次可选）

### 5.1 `saveCleanedResults` 静默吞异常

当前 `AutoAnnotationService.saveCleanedResults` 在 catch 块里只 log 不抛，外层认为"保留了 kept 个"。实际上如果异常发生在循环中段，kept 计数已经部分增加但后续没 save 完。

建议改为：

```java
} catch (Exception e) {
    log.error("Error saving cleaned results: {}", e.getMessage(), e);
    throw new RuntimeException("保存 VLM 清洗结果失败: " + e.getMessage(), e);
}
```

这样失败会直接让整个 job 失败，而不是"看起来成功但数据不一致"。`saveDinoResults` 同样处理。

### 5.2 LS 项目删除的 404 其实是正常情况

`/tmp/springboot.log` 里删除 756/644/643 时刷出大量 `404 Not Found`，是因为这些项目的
`ls_project_id` 指向的 LS 项目早就被手工清掉了。`deleteProject` 里应该识别 404 并 info 级别
记录，而不是 warn/error 刷屏。

在 `LabelStudioProxyServiceImpl.deleteProject` 里：

```java
} catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
    log.info("Label Studio 项目已不存在，跳过: lsProjectId={}", lsProjectId);
    // 不再 throw，视同成功
} catch (Exception e) {
    log.warn("Label Studio 删除项目失败: lsProjectId={}, error={}", lsProjectId, e.getMessage());
    throw e;
}
```

`deleteLocalStorageByProject` 同样加 404 吞异常。

---

## 6. 修改顺序（建议）

按风险从低到高、收益从高到低：

1. **(P0)** §2 项目删除修复 —— 改动面小，纯新增，不会回归
2. **(P0)** §1 LS predictions 同步修复 —— 核心改动，需要在测试项目上验证一轮
3. **(P1)** §4.1 DINO 并发化 —— 有性能收益，但需要观察 GPU 是否稳定
4. **(P1)** §3 前端进度条拆分 —— 纯 UX，不影响逻辑
5. **(可选)** §5 周边小 bug

---

## 7. 回归测试清单

每一项改完都要跑一遍这套：

- [ ] 新建项目 → 上传 1004 张图 → 启动 DINO_VLM 自动标注 → 等完成 → LS 项目里能看到 predictions
- [ ] 上面流程完成后，删除该项目 → H2 里 `auto_annotation_jobs`/`detection_results`/`project_images` 都应为空
- [ ] 一个项目跑两次自动标注（`processRange=all`）→ 第二次应该清掉旧结果、重新跑
- [ ] 自动标注进行到 DINO 阶段时，点"取消任务" → 应该在 10s 内停下
- [ ] `nvidia-smi` 观察 DINO 阶段 GPU 利用率（预期 60%+）

以上。建议你先从 §2 开始动刀，跑通后再做 §1，这两个搞定 LS 就能正常用了。