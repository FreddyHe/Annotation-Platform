#!/usr/bin/env python3
"""
测试脚本：使用已有的 VLM 清洗结果测试 Label Studio 集成

使用方法:
    python test_label_studio_integration.py

确保:
1. Label Studio 服务已启动 (http://localhost:5001)
2. 已设置环境变量 LABEL_STUDIO_API_KEY 或在脚本中配置
"""

import os
import sys
import json
import time
import logging
from pathlib import Path

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# ==================== 配置 ====================
# Label Studio 配置
LABEL_STUDIO_URL = "http://localhost:5001"

# 使用你上次的清洗结果
VLM_CLEANED_PATH = "/root/autodl-fs/web_biaozhupingtai/data/organizations/xingmu/processed/animaldd/vlm_cleaned.json"

# 图片目录（根据你的项目结构）
UPLOAD_DIR = "/root/autodl-fs/web_biaozhupingtai/data/organizations/xingmu/uploads/animaldd/extracted"

# 项目标题
PROJECT_TITLE = "test_animaldd_标注"

# 标签定义（根据你的实际标签修改）
LABELS_DEF = {
    "animal": "动物目标"
}

# API Key（如果没有设置环境变量，在这里填写）
API_KEY = os.getenv('LABEL_STUDIO_API_KEY', None)

# ==================== 测试函数 ====================

def check_prerequisites():
    """检查前置条件"""
    global UPLOAD_DIR  # 必须在函数开头声明
    
    print("=" * 60)
    print("🔍 检查前置条件...")
    print("=" * 60)
    
    # 检查 VLM 清洗结果
    if not Path(VLM_CLEANED_PATH).exists():
        print(f"❌ VLM 清洗结果不存在: {VLM_CLEANED_PATH}")
        return False
    
    with open(VLM_CLEANED_PATH, 'r', encoding='utf-8') as f:
        vlm_data = json.load(f)
    
    total = len(vlm_data)
    kept = sum(1 for item in vlm_data if item.get('vlm_decision') == 'keep')
    print(f"✅ VLM 清洗结果: 总数 {total}, 保留 {kept}")
    
    # 检查图片目录
    if not Path(UPLOAD_DIR).exists():
        print(f"⚠️ 图片目录不存在: {UPLOAD_DIR}")
        # 尝试自动检测
        possible_dirs = [
            "/root/autodl-fs/web_biaozhupingtai/data/organizations/xingmu/uploads/animaldd",
            "/root/autodl-fs/web_biaozhupingtai/data/organizations/xingmu/uploads/animaldd/extracted",
        ]
        for d in possible_dirs:
            if Path(d).exists():
                print(f"   尝试使用: {d}")
                UPLOAD_DIR = d
                break
        else:
            print("❌ 无法找到有效的图片目录")
            return False
    
    # 统计图片数量
    img_count = len(list(Path(UPLOAD_DIR).glob("*.jpg"))) + len(list(Path(UPLOAD_DIR).glob("*.png")))
    print(f"✅ 图片目录: {UPLOAD_DIR} ({img_count} 张图片)")
    
    # 检查 Label Studio 连接
    try:
        import requests
        resp = requests.get(f"{LABEL_STUDIO_URL}/api/health", timeout=5)
        if resp.status_code == 200:
            print(f"✅ Label Studio 服务正常: {LABEL_STUDIO_URL}")
        else:
            print(f"⚠️ Label Studio 返回状态码: {resp.status_code}")
    except Exception as e:
        print(f"❌ 无法连接 Label Studio: {e}")
        return False
    
    # 检查 API Key
    if not API_KEY:
        print("❌ 未设置 LABEL_STUDIO_API_KEY 环境变量")
        print("   请运行: export LABEL_STUDIO_API_KEY=your_token")
        print("   或在脚本中设置 API_KEY 变量")
        return False
    
    print(f"✅ API Key 已配置 (前8位: {API_KEY[:8]}...)")
    
    return True


