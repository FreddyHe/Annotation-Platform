# 数据飞轮 P0+P1 完整迭代任务书

> 本文档承接 MVP 任务书,在 MVP 已经跑通的基础上补强飞轮,使其真正进入"可生产"状态。Agent 必须严格按本文档从头到尾执行,不得跳阶段、不得自行扩展范围。

---

## 0. 背景与前置条件

### 0.1 前置条件

执行本任务书前,必须满足:

- MVP 任务书已完成,验收报告已通过(见 `/tmp/flywheel_mvp_report.md`)
- 后端、前端、Label Studio、算法服务均在运行
- 至少有一个项目跑通过完整飞轮流程(LOW_B → 增量项目 → 人审 → webhook 回写)

### 0.2 本次任务的目标

修复 MVP 暴露的三个**生产不可用问题**,并补充必需的可视化能力:

| 编号 | 名称 | 优先级 | 性质 |
|------|------|--------|------|
| P0-1 | 增量项目自动配 webhook | P0 | 飞轮闭环依赖 |
| P0-2 | ls_task_id 加索引 + 专用查询 | P0 | 性能基础 |
| P0-3 | 主项目 lsProjectId 失效检测 + 自愈 | P0 | 数据可靠性 |
| P1-1 | **训练时聚合多 LS 项目 + feedback** | P1 | **核心闭环修复** |
| P1-2 | 增量审核中心(后端接口 + 前端 tab) | P1 | 可观测性 |
| P1-3 | 训练前数据预览(后端接口 + 前端确认对话框) | P1 | 用户决策支持 |

### 0.3 本次**不做**的事

- 不做 holdout 评估集与训练曲线对比
- 不做主动学习采样(熵/置信度边界)
- 不改 AutoML 逻辑
- 不做 webhook 签名校验(P2 再说)
- 不重构 ProjectController 的导出代码(单独看 export 决策结果)
- 不删除任何现有字段
- 不改 InferenceDataPoint 的 PoolType 枚举值
- 不改 VlmJudgeService 的判定逻辑

---

## 1. 执行阶段总览

```
阶段 0: 决策点(2 个问题必须先回答)
阶段 A: 环境复检(确认 MVP 改动还在,服务正常)
阶段 B: P0-1 增量项目自动 webhook
阶段 C: P0-2 ls_task_id 索引 + 专用查询
阶段 D: P0-3 主项目失效检测与修复接口
阶段 E: 编译 + 重启 + P0 端到端验证
阶段 F: P1-1 训练侧改造(聚合多 LS 项目 + feedback)
阶段 G: 编译 + 重启 + 训练流程验证
阶段 H: P1-2 增量审核中心(后端接口)
阶段 I: P1-2 增量审核中心(前端 tab)
阶段 J: P1-3 训练前数据预览(后端 + 前端)
阶段 K: 全流程端到端验证 + 验收报告
```

预估工期:**3-5 天**(顺利情况)。如果阶段 F 改训练逻辑出问题可能拉长到一周。

---

## 2. 阶段 0:决策点(必须先解决,否则后续全错)


### 决策 0.1: LS 主项目失效项目数量

执行(需要有效 JWT):

```bash
JWT=<你的 JWT>
ADMIN_TOKEN=3dd84879dff6fd5949dc1dd76edbecccac3f8524

> /tmp/flywheel_ls_health.tsv
echo -e "projectId\tname\tlsProjectId\thttpStatus" > /tmp/flywheel_ls_health.tsv

curl -s -H "Authorization: Bearer $JWT" "http://localhost:8080/api/v1/projects?size=200" \
  | jq -r '.data[]? // .[]? | select(.lsProjectId != null) | [.id, .name, .lsProjectId] | @tsv' \
  | while IFS=$'\t' read pid name lsid; do
    status=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Token $ADMIN_TOKEN" \
      "http://localhost:5001/api/projects/$lsid")
    echo -e "$pid\t$name\t$lsid\t$status" >> /tmp/flywheel_ls_health.tsv
  done

cat /tmp/flywheel_ls_health.tsv
echo "---"
echo "失效项目数:"
awk -F'\t' 'NR>1 && $4!="200" {print}' /tmp/flywheel_ls_health.tsv | wc -l
```

**记录到 `/tmp/flywheel_p1_decisions.md`**:

```
LS_HEALTH_TOTAL: <总数>
LS_HEALTH_DEAD: <失效数>
LS_HEALTH_DEAD_IDS: <失效项目 id 列表, 逗号分隔>
```

如果失效数 == 0,P0-3 自愈接口仍然要做(防御性),但不需要批量修复脚本。如果失效数 >= 1,P0-3 完成后必须执行批量修复并验证。

---

## 3. 阶段 A:环境复检

### A.1 检查 MVP 改动还在

```bash
LOG=/tmp/flywheel_p1_precheck.log
> $LOG

PROJECT_ROOT=/root/autodl-fs/Annotation-Platform

echo "===== A.1 MVP 新文件 =====" >> $LOG
for f in \
  entity/LsSubProject.java \
  repository/LsSubProjectRepository.java \
  service/IncrementalProjectService.java \
  controller/LabelStudioWebhookController.java ; do
  full=$PROJECT_ROOT/backend-springboot/src/main/java/com/annotation/platform/$f
  if [ -f "$full" ]; then echo "OK $f ($(wc -l < $full) lines)"; else echo "MISSING $f"; fi
done >> $LOG

echo "===== A.2 关键修改痕迹 =====" >> $LOG
grep -c "incrementalProjectService" \
  $PROJECT_ROOT/backend-springboot/src/main/java/com/annotation/platform/service/EdgeSimulatorService.java \
  >> $LOG
grep -c "incrementalProjectService" \
  $PROJECT_ROOT/backend-springboot/src/main/java/com/annotation/platform/service/VlmJudgeService.java \
  >> $LOG
grep -c "lowBBatchSize\|low_b_batch_size" \
  $PROJECT_ROOT/backend-springboot/src/main/java/com/annotation/platform/entity/ProjectConfig.java \
  >> $LOG
grep -c "ls-webhook" \
  $PROJECT_ROOT/backend-springboot/src/main/java/com/annotation/platform/config/SecurityConfig.java \
  >> $LOG

echo "===== A.3 服务状态 =====" >> $LOG
ss -tlnp 2>/dev/null | grep -E ":8080|:5001|:8001|:5003" >> $LOG

echo "===== A.4 现有增量项目快照(用于回归对照) =====" >> $LOG
curl -s -H "Authorization: Token 3dd84879dff6fd5949dc1dd76edbecccac3f8524" \
  "http://localhost:5001/api/projects?page_size=200" \
  | jq '.results[] | select(.title | contains("__增量")) | {id, title, task_number, num_tasks_with_annotations}' \
  >> $LOG

cat $LOG
```

### A.2 通过标准

- 4 个 MVP 新文件都 `OK`
- A.2 四项 grep 计数均 >= 1
- 后端 8080、LS 5001 都在监听

如果 MVP 改动缺失 → **停止**,先恢复 MVP 状态再开始 P0+P1。

---

## 4. 阶段 B:P0-1 增量项目自动配 webhook

### B.1 改动概览

**文件**:`IncrementalProjectService.java`

**目标**:在 `createIncrementalProject` 创建完 LS 项目并挂载 storage 之后,**自动调用 LS API 创建 webhook**。幂等(同 URL 不重复创建)。

**新增配置**:`application.yml` 加 `app.backend.webhook-url`

### B.2 application.yml 改动

**先 view** 确认 `app:` 段内容,然后在 `app:` 段下,与 `label-studio` 平级新增:

