# 核心业务 API 接口清单

## 📡 RESTful API 设计

### 基础信息
- **Base URL**: `http://localhost:8080/api/v1`
- **认证方式**: JWT Bearer Token
- **响应格式**: JSON
- **字符编码**: UTF-8

---

## 1. 认证模块 (Auth)

### 1.1 用户注册
```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "username": "string",
  "email": "string",
  "password": "string",
  "displayName": "string",
  "organizationName": "string"
}

Response 201:
{
  "success": true,
  "message": "注册成功",
  "data": {
    "userId": 1,
    "username": "string",
    "email": "string",
    "displayName": "string",
    "organization": {
      "id": 1,
      "name": "string",
      "displayName": "string"
    }
  }
}
```

### 1.2 用户登录
```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "string",
  "password": "string"
}

Response 200:
{
  "success": true,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "username": "string",
      "email": "string",
      "displayName": "string",
      "organization": {
        "id": 1,
        "name": "string",
        "displayName": "string"
      }
    }
  }
}
```

### 1.3 用户登出
```http
POST /api/v1/auth/logout
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "message": "登出成功"
}
```

### 1.4 刷新 Token
```http
POST /api/v1/auth/refresh
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}
```

---

## 2. 用户管理模块 (User)

### 2.1 获取当前用户信息
```http
GET /api/v1/users/me
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "id": 1,
    "username": "string",
    "email": "string",
    "displayName": "string",
    "organization": {
      "id": 1,
      "name": "string",
      "displayName": "string"
    },
    "createdAt": "2026-01-01T00:00:00Z",
    "lastLogin": "2026-01-01T00:00:00Z"
  }
}
```

### 2.2 更新用户信息
```http
PUT /api/v1/users/me
Authorization: Bearer {token}
Content-Type: application/json

{
  "displayName": "string",
  "email": "string"
}

Response 200:
{
  "success": true,
  "message": "更新成功",
  "data": {
    "id": 1,
    "username": "string",
    "email": "string",
    "displayName": "string"
  }
}
```

### 2.3 修改密码
```http
PUT /api/v1/users/me/password
Authorization: Bearer {token}
Content-Type: application/json

{
  "oldPassword": "string",
  "newPassword": "string"
}

Response 200:
{
  "success": true,
  "message": "密码修改成功"
}
```

---

## 3. 组织管理模块 (Organization)

### 3.1 获取组织列表
```http
GET /api/v1/organizations
Authorization: Bearer {token}
Query Parameters:
  - page: int (default: 0)
  - size: int (default: 20)
  - sort: string (default: createdAt,desc)

Response 200:
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "name": "string",
        "displayName": "string",
        "createdAt": "2026-01-01T00:00:00Z",
        "userCount": 10,
        "projectCount": 5
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "totalElements": 100,
      "totalPages": 5
    }
  }
}
```

### 3.2 获取组织详情
```http
GET /api/v1/organizations/{id}
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "id": 1,
    "name": "string",
    "displayName": "string",
    "createdAt": "2026-01-01T00:00:00Z",
    "userCount": 10,
    "projectCount": 5,
    "users": [
      {
        "id": 1,
        "username": "string",
        "displayName": "string",
        "email": "string"
      }
    ]
  }
}
```

---

## 4. 项目管理模块 (Project)

### 4.1 创建项目
```http
POST /api/v1/projects
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "string",
  "labels": {
    "cat": "猫咪",
    "dog": "狗"
  }
}

Response 201:
{
  "success": true,
  "message": "项目创建成功",
  "data": {
    "id": 1,
    "name": "string",
    "organization": {
      "id": 1,
      "name": "string"
    },
    "createdBy": {
      "id": 1,
      "username": "string"
    },
    "labels": {
      "cat": "猫咪",
      "dog": "狗"
    },
    "status": "DRAFT",
    "totalImages": 0,
    "processedImages": 0,
    "createdAt": "2026-01-01T00:00:00Z"
  }
}
```

