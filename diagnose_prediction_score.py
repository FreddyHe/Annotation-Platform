#!/usr/bin/env python3
"""
Label Studio 预测分数诊断脚本
用于排查 Prediction score 列不显示的问题
"""
import json
import requests
import sqlite3
import os
from pathlib import Path

# ============ 配置区 ============
LABEL_STUDIO_URL = "http://localhost:5001"
API_KEY = os.getenv('LABEL_STUDIO_API_KEY', '')  # 请设置你的 API Key
DB_PATH = "/root/label_studio_data_fast/label_studio.sqlite3"
PROJECT_ID = None  # 设为 None 会自动查找第一个项目，或指定具体 ID

# ================================

def get_headers():
    return {"Authorization": f"Token {API_KEY}"}

def print_section(title):
    print(f"\n{'='*60}")
    print(f"  {title}")
    print('='*60)

def check_api_connection():
    """检查 API 连接"""
    print_section("1. 检查 API 连接")
    try:
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/current-user/whoami",
            headers=get_headers(),
            timeout=10
        )
        if resp.status_code == 200:
            user = resp.json()
            print(f"✅ API 连接成功")
            print(f"   用户: {user.get('email', 'N/A')}")
            return True
        else:
            print(f"❌ API 连接失败: {resp.status_code}")
            print(f"   响应: {resp.text[:200]}")
            return False
    except Exception as e:
        print(f"❌ 连接异常: {e}")
        return False

def get_project_id():
    """获取项目 ID"""
    global PROJECT_ID
    if PROJECT_ID:
        return PROJECT_ID
    
    try:
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/projects",
            headers=get_headers(),
            timeout=10
        )
        if resp.status_code == 200:
            projects = resp.json().get('results', [])
            if projects:
                PROJECT_ID = projects[0]['id']
                print(f"   自动选择项目: {projects[0].get('title')} (ID: {PROJECT_ID})")
                return PROJECT_ID
    except:
        pass
    return None

def check_predictions_via_api():
    """通过 API 检查预测数据"""
    print_section("2. 通过 API 检查预测数据")
    
    pid = get_project_id()
    if not pid:
        print("❌ 未找到项目")
        return
    
    try:
        # 获取预测列表
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/predictions",
            headers=get_headers(),
            params={"project": pid, "page_size": 5},
            timeout=30
        )
        
        if resp.status_code != 200:
            print(f"❌ 获取预测失败: {resp.status_code}")
            return
        
        data = resp.json()
        predictions = data.get('results', []) if isinstance(data, dict) else data
        
        print(f"✅ 获取到 {len(predictions)} 条预测记录 (显示前5条)")
        print()
        
        for i, pred in enumerate(predictions[:5]):
            print(f"--- 预测 #{i+1} ---")
            print(f"   prediction_id: {pred.get('id')}")
            print(f"   task_id: {pred.get('task')}")
            print(f"   model_version: {pred.get('model_version')}")
            print(f"   score (prediction级): {pred.get('score')}")  # 关键！
            
            # 检查 result 中的 score
            result = pred.get('result', [])
            if result and isinstance(result, list):
                print(f"   result 数量: {len(result)} 个区域")
                for j, r in enumerate(result[:2]):  # 只显示前2个
                    print(f"      result[{j}].score: {r.get('score')}")
                    print(f"      result[{j}].type: {r.get('type')}")
            print()
            
    except Exception as e:
        print(f"❌ 异常: {e}")

def check_predictions_via_db():
    """通过数据库直接检查预测数据"""
    print_section("3. 通过数据库检查预测数据")
    
    if not os.path.exists(DB_PATH):
        print(f"❌ 数据库文件不存在: {DB_PATH}")
        return
    
    try:
        conn = sqlite3.connect(DB_PATH)
        cursor = conn.cursor()
        
        # 检查 prediction 表结构
        print("📋 prediction 表结构:")
        cursor.execute("PRAGMA table_info(prediction)")
        columns = cursor.fetchall()
        for col in columns:
            print(f"   {col[1]:20} | {col[2]:15} | nullable: {col[3]==0}")
        
        print()
        
        # 检查实际数据
        print("📋 前5条预测数据 (关键字段):")
        cursor.execute("""
            SELECT id, task_id, model_version, score, 
                   substr(result, 1, 200) as result_preview
            FROM prediction 
            ORDER BY id DESC 
            LIMIT 5
        """)
        
        rows = cursor.fetchall()
        for row in rows:
            print(f"\n--- prediction_id: {row[0]} ---")
            print(f"   task_id: {row[1]}")
            print(f"   model_version: {row[2]}")
            print(f"   score (DB存储): {row[3]}")  # 关键！
            print(f"   result 预览: {row[4]}...")
            
            # 解析 result JSON 检查内部 score
            try:
                cursor.execute("SELECT result FROM prediction WHERE id = ?", (row[0],))
                full_result = cursor.fetchone()[0]
                result_json = json.loads(full_result)
                if result_json and isinstance(result_json, list):
                    for j, r in enumerate(result_json[:2]):
                        print(f"   result[{j}].score (JSON内): {r.get('score')}")
            except:
                pass
        
        conn.close()
        
    except Exception as e:
        print(f"❌ 数据库异常: {e}")

