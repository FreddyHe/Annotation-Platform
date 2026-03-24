# 可行性评估模块开发总结（阶段16-18）

**开发日期**: 2026-03-19  
**开发内容**: 实施计划生成（阶段16）、完整报告生成（阶段17）、前端页面（阶段18）

---

## 一、阶段16：实施计划生成

### 1.1 核心功能

根据三桶分类结果和资源估算数据，动态生成实施计划阶段。

### 1.2 实现文件

- `ImplementationPlanRepository.java`: 添加 `deleteByAssessmentId` 方法支持幂等性
- `ImplementationPlan.java`: 添加 `status` 字段（默认值 "PENDING"）
- `FeasibilityAssessmentService.java`: 实现 `generateImplementationPlan` 方法
- `FeasibilityAssessmentController.java`: 添加 POST `/assessments/{id}/generate-plan` 和 GET `/assessments/{id}/implementation-plans` 接口

### 1.3 生成规则

**桶B存在时**（需定制训练）：
1. 数据采集方案设计（12天）
2. 数据标注（max人天 + 7天缓冲）
3. 模型训练与调优（max GPU时/8 + 5天缓冲）

**桶A存在时**（OVD可用）：
4. OVD配置与校验（4天）

**桶C存在时**（VLM专用）：
5. VLM方案调试（6天）

**始终生成**：
6. 系统集成测试（7天，依赖所有前置阶段）
7. 部署上线（4天，依赖系统集成测试）
8. 持续运维（0天，依赖部署上线）

### 1.4 关键特性

- **幂等性**: 每次调用先删除旧计划，再生成新计划
- **动态天数**: 根据资源估算的实际数据计算
- **依赖管理**: dependencies字段存储逗号分隔的前置阶段名称
- **JSON存储**: tasks字段存储为JSON字符串数组

### 1.5 测试结果

```json
{
  "assessmentId": 97,
  "totalPhases": 6,
  "totalEstimatedDays": 39,
  "phases": [
    {"phaseOrder": 1, "phaseName": "数据采集方案设计", "estimatedDays": 12},
    {"phaseOrder": 2, "phaseName": "数据标注", "estimatedDays": 10},
    {"phaseOrder": 3, "phaseName": "模型训练与调优", "estimatedDays": 6},
    {"phaseOrder": 4, "phaseName": "系统集成测试", "estimatedDays": 7},
    {"phaseOrder": 5, "phaseName": "部署上线", "estimatedDays": 4},
    {"phaseOrder": 6, "phaseName": "持续运维", "estimatedDays": 0}
  ]
}
```

---

## 二、阶段17：完整报告生成

### 2.1 核心功能

聚合所有评估数据，生成包含评估概要、类别详情、实施计划和总结的完整报告。

### 2.2 实现文件

- `FeasibilityAssessmentService.java`: 实现 `generateReport` 方法
- `FeasibilityAssessmentController.java`: 添加 GET `/assessments/{id}/report` 接口

### 2.3 报告结构

```json
{
  "assessmentInfo": {
    "id": 97,
    "assessmentName": "OVD测试评估",
    "status": "COMPLETED",
    "createdAt": "2026-03-19T14:50:14",
    "completedAt": "2026-03-19T19:15:00"
  },
  "categories": [
    {
      "categoryName": "大型石块",
      "categoryNameEn": "large rock",
      "feasibilityBucket": "CUSTOM_TRAINING",
      "confidence": 1.0,
      "reasoning": "需要定制训练 (P=1.00, R=0.50)",
      "ovdResults": [...],
      "qualityScores": [...],
      "datasets": [...],
      "resourceEstimation": {...}
    }
  ],
  "implementationPlan": [...],
  "summary": {
    "totalCategories": 1,
    "bucketA": 0,
    "bucketB": 1,
    "bucketC": 0,
    "totalEstimatedDays": 39,
    "totalEstimatedCost": 0.0
  }
}
```

### 2.4 关键特性

- **数据聚合**: 从7个Repository查询完整数据链
- **按类别关联**: ovdResults、qualityScores、datasets、resourceEstimation都按categoryName关联
- **状态更新**: 首次调用时更新status为COMPLETED，设置completedAt
- **幂等性**: 再次调用不重复更新completedAt
- **统计准确**: summary中的桶分类计数和总天数计算准确

---

## 三、阶段18：前端页面

### 3.1 文件清单

**新增文件**（4个）：
1. `src/api/feasibility.js` - API封装（127行）
2. `src/views/feasibility/AssessmentList.vue` - 列表页（177行）
3. `src/views/feasibility/CreateAssessment.vue` - 创建页（167行）
4. `src/views/feasibility/AssessmentDetail.vue` - 详情页（832行）

**修改文件**（2个）：
1. `src/router/index.js` - 添加3个路由
2. `src/layout/index.vue` - 添加侧边栏菜单项

### 3.2 列表页（AssessmentList.vue）