def test_integration_sync():
    """测试同步版本的集成"""
    print("\n" + "=" * 60)
    print("🧪 测试同步版本 Label Studio 集成")
    print("=" * 60)
    
    try:
        # 先尝试导入异步版本
        sys.path.insert(0, '/root/autodl-fs/web_biaozhupingtai')
        
        try:
            from label_studio_integration_async import LabelStudioIntegrationAsync
            print("✅ 导入 label_studio_integration_async 成功")
            integration_class = LabelStudioIntegrationAsync
        except ImportError:
            try:
                from label_studio_integration_enhanced import LabelStudioIntegrationAsync
                print("✅ 导入 label_studio_integration_enhanced (Async) 成功")
                integration_class = LabelStudioIntegrationAsync
            except ImportError:
                from label_studio_integration_enhanced import LabelStudioIntegration
                print("✅ 导入 label_studio_integration_enhanced 成功")
                integration_class = LabelStudioIntegration
        
        # 创建集成实例
        print("\n📝 创建 Label Studio 集成实例...")
        integration = integration_class(
            url=LABEL_STUDIO_URL,
            api_key=API_KEY
        )
        print("✅ 集成实例创建成功")
        
        # 执行完整设置流程
        print("\n🚀 开始完整设置流程...")
        print(f"   项目标题: {PROJECT_TITLE}")
        print(f"   标签: {list(LABELS_DEF.keys())}")
        print(f"   图片目录: {UPLOAD_DIR}")
        print(f"   VLM 结果: {VLM_CLEANED_PATH}")
        
        start_time = time.time()
        
        result = integration.setup_project_complete(
            project_title=PROJECT_TITLE,
            labels=LABELS_DEF,
            upload_dir=UPLOAD_DIR,
            vlm_cleaned_path=VLM_CLEANED_PATH
        )
        
        elapsed = time.time() - start_time
        
        print("\n" + "=" * 60)
        print(f"⏱️ 总耗时: {elapsed:.2f} 秒")
        print("=" * 60)
        
        if result['success']:
            print("🎉 测试成功！")
            print(f"   项目 ID: {result.get('project_id')}")
            print(f"   项目 URL: {result.get('project_url')}")
            
            stats = result.get('stats', {})
            print(f"   总任务数: {stats.get('total_tasks', 0)}")
            print(f"   成功添加预测: {stats.get('success', 0)}")
            print(f"   跳过（无匹配）: {stats.get('skipped', 0)}")
            print(f"   失败: {stats.get('failed', 0)}")
            
            return True
        else:
            print(f"❌ 测试失败: {result.get('error')}")
            return False
            
    except Exception as e:
        print(f"❌ 测试异常: {e}")
        import traceback
        traceback.print_exc()
        return False


