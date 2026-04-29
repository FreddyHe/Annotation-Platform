# 宇浩协作开发指南：单类别训练算法服务

本文档给宇浩使用，目标是从 0 clone 项目，在独立目录、独立端口、独立数据库和独立上传目录中开发，不影响主项目 `/root/autodl-fs/Annotation-Platform`。

本次只做一个方向：

```text
单类别训练算法服务
```

具体包括两条主线：

1. 把现有“Roboflow URL / curl / Python SDK 下载代码 -> 数据下载 -> 数据集解析 -> AutoML 训练”的链路做扎实，进行多层面 debug、错误处理、日志、状态恢复和可测试性补强。
2. 新增一个“用户只输入类别名，系统自动检索公开数据集、下载、预检、转换并训练”的页面和后台智能流程。

本次不改可行性评估页面，不改 Label Studio，不改 DINO 自动标注，不改增量闭环和边端模拟。可行性评估相关文件除非修复编译错误，否则不要动。

## 1. 目录和端口约定

主项目目录由 A 使用：

```text
/root/autodl-fs/Annotation-Platform
```

宇浩自己的开发目录使用：

```text
/root/autodl-fs/Annotation-Platform-yuhao
```

不要直接在主项目目录里改代码。

端口固定这样分开：

| 服务 | 主项目默认端口 | 宇浩开发端口 | 说明 |
|---|---:|---:|---|
| 前端 Vite | 6006 | 16106 | 用启动命令的 `--port` 指定，不改 `package.json` |
| Spring Boot 后端 | 8080 | 18180 | 用启动参数 `--server.port` 指定，不改 `application.yml` |
| Algorithm Service | 8001 | 18011 | 用 `uvicorn --port` 指定 |
| Label Studio | 5001 | 不启动 | 本次不需要 |
| DINO Server | 5003 | 不启动 | 本次不需要 |

启动前检查端口：

```bash
ss -ltnp | grep -E ':16106|:18180|:18011'
```

如果有占用，先确认是不是自己的旧进程。不要停主项目的 `6006/8080/8001/5001/5003`。

## 2. 从 0 clone 项目

先在 GitHub 上 fork 主仓库：

```text
主仓库：git@github.com:FreddyHe/Annotation-Platform.git
宇浩 fork：git@github.com:<YuhaoGithubName>/Annotation-Platform.git
```

然后在服务器上 clone 到自己的目录：

```bash
cd /root/autodl-fs
git clone git@github.com:<YuhaoGithubName>/Annotation-Platform.git Annotation-Platform-yuhao
cd /root/autodl-fs/Annotation-Platform-yuhao
git remote add upstream git@github.com:FreddyHe/Annotation-Platform.git
git remote -v
```

配置本仓库的提交身份：

```bash
git config user.name "Yuhao"
git config user.email "yuhao@example.com"
```

每个任务单独开分支。本次建议：

```bash
git fetch upstream
git checkout main
git reset --hard upstream/main
git checkout -b feat/yuhao-single-class-training-agent
```

注意：`git reset --hard upstream/main` 只能在自己目录、自己的 `main` 上使用，用来同步主仓库最新代码。不要在主项目目录执行。

## 3. 本次开发边界

优先修改这些文件或同目录相关文件：

```text
frontend-vue/src/views/SingleClassWorkflow.vue
frontend-vue/src/views/AutoDatasetTraining.vue
frontend-vue/src/api/customModel.js
frontend-vue/src/api/autoDatasetTraining.js
frontend-vue/src/router/index.js
frontend-vue/src/layout/index.vue

backend-springboot/src/main/java/com/annotation/platform/controller/CustomModelController.java
backend-springboot/src/main/java/com/annotation/platform/controller/AutoDatasetTrainingController.java
backend-springboot/src/main/java/com/annotation/platform/service/CustomModelService.java
backend-springboot/src/main/java/com/annotation/platform/service/AutoDatasetTrainingService.java
backend-springboot/src/main/java/com/annotation/platform/dto/CreateTrainingTaskRequest.java
backend-springboot/src/main/java/com/annotation/platform/entity/CustomModel.java
backend-springboot/src/main/java/com/annotation/platform/entity/CustomModelClass.java

algorithm-service/routers/training.py
algorithm-service/routers/auto_dataset_training.py
algorithm-service/main.py
algorithm-service/config.py
```