### 4.2 获取项目列表
```http
GET /api/v1/projects
Authorization: Bearer {token}
Query Parameters:
  - page: int (default: 0)
  - size: int (default: 20)
  - status: string (DRAFT, UPLOADING, PROCESSING, COMPLETED, FAILED)
  - sort: string (default: createdAt,desc)

Response 200:
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "name": "string",
        "organization": {
          "id": 1,
          "name": "string"
        },
        "createdBy": {
          "id": 1,
          "username": "string"
        },
        "status": "COMPLETED",
        "totalImages": 100,
        "processedImages": 100,
        "labels": {
          "cat": "猫咪"
        },
        "createdAt": "2026-01-01T00:00:00Z",
        "updatedAt": "2026-01-01T00:00:00Z"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "totalElements": 50,
      "totalPages": 3
    }
  }
}
```

### 4.3 获取项目详情
```http
GET /api/v1/projects/{id}
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "id": 1,
    "name": "string",
    "organization": {
      "id": 1,
      "name": "string"
    },
    "createdBy": {
      "id": 1,
      "username": "string"
    },
    "status": "COMPLETED",
    "totalImages": 100,
    "processedImages": 100,
    "labels": {
      "cat": "猫咪"
    },
    "createdAt": "2026-01-01T00:00:00Z",
    "updatedAt": "2026-01-01T00:00:00Z",
    "statistics": {
      "totalImages": 100,
      "processedImages": 100,
      "pendingImages": 0,
      "failedImages": 0,
      "detectionResults": 500,
      "vlmCleaned": 450
    }
  }
}
```

### 4.4 更新项目
```http
PUT /api/v1/projects/{id}
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "string",
  "labels": {
    "cat": "猫咪",
    "dog": "狗"
  }
}

Response 200:
{
  "success": true,
  "message": "项目更新成功",
  "data": {
    "id": 1,
    "name": "string",
    "labels": {
      "cat": "猫咪",
      "dog": "狗"
    },
    "updatedAt": "2026-01-01T00:00:00Z"
  }
}
```

### 4.5 删除项目
```http
DELETE /api/v1/projects/{id}
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "message": "项目删除成功"
}
```

### 4.6 获取项目图片列表
```http
GET /api/v1/projects/{id}/images
Authorization: Bearer {token}
Query Parameters:
  - page: int (default: 0)
  - size: int (default: 20)
  - status: string (PENDING, PROCESSING, COMPLETED, FAILED)

Response 200:
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "fileName": "image.jpg",
        "filePath": "/path/to/image.jpg",
        "fileSize": 1024000,
        "status": "COMPLETED",
        "uploadedAt": "2026-01-01T00:00:00Z"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "totalElements": 100,
      "totalPages": 5
    }
  }
}
```

---

## 5. 文件上传模块 (File Upload)

### 5.1 上传文件分块
```http
POST /api/v1/upload/chunk
Authorization: Bearer {token}
Content-Type: multipart/form-data

Form Data:
  - chunk: File (文件分块)
  - filename: string (文件名)
  - fileId: string (文件唯一标识)
  - chunkIndex: int (分块索引，从0开始)
  - totalChunks: int (总分块数)
  - fileSize: long (文件总大小)
  - projectId: long (项目ID)

Response 200:
{
  "success": true,
  "message": "分块上传成功",
  "data": {
    "chunkIndex": 0,
    "receivedChunks": 1,
    "totalChunks": 10
  }
}
```

### 5.2 合并文件分块
```http
POST /api/v1/upload/merge
Authorization: Bearer {token}
Content-Type: application/json

{
  "fileId": "string",
  "filename": "string",
  "totalChunks": 10,
  "projectId": 1
}

Response 200:
{
  "success": true,
  "message": "文件合并成功",
  "data": {
    "imageId": 1,
    "fileName": "image.jpg",
    "filePath": "/path/to/image.jpg",
    "fileSize": 1024000
  }
}
```

### 5.3 获取上传进度
```http
GET /api/v1/upload/progress/{fileId}
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "fileId": "string",
    "filename": "string",
    "totalChunks": 10,
    "receivedChunks": 5,
    "progress": 50,
    "status": "UPLOADING"
  }
}
```