```yaml
app:
  # ...existing fields...

  backend:
    # 公开访问 URL, Label Studio 回调用. 同机部署时填 localhost:8080.
    # 如果 Label Studio 与 Spring Boot 跨机部署, 改成 Spring Boot 的对外可达地址.
    public-url: http://localhost:8080
    webhook-url: http://localhost:8080/api/v1/ls-webhook/annotation
```

**注意**:`app.backend.public-url` 这个字段先放着,本次只用 `webhook-url`,但留着以后扩展。

### B.3 IncrementalProjectService.java 改动

#### B.3.1 新增 @Value 注入

在类顶部 `@Value` 列表里加:

```java
    @Value("${app.backend.webhook-url:http://localhost:8080/api/v1/ls-webhook/annotation}")
    private String webhookUrl;
```

#### B.3.2 在 createIncrementalProject 末尾追加 webhook 配置

定位到方法 `createIncrementalProject`,找到这段:

```java
            try {
                labelStudioProxyService.mountLocalStorage(lsProjectId, uploadBasePath, userId);
            } catch (Exception ex) {
                log.warn("Failed to mount storage for incremental Label Studio project: {}", ex.getMessage());
            }

            LsSubProject sub = LsSubProject.builder()
```

**在 `mountLocalStorage` 的 try-catch 之后、`LsSubProject sub = ...` 之前**插入:

```java
            try {
                ensureWebhookConfigured(lsProjectId);
            } catch (Exception ex) {
                log.warn("Failed to configure webhook for incremental Label Studio project: lsProjectId={}, err={}",
                        lsProjectId, ex.getMessage());
            }

```

#### B.3.3 新增 ensureWebhookConfigured 方法

在类的末尾(最后一个 `}` 之前)追加:

```java
    /**
     * 给 LS 项目配置 webhook (幂等)。
     *
     * 流程:
     *   1. GET /api/webhooks/?project={id} 看是否已经有同 URL 的 webhook
     *   2. 没有则 POST /api/webhooks/ 创建
     *
     * Webhook 触发的事件: ANNOTATION_CREATED, ANNOTATION_UPDATED
     */
    private void ensureWebhookConfigured(Long lsProjectId) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("webhookUrl 未配置, 跳过 webhook 创建: lsProjectId={}", lsProjectId);
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 1. 查现有 webhook
        try {
            String listUrl = String.format("%s/api/webhooks/?project=%d", labelStudioUrl, lsProjectId);
            ResponseEntity<String> listResp = restTemplate.exchange(
                    listUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = listResp.getBody();
            if (body != null && !body.isBlank()) {
                com.alibaba.fastjson2.JSONArray arr = body.trim().startsWith("[")
                        ? JSON.parseArray(body)
                        : JSON.parseObject(body).getJSONArray("results");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject existing = arr.getJSONObject(i);
                        if (webhookUrl.equals(existing.getString("url"))) {
                            log.info("Webhook 已存在, 跳过创建: lsProjectId={}, webhookId={}",
                                    lsProjectId, existing.getLong("id"));
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("查询现有 webhook 失败 (可能 LS 不支持该端点), 直接尝试创建: {}", e.getMessage());
        }

        // 2. 创建新 webhook
        Map<String, Object> payload = new HashMap<>();
        payload.put("project", lsProjectId);
        payload.put("url", webhookUrl);
        payload.put("send_payload", true);
        payload.put("send_for_all_actions", false);
        payload.put("actions", List.of("ANNOTATION_CREATED", "ANNOTATION_UPDATED"));
        payload.put("is_active", true);

        String createUrl = labelStudioUrl + "/api/webhooks/";
        ResponseEntity<String> createResp = restTemplate.exchange(
                createUrl, HttpMethod.POST,
                new HttpEntity<>(JSON.toJSONString(payload), headers), String.class);
        log.info("创建 webhook 成功: lsProjectId={}, status={}", lsProjectId, createResp.getStatusCode());
    }
```

### B.4 验证(等阶段 E 一起跑)

阶段 E 推理产生新增量项目时,应当看到日志 `创建 webhook 成功: lsProjectId=...`。

---

## 5. 阶段 C:P0-2 ls_task_id 索引 + 专用查询

### C.1 修改 InferenceDataPoint.java

**定位** `@Table` 注解:

```java
@Table(name = "inference_data_points", indexes = {
        @Index(name = "idx_point_project_round", columnList = "project_id, round_id"),
        @Index(name = "idx_point_project_pool", columnList = "project_id, pool_type"),
        @Index(name = "idx_point_used", columnList = "used_in_round_id")
})
```

**替换为**(增加一个 idx_point_ls_task):

```java
@Table(name = "inference_data_points", indexes = {
        @Index(name = "idx_point_project_round", columnList = "project_id, round_id"),
        @Index(name = "idx_point_project_pool", columnList = "project_id, pool_type"),
        @Index(name = "idx_point_used", columnList = "used_in_round_id"),
        @Index(name = "idx_point_ls_task", columnList = "ls_task_id")
})
```

### C.2 修改 InferenceDataPointRepository.java

在接口里、最后一个 `@Modifying` 之前**追加**:

```java
    java.util.Optional<InferenceDataPoint> findByLsTaskId(Long lsTaskId);

    java.util.List<InferenceDataPoint> findByProjectIdAndPoolTypeAndHumanReviewed(
            Long projectId, PoolType poolType, Boolean humanReviewed);

    @Query("SELECT d FROM InferenceDataPoint d WHERE d.projectId = :projectId AND d.poolType IN :poolTypes")
    java.util.List<InferenceDataPoint> findByProjectIdAndPoolTypeIn(
            @Param("projectId") Long projectId,
            @Param("poolTypes") java.util.List<PoolType> poolTypes);
```

(后两个方法是给阶段 F 训练侧用的,提前加在这里。)

### C.3 修改 LabelStudioWebhookController.java

**定位**:

```java
            Optional<InferenceDataPoint> opt = inferenceDataPointRepository.findAll().stream()
                    .filter(point -> lsTaskId.equals(point.getLsTaskId()))
                    .findFirst();
```

**替换为**:

```java
            Optional<InferenceDataPoint> opt = inferenceDataPointRepository.findByLsTaskId(lsTaskId);
```

### C.4 验证(阶段 E 一起跑)

阶段 E 重新触发 webhook 时,日志里应看不到任何 `findAll()` 的踪迹,响应延迟应明显下降。

---

## 6. 阶段 D:P0-3 主项目失效检测与自愈

### D.1 改动总览

**改动 4 处**:

1. `IncrementalProjectService.syncTrustedPointsToMainProject` 加失效探测
2. `Project.java` 新增字段 `lsProjectStatus`(枚举)
3. 新增 `ProjectController` 端点 `POST /projects/{id}/repair-ls-binding`
4. 新增 `ProjectController` 端点 `GET /projects/{id}/ls-health`(供前端查状态)

### D.2 修改 Project.java

**定位** `lsProjectId` 字段:

```java
    @Column(name = "ls_project_id")
    private Long lsProjectId;
```

**之后追加**:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "ls_project_status", length = 20)
    @Builder.Default
    private LsProjectStatus lsProjectStatus = LsProjectStatus.UNKNOWN;
```

然后在 `Project.java` 内部、`ProjectType` 枚举之后追加:

```java
    public enum LsProjectStatus {
        UNKNOWN,    // 从未探测
        ACTIVE,     // 探测过, LS 项目存在
        DEAD,       // 探测过, LS 返回 404
        REPAIRED    // 曾经 DEAD, 已重建
    }
```

### D.3 修改 IncrementalProjectService.java

在 `syncTrustedPointsToMainProject` 方法的开头(原本检查 `project.getLsProjectId() == null` 之后),增加一段探活:

**原代码**:

```java
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        if (project.getLsProjectId() == null) {
            log.warn("Project has no Label Studio project, skip trusted point sync: projectId={}", projectId);
            return;
        }

        List<InferenceDataPoint> toSync = points.stream()