不要顺手改这些模块，除非 A 明确要求：

```text
可行性评估页面
Label Studio 集成
DINO 自动标注
项目管理主流程
增量闭环
边端模拟
全局端口配置
全局默认数据目录
```

## 4. 当前单类别训练页面现状

当前主入口：

```text
前端路由：/model-training
页面文件：frontend-vue/src/views/SingleClassWorkflow.vue
```

`/single-class-detection` 现在也指向同一个 `SingleClassWorkflow.vue`。仓库里仍保留旧页面：

```text
frontend-vue/src/views/SingleClassDetection.vue
```

但当前路由没有直接使用旧页面。宇浩优先改 `SingleClassWorkflow.vue`，不要把主要逻辑继续写到旧 `SingleClassDetection.vue`。

`SingleClassWorkflow.vue` 当前有 4 个标签页：

1. 训练配置。
2. 训练任务。
3. 模型管理。
4. 单类别检测。

训练配置当前已支持：

```text
ROBOFLOW：粘贴 Roboflow URL、curl 命令或 Python SDK 下载代码
URL_ZIP：填写公开 ZIP URL
UPLOAD_ZIP：上传 ZIP 数据集
```

页面上已经没有“服务器本地路径”这一项。用户也不需要手动输入关联项目和目标类别。

现有训练流程：

1. 用户选择数据来源。
2. 用户粘贴 Roboflow 下载内容、公开 ZIP URL，或上传 ZIP。
3. 前端调用 `POST /api/v1/custom-models/inspect-dataset` 做数据集预检。
4. 系统展示数据集格式、类别数、图片数、标签数、类别名和 warnings。
5. 前端根据预检结果自动生成模型名称。
6. 前端根据预检结果自动拼出 `targetClassName`。
7. 前端调用 `POST /api/v1/custom-models/train` 创建训练任务。
8. 后端提交算法服务开始训练。
9. 前端轮询训练状态。
10. 训练完成后，模型进入模型管理和单类别检测页。

当前前端 API 文件：

```text
frontend-vue/src/api/customModel.js
```

主要接口：

```text
POST   /api/v1/custom-models/inspect-dataset
POST   /api/v1/custom-models/train
GET    /api/v1/custom-models
GET    /api/v1/custom-models/{id}/status
GET    /api/v1/custom-models/{id}/logs
POST   /api/v1/custom-models/{id}/retry
DELETE /api/v1/custom-models/{id}
GET    /api/v1/custom-models/available
POST   /api/v1/upload/training-dataset
```

## 5. 当前后端和算法服务链路

后端入口：

```text
backend-springboot/src/main/java/com/annotation/platform/controller/CustomModelController.java
backend-springboot/src/main/java/com/annotation/platform/service/CustomModelService.java
backend-springboot/src/main/java/com/annotation/platform/dto/CreateTrainingTaskRequest.java
backend-springboot/src/main/java/com/annotation/platform/entity/CustomModel.java
backend-springboot/src/main/java/com/annotation/platform/entity/CustomModelClass.java
```

后端创建训练任务时当前行为：

1. 校验数据源配置。
2. 写入 `CustomModel` 记录。
3. `projectId` 固定写成 `null`。
4. `targetClassName` 接收前端自动解析后的值。
5. 状态初始化为 `PENDING`。
6. 异步调用算法服务 `POST /api/v1/training/start`。
7. 轮询状态时调用算法服务 `GET /api/v1/training/status/{taskId}`。
8. 训练完成后保存 `modelPath`、mAP、precision、recall 和类别列表。

算法服务入口：

```text
algorithm-service/routers/training.py
```

当前训练相关接口：

```text
POST /api/v1/training/parse-command
POST /api/v1/training/download-and-convert
POST /api/v1/training/inspect-dataset
POST /api/v1/training/start
GET  /api/v1/training/status/{task_id}
```

当前算法服务支持：

1. 解析 Roboflow URL、curl 或 Python SDK 下载代码。
2. 下载 Roboflow 数据集。
3. 读取 URL ZIP。
4. 读取本地上传 ZIP。
5. 解压并检测数据集格式。
6. 读取 YOLO `data.yaml`。
7. 支持 COCO 转 YOLO。
8. 自动统计类别、图片数、标签数和数据划分。
9. `automl=true` 时调用 `choose_automl_params` 自动选择训练参数。
10. 使用 `yolo detect train` 训练。
11. 找到 `best.pt` 后返回模型路径、类别和指标。

