# 结果查看功能问题分析与解决方案

## 问题描述

用户报告三个问题：
1. **检测结果为空** - DINO 检测结果没有显示
2. **清洗结果为空** - VLM 清洗结果没有显示
3. **审核结果为空** - Label Studio 中已审核的任务没有显示

## 根本原因分析

### 问题1 & 2: 检测结果和清洗结果为空

**数据流分析**：
```
自动标注流程：
1. DINO 检测 → 保存到 DetectionResult 表 (type=DINO_DETECTION)
2. VLM 清洗 → 保存到 DetectionResult 表 (type=VLM_CLEANING)
3. 同步到 Label Studio → 只同步清洗后的结果
```

**问题根源**：
- ✅ 数据**已保存**到 `DetectionResult` 表
- ❌ `getProjectImages` API **没有关联查询** `DetectionResult` 数据
- ❌ 前端期望的数据结构 `img.detections` 和 `img.cleaningResults` 没有返回

**相关代码**：

1. **前端期望的数据结构** (`DetectionResults.vue:48`):
```javascript
const response = await projectAPI.getProjectImages(props.project.id, { page: 1, pageSize: 1000 })
const images = response.data.images || []
images.forEach(img => {
  if (img.detections && img.detections.length > 0) {  // ❌ 这个字段没有返回
    // ...
  }
})
```

2. **原始 API 实现** (`ProjectController.java:214-260`):
```java
@GetMapping("/{id}/images")
public Result<Map<String, Object>> getProjectImages(...) {
    List<ProjectImage> images = projectImageRepository.findProjectImagesNativeByType(id);
    // ❌ 只返回 ProjectImage 基本信息，没有关联 DetectionResult
    List<ProjectImageResponse> imageResponses = paginatedImages.stream()
        .map(image -> ProjectImageResponse.builder()
            .id(image.getId())
            .fileName(image.getFileName())
            // ... 没有 detections 和 cleaningResults 字段
            .build())
        .collect(Collectors.toList());
}
```

3. **数据实际存储位置** (`DetectionResult` 实体):
```java
@Entity
@Table(name = "detection_results")
public class DetectionResult {
    @ManyToOne
    private ProjectImage image;
    
    @Enumerated(EnumType.STRING)
    private ResultType type;  // DINO_DETECTION 或 VLM_CLEANING
    
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> resultData;  // 包含 label, bbox, score 等
}
```

### 问题3: 审核结果为空

**可能原因**：
1. Label Studio API 调用路径或参数不正确
2. 返回的数据结构解析有问题
3. 用户的审核数据没有正确保存到 Label Studio

需要查看日志验证 `getProjectReviewResults` API 的实际返回。

## 解决方案

### ✅ 已实现：修复检测结果和清洗结果

**修改内容**：

1. **添加 Repository 查询方法** (`DetectionResultRepository.java`):
```java
@Query("SELECT dr FROM DetectionResult dr WHERE dr.image.id IN :imageIds AND dr.type = :type")
List<DetectionResult> findByImageIdInAndType(
    @Param("imageIds") List<Long> imageIds, 
    @Param("type") DetectionResult.ResultType type
);
```

2. **重写 `getProjectImages` API** (`ProjectController.java:214-320`):
```java
@GetMapping("/{id}/images")
public Result<Map<String, Object>> getProjectImages(...) {
    // 1. 获取图片列表
    List<ProjectImage> paginatedImages = images.subList(start, end);
    List<Long> imageIds = paginatedImages.stream()
        .map(ProjectImage::getId)
        .collect(Collectors.toList());
    
    // 2. 查询 DINO 检测结果
    List<DetectionResult> dinoResults = 
        detectionResultRepository.findByImageIdInAndType(
            imageIds, DetectionResult.ResultType.DINO_DETECTION);
    
    // 3. 查询 VLM 清洗结果
    List<DetectionResult> vlmResults = 
        detectionResultRepository.findByImageIdInAndType(
            imageIds, DetectionResult.ResultType.VLM_CLEANING);
    
    // 4. 按图片ID分组
    Map<Long, List<DetectionResult>> dinoByImage = 
        dinoResults.stream().collect(Collectors.groupingBy(r -> r.getImage().getId()));
    Map<Long, List<DetectionResult>> vlmByImage = 
        vlmResults.stream().collect(Collectors.groupingBy(r -> r.getImage().getId()));
    
    // 5. 构建响应，包含 detections 和 cleaningResults
    List<Map<String, Object>> imageResponses = paginatedImages.stream()
        .map(image -> {
            Map<String, Object> response = new HashMap<>();
            response.put("id", image.getId());
            response.put("name", image.getFileName());
            // ...
            
            // 添加检测结果
            List<Map<String, Object>> detections = new ArrayList<>();
            if (dinoByImage.containsKey(image.getId())) {
                for (DetectionResult dr : dinoByImage.get(image.getId())) {
                    Map<String, Object> resultData = dr.getResultData();
                    Map<String, Object> detection = new HashMap<>();
                    detection.put("label", resultData.get("label"));
                    detection.put("confidence", resultData.get("score"));
                    detection.put("bbox", resultData.get("bbox"));
                    detections.add(detection);
                }
            }
            response.put("detections", detections);
            
            // 添加清洗结果
            List<Map<String, Object>> cleaningResults = new ArrayList<>();
            if (vlmByImage.containsKey(image.getId())) {
                for (DetectionResult dr : vlmByImage.get(image.getId())) {
                    Map<String, Object> resultData = dr.getResultData();
                    Map<String, Object> cleaning = new HashMap<>();
                    
                    String decision = (String) resultData.get("vlm_decision");
                    String label = (String) resultData.get("label");
                    
                    cleaning.put("originalLabels", Arrays.asList(label));
                    if ("keep".equals(decision)) {
                        cleaning.put("cleanedLabels", Arrays.asList(label));
                        cleaning.put("removedLabels", new ArrayList<>());
                    } else {
                        cleaning.put("cleanedLabels", new ArrayList<>());
                        cleaning.put("removedLabels", Arrays.asList(label));
                    }
                    cleaning.put("reason", resultData.get("vlm_reasoning"));
                    cleaningResults.add(cleaning);
                }
            }
            response.put("cleaningResults", cleaningResults);
            
            return response;
        })
        .collect(Collectors.toList());
    
    return Result.success(Map.of("images", imageResponses, "total", images.size()));
}
```

