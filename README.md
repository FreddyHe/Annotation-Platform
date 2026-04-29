# Annotation Platform

面向目标检测数据闭环的智能标注平台，覆盖数据上传、项目管理、自动标注、人工复核、单类别 AutoML 训练与检测、可行性评估、增量训练和边端回流验证等流程。

当前项目已经从早期 Streamlit 原型演进为五服务架构：

```text
Vue 3 前端
  -> Spring Boot 后端
    -> FastAPI 算法服务
    -> Label Studio
    -> DINO 模型服务
```

## 核心能力

### 项目与标注管理

- 用户注册、登录、JWT 鉴权。
- 项目创建、标签配置、图片管理和标注结果导出。
- 与 Label Studio 同步项目、用户、任务和预测结果。
- 支持大文件分片上传、断点状态查询和上传合并。

### 自动标注

当前自动标注支持两条模式：

```text
DINO_VLM：DINO 检测 -> VLM 清洗 -> 同步预测到 Label Studio
DINO_THRESHOLD：DINO 检测 -> score 阈值过滤 -> 同步预测到 Label Studio
```

自动标注任务会记录阶段、进度、保留框数量、丢弃框数量、DINO/VLM 子任务 ID 和取消状态。

### 单类别训练与检测

统一入口：

```text
/model-training
```

能力包括：

- 支持 Roboflow URL、curl 命令、Python SDK 下载代码。
- 支持公开 URL ZIP 和上传 ZIP 数据集。
- 训练前自动预检数据集格式、类别、图片数、标签数和 warnings。
- 自动读取类别，不要求用户手动输入关联项目或目标类别。
- 默认走 AutoML 训练参数选择。
- 训练任务轮询、训练日志、失败重试、模型删除。
- 自定义模型训练完成后可直接进入单类别检测。
- 单图检测支持模型选择、类别选择、置信度阈值、IOU 阈值、结果图片和 JSON 导出。

### 可行性评估

入口：

```text
/feasibility
```

能力包括：

- 新建评估任务并上传参考图片。
- LLM 解析需求，生成类别、英文名、场景描述和视角。
- OVD 测试、VLM 质量评估、桶分类。
- 公开数据集检索、用户判断数据集匹配度。
- 资源估算、实施计划和 AI 可行性报告。

### 增量训练与边端回流

项目中包含增量闭环相关服务和实体：

- Round 管理。
- 训练记录与轮次绑定。
- 边端模拟器推理。
- 高低置信度样本回流。
- VLM 二次判定和人工复核入口。

## 技术栈

| 模块 | 技术栈 | 默认端口 |
|---|---|---:|
| 前端 | Vue 3, Vite, Element Plus, Pinia, Axios | 6006 |
| 后端 | Spring Boot 3.2, Java 17, Spring Security, JPA, H2 | 8080 |
| 算法服务 | FastAPI, Uvicorn, PyTorch, Ultralytics, OpenAI-compatible VLM/LLM | 8001 |
| DINO 服务 | GroundingDINO Flask 服务 | 5003 |
| 标注工具 | Label Studio | 5001 |

后端 API 统一前缀为：

```text
/api/v1
```

## 项目结构

```text
Annotation-Platform/
├── README.md
├── CLAUDE.md
├── startup.sh
├── YUHAO_GIT_COLLABORATION_GUIDE.md
├── .context/
│   ├── ARCHITECTURE.md
│   ├── SETUP.md
│   ├── CONVENTIONS.md
│   └── LESSONS.md
├── frontend-vue/
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── api/
│       ├── layout/
│       ├── router/
│       ├── stores/
│       └── views/
├── backend-springboot/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/annotation/platform/
│       │   ├── config/
│       │   ├── controller/
│       │   ├── dto/
│       │   ├── entity/
│       │   ├── repository/
│       │   └── service/
│       └── resources/application.yml
├── algorithm-service/
│   ├── main.py
│   ├── config.py
│   ├── dino_model_server.py
│   ├── requirements.txt
│   └── routers/
├── scripts/
│   └── train_yolo.py
└── tests_e2e/
    ├── conftest.py
    ├── requirements.txt
    └── test_*.py
```

运行期生成目录包括：

```text
backend-springboot/data/
logs/
temp_uploads/
training_runs/
data/
frontend-vue/dist/
frontend-vue/node_modules/
```

这些目录默认不应提交到 Git。

## 快速启动

推荐使用根目录的一键脚本管理全部服务：