当前重要限制：

1. `BASE_DATASET_DIR` 仍硬编码为 `/root/autodl-fs/custom_datasets`。
2. `BASE_MODEL_DIR` 仍硬编码为 `/root/autodl-fs/custom_models`。
3. `UPLOAD_BASE_DIR` 仍硬编码为 `/root/autodl-fs/uploads`。
4. 训练状态 `_task_status` 是算法服务内存变量，算法服务重启后会丢失实时状态。
5. 后端有数据库里的 `CustomModel` 记录，但算法侧训练中状态不会自动恢复。
6. callback 只有配置 `app.callback.url` 时才会主动回调；当前主要依赖后端轮询。

## 6. 必做任务一：现有 Roboflow 下载训练链路深度 debug

这部分不是简单“能跑一次”就结束，要把链路做成真实服务能支撑的状态。

### 6.1 解析层 debug

重点函数：

```text
algorithm-service/routers/training.py
parse_download_command
```

必须覆盖这些输入：

```text
纯 Roboflow URL
curl -L "https://universe.roboflow.com/ds/..."
curl 带输出文件名
Python SDK 代码：Roboflow(api_key=...).workspace(...).project(...).version(...).download(...)
包含换行、缩进、中文注释、前后空格的复制内容
无效 URL
缺少 api_key / workspace / project / version 的 Python 代码
非 Roboflow 内容
```

要求：

1. 解析失败要返回明确原因。
2. 不允许执行用户粘贴的任意 shell 或 Python 代码。
3. 只提取必要字段和 URL。
4. 日志里不要打印完整 token 或 API key。
5. 为解析逻辑补充单元测试或至少补充可复用测试脚本。

### 6.2 下载层 debug

重点函数：

```text
download_dataset
download_url_zip
download_roboflow_dataset
```

要求：

1. 下载前记录任务 ID、数据源类型、目标目录。
2. 下载超时要可配置。
3. HTTP 失败、重定向失败、文件为空、非 ZIP 内容要能识别。
4. 下载完成后校验文件大小和压缩包结构。
5. 解压要防 Zip Slip，不能允许压缩包写出目标目录。
6. 重试时要清理旧的半成品目录。
7. 失败信息要能回传到前端训练日志。

### 6.3 数据集检测和转换 debug

重点函数：

```text
detect_dataset_format
find_data_yaml
inspect_dataset_dir
inspect_yolo_dataset
inspect_coco_dataset
convert_coco_to_yolo
create_yolo_yaml_from_directory
```

要求：

1. YOLO、COCO、VOC、Roboflow 导出格式都要给出清晰判断。
2. 缺少图片、缺少标签、类别为空、标签 class id 越界，要给出 warnings。
3. `data.yaml` 的 `path/train/val/test/names/nc` 要统一校正。
4. 多层目录 ZIP 要能自动找到真正的数据集根目录。
5. COCO 转 YOLO 后要重新预检。
6. 如果是多类别数据集，要保留类别列表，不要假设一定单类别。
7. 如果后续做“输入类别名自动训练”，要支持按目标类别筛选并重映射为 class 0。

### 6.4 AutoML 参数 debug

重点函数：

```text
choose_automl_params
run_full_pipeline
```

要求：

1. AutoML 参数最终以算法服务选择为准。
2. 前端不让用户手动输入 epochs、batch、image size、learning rate。
3. 算法服务状态日志要写清楚最终选择的参数。
4. 小数据集、无验证集、多类别、大图片、多标签异常时要有不同策略。
5. 训练前把最终参数写入任务状态，后端同步后前端可展示。

### 6.5 训练层 debug

重点函数：

```text
run_yolo_training
run_full_pipeline
```

要求：

1. 训练命令要完整记录，但不要泄漏敏感信息。
2. stdout/stderr 要持续进入任务日志，不能只在失败时截断最后 2000 字符。
3. 训练失败要区分：依赖缺失、GPU/显存、数据集错误、YOLO 参数错误、找不到 best.pt。
4. 训练完成后要校验 `best.pt` 是否存在且文件非空。
5. 解析 `results.csv` 指标失败时不能让训练任务失败，但要给 warning。
6. 训练产物目录必须按任务 ID 隔离。
7. 支持重试，重试不能混用旧产物。

