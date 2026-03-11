# Annotation Platform - Phase 1 Design

## 📋 第一阶段：数据库设计与架构规划

### 1. 数据库 ER 图设计

```
┌─────────────────────────────────────────────────────────────────┐
│                        数据库架构设计                            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────┐         ┌─────────────────────┐
│     Organization     │         │       User          │
├─────────────────────┤         ├─────────────────────┤
│ id (PK)            │◄────────│ id (PK)            │
│ name (UNIQUE)       │         │ username (UNIQUE)   │
│ display_name        │         │ email (UNIQUE)      │
│ created_at         │         │ password_hash       │
│ updated_at         │         │ display_name        │
│ ls_org_id          │         │ organization_id (FK) │
└─────────────────────┘         │ created_at          │
                                │ updated_at          │
                                │ last_login          │
                                │ is_active           │
                                │ ls_user_id          │
                                │ ls_token            │
                                └─────────────────────┘
                                        │
                                        │ 1:N
                                        │
┌─────────────────────┐         ┌─────────────────────┐
│      Project        │◄────────│      Session        │
├─────────────────────┤         ├─────────────────────┤
│ id (PK)            │         │ token (PK)          │
│ name (UNIQUE)      │         │ user_id (FK)        │
│ organization_id (FK)│         │ created_at          │
│ created_by (FK)     │         │ expires_at          │
│ created_at         │         └─────────────────────┘
│ updated_at         │
│ total_images       │
│ processed_images   │
│ labels (JSON)      │
│ status             │
└─────────────────────┘
         │
         │ 1:N
         │
┌─────────────────────┐         ┌─────────────────────┐
│   ProjectImage     │         │   AnnotationTask    │
├─────────────────────┤         ├─────────────────────┤
│ id (PK)            │         │ id (PK)            │
│ project_id (FK)     │         │ project_id (FK)     │
│ file_path          │         │ type (DINO/VLM)     │
│ file_name          │         │ status              │
│ file_size          │         │ started_at          │
│ uploaded_at        │         │ completed_at        │
│ status             │         │ error_message       │
└─────────────────────┘         │ parameters (JSON)   │
         │                      └─────────────────────┘
         │ 1:N
         │
┌─────────────────────┐
│   DetectionResult  │
├─────────────────────┤
│ id (PK)            │
│ image_id (FK)       │
│ task_id (FK)        │
│ type (DINO/VLM)     │
│ result_data (JSON)  │
│ created_at          │
└─────────────────────┘
```

### 2. 实体关系说明

- **Organization (组织)**: 一个组织可以有多个用户和多个项目
- **User (用户)**: 用户属于一个组织，可以有多个会话
- **Project (项目)**: 项目属于一个组织，由一个用户创建，包含多个图片
- **ProjectImage (项目图片)**: 图片属于一个项目，可以有多个检测结果
- **Session (会话)**: 用户登录会话，用于 JWT 认证
- **AnnotationTask (标注任务)**: 项目的算法处理任务（DINO 检测、VLM 清洗）
- **DetectionResult (检测结果)**: 存储算法返回的标注结果