**功能**：
- 展示评估列表（el-table）
- 状态着色（CREATED灰/PARSING蓝/EVALUATED橙/COMPLETED绿/FAILED红）
- 新建评估按钮 → `/feasibility/create`
- 查看/删除操作
- 点击行跳转详情页

### 3.3 创建页（CreateAssessment.vue）

**功能**：
- el-form表单验证
- 评估名称（必填，2-100字符）
- 需求描述（必填，10-2000字符，8行textarea）
- 图片上传（最多10张，picture-card样式）
- 提交成功后跳转详情页

### 3.4 详情页（AssessmentDetail.vue）

**核心功能**：5步骤流程页面

#### 顶部信息
- 评估名称 + 状态tag + 创建时间
- 5步骤条（el-steps）：需求解析 → OVD测试 → VLM评估 → 资源估算 → 实施规划

#### Step 1 - 需求解析
- 显示原始需求（只读）
- "开始解析"按钮（disabled=!canParse）
- 解析结果表格（类别列表）
- 编辑/删除类别功能
- "确认类别，下一步"按钮

#### Step 2 - OVD测试
- 图片上传区（el-upload）
- "运行OVD测试"按钮
- 结果按类别分组展示
- 每个结果：标注图（可放大）+ prompt + 检测数 + 置信度

#### Step 3 - VLM评估
- "开始评估"按钮
- 三桶分类卡片（绿色桶A/橙色桶B/蓝色桶C）
- 每个类别显示：名称 + 置信度 + 原因

#### Step 4 - 资源估算
- "开始估算"按钮
- 数据集表格（来源/名称/样本数/格式/许可证/相关度）
- 资源表格（类别/路径/标注图片数/人天/GPU时/总天数/成本）

#### Step 5 - 实施规划
- "生成计划"按钮
- el-timeline展示实施计划
- 每个节点：阶段名 + 天数 + 任务列表 + 交付物 + 依赖
- "查看完整报告"按钮 → el-dialog展示
- "导出JSON"功能（Blob下载）

#### 按钮禁用逻辑（computed）
```javascript
const canParse = computed(() => status.value === 'CREATED')
const canOvdTest = computed(() => status.value === 'PARSED')
const canEvaluate = computed(() => status.value === 'OVD_TESTED')
const canEstimate = computed(() => status.value === 'EVALUATED')
const canGenPlan = computed(() => ['EVALUATED','ESTIMATING'].includes(status.value))
```

### 3.5 构建测试

```bash
npm run build
✓ built in 28.13s
```

**生成文件**：
- AssessmentList-B08iOmKu.css (0.39 kB)
- AssessmentDetail-BGI8rWeM.css (1.94 kB)
- CreateAssessment-CKK3gbAn.css (0.37 kB)
- feasibility-7DFjTQrv.js (1.59 kB)
- AssessmentDetail-SU2oJHT3.js (18.23 kB)

**警告**: Element Plus主包较大（1,210 kB），建议后续优化为按需导入。

---

## 四、遇到的问题与解决方案

### 4.1 ImplementationPlan缺失status字段

**问题**: 数据库表有status列且NOT NULL，但实体没有对应字段，导致插入时报错：`NULL not allowed for column "STATUS"`

**解决**: 在实体中添加status字段，使用`@Builder.Default`设置默认值"PENDING"

### 4.2 Repository方法名不匹配

**问题**: Service调用`findByAssessmentId`，但Repository实际方法是`findByAssessmentIdOrderByTestTimeDesc`

**解决**: 使用正确的方法名，包括排序后缀

### 4.3 前端构建chunk过大

**问题**: Element Plus主包1,210 kB，影响首屏加载

**建议**: 使用按需导入、手动分包、动态import

---

## 五、代码统计

### 后端代码
- Java文件修改：3个
- 新增方法：2个（generateImplementationPlan、generateReport）
- 新增接口：3个（POST generate-plan、GET implementation-plans、GET report）
- 代码行数：约350行

### 前端代码
- 新增文件：4个
- 修改文件：2个
- 总代码行数：约1,300行
- 组件数：3个页面组件 + 1个API模块

---

## 六、测试验证

### 后端测试
- ✅ 阶段16生成计划：6个阶段，总39天
- ✅ 阶段17完整报告：包含所有模块，统计准确
- ✅ 幂等性验证：completedAt不变
- ✅ 数据关联正确：按categoryName关联

### 前端测试
- ✅ 构建成功：npm run build通过
- ✅ 开发服务器：正常启动在5173端口
- ✅ 无编译错误
- ✅ 无控制台JS错误

---

## 七、后续优化建议

1. **前端性能优化**：
   - Element Plus按需导入
   - 路由懒加载优化
   - 图片懒加载

2. **功能增强**：
   - 实施计划编辑功能
   - 报告导出PDF格式
   - 评估进度可视化

3. **用户体验**：
   - 添加操作引导
   - 优化移动端适配
   - 添加快捷操作

---

**开发完成时间**: 2026-03-19 20:30  
**总开发时长**: 约3小时  
**状态**: ✅ 全部功能正常运行