### 6.6 状态、日志和恢复

当前 `_task_status` 是内存变量，不适合长任务真实服务。至少要做一层落盘：

```text
runtime/task_status/{task_id}.json
runtime/task_logs/{task_id}.jsonl
```

要求：

1. 每个任务的状态、阶段、进度、错误、最终模型路径要落盘。
2. 算法服务重启后，`GET /status/{task_id}` 至少能读到最后状态。
3. 日志按 JSONL 追加，方便前端增量拉取。
4. 后端状态和算法服务状态要有一致性策略。
5. 训练中的任务如果算法服务重启，允许标记为 `FAILED_RECOVERABLE` 或 `INTERRUPTED`，不要假装还在训练。

## 7. 必做任务二：新增“输入类别名自动找数据集并训练”页面

新增页面建议：

```text
前端路由：/auto-dataset-training
页面文件：frontend-vue/src/views/AutoDatasetTraining.vue
页面名称：智能数据集训练 / 类别一键训练
```

用户界面只保留一个主输入框：

```text
请输入目标类别：例如 fossil, ammonite, helmet, crack, safety vest
```

页面展示流程进度：

```text
1. 类别解析
2. 数据集检索
3. 候选数据集评分
4. 数据集下载
5. 数据集预检与格式转换
6. AutoML 参数选择
7. 自动训练
8. 模型测试
```

用户不需要输入：

```text
关联项目
目标类别 ID
服务器路径
epochs
batch size
image size
learning rate
```

页面可以展示候选数据集，但默认不要暴露训练参数。推荐交互：

1. 用户输入类别名。
2. 点击“搜索并训练”。
3. 系统搜索 Roboflow、Kaggle 和允许来源。
4. 系统展示候选数据集评分。
5. 如果最高分超过阈值，允许一键自动训练。
6. 如果最高分不足，要求用户选择一个候选数据集再训练。
7. 训练完成后跳转到单类别检测标签页测试模型。

## 8. 智能流程设计

不要把它设计成“完全自由上网的智能体”。正确做法是一个受控状态机：

```text
输入类别名
  ↓
类别标准化：中文转英文、英文规范化、同义词扩展
  ↓
Roboflow Universe 搜索
  ↓
Kaggle 搜索
  ↓
联网搜索白名单来源
  ↓
候选数据集评分
  ↓
选择 Top 1 或让用户确认
  ↓
下载数据集
  ↓
解压与安全检查
  ↓
识别格式：YOLO / COCO / VOC / Roboflow
  ↓
转换到 YOLO 格式
  ↓
检查 data.yaml、图片数、标签数、类别列表
  ↓
如果多类别，筛选/重映射目标类别为 class 0
  ↓
自动划分 train/val/test
  ↓
调用现有训练流水线
  ↓
训练完成后登记 CustomModel
  ↓
跳转检测页面测试
```

LLM 或智能体只负责：

1. 类别名称标准化。
2. 中英文翻译。
3. 同义词和检索词生成。
4. 候选数据集相关性解释。
5. 失败原因归纳。

确定性代码负责：

1. 搜索 API 调用。
2. 下载。
3. 安全解压。
4. 格式识别。
5. 格式转换。
6. 类别筛选和重映射。
7. YOLO 训练。
8. 状态和日志持久化。

不要让 LLM 执行 shell 命令。不要执行网页里复制出来的任意代码。

## 9. 数据源策略

### 9.1 Roboflow

Roboflow 是第一优先级数据源。

要求：

1. 优先使用 Roboflow 官方 Universe API 做搜索。
2. 不要把 HTML 爬虫作为第一方案。
3. 搜索结果要保存 source、datasetName、datasetUrl、license、imageCount、classNames、format、downloadMethod。
4. 下载时优先复用现有 Roboflow URL / curl / Python SDK 解析和下载能力。
5. 如果只能拿到数据集页面，先展示候选，让用户确认或粘贴下载代码，不要静默爬页面。

Roboflow 官方文档参考：

```text
https://docs.roboflow.com/developer/rest-api/universe-api
```

