"""
Label Studio 自动化集成模块 - 增强版
用于自动创建项目、配置存储、导入预标注数据

增强功能:
1. 详细的日志输出，跟踪每个步骤的进度
2. 更好的错误处理和状态反馈
3. 支持回调函数实时更新 UI
"""
import json
import requests
import sqlite3
from pathlib import Path
from typing import Dict, List, Any, Optional, Set, Callable
from PIL import Image
import logging
import time
import hashlib
import os
from collections import defaultdict
from urllib.parse import unquote

logger = logging.getLogger(__name__)


class LabelStudioIntegration:
    """Label Studio 自动化集成类 - 增强版"""
    
    # 数据库默认路径
    DB_PATH = "/root/autodl-fs/label_studio_data/label_studio.sqlite3"
    
    # 超时设置
    DEFAULT_TIMEOUT = 120
    PREDICTION_TIMEOUT = 180
    
    def __init__(
        self,
        url: str = "http://localhost:5001",
        api_key: Optional[str] = None,
        progress_callback: Optional[Callable[[str, str], None]] = None
    ):
        """
        初始化 Label Studio 集成
        
        Args:
            url: Label Studio URL
            api_key: API Token
            progress_callback: 进度回调函数 (message, status) -> None
        """
        self.url = url.rstrip('/')
        self.api_key = api_key or self._get_api_key()
        self.progress_callback = progress_callback
        
        if not self.api_key:
            raise ValueError("Label Studio API Key 未提供。")
        
        # 创建高性能 Session (绕过代理)
        self.session = requests.Session()
        self.session.proxies = {'http': None, 'https': None}
        self.session.trust_env = False
        
        # 设置认证头
        self._setup_auth_headers()
        
        # 验证并尝试修复
        if not self._validate_api_key():
            self._log("⚠️ API Key 验证失败，尝试修复...", "warning")
            self._auto_fix_legacy_auth()
            
            if self._validate_api_key():
                self._log("✅ 修复成功，验证通过！", "success")
            else:
                self._log("❌ 认证失败，请检查配置", "error")

    def _log(self, message: str, status: str = "info"):
        """记录日志并调用回调"""
        if status == "info":
            logger.info(message)
        elif status == "warning":
            logger.warning(message)
        elif status == "error":
            logger.error(message)
        elif status == "success":
            logger.info(message)
        
        if self.progress_callback:
            try:
                self.progress_callback(message, status)
            except:
                pass

    def _get_api_key(self) -> Optional[str]:
        return os.getenv('LABEL_STUDIO_API_KEY')

    def _setup_auth_headers(self):
        """设置认证头"""
        self.headers = {"Authorization": f"Token {self.api_key}"}
        self._log("✅ 使用 Legacy Token 认证", "success")

    def _validate_api_key(self) -> bool:
        try:
            response = self.session.get(
                f"{self.url}/api/current-user/whoami",
                headers=self.headers,
                timeout=5
            )
            return response.status_code == 200
        except Exception:
            return False

    def _auto_fix_legacy_auth(self):
        """尝试开启数据库 Token 认证"""
        if not os.path.exists(self.DB_PATH):
            return

        conn = None
        try:
            conn = sqlite3.connect(self.DB_PATH)
            cursor = conn.cursor()
            
            cursor.execute("PRAGMA table_info(organization)")
            columns = [info[1] for info in cursor.fetchall()]
            
            if 'token_authentication_enabled' in columns:
                self._log("🛠️ 修改数据库：开启 Token 认证...", "info")
                cursor.execute("UPDATE organization SET token_authentication_enabled = 1")
                conn.commit()
                self._log(f"✅ 已更新 {cursor.rowcount} 个组织配置", "success")
                
        except Exception as e:
            self._log(f"❌ 数据库操作异常: {e}", "error")
        finally:
            if conn: conn.close()

    # ================= 核心功能方法 =================

    def create_project(self, title: str, label_config: str) -> Optional[Dict[str, Any]]:
        """创建项目"""
        self._log(f"📝 正在创建项目: {title}", "info")
        try:
            payload = {"title": title, "label_config": label_config}
            response = self.session.post(
                f"{self.url}/api/projects",
                json=payload,
                headers=self.headers,
                timeout=self.DEFAULT_TIMEOUT
            )
            if response.status_code == 201:
                project = response.json()
                self._log(f"✅ 项目创建成功，ID: {project.get('id')}", "success")
                return project
            self._log(f"❌ 项目创建失败: {response.status_code} - {response.text}", "error")
            return None
        except Exception as e:
            self._log(f"❌ 创建项目异常: {e}", "error")
            return None

    def get_or_create_project(self, title: str, label_config: str) -> Optional[Dict[str, Any]]:
        """获取或创建项目"""
        self._log(f"🔍 查找项目: {title}", "info")
        try:
            response = self.session.get(
                f"{self.url}/api/projects",
                headers=self.headers,
                params={"title": title},
                timeout=self.DEFAULT_TIMEOUT
            )
            if response.status_code == 200:
                projects = response.json()
                for project in projects.get('results', []):
                    if project.get('title') == title:
                        self._log(f"✅ 找到现有项目，ID: {project.get('id')}", "success")
                        if project.get('label_config') != label_config:
                            self._log("📝 更新项目标签配置...", "info")
                            self.update_project_config(project['id'], label_config)
                        return project
        except Exception as e:
            self._log(f"⚠️ 查找项目出错: {e}", "warning")
        return self.create_project(title, label_config)

    def update_project_config(self, project_id: int, label_config: str) -> bool:
        """更新项目配置"""
        try:
            response = self.session.patch(
                f"{self.url}/api/projects/{project_id}",
                json={"label_config": label_config},
                headers=self.headers,
                timeout=self.DEFAULT_TIMEOUT
            )
            if response.status_code == 200:
                self._log("✅ 项目配置更新成功", "success")
                return True
            return False
        except Exception as e:
            self._log(f"❌ 更新配置异常: {e}", "error")
            return False

    def mount_local_storage(
        self,
        project_id: int,
        storage_path: str,
        title: str = "Auto_Local_Images",
        regex_filter: str = ".*",
        scan_subfolders: bool = True
    ) -> Optional[Dict[str, Any]]:
        """挂载本地存储"""
        self._log(f"📂 准备挂载本地存储: {storage_path}", "info")
        try:
            storage_path_norm = str(Path(storage_path).resolve())
            existing = self.get_existing_local_storage(project_id, storage_path_norm)
            
            if existing:
                storage_id = existing.get("id")
                self._log(f"✅ 复用现有存储 ID: {storage_id}", "success")
                self.sync_local_storage(storage_id)
                return existing

            self._log("📝 创建新的本地存储...", "info")
            payload = {
                "path": storage_path_norm,
                "project": project_id,
                "title": title,
                "use_blob_urls": True,
                "regex_filter": regex_filter,
                "recursive_scan": scan_subfolders,
                "scan_on_creation": True,
                "can_delete_objects": False,
                "presign": True,
                "presign_ttl": 1,
                "description": "自动挂载的本地存储"
            }
            
            response = self.session.post(
                f"{self.url}/api/storages/localfiles",
                json=payload,
                headers=self.headers,
                timeout=self.DEFAULT_TIMEOUT
            )
            
            if response.status_code == 201:
                storage = response.json()
                self._log(f"✅ 存储创建成功，ID: {storage.get('id')}", "success")
                self.sync_local_storage(storage.get('id'))
                return storage
            else:
                self._log(f"❌ 挂载失败: {response.text}", "error")
                return None
        except Exception as e:
            self._log(f"❌ 挂载异常: {e}", "error")
            return None

    def sync_local_storage(self, storage_id: int) -> bool:
        """同步本地存储"""
        self._log(f"🔄 正在同步存储 (ID: {storage_id})...", "info")
        try:
            resp = self.session.post(
                f"{self.url}/api/storages/localfiles/{storage_id}/sync",
                headers=self.headers,
                timeout=300
            )
            if resp.status_code in [200, 201]:
                result = resp.json()
                self._log(f"✅ 存储同步完成！", "success")
                # 尝试获取同步结果详情
                if isinstance(result, dict):
                    tasks_created = result.get('task_count', result.get('tasks_created', 'N/A'))
                    self._log(f"   📊 同步任务数: {tasks_created}", "info")
                return True
            else:
                self._log(f"⚠️ 同步返回状态码: {resp.status_code}", "warning")
                return False
        except Exception as e:
            self._log(f"⚠️ 同步异常: {e}", "warning")
            return False

    def get_existing_local_storage(self, project_id: int, storage_path_norm: str) -> Optional[Dict]:
        """获取现有的本地存储"""
        try:
            page = 1
            while True:
                resp = self.session.get(
                    f"{self.url}/api/storages/localfiles",
                    headers=self.headers,
                    params={"project": project_id, "page": page, "page_size": 50},
                    timeout=30
                )
                if resp.status_code != 200: return None
                data = resp.json()
                items = data.get("results", []) if isinstance(data, dict) else data
                if not items: break
                
                for s in items:
                    if not isinstance(s, dict): continue
                    sp = s.get("path") or ""
                    try:
                        if str(Path(sp).resolve()) == storage_path_norm:
                            return s
                    except: pass
                
                if isinstance(data, dict) and not data.get("next"): break
                page += 1
            return None
        except Exception:
            return None

    def get_project_tasks(self, project_id: int) -> List[Dict[str, Any]]:
        """获取项目任务列表"""
        self._log(f"📋 获取项目任务列表 (项目ID: {project_id})...", "info")
        tasks = []
        page = 1
        try:
            while True:
                response = self.session.get(
                    f"{self.url}/api/projects/{project_id}/tasks",
                    headers=self.headers,
                    params={"page": page, "page_size": 100},
                    timeout=self.DEFAULT_TIMEOUT
                )
                if response.status_code != 200: break
                data = response.json()
                
                batch = data.get('tasks', []) if isinstance(data, dict) else data
                if not batch: break
                
                tasks.extend(batch)
                if isinstance(data, dict) and not data.get('tasks'): break
                if isinstance(data, list) and len(batch) < 100: break
                
                page += 1
            
            self._log(f"✅ 获取到 {len(tasks)} 个任务", "success")
            return tasks
        except Exception as e:
            self._log(f"❌ 获取任务异常: {e}", "error")
            return tasks

    def _get_existing_predictions_for_project(self, project_id: int) -> Dict[int, Set[str]]:
        """获取项目现有的预测"""
        self._log("🔍 检查现有预测...", "info")
        existing = defaultdict(set)
        page = 1
        try:
            while True:
                resp = self.session.get(
                    f"{self.url}/api/predictions",
                    headers=self.headers,
                    params={"project": project_id, "page": page, "page_size": 100},
                    timeout=self.DEFAULT_TIMEOUT
                )
                if resp.status_code != 200: break
                data = resp.json()
                preds = data.get("results", []) if isinstance(data, dict) else data
                if not preds: break
                
                for p in preds:
                    if not isinstance(p, dict): continue
                    task_id = p.get("task")
                    res = p.get("result")
                    ver = p.get("model_version", "")
                    if task_id and isinstance(res, list):
                        h = self._hash_prediction_result(res)
                        if h: existing[task_id].add(f"{ver}:{h}")
                
                if isinstance(data, dict) and not data.get("next"): break
                if isinstance(data, list) and len(preds) < 100: break
                page += 1
            
            self._log(f"✅ 找到 {len(existing)} 个任务已有预测", "info")
            return existing
        except Exception:
            return existing

    def _hash_prediction_result(self, result: List) -> str:
        try:
            s = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            return hashlib.sha256(s.encode("utf-8")).hexdigest()
        except: return ""

    def add_prediction_to_task(
        self, 
        task_id: int, 
        prediction_result: List, 
        existing_hashes: Optional[Set[str]] = None,
        project_id: Optional[int] = None,
        model_version: str = "vlm_cleaned_v1",
        score: float = 0.95
    ) -> bool:
        """添加预测到任务"""
        try:
            if existing_hashes:
                h = self._hash_prediction_result(prediction_result)
                if f"{model_version}:{h}" in existing_hashes:
                    return True

            payload = {
                "task": task_id,
                "result": prediction_result,
                "model_version": model_version,
                "score": score
            }
            if project_id:
                payload["project"] = project_id
            
            resp = self.session.post(
                f"{self.url}/api/predictions",
                json=payload,
                headers=self.headers,
                timeout=self.PREDICTION_TIMEOUT
            )
            
            if resp.status_code in [200, 201]:
                return True
            if resp.status_code == 504:
                return True
                
            return False
        except Exception:
            return False

    def _extract_task_keys(self, img_url: str) -> List[str]:
        """提取任务的图片路径键"""
        keys = []
        if '?d=' in img_url:
            path = unquote(img_url.split('?d=')[-1])
            keys.extend([path, Path(path).name])
            try: keys.append(str(Path(path).resolve()))
            except: pass
        else:
            keys.append(Path(img_url).name)
            keys.append(img_url)
        return keys

    def prepare_predictions_from_vlm(self, vlm_path: str) -> Dict[str, List]:
        """从 VLM 结果准备预测数据"""
        self._log(f"📊 加载 VLM 清洗结果: {vlm_path}", "info")
        preds_map = {}
        try:
            with open(vlm_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            self._log(f"   📂 总记录数: {len(data)}", "info")
            
            grouped = defaultdict(list)
            keep_count = 0
            for item in data:
                if item.get('vlm_decision') == 'keep':
                    grouped[item['image_path']].append(item)
                    keep_count += 1
            
            self._log(f"   ✅ 保留的检测框: {keep_count}", "info")
            self._log(f"   📷 涉及图片数: {len(grouped)}", "info")
            
            for img_path_str, items in grouped.items():
                p = Path(img_path_str)
                if not p.exists(): continue
                
                try:
                    with Image.open(p) as img: w, h = img.size
                except: continue
                
                results = []
                for idx, item in enumerate(items):
                    bx, by, bw, bh = item['bbox']
                    results.append({
                        "original_width": w, "original_height": h, "image_rotation": 0,
                        "value": {
                            "x": bx/w*100, "y": by/h*100, "width": bw/w*100, "height": bh/h*100,
                            "rotation": 0, "rectanglelabels": [item['label']]
                        },
                        "id": f"{p.name}_{idx}", "from_name": "label", "to_name": "image",
                        "type": "rectanglelabels", "score": item.get('score', 1.0)
                    })
                
                if results:
                    preds_map[img_path_str] = results
                    preds_map[p.name] = results
                    try: preds_map[str(p.resolve())] = results
                    except: pass
            
            self._log(f"✅ 预测数据准备完成，共 {len(grouped)} 张图片", "success")
            return preds_map
        except Exception as e:
            self._log(f"❌ 预处理失败: {e}", "error")
            return {}

    def add_predictions_to_tasks(self, pid: int, pmap: Dict, skip_duplicate_check: bool = False) -> Dict:
        """批量添加预测到任务"""
        self._log("🚀 开始添加预测到任务...", "info")
        stats = {'success': 0, 'failed': 0, 'skipped': 0, 'total_tasks': 0}
        tasks = self.get_project_tasks(pid)
        stats['total_tasks'] = len(tasks)
        
        if not tasks:
            self._log("⚠️ 项目中没有任务", "warning")
            return stats
        
        existing = {}
        if not skip_duplicate_check:
            existing = self._get_existing_predictions_for_project(pid)
        
        self._log(f"📝 正在处理 {len(tasks)} 个任务...", "info")
        
        for i, task in enumerate(tasks):
            keys = self._extract_task_keys(task['data']['image'])
            match_key = next((k for k in keys if k in pmap), None)
            
            if match_key:
                match = pmap[match_key]
                t_hashes = existing.get(task['id'], set())
                if self.add_prediction_to_task(task['id'], match, t_hashes, pid):
                    stats['success'] += 1
                else:
                    stats['failed'] += 1
            else:
                stats['skipped'] += 1
            
            # 每处理 20 个任务输出一次进度
            if (i + 1) % 20 == 0 or (i + 1) == len(tasks):
                self._log(f"   📊 进度: {i+1}/{len(tasks)} (成功: {stats['success']}, 跳过: {stats['skipped']})", "info")
        
        self._log(f"✅ 预测添加完成！成功: {stats['success']}, 失败: {stats['failed']}, 跳过: {stats['skipped']}", "success")
        return stats

    def _generate_label_config(self, labels: Dict) -> str:
        """生成标签配置 XML"""
        colors = ["#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF", "#00FFFF", "#FFA500", "#800080"]
        lines = [f'<Label value="{k}" background="{colors[i%len(colors)]}"/>' for i, k in enumerate(labels)]
        return f'<View><Image name="image" value="$image" zoom="true"/><RectangleLabels name="label" toName="image">{"".join(lines)}</RectangleLabels></View>'

    def setup_project_complete(
        self, 
        project_title: str, 
        labels: Dict, 
        upload_dir: str, 
        vlm_cleaned_path: str, 
        output_import_path: str = None
    ) -> Dict:
        """
        完整的项目设置流程
        
        Returns:
            包含 success, project_id, project_url, steps, stats 等信息的字典
        """
        self._log("=" * 60, "info")
        self._log("🚀 开始 Label Studio 项目完整设置流程", "info")
        self._log("=" * 60, "info")
        
        res = {'success': False, 'steps': {}, 'stats': {}}
        
        try:
            # 步骤 1: 生成标签配置
            self._log("📝 步骤 1/5: 生成标签配置...", "info")
            config = self._generate_label_config(labels)
            res['steps']['label_config'] = True
            self._log(f"   ✅ 标签配置生成完成，共 {len(labels)} 个类别", "success")
            
            # 步骤 2: 创建或获取项目
            self._log("📝 步骤 2/5: 创建/获取项目...", "info")
            project = self.get_or_create_project(project_title, config)
            if not project: 
                raise Exception("项目创建失败")
            pid = project['id']
            res['project_id'] = pid
            res['project_url'] = f"{self.url}/projects/{pid}/"
            res['steps']['create_project'] = True
            
            is_new = project.get('task_number', 0) == 0
            self._log(f"   ✅ 项目就绪，ID: {pid}, 是否新项目: {is_new}", "success")
            
            # 步骤 3: 挂载本地存储
            self._log("📝 步骤 3/5: 挂载本地存储...", "info")
            storage = self.mount_local_storage(pid, upload_dir, f"{project_title}_Storage")
            if not storage:
                raise Exception("存储挂载失败")
            res['steps']['mount_storage'] = True
            self._log(f"   ✅ 存储挂载完成", "success")
            
            # 步骤 4: 准备预测数据
            self._log("📝 步骤 4/5: 准备预测数据...", "info")
            pmap = self.prepare_predictions_from_vlm(vlm_cleaned_path)
            res['stats']['images_with_predictions'] = len(pmap) // 3  # 每张图有3个key
            res['steps']['prepare_predictions'] = True
            
            # 步骤 5: 添加预测到任务
            self._log("📝 步骤 5/5: 添加预测到任务...", "info")
            add_stats = self.add_predictions_to_tasks(pid, pmap, skip_duplicate_check=is_new)
            res['stats'].update(add_stats)
            res['steps']['add_predictions'] = True
            
            # 完成
            self._log("=" * 60, "info")
            self._log("🎉 Label Studio 项目设置完成！", "success")
            self._log(f"   📊 项目 ID: {pid}", "info")
            self._log(f"   🔗 项目 URL: {res['project_url']}", "info")
            self._log(f"   📋 总任务数: {res['stats'].get('total_tasks', 0)}", "info")
            self._log(f"   ✅ 成功添加预测: {res['stats'].get('success', 0)}", "info")
            self._log(f"   ⏭️ 跳过（无匹配）: {res['stats'].get('skipped', 0)}", "info")
            self._log("=" * 60, "info")
            self._log("✅ 您现在可以访问项目进行标注了！", "success")
            
            res['success'] = True
            return res
            
        except Exception as e:
            self._log(f"❌ 设置流程失败: {e}", "error")
            import traceback
            self._log(traceback.format_exc(), "error")
            res['error'] = str(e)
            return res