```bash
cd /root/autodl-fs/Annotation-Platform

./startup.sh          # 启动所有未运行服务
./startup.sh status   # 查看服务状态
./startup.sh stop     # 停止所有服务
./startup.sh restart  # 重启所有服务
./startup.sh build    # 编译后端 JAR 并启动
```

脚本管理的服务：

| 服务 | 端口 | 日志 |
|---|---:|---|
| Spring Boot 后端 | 8080 | `/tmp/springboot.log` |
| Label Studio | 5001 | `/tmp/labelstudio.log` |
| DINO 模型服务 | 5003 | `/tmp/dino.log` |
| FastAPI 算法服务 | 8001 | `/tmp/algorithm.log` |
| Vue 前端 | 6006 | `/tmp/frontend.log` |

访问入口：

```text
前端：http://localhost:6006
后端健康检查：http://localhost:8080/api/v1/actuator/health
算法服务文档：http://localhost:8001/api/v1/docs
Label Studio：http://localhost:5001
```

在 AutoDL 环境中，需要通过控制台端口映射访问对应公网端口。

## 手动启动

### 后端

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests
nohup java -jar target/platform-backend-1.0.0.jar --server.port=8080 > /tmp/springboot.log 2>&1 &
curl http://localhost:8080/api/v1/actuator/health
```

### 前端

```bash
cd /root/autodl-fs/Annotation-Platform/frontend-vue
source /root/.nvm/nvm.sh
npm install
npm run dev -- --host 0.0.0.0 --port 6006
```

### 算法服务

```bash
source $(conda info --base)/etc/profile.d/conda.sh
conda activate algo_service
cd /root/autodl-fs/Annotation-Platform/algorithm-service
uvicorn main:app --host 0.0.0.0 --port 8001
```

### DINO 模型服务

```bash
source $(conda info --base)/etc/profile.d/conda.sh
conda activate groundingdino310
export LD_LIBRARY_PATH=/root/miniconda3/envs/groundingdino310/lib/python3.10/site-packages/torch/lib:${LD_LIBRARY_PATH:-}
cd /root/autodl-fs/Annotation-Platform/algorithm-service
python dino_model_server.py
```

### Label Studio

```bash
source $(conda info --base)/etc/profile.d/conda.sh
conda activate web_annotation
export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs
label-studio start --port 5001 --no-browser --log-level INFO
```

## 关键配置

后端配置文件：

```text
backend-springboot/src/main/resources/application.yml
```

重要配置：

```text
server.port=8080
server.servlet.context-path=/api/v1
spring.datasource.url=jdbc:h2:file:/root/autodl-fs/Annotation-Platform/backend-springboot/data/testdb
app.file.upload.base-path=/root/autodl-fs/uploads
app.file.upload.chunk-path=/tmp/upload_chunks
app.label-studio.url=http://localhost:5001
app.algorithm.url=http://localhost:8001
app.backend.public-url=http://localhost:8080/api/v1
```

前端 Vite 配置：

```text
frontend-vue/vite.config.js
```

默认端口为 `6006`，`/api` 代理到 `http://localhost:8080`。

算法服务配置：

```text
algorithm-service/config.py
algorithm-service/.env.example
```

## 主要页面

```text
/login                      登录
/dashboard                  概览
/projects                   项目列表
/projects/:id               项目详情
/model-training             单类别训练与检测
/single-class-detection     单类别检测兼容入口
/feasibility                可行性评估列表
/feasibility/create         新建可行性评估
/feasibility/:id            可行性评估详情
/profile                    个人中心
/settings                   模型配置与设置
```

## 主要接口

### 认证与用户

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
GET  /api/v1/users/me
GET  /api/v1/api/user/model-config
PUT  /api/v1/api/user/model-config
```

### 项目与上传

```text
GET    /api/v1/projects
POST   /api/v1/projects
GET    /api/v1/projects/{id}
PUT    /api/v1/projects/{id}
DELETE /api/v1/projects/{id}

POST   /api/v1/upload/chunk
POST   /api/v1/upload/merge
GET    /api/v1/upload/progress/{fileId}
POST   /api/v1/upload/image
POST   /api/v1/upload/training-dataset
```

### 自动标注

```text
POST /api/v1/auto-annotation/start/{projectId}
GET  /api/v1/auto-annotation/status/{taskId}
GET  /api/v1/auto-annotation/results/{taskId}
GET  /api/v1/auto-annotation/jobs/{jobId}
GET  /api/v1/auto-annotation/projects/{projectId}/jobs/latest
POST /api/v1/auto-annotation/jobs/{jobId}/cancel
```

### 单类别训练与检测

```text
POST   /api/v1/custom-models/inspect-dataset
POST   /api/v1/custom-models/train
GET    /api/v1/custom-models
GET    /api/v1/custom-models/{id}/status
GET    /api/v1/custom-models/{id}/logs
POST   /api/v1/custom-models/{id}/retry
DELETE /api/v1/custom-models/{id}
GET    /api/v1/custom-models/available