3. **注入依赖** (`ProjectController.java`):
```java
@RequiredArgsConstructor
public class ProjectController {
    private final DetectionResultRepository detectionResultRepository;  // 新增
    // ...
}
```

### 待验证：审核结果问题

**需要检查的点**：

1. **Label Studio API 调用** (`LabelStudioProxyServiceImpl.java:1022-1103`):
```java
@Override
public Map<String, Object> getProjectReviewResults(Long lsProjectId, Long userId) {
    String url = String.format("%s/api/projects/%d/tasks", labelStudioUrl, lsProjectId);
    // 检查这个 API 是否返回正确的数据
}
```

2. **前端 API 调用** (`ReviewResults.vue:145`):
```javascript
const response = await projectAPI.getReviewResults(props.project.id)
results.value = response.data.tasks || []
```

**调试步骤**：
1. 查看后端日志 `/tmp/springboot.log`，搜索 "获取审核结果"
2. 检查 Label Studio API 返回的原始数据
3. 验证 `project.labelStudioProjectId` 是否正确

## 测试步骤

### 1. 测试检测结果
1. 刷新前端页面
2. 进入项目详情 → 结果查看 → 检测结果
3. 应该能看到 DINO 检测的所有边界框和标签

### 2. 测试清洗结果
1. 进入结果查看 → 清洗结果
2. 应该能看到 VLM 清洗后保留/删除的标注

### 3. 测试审核结果
1. 进入结果查看 → 审核结果
2. 查看后端日志确认 API 调用情况
3. 如果仍为空，需要进一步调试 Label Studio API

## 数据库表结构

```sql
-- 检测和清洗结果存储
CREATE TABLE detection_results (
    id BIGINT PRIMARY KEY,
    image_id BIGINT,  -- 关联 project_images
    task_id BIGINT,   -- 关联 annotation_tasks
    type VARCHAR(20), -- 'DINO_DETECTION' 或 'VLM_CLEANING'
    result_data JSON, -- 包含 label, bbox, score, vlm_decision, vlm_reasoning 等
    created_at TIMESTAMP
);

-- 图片基本信息
CREATE TABLE project_images (
    id BIGINT PRIMARY KEY,
    project_id BIGINT,
    file_path VARCHAR(500),
    file_name VARCHAR(255),
    status VARCHAR(20)
);
```

## 关键代码文件

### 后端
- `ProjectController.java` - 修改了 `getProjectImages` 方法
- `DetectionResultRepository.java` - 添加了 `findByImageIdInAndType` 方法
- `LabelStudioProxyServiceImpl.java` - 实现了 `getProjectReviewResults` 方法

### 前端
- `DetectionResults.vue` - 显示检测结果
- `CleaningResults.vue` - 显示清洗结果
- `ReviewResults.vue` - 显示审核结果

## 总结

**已修复**：
- ✅ 检测结果和清洗结果的数据查询逻辑
- ✅ API 返回包含 `detections` 和 `cleaningResults` 字段

**待验证**：
- ⏳ 审核结果是否能正确从 Label Studio 获取
- ⏳ 前端页面是否正确显示所有三种结果

**下一步**：
1. 刷新前端测试检测结果和清洗结果
2. 查看审核结果的后端日志
3. 根据日志调试 Label Studio API 调用