```

**替换为**:

```java
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        if (project.getLsProjectId() == null) {
            log.warn("Project has no Label Studio project, skip trusted point sync: projectId={}", projectId);
            return;
        }

        // P0-3: 主项目存活探测
        if (!isLsProjectAlive(project.getLsProjectId())) {
            if (project.getLsProjectStatus() != Project.LsProjectStatus.DEAD) {
                project.setLsProjectStatus(Project.LsProjectStatus.DEAD);
                projectRepository.save(project);
                log.error("主 Label Studio 项目失效, 标记为 DEAD: projectId={}, lsProjectId={}",
                        projectId, project.getLsProjectId());
            }
            log.warn("主 LS 项目 DEAD, 跳过 HIGH/LOW_A 同步: projectId={}", projectId);
            return;
        } else if (project.getLsProjectStatus() != Project.LsProjectStatus.ACTIVE
                && project.getLsProjectStatus() != Project.LsProjectStatus.REPAIRED) {
            project.setLsProjectStatus(Project.LsProjectStatus.ACTIVE);
            projectRepository.save(project);
        }

        List<InferenceDataPoint> toSync = points.stream()
```

然后在类末尾新增方法:

```java
    /**
     * 通过 GET /api/projects/{id} 探测 LS 项目是否存活 (200 即 alive)。
     * 用于 P0-3 失效检测。
     */
    public boolean isLsProjectAlive(Long lsProjectId) {
        if (lsProjectId == null) return false;
        try {
            String url = String.format("%s/api/projects/%d", labelStudioUrl, lsProjectId);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + adminToken);
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return false;
        } catch (Exception e) {
            log.warn("探测 LS 项目状态异常 (按未知处理, 不视为 dead): lsProjectId={}, err={}",
                    lsProjectId, e.getMessage());
            return true;  // 网络错误时不要误判 DEAD, 避免数据丢失
        }
    }

    /**
     * 重建主项目: 把 lsProjectId 置 null, 调 LabelStudioProxyService 重新同步,
     * 然后把所有"曾经同步过 DEAD 项目"的数据点 lsTaskId 也清掉, 让下一次推理重新同步。
     */
    @Transactional
    public Map<String, Object> repairMainLsBinding(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));

        Long oldLsProjectId = project.getLsProjectId();
        Map<String, Object> result = new HashMap<>();
        result.put("projectId", projectId);
        result.put("oldLsProjectId", oldLsProjectId);

        // 1. 置 null, 让 syncProjectToLS 重建
        project.setLsProjectId(null);
        project = projectRepository.save(project);

        Long newLsId = labelStudioProxyService.syncProjectToLS(project, userId);
        if (newLsId == null) {
            project.setLsProjectId(oldLsProjectId);  // 失败回滚
            projectRepository.save(project);
            result.put("status", "FAILED");
            result.put("message", "重建 LS 项目失败");
            return result;
        }

        // 2. 把所有 lsTaskId 非 null 的数据点的 lsTaskId 清掉
        //    下次推理会重新同步到新主项目
        int reset = resetLsTaskIdsForDeadProject(projectId);

        project.setLsProjectStatus(Project.LsProjectStatus.REPAIRED);
        projectRepository.save(project);

        // 3. 给新主项目挂 storage
        try {
            labelStudioProxyService.mountLocalStorage(newLsId, uploadBasePath, userId);
        } catch (Exception ignored) {}

        result.put("status", "OK");
        result.put("newLsProjectId", newLsId);
        result.put("resetDataPoints", reset);
        log.info("主 LS 项目重建完成: projectId={}, newLsProjectId={}, resetDataPoints={}",
                projectId, newLsId, reset);
        return result;
    }

    private int resetLsTaskIdsForDeadProject(Long projectId) {
        // 仅重置 HIGH / LOW_A / LOW_A_CANDIDATE (这些原本进主项目)
        // LOW_B 是进增量项目的, 不动
        List<InferenceDataPoint> points = inferenceDataPointRepository
                .findByProjectIdAndPoolTypeIn(projectId, List.of(
                        InferenceDataPoint.PoolType.HIGH,
                        InferenceDataPoint.PoolType.LOW_A,
                        InferenceDataPoint.PoolType.LOW_A_CANDIDATE));
        int count = 0;
        for (InferenceDataPoint p : points) {
            if (p.getLsTaskId() != null) {
                p.setLsTaskId(null);
                inferenceDataPointRepository.save(p);
                count++;
            }
        }
        return count;
    }
```

### D.4 修改 ProjectController.java

在 `ProjectController` 类内的合适位置(比如 `getReviewStats` 之后)追加:

```java
    /**
     * P0-3: 检查项目主 LS 项目存活状态。
     * 前端可在项目详情页用此判断是否需要提示用户"修复 LS 绑定"。
     */
    @GetMapping("/{id}/ls-health")
    public Result<java.util.Map<String, Object>> getLsHealth(@PathVariable Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));

        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("projectId", id);
        resp.put("lsProjectId", project.getLsProjectId());
        resp.put("lsProjectStatus", project.getLsProjectStatus() != null
                ? project.getLsProjectStatus().name() : "UNKNOWN");

        if (project.getLsProjectId() != null) {
            boolean alive = incrementalProjectService.isLsProjectAlive(project.getLsProjectId());
            resp.put("alive", alive);
            if (!alive && project.getLsProjectStatus() != Project.LsProjectStatus.DEAD) {
                project.setLsProjectStatus(Project.LsProjectStatus.DEAD);
                projectRepository.save(project);
            }
        } else {
            resp.put("alive", false);
        }
        return Result.success(resp);
    }

    /**
     * P0-3: 重建项目的主 LS 项目绑定。
     * 当 ls-health 返回 alive=false 时, 用户点击"修复"调用此接口。
     */
    @PostMapping("/{id}/repair-ls-binding")
    public Result<java.util.Map<String, Object>> repairLsBinding(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        java.util.Map<String, Object> result = incrementalProjectService.repairMainLsBinding(id, userId);
        return Result.success(result);
    }
```

注意类顶部需要补一个注入:

```java
    private final com.annotation.platform.service.IncrementalProjectService incrementalProjectService;
```

加到现有的 `RoundService` 之后。

## 7. 阶段 E:P0 编译 + 重启 + 验证

### E.1 编译

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests -q 2>&1 | tee /tmp/flywheel_p1_build.log
```

**通过标准**:`BUILD SUCCESS`。

**常见失败**:

- 找不到 `IncrementalProjectService.repairMainLsBinding` → 看你 D.3 的方法名拼写
- `Project.LsProjectStatus` 找不到 → 确认枚举写在 Project 类内部
- `findByLsTaskId` 找不到 → 确认 C.2 加在了 Repository 接口里(不是 impl)

### E.2 备份 + 重启

```bash
# 备份当前 jar (P0 完成后留存)
cp /root/autodl-fs/Annotation-Platform/backend-springboot/target/platform-backend-1.0.0.jar \
   /tmp/platform-backend-mvp.jar

kill $(lsof -ti:8080) 2>/dev/null; sleep 3
cd /root/autodl-fs/Annotation-Platform/backend-springboot
nohup java -jar target/platform-backend-1.0.0.jar --server.port=8080 \
  > /tmp/springboot.log 2>&1 &

for i in $(seq 1 30); do
  if grep -q "Started .* in " /tmp/springboot.log 2>/dev/null; then
    echo "started"; break
  fi
  sleep 1
done
curl -s http://localhost:8080/api/v1/actuator/health
```

