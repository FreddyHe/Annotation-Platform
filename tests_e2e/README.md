# E2E测试套件 - 自动标注与MLOps平台

## 快速开始

### 方式一：使用自动化脚本（最简单）

```bash
cd /root/autodl-fs/Annotation-Platform/tests_e2e
./run_tests.sh
```

该脚本会自动完成以下步骤：
1. 检查并创建conda环境（如果不存在）
2. 检查并安装依赖（如果未安装）
3. 配置环境变量
4. 运行所有测试

### 方式二：手动执行

```bash
# 1. 创建并激活conda环境
conda create -n e2e_test python=3.10 -y
conda activate e2e_test

# 2. 安装依赖
cd /root/autodl-fs/Annotation-Platform/tests_e2e
pip install -r requirements.txt

# 3. 运行测试
cd /root/autodl-fs/Annotation-Platform
pytest tests_e2e/ -v
```

## 概述

这是一套基于 pytest 和 requests 的端到端自动化测试脚本，用于测试自动标注与MLOps平台的核心业务链路。

## 核心测试链路

### 链路A: 用户注册与项目管理
- 用户注册/登录获取JWT Token
- 创建项目并配置标签
- 查询项目详情和列表
- 删除项目

### 链路B: 文件切片上传与合并
- 创建测试项目
- 生成临时大文件（5MB）
- 分块上传文件（并发切片）
- 合并文件分块
- 查询上传进度
- 删除文件

### 链路C: DINO目标检测工作流
- 创建项目并上传测试图片
- 调用FastAPI DINO检测接口
- 轮询任务状态直到完成
- 获取检测结果
- 任务取消功能测试

### 链路D: YOLO模型训练工作流
- 创建项目并准备数据集
- 调用FastAPI YOLO训练接口
- 查询训练状态
- 获取训练日志
- 训练任务取消功能测试

### 链路E: VLM数据清洗工作流
- 创建项目并上传测试图片
- 调用FastAPI VLM清洗接口
- 轮询任务状态直到完成
- 获取清洗结果
- 任务取消功能测试

## 环境要求

- Python 3.8+
- pytest 7.4+
- requests 2.31+
- Pillow 10.0+
- numpy 1.24+
- conda（推荐使用conda环境）

## 安装依赖

### 方式一：使用Conda环境（推荐）

```bash
# 创建conda环境
conda create -n e2e_test python=3.10 -y

# 激活环境
conda activate e2e_test

# 安装依赖
cd /root/autodl-fs/Annotation-Platform/tests_e2e
pip install -r requirements.txt
```

### 方式二：使用虚拟环境

```bash
cd /root/autodl-fs/Annotation-Platform/tests_e2e
python -m venv venv
source venv/bin/activate  # Linux/Mac
# 或 venv\Scripts\activate  # Windows
pip install -r requirements.txt
```

### 方式三：直接安装（不推荐）

```bash
cd /root/autodl-fs/Annotation-Platform/tests_e2e
pip install -r requirements.txt
```

## 配置环境变量

可以通过环境变量配置服务地址（可选，默认值如下）：

```bash
export BACKEND_BASE_URL="http://localhost:8080"
export ALGORITHM_BASE_URL="http://localhost:8000"
```

## 运行测试

### 运行所有测试

```bash
# 激活conda环境
conda activate e2e_test

# 运行测试
cd /root/autodl-fs/Annotation-Platform
pytest tests_e2e/ -v
```

### 运行特定测试文件

```bash
conda activate e2e_test
pytest tests_e2e/test_auth_and_project.py -v
pytest tests_e2e/test_file_upload.py -v
pytest tests_e2e/test_dino_detection.py -v
pytest tests_e2e/test_yolo_training.py -v
pytest tests_e2e/test_vlm_cleaning.py -v
```

### 运行特定测试用例

```bash
conda activate e2e_test
pytest tests_e2e/test_auth_and_project.py::test_user_registration_and_project_creation -v
```

### 显示详细输出

```bash
conda activate e2e_test
pytest tests_e2e/ -v -s
```

### 生成测试报告

```bash
conda activate e2e_test
pytest tests_e2e/ --html=report.html --self-contained-html
```

### 在不激活环境的情况下运行（使用conda run）

```bash
conda run -n e2e_test pytest /root/autodl-fs/Annotation-Platform/tests_e2e/ -v
```

## 测试结构

```
tests_e2e/
├── conftest.py              # pytest配置文件，包含fixtures和工具函数
├── test_auth_and_project.py  # 用户认证和项目管理测试
├── test_file_upload.py       # 文件上传测试
├── test_dino_detection.py    # DINO检测测试
├── test_yolo_training.py     # YOLO训练测试
├── test_vlm_cleaning.py      # VLM清洗测试
├── run_tests.sh            # 自动化测试运行脚本
├── requirements.txt         # Python依赖
└── README.md                 # 本文件
```

## 核心Fixtures

- `backend_session`: Spring Boot后端会话
- `algorithm_session`: FastAPI算法服务会话
- `auth_token`: JWT认证令牌
- `authenticated_session`: 带认证的后端会话
- `temp_large_file`: 临时大文件（用于测试分片上传）
- `temp_image_file`: 临时图片文件（用于测试算法服务）

## 核心工具函数

- `wait_for_task_completion()`: 轮询任务状态直到完成
- `upload_file_in_chunks()`: 分块上传文件

## 注意事项

1. 测试会自动检查服务健康状态，如果服务不可用会跳过测试
2. 测试会创建临时文件和测试项目，测试完成后会自动清理
3. YOLO训练测试可能会因为缺少真实数据集而跳过
4. 某些测试需要较长时间完成（如训练任务），请耐心等待

## 故障排查

如果测试失败，请检查：

1. 服务是否正常运行
   ```bash
   curl http://localhost:8080/actuator/health
   curl http://localhost:8000/api/v1/health
   ```

2. 查看服务日志
   - Spring Boot: 查看后端控制台输出
   - FastAPI: 查看算法服务控制台输出

3. 检查网络连接和防火墙设置

4. 确认数据库连接正常

## 调试模式

启用详细日志输出：

```bash
pytest tests_e2e/ -v -s --log-cli-level=DEBUG
```

## 持续集成

可以将此测试套件集成到CI/CD流程中：

```yaml
# .github/workflows/e2e-tests.yml
name: E2E Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up conda
        uses: conda-incubator/setup-miniconda@v2
        with:
          auto-update-conda: true
          python-version: '3.10'
          
      - name: Create conda environment
        run: |
          conda create -n e2e_test python=3.10 -y
          conda activate e2e_test
          
      - name: Install dependencies
        run: |
          conda run -n e2e_test pip install -r tests_e2e/requirements.txt
          
      - name: Run E2E tests
        run: |
          conda run -n e2e_test pytest tests_e2e/ -v
```

## 快速开始（完整流程）

```bash
# 1. 创建并激活conda环境
conda create -n e2e_test python=3.10 -y
conda activate e2e_test

# 2. 安装依赖
cd /root/autodl-fs/Annotation-Platform/tests_e2e
pip install -r requirements.txt

# 3. 配置环境变量（可选）
export BACKEND_BASE_URL="http://localhost:8080"
export ALGORITHM_BASE_URL="http://localhost:8000"

# 4. 运行测试
cd /root/autodl-fs/Annotation-Platform
pytest tests_e2e/ -v
```
