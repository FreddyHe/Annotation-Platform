# 自动标注工作流架构说明

## 概述

本系统采用微服务架构，将自动标注流程分解为三个核心组件：

1. **Spring Boot 后端** - 工作流编排者 (Orchestrator)
2. **FastAPI 算法服务** - 大模型 API 网关 (Adapter)
3. **Label Studio** - 标注平台

## 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                    前端 Vue (用户界面)                            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│              Spring Boot 后端 (编排服务)                          │
│  - 项目管理                                                      │
│  - 用户认证                                                      │
│  - 数据库操作                                                    │
│  - 工作流编排                                                    │
│  - 调用 FastAPI 算法服务                                         │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│              FastAPI 算法服务 (模型网关)                          │
│  - DINO 检测接口 (Mock)                                         │
│  - VLM 清洗接口 (Mock)                                          │
│  - YOLO 训练接口                                                 │
│  - 任务状态管理                                                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│              Label Studio (标注平台)                             │
│  - 项目管理                                                      │
│  - 任务分配                                                      │
│  - 人工标注                                                      │
└─────────────────────────────────────────────────────────────────┘
```

## API 接口规范

### 1. DINO 检测接口

**端点**: `POST /api/v1/algo/detect/dino`

**请求参数**:
```json
{
  "project_id": 1,
  "image_paths": ["/path/to/image1.jpg", "/path/to/image2.jpg"],
  "labels": ["cat", "dog", "car"],
  "api_key": "optional_api_key",
  "endpoint": "optional_endpoint"
}
```

**响应格式**:
```json
{
  "success": true,
  "message": "DINO detection task started successfully",
  "task_id": "uuid-12345",
  "status": "RUNNING",
  "results": []
}
```

### 2. VLM 清洗接口

**端点**: `POST /api/v1/algo/clean/vlm`

**请求参数**:
```json
{
  "project_id": 1,
  "detections": [
    {
      "image_path": "/path/to/image1.jpg",
      "label": "cat",
      "bbox": [100.0, 150.0, 200.0, 300.0],
      "score": 0.85
    }
  ],
  "label_definitions": {
    "cat": "A domestic cat with four legs and a tail",
    "dog": "A domestic dog with four legs and a tail"
  },
  "api_key": "optional_api_key",
  "endpoint": "optional_endpoint"
}
```

**响应格式**:
```json
{
  "success": true,
  "message": "VLM cleaning task started successfully",
  "task_id": "uuid-67890",
  "status": "RUNNING",
  "results": []
}
```

### 3. 任务状态查询

**端点**: `GET /api/v1/algo/status/{task_id}`

**响应格式**:
```json
{
  "task_id": "uuid-12345",
  "task_type": "DINO_DETECTION",
  "status": "completed",
  "progress": 100,
  "total": 10,
  "processed": 10,
  "created_at": "2024-01-01T12:00:00",
  "completed_at": "2024-01-01T12:05:00",
  "error": null
}
```

### 4. 任务结果查询

**端点**: `GET /api/v1/algo/results/{task_id}`

**响应格式**:
```json
{
  "success": true,
  "task_id": "uuid-12345",
  "status": "completed",
  "total": 10,
  "processed": 10,
  "results": [
    {
      "image_path": "/path/to/image1.jpg",
      "image_name": "image1.jpg",
      "detections": [
        {
          "label": "cat",
          "bbox": [100.0, 150.0, 200.0, 300.0],
          "score": 0.85
        }
      ],
      "labels": ["cat", "dog", "car"]
    }
  ]
}
```

## 自动标注工作流

### 步骤 1: 启动自动标注

**端点**: `POST /api/v1/auto-annotation/start/{projectId}`

**响应**:
```json
{
  "code": 200,
  "message": "Auto annotation started successfully"
}
```

### 步骤 2: 工作流执行

1. **获取项目信息**
   - 从数据库获取项目配置
   - 获取图片列表
   - 获取标签定义

2. **调用 DINO 检测**
   - 构建请求参数
   - 调用 FastAPI DINO 接口
   - 获取检测结果

3. **调用 VLM 清洗**
   - 提取 DINO 检测结果
   - 构建 VLM 清洗请求
   - 调用 FastAPI VLM 接口
   - 获取清洗结果

4. **保存结果**
   - 解析 VLM 清洗结果
   - 保存 `keep` 决策的检测框
   - 记录任务状态

5. **导入 Label Studio**
   - 调用 Label Studio API
   - 创建项目（如果不存在）
   - 挂载图片存储
   - 导入预测结果

### 步骤 3: 查询进度

前端可以通过轮询任务状态接口获取进度：

```javascript
// 轮询任务状态
async function pollTaskStatus(taskId) {
  const response = await fetch(`/api/v1/algo/status/${taskId}`);
  const data = await response.json();
  
  if (data.status === 'completed') {
    // 任务完成
    return data;
  } else if (data.status === 'failed') {
    // 任务失败
    throw new Error(data.error);
  } else {
    // 继续轮询
    setTimeout(() => pollTaskStatus(taskId), 2000);
  }
}
```

## 数据库实体

### AnnotationTask (标注任务)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| project | Project | 关联项目 |
| type | Enum | 任务类型 (DINO_DETECTION, VLM_CLEANING) |
| status | Enum | 任务状态 (PENDING, RUNNING, COMPLETED, FAILED) |
| startedAt | LocalDateTime | 开始时间 |
| completedAt | LocalDateTime | 完成时间 |
| errorMessage | String | 错误信息 |
| parameters | JSON | 任务参数 |

### DetectionResult (检测结果)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| image | ProjectImage | 关联图片 |
| task | AnnotationTask | 关联任务 |
| type | Enum | 结果类型 (DINO_DETECTION, VLM_CLEANING) |
| resultData | JSON | 结果数据 |
| createdAt | LocalDateTime | 创建时间 |

## 配置说明

### FastAPI 算法服务配置

```python
# config.py
settings = Settings(
    APP_NAME="Algorithm Service",
    APP_VERSION="1.0.0",
    API_PREFIX="/api/v1",
    HOST="0.0.0.0",
    PORT=8001,
    CORS_ORIGINS=["http://localhost:3000", "http://localhost:8080"],
    UPLOAD_BASE_PATH="/root/autodl-fs/uploads"
)
```

### Spring Boot 后端配置

```yaml
# application.yml
app:
  algorithm:
    url: http://localhost:8001
    timeout: 600000