### E.3 表结构检查

```bash
# 应该看到没有 HibernateException
grep -iE "HibernateException|cannot create|column .* not found" /tmp/springboot.log | head -5
# 期望: 空输出
```

### E.4 P0-2 索引验证

启动日志里 `idx_point_ls_task` 应被 Hibernate 创建(如果 `show-sql=true`):

```bash
grep -i "idx_point_ls_task" /tmp/springboot.log
```

如果 show-sql 关着看不到也没关系,继续往下。

### E.5 P0-3 失效项目 ls-health 测试

如果决策 0.2 中有失效项目(比如 769):

```bash
JWT=<...>
curl -s -H "Authorization: Bearer $JWT" \
  "http://localhost:8080/api/v1/projects/769/ls-health" | jq .
# 期望: alive=false, lsProjectStatus="DEAD"
```

**重建**:

```bash
curl -s -X POST -H "Authorization: Bearer $JWT" \
  "http://localhost:8080/api/v1/projects/769/repair-ls-binding" | jq .
# 期望: status="OK", 有 newLsProjectId
```

**再查一次**:

```bash
curl -s -H "Authorization: Bearer $JWT" \
  "http://localhost:8080/api/v1/projects/769/ls-health" | jq .
# 期望: alive=true, lsProjectStatus="REPAIRED"
```

### E.6 P0-1 webhook 自动配置验证

触发一次会产生 LOW_B 的推理(参考 MVP 任务书 G.2):

```bash
# ... 执行推理 ...

# 看新建增量项目时是否自动配了 webhook
grep "创建 webhook 成功\|Webhook 已存在" /tmp/springboot.log | tail -5
```

到 LS UI 里打开新增量项目 → Settings → Webhooks,应能看到一条 URL = `http://localhost:8080/api/v1/ls-webhook/annotation` 的记录。

### E.7 P0-2 webhook 性能验证

```bash
# 在 LS 里给某个增量项目 task 创建 annotation
# (curl 模拟见 MVP 任务书 H.2.1)

# 看后端日志, 应该没有 findAll 引发的全表扫描日志
grep "Webhook processed" /tmp/springboot.log | tail -3
# 响应时间(从 LS 发出 webhook 到这条 log)应该 <100ms
```

### E.8 P0 阶段通过标准

- [ ] E.1 编译 BUILD SUCCESS
- [ ] E.2 后端启动成功,健康检查 UP
- [ ] E.3 无 HibernateException
- [ ] E.5 失效项目能成功修复(若有)
- [ ] E.6 新增量项目日志包含"创建 webhook 成功"
- [ ] E.7 webhook 调用没有性能问题

---

## 8. 阶段 F:P1-1 训练侧改造(核心闭环修复)

### F.1 问题与目标

**当前 bug**:

- `TrainingService.startTraining` 只读主项目 LS task,**完全不读增量项目**
- 也不读 `inference_data_points` 表里 humanReviewed=true 的数据
- 飞轮收集的人审标签永远进不了训练

**目标**:

- 训练数据来源 = 主项目 annotation/prediction + 所有"已审"增量项目 annotation + feedback 池
- "已审"判定:`LsSubProject.status = REVIEWED`
- 未审增量项目的数据**跳过**,但记录跳过了多少,接口返回给前端

### F.2 改动总览

新增 `FormatConverterService.buildFlywheelDataset()` 方法,聚合三个数据源,然后修改 `TrainingService.startTraining` 调用它而不是 `convertLabelStudioToYOLO`。

| 数据源 | 来自 | 当 humanReviewed | 逻辑 |
|--------|------|------------------|------|
| 主项目 | LS API GET tasks | 不区分 | 沿用 annotation 优先 prediction fallback |
| 已审增量项目 | LS API GET tasks | =true | 必须走 annotation 路径 |
| 未审增量项目 | LS API GET tasks | n/a | **跳过** |
| feedback HIGH | inference_data_points | n/a | bbox 来自 inferenceBboxJson |
| feedback LOW_A | inference_data_points | n/a | 同上 |
| feedback LOW_B 已审 | inference_data_points | =true | 同上(bbox 已被 webhook 改写) |

但其实主项目 + 已审增量项目里的 prediction/annotation **本质就是** feedback 池里的数据(只是物理位置不同)。如果同时从两边拿,会重复——同一张图既被作为 LS task 拿一遍,又被作为 feedback point 拿一遍。

**因此设计成**:

```
方案 A (推荐): 只走 LS 一边
├── 主项目 (annotation 优先 prediction fallback)
├── 已审增量项目 (annotation only)
└── 跳过未审增量项目

方案 B: 只走 feedback 一边
├── inference_data_points 全表 (HIGH + LOW_A + 人审 LOW_B)
└── 跳过主项目和增量项目 LS

方案 C: 混合
├── 老的初始 1000 张图 (没经过推理) → 走 LS 主项目 annotation
└── 飞轮新数据 → 走 feedback 池
```

**采用方案 A**——理由:

- LS 的 annotation/prediction 是真实显示给标注员的数据,人审改的也在这里
- inference_data_points 里的 inferenceBboxJson 在 webhook 处理后会被改写成审核结果,所以是镜像数据
- 走 LS 的话,初始 1000 张人工标注的图(没进过推理流程,不在 inference_data_points 里)也能被自然采集

但是,LS 主项目里还有**老的"非飞轮"的 task**(MVP 之前就在那的)和**新的"飞轮"task**(`data._point_id` 字段非空)。这两类我们都要,逻辑上不区分。

最终方案:

```python
# 伪代码
dataset = []
# 1. 主项目 (复用 convertLabelStudioToYOLO 的核心逻辑)
dataset += convert(mainProject)
# 2. 所有 status=REVIEWED 的增量项目
for sub in lsSubProjects.findByStatus(REVIEWED):
    dataset += convert(sub)
# 3. 跳过 PENDING_REVIEW / PARTIAL 的, 但记下来
skipped = lsSubProjects.findByStatusIn(PENDING_REVIEW, PARTIAL)
return dataset, skipped
```

### F.3 修改 FormatConverterService.java

#### F.3.1 注入 LsSubProjectRepository

类顶部加:

```java
    @Autowired
    private com.annotation.platform.repository.LsSubProjectRepository lsSubProjectRepository;
```

#### F.3.2 把 convertLabelStudioToYOLO 拆成可复用单元

**目标**:抽出一个 `convertLsTasksToYoloFiles(tasks, imagesDir, labelsDir, labelMap, ...)` 方法,接受预先 fetch 好的 task 列表,把它们写成 YOLO 文件。

**步骤**:

定位 `convertLabelStudioToYOLO` 方法体里这段(从 `JsonNode annotations = fetchLabelStudioAnnotations(...)` 之后到方法末尾的 `createDataYaml(...)` 之前):

```java
        JsonNode annotations = fetchLabelStudioAnnotations(labelStudioProjectId, lsToken);

        int trainCount = 0;
        int valCount = 0;
        int totalAnnotations = 0;
        List<Object[]> validTasks = new ArrayList<>();

        for (JsonNode task : annotations) {
            // ... 解析 ...
        }

        // ... 切分 train/val ...

        for (int idx = 0; idx < validTasks.size(); idx++) {
            // ... 写文件 ...
        }
```

**抽成一个 private 方法**(按你的代码风格):