### 9.2 Kaggle

Kaggle 是第二优先级数据源。

注意：Kaggle 很多数据集不是目标检测数据集，可能只是 CSV、分类图片、分割掩码或医学影像。不能搜索到就直接训练。

要求：

1. 服务端必须显式配置 Kaggle 凭证。
2. 没有 Kaggle 凭证时，页面要提示“未配置 Kaggle，已跳过 Kaggle 搜索”。
3. Kaggle 搜索和下载可以用 Kaggle CLI 或 Python API。
4. 下载后必须先预检格式。
5. 不满足 YOLO/COCO/VOC/可转换格式的候选不能进入自动训练。

建议命令形态：

```bash
kaggle datasets list -s "<类别关键词>"
kaggle datasets download -d "<owner/dataset>" -p "<download_dir>" --unzip
```

具体参数以当前环境安装的 Kaggle CLI `--help` 为准。

Kaggle CLI 文档参考：

```text
https://github.com/Kaggle/kaggle-cli/blob/main/docs/README.md
```

### 9.3 联网搜索

联网搜索只作为补充，不允许随便下载任意链接。

允许来源白名单建议：

```text
roboflow.com
universe.roboflow.com
kaggle.com
huggingface.co
github.com
zenodo.org
```

允许文件类型：

```text
.zip
.tar
.tar.gz
.json
.yaml
.yml
```

要求：

1. 搜索结果必须先进入候选列表。
2. 每个候选必须有来源、URL、许可证或许可证未知标记、可下载性、格式判断。
3. 未知许可证不能默认自动训练，只能让用户确认。
4. 不允许执行下载页面提供的任意 shell 命令。
5. 下载文件必须做大小限制、MIME 检查和安全解压。

## 10. 候选数据集评分

新增一个候选数据集模型，建议字段：

```text
taskId
sourcePlatform
sourceDatasetId
datasetName
datasetUrl
downloadUrl
license
taskType
annotationFormat
imageCount
labelCount
classNames
matchedClassNames
selectedClassName
relevanceScore
formatScore
sizeScore
licenseScore
downloadScore
totalScore
scoreReasons
status
localPath
createdAt
```

评分建议：

```text
类别匹配度：0-40
是否目标检测数据集：0-20
是否有 YOLO/COCO/VOC 标注：0-15
图片数量和标签数量：0-10
是否可直接下载：0-5
许可证可用性：0-5
数据集结构可预检：0-5
```

自动训练阈值建议：

```text
totalScore >= 80：可以默认推荐自动训练
60 <= totalScore < 80：展示给用户确认
totalScore < 60：不自动训练，只展示原因
```

所有评分都要有 `scoreReasons`，方便用户理解为什么选它。

## 11. 新增接口建议

### 11.1 Spring Boot 接口

新增控制器建议：

```text
backend-springboot/src/main/java/com/annotation/platform/controller/AutoDatasetTrainingController.java
```

建议接口：

```text
POST /api/v1/auto-dataset-training/tasks
GET  /api/v1/auto-dataset-training/tasks
GET  /api/v1/auto-dataset-training/tasks/{id}
GET  /api/v1/auto-dataset-training/tasks/{id}/candidates
POST /api/v1/auto-dataset-training/tasks/{id}/confirm-dataset
GET  /api/v1/auto-dataset-training/tasks/{id}/logs
POST /api/v1/auto-dataset-training/tasks/{id}/retry
DELETE /api/v1/auto-dataset-training/tasks/{id}
```

`POST /tasks` 请求示例：

```json
{
  "categoryName": "helmet",
  "autoStart": true,
  "sources": ["ROBOFLOW", "KAGGLE", "WEB"],
  "minScoreToAutoTrain": 80
}
```

后端职责：

1. 创建任务记录。
2. 记录用户 ID。
3. 调用 Algorithm Service。
4. 保存候选数据集。
5. 保存状态和日志摘要。
6. 训练完成后关联或创建 `CustomModel`。
7. 给前端提供统一状态。

### 11.2 Algorithm Service 接口

新增文件建议：

```text
algorithm-service/routers/auto_dataset_training.py
```

在 `algorithm-service/main.py` 注册 router。

建议接口：

