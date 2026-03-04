#!/usr/bin/env python3
"""
针对 xingmu_animalee_标注 项目的专项诊断
"""
import json
import requests
import sqlite3
import os

# ============ 配置 ============
LABEL_STUDIO_URL = "http://localhost:5001"
API_KEY = os.getenv('LABEL_STUDIO_API_KEY', '9e9a8fe31f08e30a3875ff44bfdbcd68872b9265')
DB_PATH = "/root/label_studio_data_fast/label_studio.sqlite3"
TARGET_PROJECT_NAME = "xingmu_animalee_标注"  # ID: 34
TARGET_PROJECT_ID = 34  # 直接用ID，避免分页问题

def get_headers():
    return {"Authorization": f"Token {API_KEY}"}

def print_section(title):
    print(f"\n{'='*70}")
    print(f"  {title}")
    print('='*70)

def find_project_by_name(name):
    """查找项目 - 优先用ID"""
    # 如果有直接指定的ID，直接获取
    if TARGET_PROJECT_ID:
        try:
            resp = requests.get(
                f"{LABEL_STUDIO_URL}/api/projects/{TARGET_PROJECT_ID}",
                headers=get_headers(),
                timeout=30
            )
            if resp.status_code == 200:
                return resp.json()
        except:
            pass
    
    # 否则按名称查找
    try:
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/projects",
            headers=get_headers(),
            params={"page_size": 100},
            timeout=30
        )
        if resp.status_code == 200:
            projects = resp.json().get('results', [])
            for p in projects:
                if p.get('title') == name:
                    return p
            # 模糊匹配
            for p in projects:
                if name in p.get('title', ''):
                    return p
        return None
    except Exception as e:
        print(f"❌ 查找项目异常: {e}")
        return None

def check_project_predictions_via_api(project_id):
    """通过 API 检查项目的预测"""
    print_section(f"1. API 检查项目 {project_id} 的预测数据")
    
    try:
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/predictions",
            headers=get_headers(),
            params={"project": project_id, "page_size": 20},
            timeout=30
        )
        
        if resp.status_code != 200:
            print(f"❌ 获取预测失败: {resp.status_code}")
            return
        
        data = resp.json()
        predictions = data.get('results', []) if isinstance(data, dict) else data
        total_count = data.get('count', len(predictions)) if isinstance(data, dict) else len(predictions)
        
        print(f"📊 该项目共有 {total_count} 条预测记录")
        print(f"📋 显示前 {min(20, len(predictions))} 条:\n")
        
        score_stats = {'has_score': 0, 'no_score': 0, 'score_values': []}
        
        for i, pred in enumerate(predictions[:20]):
            pred_id = pred.get('id')
            task_id = pred.get('task')
            score = pred.get('score')
            model_ver = pred.get('model_version')
            
            # 统计
            if score is not None:
                score_stats['has_score'] += 1
                score_stats['score_values'].append(score)
            else:
                score_stats['no_score'] += 1
            
            # 检查 result 内部的 score
            result = pred.get('result', [])
            result_scores = []
            if result and isinstance(result, list):
                for r in result:
                    if isinstance(r, dict) and 'score' in r:
                        result_scores.append(r.get('score'))
            
            print(f"  预测 #{pred_id:4d} | task: {task_id:4d} | "
                  f"prediction.score: {str(score):6s} | "
                  f"model: {model_ver} | "
                  f"result内score: {result_scores[:3]}")
        
        print(f"\n📈 统计:")
        print(f"   有 score 的预测: {score_stats['has_score']}")
        print(f"   无 score 的预测: {score_stats['no_score']}")
        if score_stats['score_values']:
            print(f"   score 值范围: {min(score_stats['score_values']):.4f} ~ {max(score_stats['score_values']):.4f}")
            
    except Exception as e:
        print(f"❌ 异常: {e}")
        import traceback
        traceback.print_exc()

def check_project_predictions_via_db(project_id):
    """通过数据库直接检查"""
    print_section(f"2. 数据库直接检查项目 {project_id} 的预测")
    
    if not os.path.exists(DB_PATH):
        print(f"❌ 数据库不存在: {DB_PATH}")
        return
    
    try:
        conn = sqlite3.connect(DB_PATH)
        cursor = conn.cursor()
        
        # 统计
        cursor.execute("""
            SELECT 
                COUNT(*) as total,
                COUNT(score) as has_score,
                SUM(CASE WHEN score IS NULL THEN 1 ELSE 0 END) as null_score,
                MIN(score) as min_score,
                MAX(score) as max_score,
                AVG(score) as avg_score
            FROM prediction 
            WHERE project_id = ?
        """, (project_id,))
        
        stats = cursor.fetchone()
        print(f"📊 数据库统计:")
        print(f"   总预测数: {stats[0]}")
        print(f"   有 score: {stats[1]}")
        print(f"   score 为 NULL: {stats[2]}")
        print(f"   score 范围: {stats[3]} ~ {stats[4]}")
        print(f"   score 平均值: {stats[5]}")
        
        # 抽样检查
        print(f"\n📋 抽样检查 (前20条):\n")
        cursor.execute("""
            SELECT id, task_id, score, model_version,
                   substr(result, 1, 100) as result_preview
            FROM prediction 
            WHERE project_id = ?
            ORDER BY id
            LIMIT 20
        """, (project_id,))
        
        rows = cursor.fetchall()
        for row in rows:
            pred_id, task_id, score, model_ver, result_preview = row
            print(f"  预测 #{pred_id:4d} | task: {task_id:4d} | "
                  f"DB.score: {str(score):6s} | model: {model_ver}")
        
        conn.close()
        
    except Exception as e:
        print(f"❌ 数据库异常: {e}")
        import traceback
        traceback.print_exc()