```java
    /**
     * 把已 fetch 的 LS tasks 转成 YOLO 文件并追加到指定 imagesDir/labelsDir。
     * 返回 (新增 train 张数, 新增 val 张数, 新增 annotation 数)。
     */
    private int[] writeLsTasksToYolo(JsonNode tasks,
                                     Path imagesDir,
                                     Path labelsDir,
                                     Map<String, Integer> labelMap,
                                     boolean annotationOnly,
                                     String filenamePrefix) throws Exception {
        List<Object[]> validTasks = new ArrayList<>();

        for (JsonNode task : tasks) {
            JsonNode annotationArray = task.get("annotations");
            JsonNode predictionArray = task.get("predictions");
            JsonNode resultSource = null;
            if (annotationArray != null && annotationArray.size() > 0) {
                resultSource = annotationArray.get(0);
            } else if (!annotationOnly && predictionArray != null && predictionArray.size() > 0) {
                resultSource = predictionArray.get(0);
            }
            if (resultSource == null) continue;

            JsonNode results = resultSource.get("result");
            if (results == null || results.size() == 0) continue;

            String imagePath = task.get("data").get("image").asText();
            Path sourceImage = resolveImagePath(imagePath);
            if (sourceImage == null || !Files.exists(sourceImage)) {
                log.warn("Image not found: {} (resolved: {})", imagePath, sourceImage);
                continue;
            }
            String fileName = sourceImage.getFileName().toString();
            validTasks.add(new Object[]{sourceImage, fileName, results});
        }

        int totalValid = validTasks.size();
        if (totalValid == 0) return new int[]{0, 0, 0};

        int valCount0 = Math.max(1, (int) Math.round(totalValid * 0.2));
        if (valCount0 >= totalValid) valCount0 = Math.max(0, totalValid - 1);
        int trainSplitEnd = totalValid - valCount0;

        java.util.Collections.shuffle(validTasks);

        int trainCount = 0, valCount = 0, totalAnnotations = 0;
        for (int idx = 0; idx < validTasks.size(); idx++) {
            Object[] item = validTasks.get(idx);
            Path sourceImage = (Path) item[0];
            String fileName = (filenamePrefix != null ? filenamePrefix : "") + (String) item[1];
            JsonNode results = (JsonNode) item[2];
            String split = idx < trainSplitEnd ? "train" : "val";

            Path targetImage = imagesDir.resolve(split).resolve(fileName);
            Files.copy(sourceImage, targetImage, StandardCopyOption.REPLACE_EXISTING);

            Path labelFile = labelsDir.resolve(split).resolve(
                    fileName.replaceFirst("\\.[^.]+$", ".txt"));
            List<String> yoloAnnotations = new ArrayList<>();

            for (JsonNode result : results) {
                if (!"rectanglelabels".equals(result.get("type").asText())) continue;

                JsonNode value = result.get("value");
                JsonNode x = value.get("x"), y = value.get("y");
                JsonNode width = value.get("width"), height = value.get("height");
                if (x == null || y == null || width == null || height == null) continue;

                double xNorm = x.asDouble() / 100.0;
                double yNorm = y.asDouble() / 100.0;
                double wNorm = width.asDouble() / 100.0;
                double hNorm = height.asDouble() / 100.0;
                double centerX = xNorm + wNorm / 2.0;
                double centerY = yNorm + hNorm / 2.0;

                JsonNode labelsNode = value.get("rectanglelabels");
                if (labelsNode == null || labelsNode.size() == 0) continue;
                String labelName = labelsNode.get(0).asText();
                Integer classId = labelMap.get(labelName);
                if (classId == null) {
                    log.warn("Label not found in label map: {}", labelName);
                    continue;
                }
                yoloAnnotations.add(String.format(Locale.US,
                        "%d %.6f %.6f %.6f %.6f", classId, centerX, centerY, wNorm, hNorm));
                totalAnnotations++;
            }
            Files.write(labelFile, yoloAnnotations);
            if ("train".equals(split)) trainCount++; else valCount++;
        }
        return new int[]{trainCount, valCount, totalAnnotations};
    }
```

(把原 `convertLabelStudioToYOLO` 里的解析-切分-写文件逻辑搬过来,加了 `annotationOnly` 标记和 `filenamePrefix` 用于不同来源避免文件名冲突。)

#### F.3.3 把原 convertLabelStudioToYOLO 改成调用新方法

定位原方法体里被抽出去的部分,**替换成**:

```java
        JsonNode annotations = fetchLabelStudioAnnotations(labelStudioProjectId, lsToken);
        int[] stats = writeLsTasksToYolo(annotations, imagesDir, labelsDir, labelMap, false, null);
        int trainCount = stats[0], valCount = stats[1], totalAnnotations = stats[2];
```

(这样 convertLabelStudioToYOLO 行为不变,但内部走新代码路径。)

#### F.3.4 新增 buildFlywheelDataset 方法

在类的合适位置(比如 buildFeedbackDataset 之后)新增:

```java
    /**
     * P1-1 飞轮训练数据组装。
     *
     * 数据来源:
     *  1. 主项目 LS (annotation 优先 prediction fallback)
     *  2. 所有 status=REVIEWED 的增量项目 (只取 annotation)
     *  3. 跳过 status=PENDING_REVIEW/PARTIAL 的增量项目
     *
     * 返回结果在 trainImages/valImages/totalAnnotations 之外,
     * 多记录 metadata: includedSubProjects / skippedSubProjects。
     */
    public DatasetConversionResult buildFlywheelDataset(Long projectId,
                                                        String lsToken,
                                                        String outputDir) throws Exception {
        com.annotation.platform.entity.Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));
        if (project.getLsProjectId() == null) {
            throw new RuntimeException("项目尚未同步到 Label Studio");
        }

        Path outputPath = Paths.get(outputDir);
        Path imagesDir = outputPath.resolve("images");
        Path labelsDir = outputPath.resolve("labels");
        Files.createDirectories(imagesDir.resolve("train"));
        Files.createDirectories(imagesDir.resolve("val"));
        Files.createDirectories(labelsDir.resolve("train"));
        Files.createDirectories(labelsDir.resolve("val"));

        List<com.annotation.platform.entity.ProjectLabel> labels = getProjectLabels(projectId);
        Map<String, Integer> labelMap = createLabelMap(labels);
        if (labelMap.isEmpty() && project.getLabels() != null) {
            int classId = 0;
            for (String labelName : project.getLabels()) labelMap.put(labelName, classId++);
        }

        DatasetConversionResult result = new DatasetConversionResult();
        result.setOutputPath(outputPath.toString());
        result.setLabelMap(labelMap);

        int totalTrain = 0, totalVal = 0, totalAnnotations = 0;
        List<Map<String, Object>> includedSubProjects = new ArrayList<>();
        List<Map<String, Object>> skippedSubProjects = new ArrayList<>();

        // 1. 主项目
        JsonNode mainTasks = fetchLabelStudioAnnotations(
                String.valueOf(project.getLsProjectId()), lsToken);
        int[] mainStats = writeLsTasksToYolo(mainTasks, imagesDir, labelsDir, labelMap, false, "main_");
        totalTrain += mainStats[0]; totalVal += mainStats[1]; totalAnnotations += mainStats[2];

        Map<String, Object> mainInfo = new HashMap<>();
        mainInfo.put("source", "main");
        mainInfo.put("lsProjectId", project.getLsProjectId());
        mainInfo.put("trainAdded", mainStats[0]);
        mainInfo.put("valAdded", mainStats[1]);
        includedSubProjects.add(mainInfo);

        // 2. 所有增量项目, 区分已审/未审
        List<com.annotation.platform.entity.LsSubProject> subs = lsSubProjectRepository
                .findByProjectIdAndSubType(projectId,
                        com.annotation.platform.entity.LsSubProject.SubType.INCREMENTAL);

        for (com.annotation.platform.entity.LsSubProject sub : subs) {
            if (sub.getStatus() == com.annotation.platform.entity.LsSubProject.Status.REVIEWED) {
                JsonNode subTasks = fetchLabelStudioAnnotations(
                        String.valueOf(sub.getLsProjectId()), lsToken);
                int[] subStats = writeLsTasksToYolo(subTasks, imagesDir, labelsDir, labelMap,
                        true /* annotationOnly */,
                        "sub_" + sub.getBatchNumber() + "_");
                totalTrain += subStats[0]; totalVal += subStats[1]; totalAnnotations += subStats[2];

                Map<String, Object> info = new HashMap<>();
                info.put("source", "incremental");
                info.put("batchNumber", sub.getBatchNumber());
                info.put("lsProjectId", sub.getLsProjectId());
                info.put("trainAdded", subStats[0]);
                info.put("valAdded", subStats[1]);
                includedSubProjects.add(info);
            } else {
                Map<String, Object> info = new HashMap<>();
                info.put("source", "incremental");
                info.put("batchNumber", sub.getBatchNumber());
                info.put("lsProjectId", sub.getLsProjectId());
                info.put("status", sub.getStatus().name());
                info.put("expectedTasks", sub.getExpectedTasks());
                info.put("reviewedTasks", sub.getReviewedTasks());
                skippedSubProjects.add(info);
            }
        }

        createDataYaml(outputPath, labelMap, imagesDir, labelsDir);
        result.setTrainImages(totalTrain);
        result.setValImages(totalVal);
        result.setTotalAnnotations(totalAnnotations);
        result.setIncludedSubProjects(includedSubProjects);
        result.setSkippedSubProjects(skippedSubProjects);
        log.info("Flywheel dataset built: projectId={}, train={}, val={}, anno={}, skipped={} subs",
                projectId, totalTrain, totalVal, totalAnnotations, skippedSubProjects.size());
        return result;
    }
```