```text
POST /api/v1/auto-dataset-training/start
GET  /api/v1/auto-dataset-training/status/{task_id}
GET  /api/v1/auto-dataset-training/candidates/{task_id}
GET  /api/v1/auto-dataset-training/logs/{task_id}
POST /api/v1/auto-dataset-training/confirm-dataset
POST /api/v1/auto-dataset-training/retry/{task_id}
```

算法服务职责：

1. 类别标准化。
2. 搜索 Roboflow / Kaggle / 白名单 Web。
3. 候选评分。
4. 下载候选数据集。
5. 调用现有 `inspect_dataset_dir`。
6. 必要时格式转换。
7. 必要时筛选目标类别并重映射。
8. 调用现有 `run_full_pipeline` 或拆出的训练函数。
9. 写入任务状态和日志。
10. 将结果返回给后端。

## 12. 数据库存储建议

后端至少新增两张表或等价实体：

```text
auto_dataset_training_tasks
auto_dataset_candidates
```

`auto_dataset_training_tasks` 建议字段：

```text
id
user_id
category_name
category_name_normalized
status
status_message
progress
selected_candidate_id
custom_model_id
model_path
error_message
created_at
updated_at
completed_at
```

`auto_dataset_candidates` 建议字段：

```text
id
task_id
source_platform
source_dataset_id
dataset_name
dataset_url
download_url
license
annotation_format
image_count
label_count
class_names_json
matched_class_names_json
total_score
score_reasons_json
status
local_path
created_at
```

不要把下载后的数据集、模型权重、日志大文本塞进数据库。数据库只存路径、摘要和状态。

## 13. 数据目录隔离

宇浩使用自己的运行时目录：

```text
/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads
/root/autodl-fs/Annotation-Platform-yuhao/runtime/upload_chunks
/root/autodl-fs/Annotation-Platform-yuhao/runtime/custom_datasets
/root/autodl-fs/Annotation-Platform-yuhao/runtime/custom_models
/root/autodl-fs/Annotation-Platform-yuhao/runtime/auto_dataset_cache
/root/autodl-fs/Annotation-Platform-yuhao/runtime/task_status
/root/autodl-fs/Annotation-Platform-yuhao/runtime/task_logs
```

创建目录：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao
mkdir -p runtime/uploads runtime/upload_chunks runtime/custom_datasets runtime/custom_models runtime/auto_dataset_cache runtime/task_status runtime/task_logs
```

算法服务中这些路径必须可配置：

```python
BASE_DATASET_DIR = os.getenv("CUSTOM_DATASET_DIR", "/root/autodl-fs/custom_datasets")
BASE_MODEL_DIR = os.getenv("CUSTOM_MODEL_DIR", "/root/autodl-fs/custom_models")
UPLOAD_BASE_DIR = os.getenv("UPLOAD_BASE_DIR", "/root/autodl-fs/uploads")
AUTO_DATASET_CACHE_DIR = os.getenv("AUTO_DATASET_CACHE_DIR", "/root/autodl-fs/auto_dataset_cache")
TASK_STATUS_DIR = os.getenv("TASK_STATUS_DIR", "/tmp/annotation-platform/task_status")
TASK_LOG_DIR = os.getenv("TASK_LOG_DIR", "/tmp/annotation-platform/task_logs")
```

不要把宇浩的运行目录写死到源码里，也不要写进 `application.yml` 或 `package.json`。

## 14. 数据库隔离

后端默认 H2 数据库在主项目目录里。宇浩必须使用自己的 H2 文件：

```text
/root/autodl-fs/Annotation-Platform-yuhao/backend-springboot/data-yuhao/testdb
```

创建目录：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao
mkdir -p backend-springboot/data-yuhao
```

启动后端时通过启动参数覆盖数据库路径：

```text
--spring.datasource.url=jdbc:h2:file:/root/autodl-fs/Annotation-Platform-yuhao/backend-springboot/data-yuhao/testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
```

不要删除或移动主项目的：

```text
/root/autodl-fs/Annotation-Platform/backend-springboot/data/
```

## 15. 启动 Algorithm Service

