# 架构概览与关键配置

## 项目简介

智能标注平台，用于图像目标检测的自动化标注。核心流程：**DINO 检测 → VLM 清洗 → 同步预测到 Label Studio**。

## 组件一览

| 组件 | 技术栈 | 端口 | conda 环境 |
|------|--------|------|-----------|
| 后端 | Spring Boot 3.2 + JPA + H2 | 8080 | — (JDK 17) |
| 前端 | Vue 3 + Element Plus + Vite | 6006 | — (Node.js) |
| Label Studio | Python | 5001 | `web_annotation` |
| 算法服务 (FastAPI) | FastAPI + Uvicorn | 8001 | `algo_service` |
| DINO 模型服务 | Python | 5003 | `groundingdino310` |

## 启动方式（开发/联调）

以 `.context/SETUP.md` 为准，这里给出最常用的启动命令与验证方式（日志统一落到 `/tmp/*.log`）。

### Spring Boot 后端（8080）

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests
kill $(lsof -ti:8080) 2>/dev/null; sleep 3
nohup java -jar target/platform-backend-1.0.0.jar --server.port=8080 > /tmp/springboot.log 2>&1 &
sleep 12 && grep "Started" /tmp/springboot.log
curl -s http://localhost:8080/api/v1/actuator/health | head -c 200
```

### 算法服务（FastAPI 8001 + DINO 5003）

```bash
source $(conda info --base)/etc/profile.d/conda.sh
cd /root/autodl-fs/Annotation-Platform/algorithm-service

conda activate groundingdino310
export CUDA_VISIBLE_DEVICES=""
nohup python dino_model_server.py > /tmp/dino.log 2>&1 &

conda activate algo_service
export CUDA_VISIBLE_DEVICES=""
nohup uvicorn main:app --host 0.0.0.0 --port 8001 > /tmp/algorithm.log 2>&1 &

curl -s http://localhost:8001/docs | head -c 120
```

### 前端（Vue3 6006）

```bash
pkill -f "vite" 2>/dev/null; sleep 2
cd /root/autodl-fs/Annotation-Platform/frontend-vue
nohup npx vite --host 0.0.0.0 --port 6006 > /tmp/frontend.log 2>&1 &
curl -I http://localhost:6006 | head -n 5
```

## 目录结构

```
/root/autodl-fs/Annotation-Platform/          ← 项目根目录
├── CLAUDE.md                                  ← Agent 入口
├── .context/                                  ← Agent 上下文
├── backend-springboot/                        ← Spring Boot 后端
│   ├── src/main/java/com/annotation/platform/
│   │   ├── controller/                        ← REST 控制器
│   │   ├── service/                           ← 业务逻辑
│   │   ├── repository/                        ← JPA Repository
│   │   ├── entity/                            ← JPA 实体
│   │   ├── dto/                               ← 请求/响应 DTO
│   │   ├── config/                            ← 安全、JWT 等配置
│   │   └── exception/                         ← 异常处理
│   ├── src/main/resources/application.yml     ← 核心配置文件
│   ├── data/testdb.mv.db                      ← H2 数据库文件
│   └── pom.xml
├── frontend-vue/                              ← Vue 3 前端
│   └── src/
│       ├── views/                             ← 页面组件
│       ├── api/                               ← API 封装
│       ├── router/                            ← 路由
│       └── layout/                            ← 布局组件
└── algorithm-service/                         ← 算法服务
    ├── main.py                                ← FastAPI 入口
    ├── dino_model_server.py                   ← DINO 模型服务
    └── routers/                               ← 路由模块
```

## 关键配置

### Spring Boot (application.yml)

- **context-path**: `/api/v1` — 所有后端 API 路径前缀是 `/api/v1`，不是 `/api`
- **数据库**: H2，文件路径 `backend-springboot/data/testdb`
- **文件上传路径**: `/root/autodl-fs/uploads`
- **YOLO 模型路径**: `/root/autodl-fs/xingmu_jiancepingtai/runs/detect/train7/weights/best.pt`（10类 VisDrone）

### Label Studio

- **URL**: `http://localhost:5001`
- **公网 URL**: `http://122.51.47.91:28450`
- **Admin Token**: `3dd84879dff6fd5949dc1dd76edbecccac3f8524`
- **Admin Email**: `datian@tongji.edu.cn`
- **Admin User ID**: 1
- **SQLite 路径**: `/root/.local/share/label-studio/label_studio.sqlite3`

### 前端

- **端口**: 6006（AutoDL 自定义服务默认暴露端口）
- **公网地址**: `http://122.51.47.91:24379/`

## 数据库说明

### H2 数据库（Spring Boot）

文件在 `backend-springboot/data/testdb.mv.db`。**Spring Boot 运行时会锁定此文件**，无法用外部工具直接读取。要查询数据需要通过 API 或日志。JPA `ddl-auto` 策略会自动建表。