#### F.3.5 扩展 DatasetConversionResult

在 `DatasetConversionResult` 内类新增两个字段:

```java
        private List<Map<String, Object>> includedSubProjects;
        private List<Map<String, Object>> skippedSubProjects;

        public List<Map<String, Object>> getIncludedSubProjects() { return includedSubProjects; }
        public void setIncludedSubProjects(List<Map<String, Object>> v) { this.includedSubProjects = v; }
        public List<Map<String, Object>> getSkippedSubProjects() { return skippedSubProjects; }
        public void setSkippedSubProjects(List<Map<String, Object>> v) { this.skippedSubProjects = v; }
```

### F.4 修改 TrainingService.java

#### F.4.1 改 startTraining 调 buildFlywheelDataset

定位:

```java
        FormatConverterService.DatasetConversionResult conversionResult =
                formatConverterService.convertLabelStudioToYOLO(projectId, labelStudioProjectId, lsToken, outputDir);
```

**替换为**:

```java
        FormatConverterService.DatasetConversionResult conversionResult =
                formatConverterService.buildFlywheelDataset(projectId, lsToken, outputDir);
```

(原 convertLabelStudioToYOLO 保留,因为可能别的地方还在用,但训练入口走新方法。)

#### F.4.2 在记录里保存 dataset 元信息

定位 ModelTrainingRecord 的 builder 那段,在 `.totalAnnotations(...)` 之后追加:

```java
                .testResults(objectMapper.writeValueAsString(java.util.Map.of(
                    "includedSubProjects", conversionResult.getIncludedSubProjects() != null
                            ? conversionResult.getIncludedSubProjects() : List.of(),
                    "skippedSubProjects", conversionResult.getSkippedSubProjects() != null
                            ? conversionResult.getSkippedSubProjects() : List.of()
                )))
```

(借用现有的 `testResults` 字段先存元信息,避免新加 schema。)

### F.5 验证(阶段 G 跑)

- 训练新发起后,`ModelTrainingRecord.testResults` 包含 includedSubProjects/skippedSubProjects
- 训练日志出现"Flywheel dataset built"
- 跳过的未审增量项目数量与 LS 实际状态一致

---

## 9. 阶段 G:编译 + 重启 + 训练验证

### G.1 编译重启(同 E.1, E.2)

### G.2 触发一次训练

```bash
# 找一个有已审增量项目 + 未审增量项目的 projectId
JWT=<...>
PROJECT_ID=<...>

curl -s -X POST -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  "http://localhost:8080/api/v1/projects/$PROJECT_ID/training/start" \
  -d '{
    "modelName": "flywheel_test",
    "epochs": 10,
    "batchSize": 16,
    "imageSize": 640,
    "modelType": "yolov8n",
    "device": "0"
  }' | tee /tmp/flywheel_p1_train_start.json
```

### G.3 验证 dataset 元信息

```bash
sleep 30
TASK_ID=$(jq -r '.data.taskId' /tmp/flywheel_p1_train_start.json)
RECORD_ID=$(jq -r '.data.recordId' /tmp/flywheel_p1_train_start.json)

curl -s -H "Authorization: Bearer $JWT" \
  "http://localhost:8080/api/v1/training/record/$RECORD_ID" | jq '.data.testResults'
```

期望 `testResults` 字段是一个 JSON 字符串,包含 `includedSubProjects` 和 `skippedSubProjects` 两个数组。

### G.4 验证训练数据集目录结构

```bash
TRAIN_DIR=/root/autodl-fs/Annotation-Platform/training_runs/project_$PROJECT_ID
ls $TRAIN_DIR/images/train/ | head -10
# 期望: 看到 main_xxx.jpg / sub_1_xxx.jpg / sub_3_xxx.jpg 这些前缀
```

### G.5 通过标准

- [ ] 训练任务能正常启动
- [ ] testResults 包含 includedSubProjects 和 skippedSubProjects
- [ ] 数据集图片名带 `main_` / `sub_N_` 前缀
- [ ] 等训练跑完看 mAP 是否合理(至少没崩溃)

---

## 10. 阶段 H:P1-2 增量审核中心(后端)

### H.1 新增接口

在 `ProjectController` 加:

```java
    /**
     * P1-2: 获取项目所有增量审核项目列表 (实时刷新审核进度)。
     */
    @GetMapping("/{id}/incrementals")
    public Result<java.util.Map<String, Object>> getIncrementals(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        java.util.Map<String, Object> result = incrementalProjectService
                .getIncrementalProjectsStatus(id, userId);
        return Result.success(result);
    }
```

(注意:`incrementalProjectService` 已经在阶段 D.4 注入过了。)

### H.2 验证

```bash
curl -s -H "Authorization: Bearer $JWT" \
  "http://localhost:8080/api/v1/projects/$PROJECT_ID/incrementals" | jq .
```

期望返回:

```json
{
  "code": "0",
  "data": {
    "reviewedIncrementals": [...],
    "pendingIncrementals": [...],
    "canUseAllIncrementals": false
  }
}
```

---

## 11. 阶段 I:P1-2 增量审核中心(前端)

### I.1 改动总览

- `frontend-vue/src/views/ProjectDetail.vue` 加一个新 tab "增量审核"
- 新增组件 `frontend-vue/src/components/IncrementalReviewCenter.vue`
- API 封装(找现有 axios 封装风格,加一个 `getIncrementals` 方法)

### I.2 view 现有 ProjectDetail.vue 找 tab 结构

先 view:

```bash
view /root/autodl-fs/Annotation-Platform/frontend-vue/src/views/ProjectDetail.vue
```

确定 tab 是用 `<el-tabs>` + `<el-tab-pane>` 组织的(几乎一定是),记录现有 tab 的 name 列表。

### I.3 新增 IncrementalReviewCenter.vue

路径:`frontend-vue/src/components/IncrementalReviewCenter.vue`

内容:

