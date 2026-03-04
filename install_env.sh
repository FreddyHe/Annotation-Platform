#!/bin/bash
# 完整的环境设置脚本 - 自动标注平台

set -e  # 遇到错误立即退出

PROJECT_DIR="/root/autodl-fs/web_biaozhupingtai"
ENV_NAME="web_annotation"
PYTHON_VERSION="3.10"

echo "=========================================="
echo "🚀 自动标注平台环境设置"
echo "=========================================="
echo "项目目录: $PROJECT_DIR"
echo "环境名称: $ENV_NAME"
echo "Python版本: $PYTHON_VERSION"
echo ""

# ==================== 步骤1: 创建conda环境 ====================
echo "📦 步骤1/5: 创建conda环境"
if conda env list | grep -q "^$ENV_NAME "; then
    echo "⚠️  环境 $ENV_NAME 已存在，跳过创建"
else
    conda create -n $ENV_NAME python=$PYTHON_VERSION -y
    echo "✅ 环境创建成功"
fi
echo ""

# ==================== 步骤2: 激活环境并安装依赖 ====================
echo "📦 步骤2/5: 安装Web平台依赖"
source $(conda info --base)/etc/profile.d/conda.sh
conda activate $ENV_NAME

# 确保pip最新
pip install --upgrade pip

# 安装依赖
if [ -f "$PROJECT_DIR/requirements_web.txt" ]; then
    pip install -r "$PROJECT_DIR/requirements_web.txt"
else
    echo "⚠️  未找到 requirements_web.txt，使用临时文件"
    cat > /tmp/requirements_web.txt << 'EOF'
streamlit>=1.28.0
fastapi>=0.104.0
uvicorn[standard]>=0.24.0
Pillow>=10.0.0
opencv-python>=4.8.0
numpy>=1.24.0
openai>=1.3.0
httpx>=0.25.0
requests>=2.31.0
pandas>=2.0.0
pyyaml>=6.0
tqdm>=4.66.0
python-multipart>=0.0.6
aiofiles>=23.2.0
EOF
    pip install -r /tmp/requirements_web.txt
fi
echo "✅ 依赖安装成功"
echo ""

# ==================== 步骤3: 配置Python路径自动导入 ====================
echo "🔗 步骤3/5: 配置现有环境包路径复用"
SITE_PACKAGES=$(python -c "import site; print(site.getsitepackages()[0])")
SITECUSTOMIZE_PATH="$SITE_PACKAGES/sitecustomize.py"

cat > "$SITECUSTOMIZE_PATH" << 'EOF'
"""自动配置Python路径 - 复用现有环境的包"""
import sys
import os

python_version = f"{sys.version_info.major}.{sys.version_info.minor}"

EXISTING_ENVS = [
    f"/root/miniconda3/envs/groundingdino310/lib/python{python_version}/site-packages",
    f"/root/miniconda3/envs/LLM_DL/lib/python{python_version}/site-packages",
]

for env_path in reversed(EXISTING_ENVS):
    if os.path.exists(env_path) and env_path not in sys.path:
        sys.path.insert(0, env_path)
EOF

echo "✅ sitecustomize.py 已创建: $SITECUSTOMIZE_PATH"
echo ""

# ==================== 步骤4: 验证关键包是否可用 ====================
echo "🔍 步骤4/5: 验证环境配置"
python << 'EOF'
import sys
print("Python路径配置:")
print("-" * 60)

# 检查关键包
packages_to_check = {
    "torch": "PyTorch (来自 groundingdino310)",
    "groundingdino": "Grounding DINO (来自 groundingdino310)",
    "streamlit": "Streamlit (Web框架)",
    "PIL": "Pillow (图像处理)",
    "pandas": "Pandas (数据处理)",
}

print("\n包可用性检查:")
print("-" * 60)
all_ok = True
for package, description in packages_to_check.items():
    try:
        __import__(package)
        print(f"✅ {package:20s} - {description}")
    except ImportError as e:
        print(f"❌ {package:20s} - {description}")
        print(f"   错误: {e}")
        all_ok = False

print("-" * 60)
if all_ok:
    print("✅ 所有关键包都可用！")
else:
    print("⚠️  部分包不可用，请检查错误信息")
print()
EOF
echo ""

# ==================== 步骤5: 创建启动脚本 ====================
echo "📝 步骤5/5: 创建启动脚本"
cat > "$PROJECT_DIR/start.sh" << EOF
#!/bin/bash
# 自动标注平台启动脚本

# 激活环境
source \$(conda info --base)/etc/profile.d/conda.sh
conda activate $ENV_NAME

# 切换到项目目录
cd "$PROJECT_DIR"

echo "=========================================="
echo "🚀 启动自动标注平台"
echo "=========================================="
echo "环境: $ENV_NAME"
echo "端口: 6006"
echo "访问地址: http://localhost:6006"
echo "=========================================="
echo ""

# 启动Streamlit
streamlit run main.py \\
    --server.port 6006 \\
    --server.address 0.0.0.0 \\
    --server.headless true \\
    --browser.gatherUsageStats false
EOF

chmod +x "$PROJECT_DIR/start.sh"
echo "✅ 启动脚本已创建: $PROJECT_DIR/start.sh"
echo ""

# ==================== 完成 ====================
echo "=========================================="
echo "✅ 环境设置完成！"
echo "=========================================="
echo ""
echo "📋 下一步操作："
echo ""
echo "1️⃣ 测试环境："
echo "   conda activate $ENV_NAME"
echo "   python -c 'import torch; import groundingdino; import streamlit; print(\"所有包导入成功！\")'"
echo ""
echo "2️⃣ 启动项目："
echo "   cd $PROJECT_DIR"
echo "   ./start.sh"
echo ""
echo "   或者手动启动："
echo "   conda activate $ENV_NAME"
echo "   cd $PROJECT_DIR"
echo "   streamlit run main.py --server.port 6006 --server.address 0.0.0.0"
echo ""
echo "3️⃣ 访问界面："
echo "   本地: http://localhost:6006"
echo "   远程: http://<你的服务器IP>:6006"
echo ""
echo "=========================================="