```

## Mock 数据说明

当前实现使用 Mock 数据，实际部署时需要替换为真实的云服务调用：

### DINO 检测 Mock

```python
# routers/auto_annotation.py
detections = [
    {
        "label": labels[0] if labels else "object",
        "bbox": [100.0, 150.0, 200.0, 300.0],
        "score": 0.85
    }
]
```

### VLM 清洗 Mock

```python
vlm_result = {
    "decision": "keep",
    "reasoning": f"Mock VLM decision: Detected {label} with confidence {score:.2f}."
}
```

## 下一步计划

1. **替换 Mock 数据为真实云服务调用**
   - 实现 DINO 云服务 API 调用
   - 实现 VLM 云服务 API 调用

2. **实现任务状态轮询**
   - 前端轮询后端任务状态
   - 后端轮询 FastAPI 任务状态

3. **集成 Label Studio 导入**
   - 调用 Label Studio API 导入预测结果
   - 处理导入失败重试

4. **添加任务队列**
   - 使用 Redis 或 RabbitMQ 实现任务队列
   - 支持并发任务处理

5. **添加监控和日志**
   - 集成 Prometheus 监控
   - 完善日志记录

## 运行说明

### 启动 FastAPI 算法服务

```bash
cd /root/autodl-fs/Annotation-Platform/algorithm-service
python main.py
```

### 启动 Spring Boot 后端

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean compile spring-boot:run
```

### 启动 Label Studio

```bash
label-studio start --data-dir /root/label_studio_data_fast --port 5001
```

## 测试

### 测试自动标注流程

```bash
# 1. 创建测试项目
curl -X POST http://localhost:8080/api/v1/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "test-project", "labels": ["cat", "dog"]}'

# 2. 上传测试图片
curl -X POST http://localhost:8080/api/v1/upload \
  -F "file=@test.jpg" \
  -F "projectId=1"

# 3. 启动自动标注
curl -X POST http://localhost:8080/api/v1/auto-annotation/start/1

# 4. 查询任务状态
curl http://localhost:8080/api/v1/auto-annotation/status/{taskId}
```

## 注意事项

1. **绝对禁止**在 Python 代码中加载本地模型权重
2. 所有模型推理都通过云服务 API 调用
3. Mock 数据仅用于开发和测试
4. 实际部署时需要替换为真实的云服务地址
5. 确保 FastAPI 服务和 Spring Boot 服务网络互通