只启动 `algorithm-service/main.py`，不要启动 DINO Server。

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/algorithm-service
source $(conda info --base)/etc/profile.d/conda.sh
conda activate algo_service
```

前台启动：

```bash
UPLOAD_BASE_PATH=/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads \
UPLOAD_BASE_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads \
CUSTOM_DATASET_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/custom_datasets \
CUSTOM_MODEL_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/custom_models \
AUTO_DATASET_CACHE_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/auto_dataset_cache \
TASK_STATUS_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/task_status \
TASK_LOG_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/task_logs \
BACKEND_URL=http://localhost:18180 \
uvicorn main:app --host 0.0.0.0 --port 18011
```

后台启动时把日志写到 `/tmp`：

```bash
UPLOAD_BASE_PATH=/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads \
UPLOAD_BASE_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads \
CUSTOM_DATASET_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/custom_datasets \
CUSTOM_MODEL_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/custom_models \
AUTO_DATASET_CACHE_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/auto_dataset_cache \
TASK_STATUS_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/task_status \
TASK_LOG_DIR=/root/autodl-fs/Annotation-Platform-yuhao/runtime/task_logs \
BACKEND_URL=http://localhost:18180 \
nohup uvicorn main:app --host 0.0.0.0 --port 18011 > /tmp/yuhao-algorithm-18011.log 2>&1 &
```

健康检查：

```bash
curl http://localhost:18011/api/v1/health
```

不要运行：

```bash
python dino_model_server.py
```

## 16. 启动后端

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/backend-springboot
```

开发模式启动：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="\
--server.port=18180 \
--spring.datasource.url=jdbc:h2:file:/root/autodl-fs/Annotation-Platform-yuhao/backend-springboot/data-yuhao/testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE \
--app.algorithm.url=http://localhost:18011 \
--app.file.upload.base-path=/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads \
--file.upload.base-path=/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads \
--app.file.upload.chunk-path=/root/autodl-fs/Annotation-Platform-yuhao/runtime/upload_chunks \
--app.backend.public-url=http://localhost:18180/api/v1"
```

后台启动：

```bash
nohup mvn spring-boot:run -Dspring-boot.run.arguments="\
--server.port=18180 \
--spring.datasource.url=jdbc:h2:file:/root/autodl-fs/Annotation-Platform-yuhao/backend-springboot/data-yuhao/testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE \
--app.algorithm.url=http://localhost:18011 \
--app.file.upload.base-path=/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads \
--file.upload.base-path=/root/autodl-fs/Annotation-Platform-yuhao/runtime/uploads \
--app.file.upload.chunk-path=/root/autodl-fs/Annotation-Platform-yuhao/runtime/upload_chunks \
--app.backend.public-url=http://localhost:18180/api/v1" > /tmp/yuhao-springboot-18180.log 2>&1 &
```

健康检查：

```bash
curl http://localhost:18180/api/v1/actuator/health
```

注意：

1. 不要改 `application.yml` 里的默认 `server.port: 8080`。
2. 不要把 `18180` 写进 `application.yml`。
3. 不要启动 Label Studio。

## 17. 启动前端

前端端口用 `--port 16106` 指定，不改 `package.json`。

Vite 当前默认把 `/api` 代理到 `http://localhost:8080`。宇浩后端使用 `18180`，所以需要本地临时 Vite 配置覆盖代理目标。这个文件只放在宇浩本地，不提交。

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/frontend-vue
source /root/.nvm/nvm.sh
npm install
```

创建本地临时配置文件：

```bash
cat > vite.yuhao.local.config.js <<'EOF'
import { defineConfig, mergeConfig } from 'vite'
import baseConfig from './vite.config.js'