def check_tasks_with_predictions(project_id):
    """检查任务视图中的预测数据"""
    print_section(f"3. 检查任务列表中返回的预测数据")
    
    try:
        # 获取任务列表（这是前端 Data Manager 使用的接口）
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/projects/{project_id}/tasks",
            headers=get_headers(),
            params={"page_size": 10},
            timeout=30
        )
        
        if resp.status_code != 200:
            print(f"❌ 获取任务失败: {resp.status_code}")
            return
        
        data = resp.json()
        tasks = data if isinstance(data, list) else data.get('tasks', [])
        
        print(f"📋 检查前 {len(tasks)} 个任务的预测关联:\n")
        
        for task in tasks[:10]:
            task_id = task.get('id')
            predictions = task.get('predictions', [])
            
            print(f"  Task #{task_id}:")
            print(f"    predictions 字段类型: {type(predictions)}")
            print(f"    predictions 数量: {len(predictions) if predictions else 0}")
            
            if predictions:
                for p in predictions[:2]:
                    if isinstance(p, dict):
                        print(f"      - id: {p.get('id')}, score: {p.get('score')}, "
                              f"model: {p.get('model_version')}")
                    else:
                        print(f"      - (仅ID): {p}")
            print()
            
    except Exception as e:
        print(f"❌ 异常: {e}")
        import traceback
        traceback.print_exc()

def check_dm_api(project_id):
    """检查 Data Manager 专用 API"""
    print_section(f"4. 检查 Data Manager API (前端实际调用的接口)")
    
    try:
        # Data Manager 使用的是这个接口
        # /api/dm/views/{view_id}/tasks 或 /api/projects/{id}/tasks
        
        # 先获取项目的视图
        resp = requests.get(
            f"{LABEL_STUDIO_URL}/api/dm/views",
            headers=get_headers(),
            params={"project": project_id},
            timeout=30
        )
        
        print(f"📋 Data Manager Views:")
        if resp.status_code == 200:
            views = resp.json()
            print(f"   找到 {len(views)} 个视图")
            
            for view in views[:3]:
                view_id = view.get('id')
                print(f"\n   View #{view_id}:")
                print(f"     data: {json.dumps(view.get('data', {}), ensure_ascii=False)[:200]}")
                
                # 通过视图获取任务
                resp2 = requests.get(
                    f"{LABEL_STUDIO_URL}/api/dm/views/{view_id}/tasks",
                    headers=get_headers(),
                    params={"page_size": 5},
                    timeout=30
                )
                
                if resp2.status_code == 200:
                    dm_data = resp2.json()
                    dm_tasks = dm_data.get('tasks', [])
                    print(f"     任务数: {len(dm_tasks)}")
                    
                    # 检查返回的字段
                    if dm_tasks:
                        task = dm_tasks[0]
                        print(f"     任务字段: {list(task.keys())[:15]}...")
                        
                        # 关键：检查预测相关字段
                        pred_fields = [k for k in task.keys() if 'pred' in k.lower() or 'score' in k.lower()]
                        print(f"     预测相关字段: {pred_fields}")
                        
                        for field in pred_fields:
                            print(f"       {field}: {task.get(field)}")
        else:
            print(f"   获取视图失败: {resp.status_code}")
            
    except Exception as e:
        print(f"❌ 异常: {e}")
        import traceback
        traceback.print_exc()

def main():
    print("\n" + "=" * 70)
    print(f"   针对项目 [{TARGET_PROJECT_NAME}] 的专项诊断")
    print("=" * 70)
    
    # 查找项目
    project = find_project_by_name(TARGET_PROJECT_NAME)
    
    if not project:
        print(f"\n❌ 未找到项目: {TARGET_PROJECT_NAME}")
        print("\n📋 列出所有项目:")
        try:
            resp = requests.get(
                f"{LABEL_STUDIO_URL}/api/projects",
                headers=get_headers(),
                params={"page_size": 50},
                timeout=30
            )
            if resp.status_code == 200:
                for p in resp.json().get('results', []):
                    print(f"   - ID: {p.get('id'):3d} | {p.get('title')}")
        except:
            pass
        return
    
    project_id = project.get('id')
    print(f"\n✅ 找到项目: {project.get('title')} (ID: {project_id})")
    print(f"   任务数: {project.get('task_number', 'N/A')}")
    print(f"   创建时间: {project.get('created_at', 'N/A')}")
    
    # 运行检查
    check_project_predictions_via_api(project_id)
    check_project_predictions_via_db(project_id)
    check_tasks_with_predictions(project_id)
    check_dm_api(project_id)
    
    print_section("诊断总结")
    print("""
请关注以下关键点：

1. API 返回的 score 是否有值？
2. 数据库中的 score 是否有值？
3. Data Manager API 返回的任务中，预测相关字段是什么？

如果 1 和 2 都有值，但前端不显示，可能是：
- Label Studio 前端 bug
- 需要特定的列配置
- 缓存问题

请把结果发给我继续分析。
""")

if __name__ == "__main__":
    main()