```vue
<template>
  <div class="incremental-review-center">
    <div class="header">
      <h3>增量审核中心</h3>
      <el-button size="small" :loading="loading" @click="fetchData">
        刷新
      </el-button>
    </div>

    <el-alert
      v-if="!loading && batches.length === 0"
      title="暂无增量审核项目"
      description="当 LOW_B 池数据攒满批次时,系统会自动创建待审项目"
      type="info"
      :closable="false"
    />

    <el-table v-else :data="batches" v-loading="loading">
      <el-table-column prop="batchNumber" label="批次" width="80" />
      <el-table-column prop="projectName" label="项目名称" />
      <el-table-column label="进度" width="200">
        <template #default="{ row }">
          <el-progress
            :percentage="progressPct(row)"
            :status="row.status === 'REVIEWED' ? 'success' : ''"
          />
          <span class="progress-text">
            {{ row.reviewedTasks }} / {{ row.expectedTasks }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" @click="openInLs(row)">打开 LS</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="summary" v-if="!loading && batches.length > 0">
      <el-tag type="success">已审 {{ reviewedCount }} 批</el-tag>
      <el-tag type="warning">待审 {{ pendingCount }} 批</el-tag>
      <el-tag>合计待审任务 {{ pendingTaskCount }} 张</el-tag>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getIncrementals } from '@/api/project'  // 调整为实际 API 路径

const props = defineProps({
  projectId: { type: [Number, String], required: true }
})

const LS_PUBLIC_URL = 'http://localhost:5001'  // TODO: 从 store / env 读

const loading = ref(false)
const reviewed = ref([])
const pending = ref([])

const batches = computed(() => {
  return [...reviewed.value, ...pending.value]
    .sort((a, b) => (b.batchNumber ?? 0) - (a.batchNumber ?? 0))
})
const reviewedCount = computed(() => reviewed.value.length)
const pendingCount = computed(() => pending.value.length)
const pendingTaskCount = computed(() =>
  pending.value.reduce((sum, b) =>
    sum + Math.max(0, (b.expectedTasks ?? 0) - (b.reviewedTasks ?? 0)), 0)
)

function progressPct(row) {
  if (!row.expectedTasks) return 0
  return Math.round((row.reviewedTasks / row.expectedTasks) * 100)
}
function statusType(s) {
  return s === 'REVIEWED' ? 'success' : s === 'PARTIAL' ? 'warning' : 'info'
}
function statusLabel(s) {
  return { REVIEWED: '已审', PARTIAL: '审核中', PENDING_REVIEW: '待审' }[s] || s
}
function openInLs(row) {
  window.open(`${LS_PUBLIC_URL}/projects/${row.lsProjectId}/data`, '_blank')
}

async function fetchData() {
  loading.value = true
  try {
    const res = await getIncrementals(props.projectId)
    reviewed.value = res.data?.reviewedIncrementals || []
    pending.value = res.data?.pendingIncrementals || []
  } catch (e) {
    ElMessage.error('加载增量审核列表失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
defineExpose({ fetchData })
</script>

<style scoped>
.incremental-review-center { padding: 16px; }
.header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.progress-text { margin-left: 8px; font-size: 12px; color: #666; }
.summary { margin-top: 16px; }
.summary .el-tag { margin-right: 8px; }
</style>
```

### I.4 在 ProjectDetail.vue 增加 tab

定位现有 `<el-tabs>` 块,加一个新 tab-pane:

```vue
<el-tab-pane label="增量审核" name="incremental">
  <IncrementalReviewCenter :project-id="projectId" v-if="activeTab === 'incremental'" />
</el-tab-pane>
```

并在 script 顶部:

```js
import IncrementalReviewCenter from '@/components/IncrementalReviewCenter.vue'
```

### I.5 API 封装

找到 `frontend-vue/src/api/project.js`(或类似),加一个方法:

```js
export function getIncrementals(projectId) {
  return request({
    url: `/projects/${projectId}/incrementals`,
    method: 'get'
  })
}
```

### I.6 验证

```bash
cd /root/autodl-fs/Annotation-Platform/frontend-vue
npm run build  # 或 npx vite build
# 期望: BUILD SUCCESS

# 重启 vite
pkill -f vite 2>/dev/null; sleep 2
nohup npx vite --host 0.0.0.0 --port 6006 > /tmp/frontend.log 2>&1 &
```

打开浏览器 `http://localhost:6006` → 进项目详情 → 切到"增量审核" tab → 应能看到 batches 列表。

---

## 12. 阶段 J:P1-3 训练前数据预览

### J.1 后端新增接口

在 `ProjectController` 加:

```java
    /**
     * P1-3: 训练前数据预览。
     * 返回训练将使用哪些数据 / 跳过哪些数据,
     * 供前端在用户点击"开始训练"前展示一个确认对话框。
     */
    @GetMapping("/{id}/training/preview")
    public Result<java.util.Map<String, Object>> trainingPreview(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");

        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));

        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("projectId", id);
        resp.put("projectName", project.getName());

        // 1. 主项目状态
        java.util.Map<String, Object> mainInfo = new java.util.HashMap<>();
        mainInfo.put("lsProjectId", project.getLsProjectId());
        mainInfo.put("alive", project.getLsProjectId() != null
                && incrementalProjectService.isLsProjectAlive(project.getLsProjectId()));
        if (project.getLsProjectId() != null && Boolean.TRUE.equals(mainInfo.get("alive"))) {
            try {
                java.util.Map<String, Object> stats =
                        labelStudioProxyService.getProjectReviewStats(project.getLsProjectId(), userId);
                mainInfo.put("totalTasks", stats.get("totalTasks"));
                mainInfo.put("annotatedTasks", stats.get("reviewedTasks"));
                mainInfo.put("predictionOnlyTasks",
                        toLong(stats.get("totalTasks"), 0L) - toLong(stats.get("reviewedTasks"), 0L));
            } catch (Exception e) {
                log.warn("获取主项目统计失败: {}", e.getMessage());
            }
        }
        resp.put("mainProject", mainInfo);

        // 2. 增量项目状态
        java.util.Map<String, Object> increments =
                incrementalProjectService.getIncrementalProjectsStatus(id, userId);
        resp.put("incrementals", increments);

        // 3. 估算训练集
        long mainAnno = toLong(mainInfo.get("annotatedTasks"), 0L);
        long mainPred = toLong(mainInfo.get("predictionOnlyTasks"), 0L);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> reviewedIncs =
                (java.util.List<java.util.Map<String, Object>>) increments.get("reviewedIncrementals");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> pendingIncs =
                (java.util.List<java.util.Map<String, Object>>) increments.get("pendingIncrementals");

        long fromIncrementals = reviewedIncs.stream()
                .mapToLong(i -> toLong(i.get("expectedTasks"), 0L)).sum();
        long skippedFromIncrementals = pendingIncs.stream()
                .mapToLong(i -> toLong(i.get("expectedTasks"), 0L)).sum();

        java.util.Map<String, Object> estimate = new java.util.HashMap<>();
        estimate.put("fromMainAnnotated", mainAnno);
        estimate.put("fromMainPrediction", mainPred);
        estimate.put("fromReviewedIncrementals", fromIncrementals);
        estimate.put("totalUsable", mainAnno + mainPred + fromIncrementals);
        estimate.put("skippedPendingIncrementals", skippedFromIncrementals);
        resp.put("estimatedTrainingSet", estimate);

        // 4. 警告
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (Boolean.FALSE.equals(mainInfo.get("alive"))) {
            warnings.add("主 Label Studio 项目已失效,请先点击'修复 LS 绑定'");
        }
        if (skippedFromIncrementals > 0) {
            warnings.add(String.format("有 %d 张未审核的增量数据将被跳过", skippedFromIncrementals));
        }
        if (mainAnno + mainPred + fromIncrementals == 0) {
            warnings.add("当前可用训练数据为 0,无法发起训练");
        }
        resp.put("warnings", warnings);
        resp.put("canStart", mainAnno + mainPred + fromIncrementals > 0
                && !Boolean.FALSE.equals(mainInfo.get("alive")));

        return Result.success(resp);
    }

    private long toLong(Object v, long defaultValue) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return defaultValue;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return defaultValue; }
    }
```