def test_step_by_step():
    """分步骤测试，定位问题"""
    print("\n" + "=" * 60)
    print("🔬 分步骤测试（用于定位问题）")
    print("=" * 60)
    
    import requests
    
    session = requests.Session()
    session.proxies = {'http': None, 'https': None}
    headers = {"Authorization": f"Token {API_KEY}"}
    
    # 步骤 1: 验证 API Key
    print("\n📝 步骤 1: 验证 API Key...")
    try:
        resp = session.get(f"{LABEL_STUDIO_URL}/api/current-user/whoami", headers=headers, timeout=10)
        if resp.status_code == 200:
            user = resp.json()
            print(f"   ✅ 验证成功，用户: {user.get('email', 'N/A')}")
        else:
            print(f"   ❌ 验证失败: {resp.status_code} - {resp.text}")
            return False
    except Exception as e:
        print(f"   ❌ 异常: {e}")
        return False
    
    # 步骤 2: 列出现有项目
    print("\n📝 步骤 2: 列出现有项目...")
    try:
        resp = session.get(f"{LABEL_STUDIO_URL}/api/projects", headers=headers, timeout=30)
        if resp.status_code == 200:
            projects = resp.json()
            count = projects.get('count', len(projects.get('results', [])))
            print(f"   ✅ 获取成功，共 {count} 个项目")
        else:
            print(f"   ❌ 获取失败: {resp.status_code}")
    except Exception as e:
        print(f"   ❌ 异常: {e}")
    
    # 步骤 3: 创建测试项目
    print("\n📝 步骤 3: 创建测试项目...")
    test_project_title = f"test_integration_{int(time.time())}"
    label_config = '''<View>
        <Image name="image" value="$image" zoom="true"/>
        <RectangleLabels name="label" toName="image">
            <Label value="animal" background="#FF0000"/>
        </RectangleLabels>
    </View>'''
    
    try:
        resp = session.post(
            f"{LABEL_STUDIO_URL}/api/projects",
            json={"title": test_project_title, "label_config": label_config},
            headers=headers,
            timeout=30
        )
        if resp.status_code == 201:
            project = resp.json()
            project_id = project['id']
            print(f"   ✅ 创建成功，ID: {project_id}")
        else:
            print(f"   ❌ 创建失败: {resp.status_code} - {resp.text}")
            return False
    except Exception as e:
        print(f"   ❌ 异常: {e}")
        return False
    
    # 步骤 4: 挂载本地存储
    print("\n📝 步骤 4: 挂载本地存储...")
    try:
        storage_payload = {
            "path": UPLOAD_DIR,
            "project": project_id,
            "title": "Test_Storage",
            "use_blob_urls": True,
            "regex_filter": ".*",
            "recursive_scan": True,
        }
        resp = session.post(
            f"{LABEL_STUDIO_URL}/api/storages/localfiles",
            json=storage_payload,
            headers=headers,
            timeout=60
        )
        if resp.status_code == 201:
            storage = resp.json()
            storage_id = storage['id']
            print(f"   ✅ 创建成功，存储 ID: {storage_id}")
        else:
            print(f"   ❌ 创建失败: {resp.status_code} - {resp.text}")
            return False
    except Exception as e:
        print(f"   ❌ 异常: {e}")
        return False
    
    # 步骤 5: 同步存储
    print("\n📝 步骤 5: 同步存储...")
    try:
        resp = session.post(
            f"{LABEL_STUDIO_URL}/api/storages/localfiles/{storage_id}/sync",
            headers=headers,
            timeout=300
        )
        if resp.status_code in [200, 201]:
            print(f"   ✅ 同步成功")
        else:
            print(f"   ⚠️ 同步返回: {resp.status_code}")
    except Exception as e:
        print(f"   ❌ 异常: {e}")
    
    # 步骤 6: 获取任务列表
    print("\n📝 步骤 6: 获取任务列表...")
    try:
        resp = session.get(
            f"{LABEL_STUDIO_URL}/api/projects/{project_id}/tasks",
            headers=headers,
            params={"page_size": 100},
            timeout=60
        )
        if resp.status_code == 200:
            tasks = resp.json()
            task_list = tasks.get('tasks', tasks) if isinstance(tasks, dict) else tasks
            print(f"   ✅ 获取成功，共 {len(task_list)} 个任务")
            
            if task_list:
                print(f"   📋 第一个任务示例: {task_list[0].get('data', {}).get('image', 'N/A')[:80]}...")
        else:
            print(f"   ❌ 获取失败: {resp.status_code}")
    except Exception as e:
        print(f"   ❌ 异常: {e}")
    
    # 步骤 7: 测试添加预测
    print("\n📝 步骤 7: 测试添加单个预测...")
    if task_list:
        task = task_list[0]
        test_prediction = {
            "task": task['id'],
            "result": [{
                "original_width": 640,
                "original_height": 480,
                "image_rotation": 0,
                "value": {
                    "x": 10, "y": 10, "width": 20, "height": 20,
                    "rotation": 0, "rectanglelabels": ["animal"]
                },
                "id": "test_pred_1",
                "from_name": "label",
                "to_name": "image",
                "type": "rectanglelabels"
            }],
            "model_version": "test_v1",
            "score": 0.95,
            "project": project_id
        }
        
        try:
            start = time.time()
            resp = session.post(
                f"{LABEL_STUDIO_URL}/api/predictions",
                json=test_prediction,
                headers=headers,
                timeout=60
            )
            elapsed = time.time() - start
            
            if resp.status_code in [200, 201]:
                print(f"   ✅ 添加成功 (耗时: {elapsed:.2f}s)")
            else:
                print(f"   ❌ 添加失败: {resp.status_code} - {resp.text[:200]}")
        except Exception as e:
            print(f"   ❌ 异常: {e}")
    
    # 步骤 8: 批量添加预测测试
    print("\n📝 步骤 8: 批量添加预测测试 (10个)...")
    if len(task_list) >= 10:
        success = 0
        failed = 0
        start = time.time()
        
        for i, task in enumerate(task_list[:10]):
            pred = {
                "task": task['id'],
                "result": [{
                    "original_width": 640,
                    "original_height": 480,
                    "image_rotation": 0,
                    "value": {
                        "x": 10 + i, "y": 10 + i, "width": 20, "height": 20,
                        "rotation": 0, "rectanglelabels": ["animal"]
                    },
                    "id": f"batch_pred_{i}",
                    "from_name": "label",
                    "to_name": "image",
                    "type": "rectanglelabels"
                }],
                "model_version": "batch_test_v1",
                "score": 0.9,
                "project": project_id
            }
            
            try:
                resp = session.post(
                    f"{LABEL_STUDIO_URL}/api/predictions",
                    json=pred,
                    headers=headers,
                    timeout=60
                )
                if resp.status_code in [200, 201]:
                    success += 1
                else:
                    failed += 1
            except:
                failed += 1
        
        elapsed = time.time() - start
        print(f"   ✅ 完成: 成功 {success}, 失败 {failed}, 总耗时 {elapsed:.2f}s")
        print(f"   📊 平均每个预测: {elapsed/10:.2f}s")
    
    # 清理测试项目
    print("\n📝 清理: 删除测试项目...")
    try:
        resp = session.delete(
            f"{LABEL_STUDIO_URL}/api/projects/{project_id}",
            headers=headers,
            timeout=30
        )
        if resp.status_code in [200, 204]:
            print(f"   ✅ 删除成功")
        else:
            print(f"   ⚠️ 删除返回: {resp.status_code}")
    except Exception as e:
        print(f"   ⚠️ 删除异常: {e}")
    
    print("\n" + "=" * 60)
    print("🔬 分步骤测试完成")
    print("=" * 60)
    
    return True


def main():
    """主函数"""
    print("\n" + "=" * 60)
    print("🧪 Label Studio 集成测试脚本")
    print("=" * 60)
    
    # 检查前置条件
    if not check_prerequisites():
        print("\n❌ 前置条件检查失败，请修复后重试")
        sys.exit(1)
    
    # 选择测试模式
    print("\n选择测试模式:")
    print("  1. 分步骤测试（用于诊断问题）")
    print("  2. 完整集成测试（使用 VLM 清洗结果）")
    print("  3. 两者都测试")
    
    choice = input("\n请输入选项 (1/2/3) [默认=3]: ").strip() or "3"
    
    if choice in ["1", "3"]:
        test_step_by_step()
    
    if choice in ["2", "3"]:
        test_integration_sync()
    
    print("\n✅ 测试脚本执行完毕")


if __name__ == "__main__":
    main()