### 5.4 获取项目文件列表
```http
GET /api/v1/projects/{id}/files
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": [
    {
      "id": 1,
      "fileName": "image.jpg",
      "filePath": "/path/to/image.jpg",
      "fileSize": 1024000,
      "uploadedAt": "2026-01-01T00:00:00Z",
      "status": "COMPLETED"
    }
  ]
}
```

### 5.5 删除文件
```http
DELETE /api/v1/files/{id}
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "message": "文件删除成功"
}
```

---

## 6. 算法任务模块 (Algorithm)

### 6.1 运行 DINO 检测
```http
POST /api/v1/algorithm/dino
Authorization: Bearer {token}
Content-Type: application/json

{
  "projectId": 1,
  "labels": ["cat", "dog"],
  "boxThreshold": 0.3,
  "textThreshold": 0.25
}

Response 200:
{
  "success": true,
  "message": "DINO 检测任务已启动",
  "data": {
    "taskId": 1,
    "type": "DINO_DETECTION",
    "status": "RUNNING",
    "startedAt": "2026-01-01T00:00:00Z",
    "parameters": {
      "labels": ["cat", "dog"],
      "boxThreshold": 0.3,
      "textThreshold": 0.25
    }
  }
}
```

### 6.2 运行 VLM 清洗
```http
POST /api/v1/algorithm/vlm
Authorization: Bearer {token}
Content-Type: application/json

{
  "projectId": 1,
  "model": "Qwen3-VL-4B-Instruct",
  "maxTokens": 4096,
  "minDim": 10
}

Response 200:
{
  "success": true,
  "message": "VLM 清洗任务已启动",
  "data": {
    "taskId": 2,
    "type": "VLM_CLEANING",
    "status": "RUNNING",
    "startedAt": "2026-01-01T00:00:00Z",
    "parameters": {
      "model": "Qwen3-VL-4B-Instruct",
      "maxTokens": 4096,
      "minDim": 10
    }
  }
}
```

### 6.3 运行 YOLO 训练
```http
POST /api/v1/algorithm/yolo/train
Authorization: Bearer {token}
Content-Type: application/json

{
  "projectId": 1,
  "epochs": 100,
  "batchSize": 16,
  "imageSize": 640,
  "model": "yolov8n.pt"
}

Response 200:
{
  "success": true,
  "message": "YOLO 训练任务已启动",
  "data": {
    "taskId": 3,
    "type": "YOLO_TRAINING",
    "status": "RUNNING",
    "startedAt": "2026-01-01T00:00:00Z",
    "parameters": {
      "epochs": 100,
      "batchSize": 16,
      "imageSize": 640,
      "model": "yolov8n.pt"
    }
  }
}
```

### 6.4 获取任务状态
```http
GET /api/v1/algorithm/tasks/{taskId}
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "id": 1,
    "type": "DINO_DETECTION",
    "status": "COMPLETED",
    "startedAt": "2026-01-01T00:00:00Z",
    "completedAt": "2026-01-01T01:00:00Z",
    "parameters": {
      "labels": ["cat", "dog"],
      "boxThreshold": 0.3
    },
    "progress": {
      "totalImages": 100,
      "processedImages": 100,
      "percentage": 100
    },
    "errorMessage": null
  }
}
```

### 6.5 获取项目任务列表
```http
GET /api/v1/projects/{id}/tasks
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": [
    {
      "id": 1,
      "type": "DINO_DETECTION",
      "status": "COMPLETED",
      "startedAt": "2026-01-01T00:00:00Z",
      "completedAt": "2026-01-01T01:00:00Z"
    },
    {
      "id": 2,
      "type": "VLM_CLEANING",
      "status": "RUNNING",
      "startedAt": "2026-01-01T01:00:00Z",
      "completedAt": null
    }
  ]
}
```