def check_task_predictions_detail():
    """检查任务的预测详情"""
    print_section("4. 检查具体任务的预测关联")
    
    pid = get_project_id()
    if not pid:
        print("❌ 未找到项目")
        return
    
    try:
        # 获取一个有预测的任务
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/projects/{pid}/tasks",
            headers=get_headers(),
            params={"page_size": 5},
            timeout=30
        )
        
        if resp.status_code != 200:
            print(f"❌ 获取任务失败: {resp.status_code}")
            return
        
        data = resp.json()
        tasks = data.get('tasks', data) if isinstance(data, dict) else data
        
        print(f"✅ 检查前5个任务的预测关联")
        print()
        
        for task in tasks[:5]:
            task_id = task.get('id')
            predictions = task.get('predictions', [])
            
            print(f"--- Task #{task_id} ---")
            print(f"   predictions 数量: {len(predictions)}")
            
            # 检查任务返回中是否包含 prediction score
            if predictions:
                for p in predictions[:2]:
                    if isinstance(p, dict):
                        print(f"   prediction.id: {p.get('id')}")
                        print(f"   prediction.score: {p.get('score')}")
                        print(f"   prediction.model_version: {p.get('model_version')}")
                    else:
                        print(f"   prediction (ID only): {p}")
            print()
            
    except Exception as e:
        print(f"❌ 异常: {e}")

def test_create_prediction_with_score():
    """测试创建一个带 score 的预测"""
    print_section("5. 测试创建新预测 (带 score)")
    
    pid = get_project_id()
    if not pid:
        print("❌ 未找到项目")
        return
    
    try:
        # 获取一个任务
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/projects/{pid}/tasks",
            headers=get_headers(),
            params={"page_size": 1},
            timeout=30
        )
        
        if resp.status_code != 200:
            print(f"❌ 获取任务失败")
            return
        
        data = resp.json()
        tasks = data.get('tasks', data) if isinstance(data, dict) else data
        if not tasks:
            print("❌ 没有任务可供测试")
            return
        
        task_id = tasks[0]['id']
        print(f"📝 选择任务 ID: {task_id} 进行测试")
        
        # 创建测试预测
        test_payload = {
            "task": task_id,
            "result": [
                {
                    "original_width": 640,
                    "original_height": 640,
                    "image_rotation": 0,
                    "value": {
                        "x": 10, "y": 10, "width": 20, "height": 20,
                        "rotation": 0,
                        "rectanglelabels": ["test_label"]
                    },
                    "id": "test_region_001",
                    "from_name": "label",
                    "to_name": "image",
                    "type": "rectanglelabels",
                    "score": 0.88  # result 级别的 score
                }
            ],
            "model_version": "diagnosis_test_v1",
            "score": 0.77  # prediction 级别的 score (这是关键!)
        }
        
        print(f"\n📤 发送的 payload:")
        print(f"   score (prediction级): {test_payload['score']}")
        print(f"   result[0].score: {test_payload['result'][0]['score']}")
        
        resp = requests.post(
            f"{LABEL_STUDIO_URL}/api/predictions",
            headers=get_headers(),
            json=test_payload,
            timeout=30
        )
        
        print(f"\n📥 API 响应:")
        print(f"   status_code: {resp.status_code}")
        
        if resp.status_code in [200, 201]:
            result = resp.json()
            print(f"   返回的 prediction_id: {result.get('id')}")
            print(f"   返回的 score: {result.get('score')}")  # 关键！看 API 是否返回了 score
            print(f"   返回的 model_version: {result.get('model_version')}")
            
            # 再次查询这个预测
            pred_id = result.get('id')
            if pred_id:
                print(f"\n🔍 重新查询刚创建的预测 (ID: {pred_id}):")
                resp2 = requests.get(
                    f"{LABEL_STUDIO_URL}/api/predictions/{pred_id}",
                    headers=get_headers(),
                    timeout=10
                )
                if resp2.status_code == 200:
                    pred_data = resp2.json()
                    print(f"   查询返回的 score: {pred_data.get('score')}")
                else:
                    print(f"   查询失败: {resp2.status_code}")
        else:
            print(f"   错误响应: {resp.text[:300]}")
            
    except Exception as e:
        print(f"❌ 异常: {e}")
        import traceback
        traceback.print_exc()

def check_label_studio_version():
    """检查 Label Studio 版本"""
    print_section("6. 检查 Label Studio 版本")
    
    try:
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/version",
            headers=get_headers(),
            timeout=10
        )
        if resp.status_code == 200:
            version_info = resp.json()
            print(f"✅ Label Studio 版本信息:")
            print(f"   {json.dumps(version_info, indent=4)}")
        else:
            # 尝试其他方式获取版本
            resp2 = requests.get(f"{LABEL_STUDIO_URL}/version", timeout=10)
            if resp2.status_code == 200:
                print(f"✅ 版本: {resp2.text[:100]}")
            else:
                print(f"⚠️ 无法获取版本信息")
    except Exception as e:
        print(f"⚠️ 版本检查异常: {e}")

def main():
    print("\n" + "=" * 60)
    print("   Label Studio 预测分数诊断工具")
    print("=" * 60)
    
    if not API_KEY:
        print("\n⚠️  警告: API_KEY 未设置!")
        print("   请设置环境变量: export LABEL_STUDIO_API_KEY='your_token'")
        print("   或在脚本中直接修改 API_KEY 变量")
        return
    
    # 运行所有检查
    if check_api_connection():
        check_label_studio_version()
        check_predictions_via_api()
        check_predictions_via_db()
        check_task_predictions_detail()
        test_create_prediction_with_score()
    
    print_section("诊断总结")
    print("""
根据以上检查结果，请关注以下关键点:

1. 【API 返回】创建预测时，API 是否返回了 score 字段？
2. 【数据库存储】数据库 prediction 表中 score 列是否有值？
3. 【查询返回】查询预测时，score 字段是否被返回？

如果 score 在创建时被接受，但查询时为 null，可能是:
- Label Studio 版本 bug
- score 字段被忽略
- 需要特定配置才能启用

请将上面的输出结果分享给我，我可以进一步分析问题。
""")

if __name__ == "__main__":
    main()