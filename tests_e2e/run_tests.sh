#!/bin/bash

set -e

echo "=========================================="
echo "E2E测试套件 - 自动标注与MLOps平台"
echo "=========================================="
echo ""

CONDA_ENV="e2e_test"
PROJECT_DIR="/root/autodl-fs/Annotation-Platform"
TESTS_DIR="${PROJECT_DIR}/tests_e2e"

echo "[1/4] 检查conda环境..."
if conda env list | grep -q "^${CONDA_ENV} "; then
    echo "✓ Conda环境 '${CONDA_ENV}' 已存在"
else
    echo "✗ Conda环境 '${CONDA_ENV}' 不存在，正在创建..."
    conda create -n ${CONDA_ENV} python=3.10 -y
    echo "✓ Conda环境 '${CONDA_ENV}' 创建成功"
fi

echo ""
echo "[2/4] 检查依赖..."
if conda run -n ${CONDA_ENV} pip show pytest > /dev/null 2>&1; then
    echo "✓ 依赖已安装"
else
    echo "✗ 依赖未安装，正在安装..."
    conda run -n ${CONDA_ENV} pip install -r ${TESTS_DIR}/requirements.txt
    echo "✓ 依赖安装成功"
fi

echo ""
echo "[3/4] 配置环境变量..."
export BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:8080}"
export ALGORITHM_BASE_URL="${ALGORITHM_BASE_URL:-http://localhost:8000}"
echo "  BACKEND_BASE_URL=${BACKEND_BASE_URL}"
echo "  ALGORITHM_BASE_URL=${ALGORITHM_BASE_URL}"

echo ""
echo "[4/4] 运行测试..."
echo "=========================================="
cd ${PROJECT_DIR}
conda run -n ${CONDA_ENV} pytest tests_e2e/ -v "$@"

echo ""
echo "=========================================="
echo "测试完成！"
echo "=========================================="