### 6.6 获取检测结果
```http
GET /api/v1/projects/{id}/detections
Authorization: Bearer {token}
Query Parameters:
  - type: string (DINO_DETECTION, VLM_CLEANING, YOLO_PREDICTION)
  - imageId: long (可选，指定图片ID)

Response 200:
{
  "success": true,
  "data": [
    {
      "id": 1,
      "imageId": 1,
      "taskId": 1,
      "type": "DINO_DETECTION",
      "resultData": {
        "boxes": [
          {
            "label": "cat",
            "confidence": 0.95,
            "bbox": [100, 100, 200, 200]
          }
        ]
      },
      "createdAt": "2026-01-01T00:00:00Z"
    }
  ]
}
```

---

## 7. Label Studio 集成模块 (Label Studio)

### 7.1 导入到 Label Studio
```http
POST /api/v1/label-studio/import
Authorization: Bearer {token}
Content-Type: application/json

{
  "projectId": 1,
  "labelStudioProjectId": 1
}

Response 200:
{
  "success": true,
  "message": "导入到 Label Studio 成功",
  "data": {
    "labelStudioProjectId": 1,
    "importedImages": 100,
    "importedAnnotations": 500
  }
}
```

### 7.2 从 Label Studio 同步
```http
POST /api/v1/label-studio/sync
Authorization: Bearer {token}
Content-Type: application/json

{
  "projectId": 1,
  "labelStudioProjectId": 1
}

Response 200:
{
  "success": true,
  "message": "从 Label Studio 同步成功",
  "data": {
    "syncedAnnotations": 450,
    "updatedAt": "2026-01-01T00:00:00Z"
  }
}
```

### 7.3 获取 Label Studio 登录链接
```http
GET /api/v1/label-studio/login-url?projectId={projectId}
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "loginUrl": "http://label-studio:5000/login-form?email=xxx&password=xxx&next=/projects/1/",
    "labelStudioUrl": "http://label-studio:5000"
  }
}
```

---

## 8. 统计分析模块 (Statistics)

### 8.1 获取项目统计
```http
GET /api/v1/projects/{id}/statistics
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "totalImages": 100,
    "processedImages": 100,
    "pendingImages": 0,
    "failedImages": 0,
    "detectionResults": 500,
    "vlmCleaned": 450,
    "labelDistribution": {
      "cat": 300,
      "dog": 200
    },
    "tasks": {
      "total": 3,
      "completed": 2,
      "running": 1,
      "failed": 0
    }
  }
}
```

### 8.2 获取组织统计
```http
GET /api/v1/organizations/{id}/statistics
Authorization: Bearer {token}

Response 200:
{
  "success": true,
  "data": {
    "totalUsers": 10,
    "totalProjects": 5,
    "totalImages": 1000,
    "totalAnnotations": 5000,
    "activeProjects": 3
  }
}
```

---

## 统一响应格式

### 成功响应
```json
{
  "success": true,
  "message": "操作成功",
  "data": {}
}
```

### 错误响应
```json
{
  "success": false,
  "message": "错误信息",
  "errorCode": "ERROR_CODE",
  "details": {}
}
```

### 分页响应
```json
{
  "success": true,
  "data": {
    "content": [],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "totalElements": 100,
      "totalPages": 5
    }
  }
}
```

---

## 错误码说明

| 错误码 | 说明 |
|--------|------|
| AUTH_001 | 未认证 |
| AUTH_002 | Token 过期 |
| AUTH_003 | 用户名或密码错误 |
| AUTH_004 | 用户已存在 |
| USER_001 | 用户不存在 |
| USER_002 | 权限不足 |
| ORG_001 | 组织不存在 |
| ORG_002 | 组织名称已存在 |
| PROJ_001 | 项目不存在 |
| PROJ_002 | 项目名称已存在 |
| FILE_001 | 文件不存在 |
| FILE_002 | 文件上传失败 |
| FILE_003 | 文件大小超限 |
| TASK_001 | 任务不存在 |
| TASK_002 | 任务执行失败 |
| LS_001 | Label Studio 连接失败 |
| LS_002 | Label Studio 认证失败 |