export default mergeConfig(baseConfig, defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:18180',
        changeOrigin: true,
        secure: false,
        timeout: 30 * 60 * 1000,
        proxyTimeout: 30 * 60 * 1000
      }
    }
  }
}))
EOF
```

把本地临时配置加入本仓库的本地排除列表：

```bash
echo 'frontend-vue/vite.yuhao.local.config.js' >> /root/autodl-fs/Annotation-Platform-yuhao/.git/info/exclude
```

启动：

```bash
npm run dev -- --host 0.0.0.0 --port 16106 --config vite.yuhao.local.config.js
```

打开：

```text
http://<服务器IP>:16106/
```

## 18. 测试要求

### 18.1 现有 Roboflow 链路测试

必须覆盖：

1. 纯 Roboflow URL。
2. Roboflow curl 命令。
3. Roboflow Python SDK 下载代码。
4. URL ZIP。
5. 上传 ZIP。
6. 无效下载内容。
7. 缺失 `data.yaml` 但可以自动生成的 YOLO 目录。
8. COCO 数据集转 YOLO。
9. 多类别数据集。
10. 小样本数据集。
11. 训练失败后的 retry。
12. 算法服务重启后状态查询。

### 18.2 新智能数据集训练测试

测试类别建议：

```text
helmet
safety vest
crack
fossil
ammonite
traffic cone
fire extinguisher
```

测试流程：

1. 打开 `http://<服务器IP>:16106/auto-dataset-training`。
2. 输入类别名。
3. 检查候选数据集列表。
4. 检查评分原因。
5. 确认最高分候选。
6. 下载并预检。
7. 自动训练。
8. 训练完成后进入 `/model-training?tab=detect` 测试模型。

### 18.3 不需要测试

本次不需要测试：

```text
Label Studio
DINO
自动标注
可行性评估
增量闭环
边端模拟
```

## 19. 常用检查命令

查看 Git 状态：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao
git status
git branch --show-current
git remote -v
```

查看宇浩服务端口：

```bash
ss -ltnp | grep -E ':16106|:18180|:18011'
```

查看日志：

```bash
tail -f /tmp/yuhao-springboot-18180.log
tail -f /tmp/yuhao-algorithm-18011.log
```

验证后端：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/backend-springboot
mvn test
```

验证前端：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/frontend-vue
npm run build
```

验证 Python 语法：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/algorithm-service
python -m py_compile main.py routers/training.py routers/single_class_detection.py
```

新增 `auto_dataset_training.py` 后也要加入：

```bash
python -m py_compile routers/auto_dataset_training.py
```

## 20. 提交和 PR

提交前确认不要包含本地运行文件：

```bash
git status --short
```

不要提交：

```text
frontend-vue/node_modules/
frontend-vue/dist/
backend-springboot/target/
backend-springboot/data-yuhao/
runtime/
logs/
*.log
*.db
*.mv.db
*.trace.db
*.pt
*.pth
*.onnx
vite.yuhao.local.config.js
```

建议提交范围：

```bash
git add frontend-vue/src backend-springboot/src algorithm-service
git diff --cached --check
git commit -m "feat: add category-driven dataset training workflow"
git push origin feat/yuhao-single-class-training-agent
```

PR 描述必须写清楚：

1. 改了哪些页面和接口。
2. Roboflow URL / curl / Python SDK 三种输入是否都测过。
3. Kaggle 是否需要凭证，未配置时如何降级。
4. 联网搜索的白名单和安全限制。
5. 候选数据集如何评分。
6. AutoML 最终参数在哪里确定。
7. 训练状态和日志是否落盘。
8. 本地测试使用的端口：前端 `16106`，后端 `18180`，算法服务 `18011`。
9. 执行过哪些验证命令。

## 21. 和主仓库同步

开发中同步 A 的最新代码：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao
git fetch upstream
git rebase upstream/main
```

如果有冲突，解决冲突后：

```bash
git add <冲突文件>
git rebase --continue
git push --force-with-lease
```

不要使用 `git push --force`。

PR 合并后，宇浩更新自己的 `main`：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao
git checkout main
git fetch upstream
git reset --hard upstream/main
git push origin main --force-with-lease
```

然后再开下一个任务分支。

## 22. 最重要的规则

1. 不在主项目目录改代码。
2. 不直接 push 主仓库 `main`。
3. 每个任务一个分支。
4. 不改全局默认端口配置。
5. 前端端口用 `--port`，后端端口用 `--server.port`。
6. 数据库、上传目录、训练数据集、模型产物、缓存目录、日志目录都和主项目隔离。
7. 本次不启动 Label Studio。
8. 本次不启动 DINO。
9. 不提交运行产物、数据库、上传文件、模型权重和本地临时配置。
10. PR 只包含单类别训练算法服务、智能数据集训练页面及其必要后端接口。

## 23. 参考资料

Roboflow Universe API：

```text
https://docs.roboflow.com/developer/rest-api/universe-api
```

Kaggle CLI：

```text
https://github.com/Kaggle/kaggle-cli/blob/main/docs/README.md
```