### J.2 前端在训练页加确认对话框

定位现有训练触发的页面/组件(可能是 `Training.vue` 或类似)。在用户点击"开始训练"按钮的处理函数里:

```js
async function handleStartTrainingClick() {
  // 1. 先获取预览
  const preview = await getTrainingPreview(projectId.value)
  const data = preview.data

  // 2. 弹出确认对话框
  const confirmHtml = buildPreviewHtml(data)
  try {
    await ElMessageBox.confirm(confirmHtml, '确认开始训练', {
      confirmButtonText: data.canStart ? '开始训练' : '无法训练',
      cancelButtonText: '取消',
      type: data.warnings.length > 0 ? 'warning' : 'info',
      dangerouslyUseHTMLString: true,
      confirmButtonDisabled: !data.canStart
    })
  } catch {
    return  // 用户取消
  }

  // 3. 真正发起训练
  await actuallyStartTraining()
}

function buildPreviewHtml(data) {
  const e = data.estimatedTrainingSet || {}
  const warnings = (data.warnings || []).map(w => `<li style="color:#e6a23c">⚠ ${w}</li>`).join('')
  return `
    <div style="line-height: 1.6">
      <p><b>本次训练数据来源:</b></p>
      <ul>
        <li>主项目人工标注: ${e.fromMainAnnotated ?? 0} 张</li>
        <li>主项目模型预标注: ${e.fromMainPrediction ?? 0} 张</li>
        <li>已审增量数据: ${e.fromReviewedIncrementals ?? 0} 张</li>
        <li><b>合计可用: ${e.totalUsable ?? 0} 张</b></li>
      </ul>
      ${e.skippedPendingIncrementals > 0
        ? `<p style="color:#909399">将跳过未审 ${e.skippedPendingIncrementals} 张</p>` : ''}
      ${warnings ? `<p><b>注意事项:</b></p><ul>${warnings}</ul>` : ''}
    </div>
  `
}
```

并在 API 封装里加:

```js
export function getTrainingPreview(projectId) {
  return request({
    url: `/projects/${projectId}/training/preview`,
    method: 'get'
  })
}
```

### J.3 验证

```bash
# 后端
curl -s -H "Authorization: Bearer $JWT" \
  "http://localhost:8080/api/v1/projects/$PROJECT_ID/training/preview" | jq .
```

期望返回 `mainProject / incrementals / estimatedTrainingSet / warnings / canStart` 字段。

前端在训练页点击"开始训练"应能看到确认对话框。

---

## 13. 阶段 K:全流程验证 + 验收报告

### K.1 跑完整流程

1. 找一个项目,部署模型,推理产生 LOW_B
2. 看新增量项目自动配 webhook 成功(P0-1)
3. 在 LS 里审核一张,看 webhook 回写快(P0-2)
4. 找一个 LS 失效的项目,调 ls-health → repair-ls-binding → 再 ls-health(P0-3)
5. 进训练页,看预览对话框,跳过未审增量(P1-3)
6. 触发训练,等完成,看 testResults 包含元信息(P1-1)
7. 进项目详情"增量审核" tab(P1-2)

### K.2 生成验收报告

```bash
REPORT=/tmp/flywheel_p1_report.md
> $REPORT
{
  echo "# 数据飞轮 P0+P1 验收报告"
  echo "生成时间: $(date)"
  echo ""
  echo "## 决策记录"
  cat /tmp/flywheel_p1_decisions.md 2>/dev/null
  echo ""
  echo "## P0 改动"
  echo "- [ ] P0-1 webhook 自动配置"
  echo "- [ ] P0-2 ls_task_id 索引"
  echo "- [ ] P0-3 主项目失效自愈"
  echo ""
  echo "## P1 改动"
  echo "- [ ] P1-1 训练侧聚合多 LS 项目"
  echo "- [ ] P1-2 增量审核中心"
  echo "- [ ] P1-3 训练前数据预览"
  echo ""
  echo "## 关键日志摘录"
  echo '```'
  grep -E "创建 webhook 成功|主 Label Studio 项目失效|主 LS 项目重建完成|Flywheel dataset built" /tmp/springboot.log | tail -30
  echo '```'
  echo ""
  echo "## 已知遗留问题"
  echo "(列出本次没修但应该后续修的)"
} > $REPORT
cat $REPORT
```

把每个 [ ] 改成 [x] 或填上"未通过"原因。

---

## 14. 停止条件

任何阶段失败 2 次以上找不到原因 → 停止报告。**特别是阶段 F 的训练侧改造**,如果跑出来训练数据集为空或者图片名冲突,**立刻停止**,不要"再调一下试试"。

可能踩的坑:

1. **F.3.2 抽方法时 import 漏了**:`StandardCopyOption`、`Locale` 等需要保证 import 在
2. **F.3.4 fetchLabelStudioAnnotations 用 admin token 还是用户 token**:看现有方法签名,它接受 `lsToken` 参数,沿用即可
3. **图片名冲突**:同一张图被主项目和增量项目都引用 → 加了 prefix 应该能避免,如果还冲突说明同一项目内重复,要 dedup
4. **D.3 探活返回 false 但其实是 LS 502/503**:isLsProjectAlive 已经处理(网络错误返回 true 不误判)
5. **I.3 Element Plus 版本不对** 导致组件用不了:看 package.json 里 `element-plus` 版本,用对应文档的 API

---

## 15. 注意事项汇总

- **每个阶段完成后必须跑该阶段的"通过标准"**,不通过不进下一阶段
- **F 阶段编译失败时不要为了让它编过而注释代码**,要解决根因
- **改完前端必须 build 验证**,不能依赖 vite dev mode 的容错
- **测试用的项目最好选 lsProjectId 健康的项目**,避免 P0-3 与其它问题混淆
- **不要在阶段 F 重构现有的 convertLabelStudioToYOLO 行为**,原方法保留兼容
- **每次重启后端用 `kill $(lsof -ti:8080)`**,不要 `pkill -f java`(可能杀别的服务)

---

## 16. 提交清单

完成后提交:

1. `/tmp/flywheel_p1_decisions.md`(阶段 0 的两个决策)
2. `/tmp/flywheel_p1_precheck.log`(阶段 A)
3. `/tmp/flywheel_p1_build.log`(阶段 E 编译日志)
4. `/tmp/flywheel_p1_train_start.json`(阶段 G 训练验证)
5. `/tmp/flywheel_p1_report.md`(最终验收)
6. 一段自然语言总结,< 15 句:做了哪些、跳过了哪些、有什么遗留

---

## 17. 自我检查清单(开始前读一遍)

- [ ] 我已读完阶段 0,知道 2 个决策点要先回答
- [ ] 我知道 P1-1 是飞轮闭环的核心修复,不能跳
- [ ] 我知道 P0 必须在 P1 之前完成
- [ ] 我知道 F 阶段抽方法不能改原 convertLabelStudioToYOLO 行为
- [ ] 我知道每个 controller 端点要在 SecurityConfig 里走的是 JWT(默认),不是 permitAll
- [ ] 我知道 webhook 端点(`/ls-webhook/**`)的 permitAll 是已有的,不要改
- [ ] 我知道发现问题先停下来报告而不是自己尝试修

全部勾选后开始阶段 0。

---

**文档结束**