POST   /api/v1/detection/single-class
GET    /api/v1/detection/model-info
GET    /api/v1/detection/single-class/history
DELETE /api/v1/detection/single-class/history
```

### 可行性评估

```text
POST   /api/v1/feasibility/assessments
GET    /api/v1/feasibility/assessments
GET    /api/v1/feasibility/assessments/{id}
DELETE /api/v1/feasibility/assessments/{id}
POST   /api/v1/feasibility/assessments/{id}/parse
POST   /api/v1/feasibility/assessments/{id}/run-ovd-test
POST   /api/v1/feasibility/assessments/{id}/evaluate
POST   /api/v1/feasibility/assessments/{id}/search-datasets
POST   /api/v1/feasibility/assessments/{id}/user-judgment
POST   /api/v1/feasibility/assessments/{id}/estimate
POST   /api/v1/feasibility/assessments/{id}/generate-plan
POST   /api/v1/feasibility/assessments/{id}/ai-report
GET    /api/v1/feasibility/assessments/{id}/report
```

### 算法服务

```text
GET  /api/v1/health
POST /api/v1/algo/dino/detect
POST /api/v1/algo/vlm/clean
POST /api/v1/algo/yolo/detect
POST /api/v1/algo/single-class-detection
POST /api/v1/training/inspect-dataset
POST /api/v1/training/start
GET  /api/v1/training/status/{task_id}
POST /api/v1/feasibility/parse-requirement
POST /api/v1/feasibility/run-ovd-test
POST /api/v1/feasibility/vlm-evaluate
POST /api/v1/feasibility/estimate-resources
```

## 数据库与数据目录

开发环境默认使用 H2 文件库：

```text
backend-springboot/data/testdb.mv.db
```

Spring Boot 运行时会锁定 H2 文件。需要检查数据时，优先通过 API 或日志，不要在后端运行中直接操作数据库文件。

运行数据默认分布：

```text
/root/autodl-fs/uploads                 上传文件
/tmp/upload_chunks                      分片上传临时目录
backend-springboot/data/                H2 数据库
training_runs/                          训练输出
/root/autodl-fs/custom_datasets         自定义训练数据集
/root/autodl-fs/custom_models           自定义训练模型
```

这些路径可在本地联调时通过启动参数或环境变量隔离，不要把个人端口或个人数据目录写死进 `application.yml`、`package.json` 或 `vite.config.js`。

## 测试

后端构建：

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn test
```

前端构建：

```bash
cd /root/autodl-fs/Annotation-Platform/frontend-vue
npm run build
```

算法服务语法检查：

```bash
cd /root/autodl-fs/Annotation-Platform/algorithm-service
python -m py_compile main.py routers/*.py
```

E2E 测试：

```bash
cd /root/autodl-fs/Annotation-Platform/tests_e2e
./run_tests.sh
```

E2E 测试基于 `pytest` 和 `requests`，覆盖用户认证、项目管理、文件上传、DINO 检测、YOLO 训练和 VLM 清洗等链路。

## 开发约定

- 新接口不要在 `@RequestMapping` 中写 `/api/v1`，该前缀由 Spring Boot context-path 提供。
- 前端 API 统一从 `frontend-vue/src/utils/request.js` 走 `/api/v1` baseURL。
- 后端日志和服务启动日志优先写到 `/tmp/*.log`。
- 不提交数据库、上传文件、训练产物、模型权重、日志和本地临时配置。
- 协作开发和端口隔离见 `YUHAO_GIT_COLLABORATION_GUIDE.md`。
- 更详细的架构、启动和踩坑记录见 `.context/` 目录。

## 相关文档

```text
.context/ARCHITECTURE.md                 架构和关键配置
.context/SETUP.md                        启动、验证和排查命令
.context/CONVENTIONS.md                  开发规范
.context/LESSONS.md                      踩坑记录
YUHAO_GIT_COLLABORATION_GUIDE.md         宇浩协作开发指南
incremental_multiclass_closed_loop_proposal.md  增量多类别闭环规划
```