主要表：`users`、`organizations`、`projects`、`feasibility_assessments`、`category_assessments`、`ovd_test_results`、`vlm_quality_scores`、`dataset_search_results`、`resource_estimations`、`implementation_plans`

用户模型配置表：
- `user_model_configs`：每用户一条 VLM/LLM 配置（`user_id` 唯一），字段包含 `vlm_api_key/vlm_base_url/vlm_model_name` 与 `llm_api_key/llm_base_url/llm_model_name`

可行性评估相关表：
- `ovd_test_results`：GroundingDINO 示例图检测结果（含 `bbox_json`、`test_time`）
- `vlm_quality_scores`：VLM 对检测结果的质量评分（外键 `ovd_test_result_id`）
- `resource_estimations`：资源估算（外键 `assessment_id`，字段如 `estimated_images`、`gpu_hours`、`estimated_cost` 等）
- `implementation_plans`：实施计划阶段（外键 `assessment_id`，字段如 `phase_order`、`phase_name`、`tasks`（TEXT 存 JSON 字符串）等）

### 接口概览（可行性评估模块补充）

- 数据集检索结果（DatasetSearchResult）
  - 基础路径：`/api/v1/feasibility/assessments/{assessmentId}/datasets`
  - `POST    .../datasets`：创建单条
  - `POST    .../datasets/batch`：批量创建
  - `GET     .../datasets[?categoryName=&source=]`：查询评估下所有数据集（按 `relevanceScore` 降序），支持可选过滤
  - `GET     .../datasets/{id}`：查详情
  - `DELETE  .../datasets/{id}`：删除

- 资源估算（ResourceEstimation）
  - 基础路径：`/api/v1/feasibility/assessments/{assessmentId}/resource-estimations`
  - `POST    .../resource-estimations`：创建单条
  - `POST    .../resource-estimations/batch`：批量创建
  - `GET     .../resource-estimations[?categoryName=]`：查询评估下所有资源估算，支持按类别过滤
  - `GET     .../resource-estimations/{id}`：查详情
  - `DELETE  .../resource-estimations/{id}`：删除

- 实施计划（ImplementationPlan）
  - 基础路径：`/api/v1/feasibility/assessments/{assessmentId}/implementation-plans`
  - `POST    .../implementation-plans`：创建单条
  - `POST    .../implementation-plans/batch`：批量创建（返回按 `phaseOrder` 升序）
  - `GET     .../implementation-plans`：查询评估下所有阶段（按 `phaseOrder` 升序）
  - `GET     .../implementation-plans/{id}`：查详情
  - `PUT     .../implementation-plans/{id}`：更新
  - `DELETE  .../implementation-plans/{id}`：删除

### LS SQLite 数据库

路径：`/root/.local/share/label-studio/label_studio.sqlite3`。可以直接用 `sqlite3` 查询（查询命令见 SETUP.md）。写入需谨慎，LS 运行时可能有锁。

关键表：`htx_user`（用户）、`organization`（组织）、`organizations_organizationmember`（组织成员关系）、`project`（项目）、`authtoken_token`（API Token）

## 核心数据流

1. **用户注册** → Spring Boot 创建用户 → 同步到 LS（创建 LS 用户 + 加入组织 + 从默认组织移除）
2. **创建项目** → Spring Boot 创建项目 → 同步到 LS（用组织管理员 token 创建 LS 项目 + 生成 label_config XML）
3. **自动标注** → 上传图片 → 调用 DINO/YOLO 检测 → VLM 清洗 → 同步预测结果到 LS
4. **可行性评估** → 创建评估 → 解析需求 → 类别评估 → OVD 测试 → 资源估算 → 实施计划

## 用户模型配置（VLM/LLM）

Spring Boot 提供用户级别的模型配置管理接口（受 JWT 保护）：
- `GET  /api/v1/api/user/model-config`：获取当前用户配置（不存在返回默认值，key 脱敏）
- `PUT  /api/v1/api/user/model-config`：更新配置（不存在自动创建；脱敏 key 不覆盖真实 key）
- `POST /api/v1/api/user/model-config/test-vlm`：测试 VLM 连通性（转发到算法服务）
- `POST /api/v1/api/user/model-config/test-llm`：测试 LLM 连通性（转发到算法服务）

算法服务提供连通性测试接口（供 Spring Boot 转发）：
- `POST /api/v1/model-config/test-vlm`
- `POST /api/v1/model-config/test-llm`

## 期望的架构设计

1. 用户注册时在 LS 中创建对应账户
2. 每个组织在 LS 中有对应组织，第一个创建者是管理员
3. **所有 LS 项目操作使用组织管理员的 token**（不是全局 admin token）
4. 项目自动归属到管理员的 active_organization
