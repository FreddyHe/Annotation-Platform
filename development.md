# 智能标注平台 · 迭代飞轮升级开发计划

> **版本**: v1.0 · **日期**: 2026-04-23
> **目标**: 把项目从"一次性自动标注流水线"升级为"持续循环的数据-模型飞轮"
> **作者**: Claude (基于 Annotation-Platform 项目 `.context/` 文档和代码深入分析)

---

## 目录

1. [需求与架构对齐](#一需求与架构对齐)
2. [核心架构决策](#二核心架构决策)
3. [数据模型改动清单](#三数据模型改动清单)
4. [分阶段开发计划](#四分阶段开发计划)
   - [阶段 0：数据模型与基础设施](#阶段-0数据模型与基础设施1-2-天)
   - [阶段 1：项目创建时自动开启 Round 1](#阶段-1项目创建时自动开启-round-11-天)
   - [阶段 2：把现有训练绑定到 Round](#阶段-2把现有训练绑定到-round1-天)
   - [阶段 3：边端模拟器](#阶段-3边端模拟器2-3-天-关键阶段-)
   - [阶段 4：VLM 二次判定 + 池子自动分流](#阶段-4vlm-二次判定--池子自动分流1-2-天)
   - [阶段 5：人工审核回流 + 触发下一轮训练](#阶段-5人工审核回流--触发下一轮训练2-天)
5. [已决定不做的事](#五已决定不做的事避免过度工程)
6. [风险与注意事项](#六风险与注意事项)
7. [总体时间线](#七总体时间线)

---

## 一、需求与架构对齐

### 1.1 现状

当前系统是**一次性跑完的自动标注流水线**。项目状态机是单向的：

```
DRAFT → UPLOADING → DETECTING → CLEANING → SYNCING → COMPLETED
```

项目一旦 `COMPLETED`，其生命周期就结束了，无法承载"训练后模型持续使用、数据持续回流"的场景。

### 1.2 目标状态

把"项目"升级成**以场景为中心、持续运转的数据-模型飞轮**:

```
     ┌──────────────────────────────────────────────────────────┐
     │                                                           │
     ▼                                                           │
  上传图片 → 预标注 → 人工审核 → 训练模型 → 下发边端            │
                                                  │             │
                                                  ▼             │
                                          边端推理(新图片)      │
                                                  │             │
                           ┌──────────────────────┼──────────┐  │
                           ▼                      ▼          ▼  │
                       高池                   低池 (待判)       │
                  (直接入训练集)                   │             │
                                                  ▼             │
                                          VLM 二次判定           │
                                                  │             │
                                    ┌─────────────┴──────┐      │
                                    ▼                    ▼      │
                                 低-A 池               低-B 池   │
                               (VLM 判对)          (VLM 不确定) │
                                    │                    │      │
                                    │                    ▼      │
                                    │         人工审核(Label Studio)
                                    │                    │      │
                                    └─────────┬──────────┘      │
                                              ▼                 │
                                     全部回流训练集 ─────────────┘
                                     (触发下一轮训练)
```

### 1.3 关键语义变化

1. **项目不再"结束"**，而是有"迭代轮次 (IterationRound)"的概念 (V1 → V2 → V3…)
2. **数据池**是新增的核心领域对象 (高池 / 低-A / 低-B)
3. **边端**是新的系统边界 (初期用前端页面模拟)
4. **VLM 二次判定**复用已有能力 (从预标注清洗阶段扩展到回流阶段)
5. **训练 → 下发 → 回流 → 再训练**是循环,不是线性

---

## 二、核心架构决策

本节所有决策**已经用户确认**,后续开发按此执行。

### 决策 1: 引入"迭代轮次 (IterationRound)"实体 ✅

在 `Project` 之下新增 `IterationRound`,每一轮完整的"训练-下发-回流"闭环是一个 Round:

```
Project (1) ─── (N) IterationRound
                       ├── roundNumber         (V1, V2, V3...)
                       ├── status              (ACTIVE/TRAINING/DEPLOYED/COLLECTING/REVIEWING/CLOSED)
                       ├── trainingRecordId    (这一轮训练出的模型)
                       ├── startedAt / closedAt
                       └── notes
```

**理由**: 现有 `ModelTrainingRecord` 只是"训练记录",没有"这一轮完整闭环"的概念;回流数据必须归属到某一轮,否则无法做"V2 比 V1 好在哪"的分析。

### 决策 2: 数据池用"状态字段",不用"独立表" ✅

**不**新建三张表 (high_pool / low_a_pool / low_b_pool),而是**在一张 `InferenceDataPoint` 表里用 `pool_type` 枚举字段区分池子**:

```
pool_type: HIGH / LOW_A_CANDIDATE / LOW_A / LOW_B / DISCARDED
```

池子之间会流动 (如 `LOW_A_CANDIDATE` 经 VLM 判定后变 `LOW_A` 或 `LOW_B`),用状态字段转换比跨表迁移简单,且查询"这轮的所有回流数据"只需一条 SQL。

### 决策 3: 边端先做"模拟器",真实部署预留接口 ✅

- 后端新增 `EdgeDeployment` 实体和 REST 接口
- 前端新增"边端模拟" tab,提供三个动作: **模拟部署 / 模拟推理 / 模拟回滚**
- 未来对接真实边端时,只需把"模拟推理"换成"接收边端 HTTP 回调"

### 决策 4: VLM 二次判定复用 `auto_annotation.py` 能力 ✅

- 算法服务新增路由 `/api/v1/algo/reinference/vlm-judge`
- **直接复用** `call_vlm_cleaning` 和 `crop_image_with_padding` 函数
- 触发时机从"DINO 检测后"扩展到"边端回流后"
- VLM prompt 新增 `uncertain` 返回值 (原版只有 keep/discard)

### 决策 5: Project 状态机保持不变,在其上新增 Round 状态机 ✅

- `Project.status` 的 7 个现有状态**保持不变** (作为"当前轮的子阶段")
- 新增 `IterationRound.status`,作为"轮次级状态"
- **理由**: 不破坏现有代码 (前端 `AlgorithmTasks.vue` 依赖 `project.status`)

### 决策 6: 其他参数化默认值 ✅ (用户已确认)

| 参数 | 默认值 |
|------|--------|
| 高池置信度阈值 | **0.8** (≥ 进高池) |
| 低池置信度阈值 | **0.4** (< 丢弃) |
| 高池和低-A 直接当 GT | **✅ 接受** (不强制人工复核) |
| 边端模拟器位置 | **✅ 作为项目详情页的 tab** (与"模型训练"tab 并列) |

---

## 三、数据模型改动清单

### 3.1 新增 4 张表

#### 表 1: `iteration_rounds`

```sql
CREATE TABLE iteration_rounds (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id      BIGINT NOT NULL,
    round_number    INT NOT NULL,                 -- 1, 2, 3...
    status          VARCHAR(20) NOT NULL,         -- ACTIVE/TRAINING/DEPLOYED/COLLECTING/REVIEWING/CLOSED
    training_record_id BIGINT,                    -- 这一轮训练出的模型 (可空,训练前 NULL)
    started_at      TIMESTAMP NOT NULL,
    closed_at       TIMESTAMP,
    notes           TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (training_record_id) REFERENCES model_training_records(id),
    INDEX idx_project_round (project_id, round_number),
    INDEX idx_status (status)
);
```

#### 表 2: `edge_deployments`

```sql
CREATE TABLE edge_deployments (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id              BIGINT NOT NULL,
    round_id                BIGINT NOT NULL,
    model_record_id         BIGINT NOT NULL,     -- 部署的模型
    edge_node_name          VARCHAR(100),         -- "虚拟边端-A" 等
    status                  VARCHAR(20) NOT NULL, -- DEPLOYING/ACTIVE/ROLLED_BACK/FAILED
    deployed_at             TIMESTAMP NOT NULL,
    rolled_back_at          TIMESTAMP,
    previous_deployment_id  BIGINT,               -- 回滚追溯
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (round_id) REFERENCES iteration_rounds(id),
    FOREIGN KEY (model_record_id) REFERENCES model_training_records(id),
    INDEX idx_project_status (project_id, status)
);
```

#### 表 3: `inference_data_points` (池子核心表)

```sql
CREATE TABLE inference_data_points (
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id           BIGINT NOT NULL,
    round_id             BIGINT NOT NULL,         -- 这一轮采集的
    deployment_id        BIGINT NOT NULL,         -- 来自哪次部署
    image_path           VARCHAR(500) NOT NULL,
    file_name            VARCHAR(255) NOT NULL,
    inference_bbox_json  JSON,                    -- 边端推理的所有框
    avg_confidence       DOUBLE,
    pool_type            VARCHAR(20) NOT NULL,    -- HIGH/LOW_A_CANDIDATE/LOW_A/LOW_B/DISCARDED ← 核心
    vlm_decision         VARCHAR(20),             -- KEEP/DISCARD/UNCERTAIN (可空)
    vlm_reasoning        TEXT,
    vlm_processed_at     TIMESTAMP,
    human_reviewed       BOOLEAN DEFAULT FALSE,
    ls_task_id           BIGINT,                  -- 低-B 同步到 LS 后的 task id
    used_in_round_id     BIGINT,                  -- 被哪轮训练消费了,NULL 表示未消费
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (round_id) REFERENCES iteration_rounds(id),
    FOREIGN KEY (deployment_id) REFERENCES edge_deployments(id),
    INDEX idx_project_round (project_id, round_id),
    INDEX idx_pool_type (project_id, pool_type),
    INDEX idx_used (used_in_round_id)
);
```

#### 表 4: `project_config`

```sql
CREATE TABLE project_config (
    project_id              BIGINT PRIMARY KEY,
    high_pool_threshold     DOUBLE DEFAULT 0.8,   -- 置信度 >= 进高池
    low_pool_threshold      DOUBLE DEFAULT 0.4,   -- 置信度 < 丢弃
    enable_auto_vlm_judge   BOOLEAN DEFAULT TRUE,
    vlm_quota_per_round     INT DEFAULT 500,      -- 每轮 VLM 判定配额 (控成本)
    auto_trigger_retrain    BOOLEAN DEFAULT FALSE,-- 凑够 N 条自动触发训练
    retrain_min_samples     INT DEFAULT 200,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);
```

### 3.2 修改 2 张现有表

```sql
-- projects 表新增字段 (不动现有字段,保持 100% 兼容)
ALTER TABLE projects ADD COLUMN current_round_id BIGINT;
ALTER TABLE projects ADD COLUMN project_type VARCHAR(20) DEFAULT 'LEGACY';
-- project_type: LEGACY=老的一次性项目; ITERATIVE=新的迭代项目

-- model_training_records 表新增字段
ALTER TABLE model_training_records ADD COLUMN round_id BIGINT;
ALTER TABLE model_training_records ADD COLUMN training_data_source VARCHAR(20);
-- training_data_source: INITIAL=首次训练(用 LS 标注数据); FEEDBACK=用回流数据再训练
```

---

## 四、分阶段开发计划

### 阶段 0:数据模型与基础设施 (1-2 天)

#### 目标
把新表建起来,新实体和 Repository 就绪,**不改任何业务逻辑**。所有现有接口行为保持 100% 不变。

#### 开发内容

**后端 (`backend-springboot`)**:

| 文件 | 操作 | 说明 |
|------|------|------|
| `entity/IterationRound.java` | 新建 | `@Entity`, 枚举 `RoundStatus` |
| `entity/EdgeDeployment.java` | 新建 | `@Entity`, 枚举 `DeploymentStatus` |
| `entity/InferenceDataPoint.java` | 新建 | `@Entity`, 枚举 `PoolType` 和 `VlmDecision` |
| `entity/ProjectConfig.java` | 新建 | `@Entity`, 与 Project 一对一 |
| `entity/Project.java` | 修改 | 添加 `currentRoundId`, `projectType` 字段 |
| `entity/ModelTrainingRecord.java` | 修改 | 添加 `roundId`, `trainingDataSource` 字段 |
| `repository/IterationRoundRepository.java` | 新建 | 带 `findByProjectIdOrderByRoundNumberDesc` |
| `repository/EdgeDeploymentRepository.java` | 新建 | 带 `findActiveByProjectId` |
| `repository/InferenceDataPointRepository.java` | 新建 | 带多个查询方法 (见下方规范) |
| `repository/ProjectConfigRepository.java` | 新建 | 基础 CRUD |

**Repository 必备方法** (避免你 `LESSONS.md` 里记过的坑):

```java
// InferenceDataPointRepository.java
List<InferenceDataPoint> findByRoundIdAndPoolType(Long roundId, PoolType poolType);
long countByProjectIdAndPoolType(Long projectId, PoolType poolType);

@Modifying
@Query("UPDATE InferenceDataPoint d SET d.poolType = :newType WHERE d.id IN :ids")
int batchUpdatePoolType(@Param("ids") List<Long> ids, @Param("newType") PoolType newType);

@Modifying
@Query("DELETE FROM InferenceDataPoint d WHERE d.project.id = :projectId")
void deleteByProjectId(@Param("projectId") Long projectId);
```

**数据库迁移**:
- 使用 `ddl-auto=update`,Spring Boot 启动时自动建表
- **不要删 `testdb.mv.db`**,会破坏现有数据;让 JPA 自动加字段

#### 测试内容

**单元测试** (`src/test/java/.../entity/`):
- `IterationRoundTest`: 验证 Builder 默认值、枚举序列化
- `InferenceDataPointTest`: 验证 JSON 字段 `inferenceBboxJson` 能存/读 `List<Map>`

**集成测试** (手动,按 SETUP.md 操作):

```bash
# 1. 编译
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests
# 预期: BUILD SUCCESS

# 2. 重启后端 (按 .context/SETUP.md 流程,不要直接运行,让用户执行)
# 验收命令(编译完成后给用户执行):
# kill $(lsof -ti:8080) 2>/dev/null; sleep 3
# nohup java -jar target/platform-backend-1.0.0.jar --server.port=8080 > /tmp/springboot.log 2>&1 &
# sleep 15 && grep "Started" /tmp/springboot.log

# 3. 验证表创建成功 (登录 H2 Console 或 curl 已有接口)
token=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 4. 调已有接口确保不破坏
curl -s http://localhost:8080/api/v1/projects -H "Authorization: Bearer $token" | head -c 200
# 预期: 返回正常的项目列表,现有老项目 projectType=LEGACY

# 5. 查日志确认没有 JPA schema 错误
grep -iE "error|SQLGrammar|constraint" /tmp/springboot.log | grep -v "INFO" | head -20
# 预期: 无输出
```

#### 验收标准 ✅

- [ ] 编译通过,Spring Boot 启动日志有 `Started` 字样
- [ ] 所有现有接口返回格式不变,老项目可正常操作
- [ ] 日志中能看到 4 张新表的 `create table` 语句
- [ ] 老项目查询时 `projectType` 字段默认为 `LEGACY`
- [ ] `iteration_rounds` 表存在但此时为空

---

### 阶段 1:项目创建时自动开启 Round 1 (1 天)

#### 目标
让**新**创建的项目一诞生就有一个 active 的 Round 1,标记为 `ITERATIVE` 类型。**老项目保持 LEGACY,不强制迁移**。

#### 开发内容

**后端**:

| 文件 | 操作 | 说明 |
|------|------|------|
| `service/ProjectService.java` (或 `ProjectServiceImpl`) | 修改 | `createProject()` 方法内新增 Round 1 创建逻辑 |
| `controller/ProjectController.java` | 修改 | `convertToDetailResponse()` 返回 `currentRoundId` 和 `projectType` |
| `dto/response/ProjectDetailResponse.java` | 修改 | 新增两个字段 |
| `service/RoundService.java` | **新建** | 封装 Round 生命周期管理 |

**核心改动代码示意**:

```java
// ProjectService.createProject() 在 projectRepository.save(project) 之后
IterationRound round1 = IterationRound.builder()
    .project(savedProject)
    .roundNumber(1)
    .status(RoundStatus.ACTIVE)
    .startedAt(LocalDateTime.now())
    .build();
iterationRoundRepository.save(round1);

savedProject.setCurrentRoundId(round1.getId());
savedProject.setProjectType("ITERATIVE");
projectRepository.save(savedProject);

// ProjectConfig 同步创建默认配置
ProjectConfig config = ProjectConfig.builder()
    .projectId(savedProject.getId())
    .highPoolThreshold(0.8)
    .lowPoolThreshold(0.4)
    .enableAutoVlmJudge(true)
    .vlmQuotaPerRound(500)
    .build();
projectConfigRepository.save(config);
```

**前端 (`frontend-vue`)**:

此阶段**前端无需改动**,现有页面继续正常工作。

#### 测试内容

**集成测试**:

```bash
# 1. 获取 token
token=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 2. 创建新项目
projectId=$(curl -s -X POST http://localhost:8080/api/v1/projects \
  -H "Authorization: Bearer $token" \
  -H "Content-Type: application/json" \
  -d '{"name":"地铁安全检查-迭代测试","labels":["helmet","vest"]}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
echo "Project ID: $projectId"

# 3. 验证返回包含 currentRoundId 和 projectType=ITERATIVE
curl -s http://localhost:8080/api/v1/projects/$projectId \
  -H "Authorization: Bearer $token" | python -m json.tool | grep -E "currentRoundId|projectType"
# 预期:
#   "currentRoundId": 1  (或其他非空值)
#   "projectType": "ITERATIVE"

# 4. 验证旧项目没被影响
curl -s http://localhost:8080/api/v1/projects \
  -H "Authorization: Bearer $token" | python -m json.tool | grep -c "LEGACY"
# 预期: 等于老项目数量

# 5. 验证 project_config 自动创建
# (通过后端日志或调 GET /projects/{id}/config 接口确认,如果暂未做接口就查日志)
grep "ProjectConfig" /tmp/springboot.log | tail -5

# 6. 跑一遍完整的老流程确认不破坏
# (上传图片 -> 自动标注 -> 查看结果) 走通即可
```

**单元测试**:
- `ProjectServiceTest.createProject_shouldCreateRound1`: 创建后检查 Round 是否存在且 status=ACTIVE
- `ProjectServiceTest.createProject_shouldCreateConfig`: ProjectConfig 默认值正确

#### 验收标准 ✅

- [ ] 新创建项目返回体有 `currentRoundId` 和 `projectType=ITERATIVE`
- [ ] 对应的 `iteration_rounds` 表有一条 `round_number=1, status=ACTIVE` 记录
- [ ] `project_config` 表有对应配置行,阈值为 0.8 和 0.4
- [ ] 老项目全部保持 `projectType=LEGACY`,未被动迁移
- [ ] 现有自动标注、导出、审核功能 100% 不受影响

---

### 阶段 2:把现有训练绑定到 Round (1 天)

#### 目标
把"人工审核完 → 启动训练"这一段落到 Round 里,使 `ModelTrainingRecord.roundId` 有值,`IterationRound.status` 随训练推进流转。

#### 开发内容

**后端**:

| 文件 | 操作 | 说明 |
|------|------|------|
| `service/TrainingService.java` | 修改 | `startTraining()` 读取 `project.currentRoundId` 填入 Record |
| `service/TrainingService.java` | 修改 | 训练回调处把 `round.trainingRecordId` 写上,`status=TRAINING`→`DEPLOYED_READY` |
| `service/RoundService.java` | 新增方法 | `markRoundTraining(roundId)`、`markRoundTrainingCompleted(roundId, trainingRecordId)` |

**核心改动代码示意**:

```java
// TrainingService.startTraining()
public ModelTrainingRecord startTraining(Long userId, Long projectId, ...) {
    Project project = projectRepository.findById(projectId).orElseThrow();

    // ========== 新增 ==========
    Long roundId = project.getCurrentRoundId();
    if (roundId != null) {
        roundService.markRoundTraining(roundId);  // status = TRAINING
    }
    // ===========================

    // ... 原有 convertLabelStudioToYOLO + 调算法服务启动训练 ...

    ModelTrainingRecord record = ModelTrainingRecord.builder()
            .projectId(projectId)
            .userId(userId)
            // ========== 新增 ==========
            .roundId(roundId)
            .trainingDataSource("INITIAL")  // 首次训练用 LS 数据
            // ===========================
            .taskId(taskId)
            // ... 其他字段
            .build();

    return trainingRecordRepository.save(record);
}

// TrainingService.refreshTrainingRecord() 训练完成时
if ("COMPLETED".equals(normalized)) {
    record.setStatus(TrainingStatus.COMPLETED);
    record.setCompletedAt(LocalDateTime.now());
    // ========== 新增 ==========
    if (record.getRoundId() != null) {
        roundService.markRoundTrainingCompleted(record.getRoundId(), record.getId());
    }
    // ===========================
    // ... 原有逻辑
}
```

**前端**:
- `Training.vue` 可选展示"当前属于 Round X",不做强制要求

#### 测试内容

**集成测试**:

```bash
# 1. 在阶段 1 创建的新项目上,上传图片并启动训练 (手动触发完整流程)
# 2. 训练启动后立即查 iteration_rounds 表
curl -s http://localhost:8080/api/v1/projects/$projectId \
  -H "Authorization: Bearer $token" | python -m json.tool | grep currentRoundId

# 3. 训练期间轮询,检查 round.status 变成 TRAINING
# (可通过新增的 /api/v1/projects/{id}/rounds/current 接口,或暂时查日志)

# 4. 训练完成后,检查 model_training_records.round_id 有值
# (可以加一个调试接口 GET /training/records/{id} 返回完整实体)

# 5. 检查 round.training_record_id 等于刚才的 training record id
```

**单元测试**:
- `TrainingServiceTest.startTraining_shouldBindToCurrentRound`: 训练 record 的 roundId 与 project.currentRoundId 一致
- `TrainingServiceTest.startTraining_shouldTriggerRoundStatusChange`: round 状态变 TRAINING
- `TrainingServiceTest.completeTraining_shouldUpdateRound`: 训练完成后 round.trainingRecordId 填充

#### 验收标准 ✅

- [ ] 启动训练后,对应 Round 的 `status` 变为 `TRAINING`
- [ ] `ModelTrainingRecord.roundId` 有正确值
- [ ] 训练完成后,Round 的 `trainingRecordId` 回填
- [ ] Round 状态变为 `DEPLOYED_READY` (表示可以进入下一步部署)
- [ ] 老项目 (无 currentRoundId) 训练不受影响,roundId 为 NULL

---

### 阶段 3:边端模拟器 (2-3 天) 关键阶段 ⭐

#### 目标
做一个独立 tab,能"假装"把模型下发到边端、上传图片模拟推理、看到结果按置信度自动分流到高池和低池候选。

#### 开发内容

**后端 - 新增 Controller**: `EdgeSimulatorController`

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/edge-simulator/deploy` | POST | 创建 EdgeDeployment,round.status=DEPLOYED |
| `/api/v1/edge-simulator/inference` | POST (multipart) | 上传图片批量推理,结果按阈值分流入池 |
| `/api/v1/edge-simulator/rollback` | POST | 切回指定 deployment |
| `/api/v1/edge-simulator/deployments?projectId={id}` | GET | 部署历史 |
| `/api/v1/edge-simulator/inference-history?deploymentId={id}` | GET | 某次部署的所有回流数据点 |
| `/api/v1/edge-simulator/pool-stats?projectId={id}&roundId={id}` | GET | 三个池子的统计 (高池/低-A/低-B 计数) |

**后端 - 新增 Service**: `EdgeSimulatorService`

核心方法: `runInference(deploymentId, imageFiles)`

```java
public InferenceResult runInference(Long deploymentId, List<MultipartFile> files) {
    EdgeDeployment deployment = edgeDeploymentRepo.findById(deploymentId).orElseThrow();
    ModelTrainingRecord model = trainingRecordRepo.findById(deployment.getModelRecordId()).orElseThrow();
    ProjectConfig config = projectConfigRepo.findById(deployment.getProjectId()).orElseThrow();

    // 1. 保存图片到 /root/autodl-fs/uploads/edge_inference/{deploymentId}/
    List<String> savedPaths = saveFiles(files, deploymentId);

    // 2. 调算法服务批量推理 (复用 single_class_detection 或新建批量接口)
    List<Map<String, Object>> inferenceResults = callAlgorithmInference(
        model.getBestModelPath(), savedPaths);

    // 3. 分流入池
    for (Map<String, Object> result : inferenceResults) {
        double avgConf = computeAvgConfidence(result);
        PoolType poolType;
        if (avgConf >= config.getHighPoolThreshold()) {
            poolType = PoolType.HIGH;
        } else if (avgConf < config.getLowPoolThreshold()) {
            poolType = PoolType.DISCARDED;  // 或根本不保存
        } else {
            poolType = PoolType.LOW_A_CANDIDATE;  // 待 VLM 判定
        }

        InferenceDataPoint point = InferenceDataPoint.builder()
            .projectId(deployment.getProjectId())
            .roundId(deployment.getRoundId())
            .deploymentId(deploymentId)
            .imagePath((String) result.get("image_path"))
            .fileName((String) result.get("file_name"))
            .inferenceBboxJson(result.get("detections"))
            .avgConfidence(avgConf)
            .poolType(poolType)
            .build();
        inferenceDataPointRepo.save(point);
    }

    // 4. 更新 round 状态为 COLLECTING
    roundService.markRoundCollecting(deployment.getRoundId());

    return new InferenceResult(...);
}
```

**算法服务改动**:

新建路由 `algorithm-service/routers/edge_inference.py` (或在 `single_class_detection.py` 里新增端点):

```python
@router.post("/algo/edge-inference/batch")
async def batch_inference(request: BatchInferenceRequest):
    """
    批量推理接口,给 Edge Simulator 用
    输入: model_path + image_paths
    输出: 每张图的检测框列表
    """
    model = model_service.load_yolo_model(request.model_path)  # 缓存
    results = []
    for img_path in request.image_paths:
        detections = model_service.run_yolo_detection(
            image_path=img_path,
            confidence_threshold=0.3,  # 用低阈值,后端再筛
            iou_threshold=0.45
        )
        avg_conf = sum(d['confidence'] for d in detections) / len(detections) if detections else 0.0
        results.append({
            "image_path": img_path,
            "file_name": os.path.basename(img_path),
            "detections": detections,
            "avg_confidence": avg_conf
        })
    return {"success": True, "results": results}
```

**前端 - 新增 Tab 组件**: `frontend-vue/src/components/EdgeSimulator.vue`

UI 分三区域:

```
┌──────────────────────────────────────────────────┐
│ 部署管理 (顶部)                                  │
│ [选择模型 ▼] [部署到边端] [当前部署: V1.0] [回滚]│
└──────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────┐
│ 模拟推理 (中部)                                  │
│ [上传区] + [运行推理] 按钮                       │
│ 推理结果表格: 图片名 | 框数 | 平均置信度 | 入池 │
└──────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────┐
│ 池子统计 (底部)                                  │
│ [高池: 42] [低-A 候选: 15] [低-A: 0] [低-B: 0] │
│ (阶段 3 只有高池和 LOW_A_CANDIDATE 有数据)      │
└──────────────────────────────────────────────────┘
```

**路由和 API 封装**:

```javascript
// frontend-vue/src/api/edgeSimulator.js (新建)
import request from '@/utils/request'

export const edgeSimulatorAPI = {
  deploy(data) { return request({ url: '/edge-simulator/deploy', method: 'post', data }) },
  inference(formData) {
    return request({ url: '/edge-simulator/inference', method: 'post', data: formData,
      headers: { 'Content-Type': 'multipart/form-data' } })
  },
  rollback(data) { return request({ url: '/edge-simulator/rollback', method: 'post', data }) },
  listDeployments(projectId) { return request({ url: `/edge-simulator/deployments`, method: 'get', params: { projectId } }) },
  poolStats(projectId, roundId) { return request({ url: '/edge-simulator/pool-stats', method: 'get', params: { projectId, roundId } }) }
}
```

在 `ProjectDetail.vue` 的 `<el-tabs>` 里新增:

```vue
<el-tab-pane label="边端模拟" name="edge">
  <EdgeSimulator :project="project" @refresh="loadProject" />
</el-tab-pane>
```

#### 测试内容

**集成测试** (端到端):

```bash
# 准备: 已有一个训练完成的 Round 1 项目
projectId=<your_project_id>
modelRecordId=<completed_training_record_id>

# 1. 部署模型到"虚拟边端-A"
curl -s -X POST http://localhost:8080/api/v1/edge-simulator/deploy \
  -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"projectId\":$projectId,\"modelRecordId\":$modelRecordId,\"edgeNodeName\":\"虚拟边端-A\"}"
# 预期: 返回 deploymentId,round.status=DEPLOYED

# 2. 上传 5 张测试图模拟推理
deploymentId=<返回的 id>
curl -s -X POST http://localhost:8080/api/v1/edge-simulator/inference \
  -H "Authorization: Bearer $token" \
  -F "deploymentId=$deploymentId" \
  -F "files=@/root/autodl-fs/uploads/449/test1.jpg" \
  -F "files=@/root/autodl-fs/uploads/449/test2.jpg" \
  -F "files=@/root/autodl-fs/uploads/449/test3.jpg"
# 预期: 返回每张图的推理结果 + 分流池信息

# 3. 查池子统计
curl -s "http://localhost:8080/api/v1/edge-simulator/pool-stats?projectId=$projectId&roundId=<round1_id>" \
  -H "Authorization: Bearer $token"
# 预期: {highCount: X, lowACandidateCount: Y, lowACount: 0, lowBCount: 0}

# 4. 查推理历史
curl -s "http://localhost:8080/api/v1/edge-simulator/inference-history?deploymentId=$deploymentId" \
  -H "Authorization: Bearer $token" | python -m json.tool | head -50

# 5. 测试回滚 (先部署 V2,再回滚到 V1)
# (需要先训练出 V2 模型,或用同一个模型做两次部署来模拟)

# 6. 验证前端页面
# - 进入项目详情 → 切换到"边端模拟" tab
# - 能看到"部署管理"区域列出可部署的模型
# - 点击部署后能看到 "当前部署" 更新
# - 上传图片后能看到推理结果表格
# - 能看到池子统计数字有变化
```

**单元测试**:
- `EdgeSimulatorServiceTest.deploy_shouldCreateDeploymentAndUpdateRound`
- `EdgeSimulatorServiceTest.inference_shouldSplitByConfidenceThreshold`: mock 算法服务返回,验证分流逻辑
- `EdgeSimulatorServiceTest.rollback_shouldSwitchActiveDeployment`

#### 验收标准 ✅

- [ ] 前端"边端模拟" tab 正常渲染
- [ ] 能选择某个训练完成的模型并"部署"成功
- [ ] 上传 10 张图后能看到分流: 部分进高池 (conf≥0.8)、部分进 LOW_A_CANDIDATE (0.4≤conf<0.8)、部分被丢弃
- [ ] 池子统计数字正确
- [ ] 回滚功能能切换当前部署
- [ ] 整个过程不影响原有自动标注功能

---

### 阶段 4:VLM 二次判定 + 池子自动分流 (1-2 天)

#### 目标
`LOW_A_CANDIDATE` 池中的数据自动经过 VLM 二次判定,分流到 `LOW_A` (VLM 背书,直接入训练集) 或 `LOW_B` (VLM 也不确定,推送到 Label Studio 等人工审核)。

#### 开发内容

**算法服务改动** (`algorithm-service`):

1. 修改 `auto_annotation.py` 的 `parse_vlm_json_response`,支持返回 `uncertain`:

```python
def parse_vlm_json_response(response_text: str) -> tuple:
    """返回 (decision, reasoning),decision ∈ {keep, discard, uncertain}"""
    # ... 原有解析逻辑 ...
    decision_str = str(data.get("decision", "")).lower().strip()
    if decision_str == "keep":
        return ("keep", reasoning)
    elif decision_str == "uncertain":
        return ("uncertain", reasoning)
    else:
        return ("discard", reasoning)
```

2. 修改 VLM prompt,让它返回 3 种决定:

```python
prompt = f"""
【背景资料】
1. 待验证类别: [{label}]
2. 类别标准定义: {label_def}
3. 原始图片和裁剪图已提供。

【任务】
请判断裁剪图中的物体是否符合[{label}]的定义,给出以下三种决定之一:
- "keep": 非常确定是该目标
- "discard": 非常确定不是该目标
- "uncertain": 看不清,或者模棱两可无法判断

【输出JSON格式】
{{
  "reasoning": "你的推理过程",
  "decision": "keep" | "discard" | "uncertain"
}}
"""
```

3. 新增路由 `/api/v1/algo/reinference/vlm-judge`:

```python
# algorithm-service/routers/reinference.py (新建)
@router.post("/algo/reinference/vlm-judge")
async def vlm_judge_batch(request: VlmJudgeRequest, background_tasks: BackgroundTasks):
    """
    批量 VLM 二次判定
    输入: data_points[{id, image_path, bboxes, label, label_definition}] + vlm_config
    输出: task_id,异步执行,结果回调后端
    复用 call_vlm_cleaning 逻辑
    """
    task_id = str(uuid.uuid4())
    background_tasks.add_task(run_vlm_judge_task, task_id, request)
    return {"task_id": task_id, "status": "RUNNING"}

async def run_vlm_judge_task(task_id, request):
    # 对每个 data_point 的每个 bbox 调 VLM
    # 聚合: 如果所有框都 keep → data_point 整体 LOW_A
    #      如果所有框都 discard → DISCARDED
    #      有 uncertain 或混合 → LOW_B
    # 调后端回调接口 POST /api/v1/inference-data-points/vlm-result
    ...
```

**后端改动**:

| 文件 | 操作 | 说明 |
|------|------|------|
| `service/EdgeSimulatorService.java` | 修改 | 推理完成后自动触发 VLM 判定 |
| `service/VlmJudgeService.java` | **新建** | 封装 VLM 调用 + 池子流转 |
| `controller/InferenceDataPointController.java` | **新建** | 接收 VLM 回调 + 查询接口 |

核心逻辑:

```java
// VlmJudgeService.java
public void judgeAndSplit(Long roundId) {
    ProjectConfig config = ...;
    if (!config.getEnableAutoVlmJudge()) return;

    List<InferenceDataPoint> candidates = inferenceDataPointRepo
        .findByRoundIdAndPoolType(roundId, PoolType.LOW_A_CANDIDATE);

    // 配额控制
    int quota = config.getVlmQuotaPerRound();
    if (candidates.size() > quota) {
        candidates = candidates.subList(0, quota);
    }

    // 调算法服务批量判定
    List<Map> vlmResults = callAlgoVlmJudge(candidates, userConfig);

    // 根据结果更新 pool_type
    for (result : vlmResults) {
        InferenceDataPoint point = ...;
        String overall = aggregate(result.bboxDecisions);  // keep/discard/uncertain
        switch(overall) {
            case "keep": point.setPoolType(PoolType.LOW_A); break;
            case "discard": point.setPoolType(PoolType.DISCARDED); break;
            case "uncertain":
                point.setPoolType(PoolType.LOW_B);
                // 推到 Label Studio (复用 importPredictions)
                pushToLabelStudio(point);
                break;
        }
        point.setVlmDecision(overall);
        point.setVlmReasoning(result.reasoning);
        inferenceDataPointRepo.save(point);
    }

    // 更新 round 状态
    roundService.markRoundReviewing(roundId);
}

private void pushToLabelStudio(InferenceDataPoint point) {
    // 复用 LabelStudioProxyService.importPredictions
    // 把 point 的 bboxes 作为 prediction 推送,人工审核
    List<Map<String, Object>> predictions = convertToLsPredictions(point);
    Map<String, Object> stats = labelStudioProxyService.importPredictions(
        project.getLsProjectId(), predictions, userId);
    // 保存 ls_task_id 回 point
}
```

**前端改动**:

修改 `EdgeSimulator.vue` 的池子统计卡片,现在四个池子都能有数据:

```
[高池: 42 ✅ 已确认] [低-A: 12 🤖 VLM背书] [低-B: 8 👤 待人审] [已丢弃: 3]
```

在"低-B"池 card 上加按钮: **"跳转 Label Studio 审核"** (复用现有 `labelStudioAPI.getLoginUrl`)。

#### 测试内容

**集成测试**:

```bash
# 1. 先跑完阶段 3 的推理,有一批 LOW_A_CANDIDATE 数据
# 2. 系统自动触发 VLM 判定 (或加一个手动触发接口)

# 3. 查判定后的池子分布
curl -s "http://localhost:8080/api/v1/edge-simulator/pool-stats?projectId=$projectId&roundId=$roundId" \
  -H "Authorization: Bearer $token" | python -m json.tool
# 预期: lowACount 和 lowBCount 都 > 0

# 4. 查某个具体数据点的 VLM 判定结果
curl -s "http://localhost:8080/api/v1/inference-data-points/$pointId" \
  -H "Authorization: Bearer $token" | python -m json.tool | grep -E "poolType|vlmDecision|vlmReasoning"

# 5. 去 Label Studio 确认低-B 数据已推送
#    - 登录 LS → 进入对应项目 → 应该看到新的 task (带 prediction 框)

# 6. VLM 配额测试
#    - 在 project_config 设 vlmQuotaPerRound=10
#    - 上传 50 张图,预期只有 10 张经过 VLM 判定,其余保持 LOW_A_CANDIDATE
```

**单元测试**:
- `VlmJudgeServiceTest.aggregate_allKeep_shouldReturnLOWA`
- `VlmJudgeServiceTest.aggregate_anyUncertain_shouldReturnLOWB`
- `VlmJudgeServiceTest.judgeAndSplit_shouldRespectQuota`

#### 验收标准 ✅

- [ ] `LOW_A_CANDIDATE` 数据会自动/手动经过 VLM 判定
- [ ] 池子 (`LOW_A` / `LOW_B` / `DISCARDED`) 分布合理
- [ ] `vlmDecision` 和 `vlmReasoning` 字段正确填充
- [ ] `LOW_B` 数据能在 Label Studio 里看到并且带预标注框
- [ ] VLM 配额生效,不会失控调用
- [ ] round 状态流转到 `REVIEWING`

---

### 阶段 5:人工审核回流 + 触发下一轮训练 (2 天)

#### 目标
**整个飞轮闭环**。低-B 人工审核完成后,把所有池子数据 (高池 + 低-A + 低-B 审核后) 汇总成下一轮训练集,关闭当前轮,开启下一轮,触发训练。

#### 开发内容

**后端**:

| 文件 | 操作 | 说明 |
|------|------|------|
| `controller/RoundController.java` | **新建** | Round 生命周期管理接口 |
| `service/RoundService.java` | 扩展 | `closeCurrentRound()`、`startNextRound()`、`triggerRetrain()` |
| `service/FormatConverterService.java` | 修改 | 支持从 InferenceDataPoint 构建 YOLO 数据集 |

新增接口:

```
GET  /api/v1/projects/{id}/rounds                                → 轮次历史
GET  /api/v1/projects/{id}/rounds/current                        → 当前轮
POST /api/v1/projects/{id}/rounds/close-current                  → 关闭当前轮(校验低B全部审完)
POST /api/v1/projects/{id}/rounds/{roundId}/trigger-retrain      → 触发下一轮训练(FEEDBACK 源)
GET  /api/v1/projects/{id}/rounds/{roundId}/training-preview     → 预览下一轮训练集构成
```

**核心逻辑 - RoundService.closeCurrentRound**:

```java
public void closeCurrentRound(Long projectId) {
    Project project = projectRepository.findById(projectId).orElseThrow();
    Long currentRoundId = project.getCurrentRoundId();
    IterationRound current = iterationRoundRepo.findById(currentRoundId).orElseThrow();

    // 1. 校验: 所有 LOW_B 必须已审核
    long unreviewed = inferenceDataPointRepo.countByRoundIdAndPoolTypeAndHumanReviewedFalse(
        currentRoundId, PoolType.LOW_B);
    if (unreviewed > 0) {
        throw new BusinessException("ROUND_NOT_READY",
            String.format("还有 %d 条低-B 数据未审核,请到 Label Studio 完成审核", unreviewed));
    }

    // 2. 关闭当前轮
    current.setStatus(RoundStatus.CLOSED);
    current.setClosedAt(LocalDateTime.now());
    iterationRoundRepo.save(current);

    // 3. 开启下一轮
    IterationRound next = IterationRound.builder()
        .project(project)
        .roundNumber(current.getRoundNumber() + 1)
        .status(RoundStatus.ACTIVE)
        .startedAt(LocalDateTime.now())
        .build();
    next = iterationRoundRepo.save(next);

    project.setCurrentRoundId(next.getId());
    projectRepository.save(project);
}
```

**核心逻辑 - RoundService.triggerRetrain**:

```java
public ModelTrainingRecord triggerRetrain(Long projectId, Long roundId, Long userId) {
    // 1. 确定当前轮的上一轮
    IterationRound targetRound = iterationRoundRepo.findById(roundId).orElseThrow();
    // 上一轮的数据才是训练数据来源
    IterationRound prevRound = iterationRoundRepo
        .findByProjectIdAndRoundNumber(projectId, targetRound.getRoundNumber() - 1);

    // 2. 构建训练数据集 (三部分合并)
    //    - (a) LS 已有人工审核 (通过现有 FormatConverter)
    //    - (b) 上一轮 HIGH 池 (边端推理当 GT)
    //    - (c) 上一轮 LOW_A 池 (VLM 背书当 GT)
    //    - (d) 上一轮 LOW_B 池已审核 (人工给 GT,从 LS 拉)
    String datasetPath = formatConverterService.buildFeedbackDataset(
        projectId,
        prevRound.getId(),
        user.getLsToken()
    );

    // 3. 调算法服务启动训练 (沿用现有 TrainingService)
    ModelTrainingRecord record = trainingService.startTrainingFromPath(
        userId, projectId, datasetPath, /* epochs */ 100, /* batchSize */ 16,
        /* imageSize */ 640, /* modelType */ "yolov8n.pt", /* device */ "0"
    );
    record.setRoundId(roundId);
    record.setTrainingDataSource("FEEDBACK");
    trainingRecordRepo.save(record);

    // 4. 标记数据点"已被消费"
    inferenceDataPointRepo.markUsedInRound(prevRound.getId(), roundId);

    return record;
}
```

**核心逻辑 - FormatConverterService.buildFeedbackDataset**:

```java
public String buildFeedbackDataset(Long projectId, Long prevRoundId, String lsToken) throws Exception {
    Project project = projectRepository.findById(projectId).orElseThrow();
    String outputDir = trainingOutputBasePath + "/project_" + projectId + "/round_" + prevRoundId;
    Path outputPath = Paths.get(outputDir);
    Files.createDirectories(outputPath.resolve("images/train"));
    Files.createDirectories(outputPath.resolve("labels/train"));
    Files.createDirectories(outputPath.resolve("images/val"));
    Files.createDirectories(outputPath.resolve("labels/val"));

    // (a) LS 已有人工审核数据 (沿用现有逻辑,但只在第一轮用)
    // 第二轮及以后,LS 数据已在上一轮用过,不重复
    if (prevRoundId 对应的是第一轮) {
        convertLabelStudioToYOLO(projectId, project.getLsProjectId().toString(), lsToken, outputDir);
    }

    // (b) HIGH 池: 边端推理结果直接当 GT
    List<InferenceDataPoint> highPoints = inferenceDataPointRepo
        .findByRoundIdAndPoolType(prevRoundId, PoolType.HIGH);
    for (InferenceDataPoint point : highPoints) {
        copyImageAndWriteLabel(point, outputPath, labelMap);
    }

    // (c) LOW_A 池: VLM 背书的当 GT
    List<InferenceDataPoint> lowAPoints = inferenceDataPointRepo
        .findByRoundIdAndPoolType(prevRoundId, PoolType.LOW_A);
    for (InferenceDataPoint point : lowAPoints) {
        copyImageAndWriteLabel(point, outputPath, labelMap);
    }

    // (d) LOW_B 池已审核: 从 LS 拉标注
    List<InferenceDataPoint> lowBReviewed = inferenceDataPointRepo
        .findByRoundIdAndPoolTypeAndHumanReviewed(prevRoundId, PoolType.LOW_B, true);
    for (InferenceDataPoint point : lowBReviewed) {
        // 从 LS 拉该 task 的 annotations
        JsonNode annotations = fetchAnnotationByTaskId(point.getLsTaskId(), lsToken);
        copyImageAndWriteLabelFromLs(point, annotations, outputPath, labelMap);
    }

    // 生成 data.yaml
    createDataYaml(outputPath, labelMap);
    return outputDir;
}
```

**前端**:

在 `EdgeSimulator.vue` 底部加"轮次控制"区:

```
┌─ 轮次控制 ──────────────────────────────────────┐
│ 当前轮: Round 1 (COLLECTING)                    │
│ 低-B 待审: 3/8 条 [跳转 Label Studio 审核]     │
│                                                  │
│ [🏁 关闭本轮,准备下一轮]  (禁用直到低B全审完) │
│ [🚀 启动 Round 2 训练]                          │
└──────────────────────────────────────────────────┘
```

新增组件 `RoundHistory.vue` 作为项目详情页的又一个 tab,展示所有轮次历史:

```
Round 1  V1.0  2026-04-20  mAP50=0.72  数据量=500   [查看详情]
Round 2  V2.0  2026-04-25  mAP50=0.81  数据量=1050  [查看详情] ← +边端回流 550
Round 3  (进行中)                                               [查看详情]
```

#### 测试内容

**端到端集成测试**:

```bash
# ============ 完整飞轮验证 ============

# 1. 创建项目 → 上传图 → 自动标注 → 去 LS 人工审核 → 训练 Round 1 (阶段 2 已支持)
# 2. 阶段 3: 部署 Round 1 模型到边端
# 3. 阶段 4: 上传一批图模拟边端推理,自动进入三池
# 4. 阶段 4: 去 Label Studio 完成低-B 人工审核

# 5. 【本阶段】检查"关闭轮"校验
curl -s -X POST http://localhost:8080/api/v1/projects/$projectId/rounds/close-current \
  -H "Authorization: Bearer $token"
# 预期 (如果有低B没审完): 返回 ROUND_NOT_READY 错误

# 6. 完成所有低B审核后再试
curl -s -X POST http://localhost:8080/api/v1/projects/$projectId/rounds/close-current \
  -H "Authorization: Bearer $token"
# 预期: Round 1 关闭,Round 2 自动开启,project.currentRoundId 变更

# 7. 预览下一轮训练集
curl -s "http://localhost:8080/api/v1/projects/$projectId/rounds/$round2Id/training-preview" \
  -H "Authorization: Bearer $token" | python -m json.tool
# 预期: 返回数据集构成,各部分来源和数量
# {
#   "lsHistoricalData": 300,    // 初次人工审核
#   "highPoolData": 42,          // 本轮高池
#   "lowAPoolData": 12,          // VLM 背书
#   "lowBReviewedData": 8,       // 人审后的低B
#   "total": 362
# }

# 8. 触发下一轮训练
curl -s -X POST "http://localhost:8080/api/v1/projects/$projectId/rounds/$round2Id/trigger-retrain" \
  -H "Authorization: Bearer $token"
# 预期: 返回 ModelTrainingRecord,roundId=round2Id,trainingDataSource=FEEDBACK

# 9. 训练完成后,查轮次历史
curl -s "http://localhost:8080/api/v1/projects/$projectId/rounds" \
  -H "Authorization: Bearer $token" | python -m json.tool
# 预期: 两条记录,Round 1 CLOSED,Round 2 DEPLOYED_READY,Round 2 的 mAP 应优于 Round 1

# 10. 验证数据点被标记消费
# 查库: inference_data_points WHERE round_id=round1Id AND used_in_round_id=round2Id
# 预期: 所有 HIGH/LOW_A/LOW_B 都被标记

# 11. 前端验证
# - 进入"轮次历史" tab 看到两轮对比
# - 进入"边端模拟" tab,发现现在有 Round 2 模型可部署
# - 部署 Round 2 模型,重复 3-9 步,形成第三轮
```

**单元测试**:
- `RoundServiceTest.closeCurrentRound_shouldRejectIfUnreviewedLowB`
- `RoundServiceTest.closeCurrentRound_shouldOpenNextRound`
- `RoundServiceTest.triggerRetrain_shouldSetFeedbackSource`
- `FormatConverterServiceTest.buildFeedbackDataset_shouldMergeAllPools`
- `FormatConverterServiceTest.buildFeedbackDataset_shouldMarkDataPointsAsUsed`

**性能测试**:
- 1000 条 InferenceDataPoint 构建 FEEDBACK 数据集,时长 < 30s

#### 验收标准 ✅

- [ ] 低-B 没全审完时不能关闭轮,有明确错误提示
- [ ] 全审完后能成功关闭轮并自动开启新轮
- [ ] 新轮训练数据集包含来自四个源的数据 (LS + 高池 + 低A + 低B审后)
- [ ] 新轮训练完成后,`ModelTrainingRecord.trainingDataSource = "FEEDBACK"`
- [ ] 被消费的数据点 `used_in_round_id` 有值
- [ ] 前端能直观展示轮次演进历史
- [ ] **整个飞轮闭环: 项目创建 → 训练 Round1 → 部署 → 回流 → VLM 判定 → 人工审核 → 关轮 → Round2 训练**,全流程打通

---

## 五、已决定不做的事 (避免过度工程)

| 不做项 | 理由 |
|--------|------|
| ❌ 真实边端对接 (MQTT/gRPC) | 模拟器足够验证闭环,未来再加 |
| ❌ 主动学习策略单独模块 | 低池入池条件已涵盖 "主动学习" 的核心语义 |
| ❌ 显式数据集版本 V1.1/V1.2 | Round 编号本身就是隐式版本 |
| ❌ AutoML 自适应参数 | 用户需求未明确提出,沿用现有 YOLO 手动配置 |
| ❌ 老项目 LEGACY → ITERATIVE 自动迁移 | 老项目维持原状,新项目用新模式,不搞数据迁移 |
| ❌ Project.status 状态机重构 | 保持兼容,新状态在 Round 层承载 |

---

## 六、风险与注意事项

### 6.1 技术风险

| 风险 | 应对 |
|------|------|
| **VLM 调用成本失控** | 加 `vlmQuotaPerRound` 配置,默认每轮 500 条上限 |
| **`inference_data_points` 表膨胀** | 已加复合索引 `(project_id, round_id)` 和 `(project_id, pool_type)`,3000 万行内可承受 |
| **CGLIB 代理坑** | 新增 Service 里调 RestTemplate 时,直接在 `.exchange()` 处捕获具体异常 (见 `.context/LESSONS.md` 2026-03-25 条) |
| **Label Studio 空项目返回 404** | 所有调 `/api/projects/{id}/tasks` 的地方都 catch `HttpClientErrorException.NotFound` |
| **Label Studio 返回 JSON 格式不固定** | 解析前判断 `body.startsWith("[")`,兼容数组和对象两种返回 |
| **并发训练** | 一个项目同时只能一个 active round 在训练,用 `round.status` 字段做软锁 |
| **JPQL DELETE/UPDATE 必加 `@Modifying`** | 所有新增 Repository 方法严格遵循 |

### 6.2 业务风险

| 风险 | 应对 |
|------|------|
| **高池数据可能有错标** | 阈值默认 0.8 已较严格;文档中提示用户可调低阈值换保守策略 |
| **低-A (VLM 背书) 误判** | 提供"定期抽样人审"机制作为后续增强 (本期不做) |
| **LS 端删除项目导致数据点引用失效** | 数据点保留 `image_path` 本地路径,即使 LS 删了也能找到图 |

### 6.3 回归风险

| 风险 | 应对 |
|------|------|
| **阶段 0/1 破坏现有老项目** | 所有老项目 `project_type=LEGACY`,业务逻辑完全不变 |
| **前端 tab 新增破坏现有 tab** | 只新增 tab 不改现有 tab 的组件,采用懒加载 |
| **数据库字段新增导致启动失败** | 所有新字段允许 NULL,`ddl-auto=update` 自动补字段 |

---

## 七、总体时间线

| 阶段 | 工期 | 前置依赖 | 可独立验收 |
|------|------|---------|-----------|
| 阶段 0: 数据模型 | 1-2 天 | 无 | ✅ |
| 阶段 1: Round 自动创建 | 1 天 | 阶段 0 | ✅ |
| 阶段 2: 训练绑定 Round | 1 天 | 阶段 1 | ✅ |
| 阶段 3: 边端模拟器 ⭐ | 2-3 天 | 阶段 2 | ✅ (能看到池子分流) |
| 阶段 4: VLM 二次判定 | 1-2 天 | 阶段 3 | ✅ (能看到 LOW_A/LOW_B) |
| 阶段 5: 闭环回流 | 2 天 | 阶段 4 | ✅ (整个飞轮打通) |

**合计: 8-11 个工作日**

**建议节奏**:
- 前 3 天: 阶段 0-2 (打好地基,不影响现有功能)
- 中间 3 天: 阶段 3 (MVP 能看到演示效果,项目级别有了观感)
- 后 3 天: 阶段 4-5 (闭环,真正具备迭代能力)

---

## 八、下一步行动 (建议)

1. **阅读本文档并确认**: 所有阶段的目标、交付物、验收标准是否符合预期
2. **建仓库分支**: `git checkout -b feature/iteration-upgrade` (在现有 master 基础上拉新分支)
3. **从阶段 0 开始**: 新建 4 个 Entity 和对应 Repository,本地编译通过
4. **小步提交**: 每个阶段结束时 git commit + push,方便回退和 code review
5. **更新 `.context/`**: 每完成一个阶段,在 `LESSONS.md` 追加经验,在 `ARCHITECTURE.md` 更新新表和新接口

---

## 附录 A: 新接口清单汇总

```
# Round 管理
GET    /api/v1/projects/{id}/rounds
GET    /api/v1/projects/{id}/rounds/current
POST   /api/v1/projects/{id}/rounds/close-current
POST   /api/v1/projects/{id}/rounds/{roundId}/trigger-retrain
GET    /api/v1/projects/{id}/rounds/{roundId}/training-preview

# 边端模拟器
POST   /api/v1/edge-simulator/deploy
POST   /api/v1/edge-simulator/inference
POST   /api/v1/edge-simulator/rollback
GET    /api/v1/edge-simulator/deployments
GET    /api/v1/edge-simulator/inference-history
GET    /api/v1/edge-simulator/pool-stats

# 数据点查询
GET    /api/v1/inference-data-points/{id}
GET    /api/v1/projects/{id}/data-points?poolType=&roundId=

# 项目配置
GET    /api/v1/projects/{id}/config
PUT    /api/v1/projects/{id}/config

# 算法服务新增
POST   /api/v1/algo/edge-inference/batch           (边端推理模拟)
POST   /api/v1/algo/reinference/vlm-judge          (VLM 二次判定)
```

## 附录 B: Entity 类名映射

| Entity | 表名 | 核心字段 |
|--------|------|---------|
| `IterationRound` | iteration_rounds | roundNumber, status, trainingRecordId |
| `EdgeDeployment` | edge_deployments | edgeNodeName, status, previousDeploymentId |
| `InferenceDataPoint` | inference_data_points | poolType, vlmDecision, humanReviewed |
| `ProjectConfig` | project_config | highPoolThreshold, vlmQuotaPerRound |

## 附录 C: 关键枚举值

```java
// PoolType
public enum PoolType { HIGH, LOW_A_CANDIDATE, LOW_A, LOW_B, DISCARDED }

// VlmDecision
public enum VlmDecision { KEEP, DISCARD, UNCERTAIN }

// RoundStatus
public enum RoundStatus { ACTIVE, TRAINING, DEPLOYED, COLLECTING, REVIEWING, CLOSED }

// DeploymentStatus
public enum DeploymentStatus { DEPLOYING, ACTIVE, ROLLED_BACK, FAILED }

// TrainingDataSource
public enum TrainingDataSource { INITIAL, FEEDBACK }
```

---

*End of Document*