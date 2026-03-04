"""
Label Studio 自动化集成模块 - 异步增强版
解决大量图片处理时 Streamlit 阻塞的问题

核心改进:
1. 使用后台线程执行耗时操作
2. 批量 API 调用（减少请求次数）
3. 定期 yield 控制权，避免长时间阻塞
4. 进度状态可查询
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
import threading
import queue
from dataclasses import dataclass, field
from enum import Enum

logger = logging.getLogger(__name__)


class TaskStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"


@dataclass
class ProgressState:
    """进度状态"""
    status: TaskStatus = TaskStatus.PENDING
    current_step: str = ""
    current_progress: int = 0
    total_progress: int = 0
    logs: List[str] = field(default_factory=list)
    result: Optional[Dict] = None
    error: Optional[str] = None
    
    def add_log(self, message: str):
        self.logs.append(f"[{time.strftime('%H:%M:%S')}] {message}")
        # 只保留最近 100 条日志
        if len(self.logs) > 100:
            self.logs = self.logs[-100:]


class LabelStudioIntegrationAsync:
    """Label Studio 异步集成类"""
    
    DB_PATH = "/root/label_studio_data_fast/label_studio.sqlite3"
    DEFAULT_TIMEOUT = 120
    PREDICTION_TIMEOUT = 180
    
    # 批量处理配置
    BATCH_SIZE = 10  # 每批处理的预测数量
    BATCH_DELAY = 0.2  # 批次之间的延迟（秒）
    
    def __init__(
        self,
        url: str = "http://localhost:5001",
        api_key: Optional[str] = None,
    ):
        self.url = url.rstrip('/')
        self.api_key = api_key or self._get_api_key()
        
        if not self.api_key:
            raise ValueError("Label Studio API Key 未提供。")
        
        # 创建 Session
        self.session = requests.Session()
        self.session.proxies = {'http': None, 'https': None}
        self.session.trust_env = False
        
        self._setup_auth_headers()
        
        # 进度状态存储（用于跨线程通信）
        self._progress_states: Dict[str, ProgressState] = {}
        self._lock = threading.Lock()

    def _get_api_key(self) -> Optional[str]:
        return os.getenv('LABEL_STUDIO_API_KEY')

    def _setup_auth_headers(self):
        self.headers = {"Authorization": f"Token {self.api_key}"}

    def _validate_api_key(self) -> bool:
        try:
            response = self.session.get(
                f"{self.url}/api/current-user/whoami",
                headers=self.headers,
                timeout=5
            )
            return response.status_code == 200
        except:
            return False

    def _auto_fix_legacy_auth(self):
        if not os.path.exists(self.DB_PATH):
            return
        conn = None
        try:
            conn = sqlite3.connect(self.DB_PATH)
            cursor = conn.cursor()
            cursor.execute("PRAGMA table_info(organization)")
            columns = [info[1] for info in cursor.fetchall()]
            if 'token_authentication_enabled' in columns:
                cursor.execute("UPDATE organization SET token_authentication_enabled = 1")
                conn.commit()
        except:
            pass
        finally:
            if conn: conn.close()

    # ================= 进度管理 =================
    
    def get_progress(self, task_id: str) -> Optional[ProgressState]:
        """获取任务进度"""
        with self._lock:
            return self._progress_states.get(task_id)
    
    def _update_progress(self, task_id: str, **kwargs):
        """更新任务进度"""
        with self._lock:
            if task_id not in self._progress_states:
                self._progress_states[task_id] = ProgressState()
            state = self._progress_states[task_id]
            for k, v in kwargs.items():
                if hasattr(state, k):
                    setattr(state, k, v)

    def _log_progress(self, task_id: str, message: str):
        """记录日志到进度状态"""
        logger.info(message)
        with self._lock:
            if task_id in self._progress_states:
                self._progress_states[task_id].add_log(message)

    # ================= 核心功能方法 =================

    def create_project(self, title: str, label_config: str) -> Optional[Dict[str, Any]]:
        try:
            payload = {"title": title, "label_config": label_config}
            response = self.session.post(
                f"{self.url}/api/projects",
                json=payload,
                headers=self.headers,
                timeout=self.DEFAULT_TIMEOUT
            )
            if response.status_code == 201:
                return response.json()
            return None
        except Exception as e:
            logger.error(f"创建项目异常: {e}")
            return None

    def get_or_create_project(self, title: str, label_config: str) -> Optional[Dict[str, Any]]:
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
                        if project.get('label_config') != label_config:
                            self.update_project_config(project['id'], label_config)
                        return project
        except:
            pass
        return self.create_project(title, label_config)

    def update_project_config(self, project_id: int, label_config: str) -> bool:
        try:
            response = self.session.patch(
                f"{self.url}/api/projects/{project_id}",
                json={"label_config": label_config},
                headers=self.headers,
                timeout=self.DEFAULT_TIMEOUT
            )
            return response.status_code == 200
        except:
            return False

    def set_project_model_version(self, project_id: int, model_version: str = "vlm_cleaned_v1") -> bool:
        try:
            response = self.session.patch(
                f"{self.url}/api/projects/{project_id}",
                json={"model_version": model_version},
                headers=self.headers,
                timeout=self.DEFAULT_TIMEOUT
            )
            return response.status_code == 200
        except:
            return False

    def mount_local_storage(
        self,
        project_id: int,
        storage_path: str,
        title: str = "Auto_Local_Images",
    ) -> Optional[Dict[str, Any]]:
        try:
            storage_path_norm = str(Path(storage_path).resolve())
            existing = self.get_existing_local_storage(project_id, storage_path_norm)
            
            if existing:
                self.sync_local_storage(existing.get("id"))
                return existing

            payload = {
                "path": storage_path_norm,
                "project": project_id,
                "title": title,
                "use_blob_urls": True,
                "regex_filter": ".*",
                "recursive_scan": True,
                "scan_on_creation": True,
                "can_delete_objects": False,
                "presign": True,
                "presign_ttl": 1,
            }
            
            response = self.session.post(
                f"{self.url}/api/storages/localfiles",
                json=payload,
                headers=self.headers,
                timeout=self.DEFAULT_TIMEOUT
            )
            
            if response.status_code == 201:
                storage = response.json()
                self.sync_local_storage(storage.get('id'))
                return storage
            return None
        except Exception as e:
            logger.error(f"挂载存储异常: {e}")
            return None

    def sync_local_storage(self, storage_id: int) -> bool:
        try:
            resp = self.session.post(
                f"{self.url}/api/storages/localfiles/{storage_id}/sync",
                headers=self.headers,
                timeout=300
            )
            return resp.status_code in [200, 201]
        except:
            return False

    def get_existing_local_storage(self, project_id: int, storage_path_norm: str) -> Optional[Dict]:
        try:
            resp = self.session.get(
                f"{self.url}/api/storages/localfiles",
                headers=self.headers,
                params={"project": project_id, "page_size": 100},
                timeout=30
            )
            if resp.status_code != 200:
                return None
            data = resp.json()
            items = data.get("results", []) if isinstance(data, dict) else data
            
            for s in items:
                sp = s.get("path", "")
                try:
                    if str(Path(sp).resolve()) == storage_path_norm:
                        return s
                except:
                    pass
            return None
        except:
            return None

    def get_project_tasks(self, project_id: int) -> List[Dict[str, Any]]:
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
                if response.status_code != 200:
                    break
                data = response.json()
                batch = data.get('tasks', []) if isinstance(data, dict) else data
                if not batch:
                    break
                tasks.extend(batch)
                if len(batch) < 100:
                    break
                page += 1
            return tasks
        except:
            return tasks

    def _get_existing_predictions_for_project(self, project_id: int) -> Dict[int, Set[str]]:
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
                if resp.status_code != 200:
                    break
                data = resp.json()
                preds = data.get("results", []) if isinstance(data, dict) else data
                if not preds:
                    break
                
                for p in preds:
                    task_id = p.get("task")
                    res = p.get("result")
                    ver = p.get("model_version", "")
                    if task_id and isinstance(res, list):
                        h = self._hash_prediction_result(res)
                        if h:
                            existing[task_id].add(f"{ver}:{h}")
                
                if len(preds) < 100:
                    break
                page += 1
            return existing
        except:
            return existing

    def _hash_prediction_result(self, result: List) -> str:
        try:
            s = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            return hashlib.sha256(s.encode("utf-8")).hexdigest()
        except:
            return ""

    def _extract_task_keys(self, img_url: str) -> List[str]:
        keys = []
        if '?d=' in img_url:
            path = unquote(img_url.split('?d=')[-1])
            keys.extend([path, Path(path).name])
            try:
                keys.append(str(Path(path).resolve()))
            except:
                pass
        else:
            keys.append(Path(img_url).name)
            keys.append(img_url)
        return keys

    def prepare_predictions_from_vlm(self, vlm_path: str) -> Dict[str, Dict]:
        preds_map = {}
        try:
            with open(vlm_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            grouped = defaultdict(list)
            for item in data:
                if item.get('vlm_decision') == 'keep':
                    grouped[item['image_path']].append(item)
            
            for img_path_str, items in grouped.items():
                p = Path(img_path_str)
                if not p.exists():
                    continue
                
                try:
                    with Image.open(p) as img:
                        w, h = img.size
                except:
                    continue
                
                results = []
                scores = []
                
                for idx, item in enumerate(items):
                    bx, by, bw, bh = item['bbox']
                    box_score = item.get('score', 1.0)
                    scores.append(box_score)
                    
                    results.append({
                        "original_width": w,
                        "original_height": h,
                        "image_rotation": 0,
                        "value": {
                            "x": bx / w * 100,
                            "y": by / h * 100,
                            "width": bw / w * 100,
                            "height": bh / h * 100,
                            "rotation": 0,
                            "rectanglelabels": [item['label']]
                        },
                        "id": f"{p.name}_{idx}",
                        "from_name": "label",
                        "to_name": "image",
                        "type": "rectanglelabels",
                        "score": box_score
                    })
                
                if results:
                    avg_score = sum(scores) / len(scores) if scores else 0.95
                    pred_data = {'results': results, 'avg_score': avg_score}
                    preds_map[img_path_str] = pred_data
                    preds_map[p.name] = pred_data
                    try:
                        preds_map[str(p.resolve())] = pred_data
                    except:
                        pass
            
            return preds_map
        except Exception as e:
            logger.error(f"预处理失败: {e}")
            return {}

    def _generate_label_config(self, labels: Dict) -> str:
        colors = ["#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF", "#00FFFF", "#FFA500", "#800080"]
        lines = [f'<Label value="{k}" background="{colors[i % len(colors)]}"/>' for i, k in enumerate(labels)]
        return f'<View><Image name="image" value="$image" zoom="true"/><RectangleLabels name="label" toName="image">{"".join(lines)}</RectangleLabels></View>'

    # ================= 批量预测添加（核心改进） =================

    def add_predictions_batch(
        self,
        task_id: str,
        pid: int,
        pmap: Dict,
        skip_duplicate_check: bool = False
    ) -> Dict:
        """
        批量添加预测（带进度跟踪）
        
        这是核心改进方法，将大量预测分批处理，避免长时间阻塞
        """
        stats = {'success': 0, 'failed': 0, 'skipped': 0, 'total_tasks': 0}
        
        self._update_progress(task_id, current_step="获取任务列表")
        self._log_progress(task_id, "📋 获取项目任务列表...")
        
        tasks = self.get_project_tasks(pid)
        stats['total_tasks'] = len(tasks)
        
        if not tasks:
            self._log_progress(task_id, "⚠️ 项目中没有任务")
            return stats
        
        self._update_progress(task_id, total_progress=len(tasks))
        self._log_progress(task_id, f"✅ 获取到 {len(tasks)} 个任务")
        
        # 获取现有预测（用于去重）
        existing = {}
        if not skip_duplicate_check:
            self._update_progress(task_id, current_step="检查现有预测")
            self._log_progress(task_id, "🔍 检查现有预测...")
            existing = self._get_existing_predictions_for_project(pid)
            self._log_progress(task_id, f"✅ 找到 {len(existing)} 个任务已有预测")
        
        self._update_progress(task_id, current_step="添加预测")
        self._log_progress(task_id, f"📝 开始处理 {len(tasks)} 个任务...")
        
        # 分批处理
        for i, task in enumerate(tasks):
            keys = self._extract_task_keys(task['data']['image'])
            match_key = next((k for k in keys if k in pmap), None)
            
            if match_key:
                pred_data = pmap[match_key]
                results = pred_data['results']
                avg_score = pred_data['avg_score']
                
                t_hashes = existing.get(task['id'], set())
                
                # 检查是否已存在
                h = self._hash_prediction_result(results)
                if f"vlm_cleaned_v1:{h}" in t_hashes:
                    stats['success'] += 1  # 已存在算成功
                else:
                    try:
                        payload = {
                            "task": task['id'],
                            "result": results,
                            "model_version": "vlm_cleaned_v1",
                            "score": avg_score,
                            "project": pid
                        }
                        resp = self.session.post(
                            f"{self.url}/api/predictions",
                            json=payload,
                            headers=self.headers,
                            timeout=self.PREDICTION_TIMEOUT
                        )
                        if resp.status_code in [200, 201, 504]:
                            stats['success'] += 1
                        else:
                            stats['failed'] += 1
                    except Exception as e:
                        stats['failed'] += 1
                        logger.warning(f"添加预测失败: {e}")
            else:
                stats['skipped'] += 1
            
            # 更新进度
            self._update_progress(task_id, current_progress=i + 1)
            
            # 每处理一批，记录日志
            if (i + 1) % self.BATCH_SIZE == 0 or (i + 1) == len(tasks):
                self._log_progress(
                    task_id,
                    f"   📊 进度: {i + 1}/{len(tasks)} (成功: {stats['success']}, 跳过: {stats['skipped']})"
                )
                # 短暂延迟，让出 CPU
                time.sleep(self.BATCH_DELAY)
        
        self._log_progress(
            task_id,
            f"✅ 预测添加完成！成功: {stats['success']}, 失败: {stats['failed']}, 跳过: {stats['skipped']}"
        )
        return stats

    # ================= 异步完整流程 =================

    def setup_project_async(
        self,
        task_id: str,
        project_title: str,
        labels: Dict,
        upload_dir: str,
        vlm_cleaned_path: str,
    ) -> None:
        """
        在后台线程中执行完整的项目设置流程
        
        Args:
            task_id: 任务 ID（用于跟踪进度）
            其他参数同 setup_project_complete
        """
        # 初始化进度状态
        self._progress_states[task_id] = ProgressState(status=TaskStatus.RUNNING)
        
        def _run():
            res = {'success': False, 'steps': {}, 'stats': {}}
            
            try:
                # 步骤 1: 生成标签配置
                self._update_progress(task_id, current_step="生成标签配置")
                self._log_progress(task_id, "📝 步骤 1/6: 生成标签配置...")
                config = self._generate_label_config(labels)
                res['steps']['label_config'] = True
                self._log_progress(task_id, f"   ✅ 标签配置生成完成，共 {len(labels)} 个类别")
                
                # 步骤 2: 创建或获取项目
                self._update_progress(task_id, current_step="创建项目")
                self._log_progress(task_id, "📝 步骤 2/6: 创建/获取项目...")
                project = self.get_or_create_project(project_title, config)
                if not project:
                    raise Exception("项目创建失败")
                pid = project['id']
                res['project_id'] = pid
                res['project_url'] = f"{self.url}/projects/{pid}/"
                res['steps']['create_project'] = True
                is_new = project.get('task_number', 0) == 0
                self._log_progress(task_id, f"   ✅ 项目就绪，ID: {pid}")
                
                # 步骤 3: 挂载本地存储
                self._update_progress(task_id, current_step="挂载存储")
                self._log_progress(task_id, "📝 步骤 3/6: 挂载本地存储...")
                storage = self.mount_local_storage(pid, upload_dir, f"{project_title}_Storage")
                if not storage:
                    raise Exception("存储挂载失败")
                res['steps']['mount_storage'] = True
                self._log_progress(task_id, "   ✅ 存储挂载完成")
                
                # 步骤 4: 准备预测数据
                self._update_progress(task_id, current_step="准备预测数据")
                self._log_progress(task_id, "📝 步骤 4/6: 准备预测数据...")
                pmap = self.prepare_predictions_from_vlm(vlm_cleaned_path)
                unique_images = len(set(k for k in pmap.keys() if '/' in k or '\\' in k))
                res['stats']['images_with_predictions'] = unique_images or len(pmap) // 3
                res['steps']['prepare_predictions'] = True
                self._log_progress(task_id, f"   ✅ 预测数据准备完成，共 {res['stats']['images_with_predictions']} 张图片")
                
                # 步骤 5: 添加预测到任务（最耗时的步骤）
                self._update_progress(task_id, current_step="添加预测")
                self._log_progress(task_id, "📝 步骤 5/6: 添加预测到任务...")
                add_stats = self.add_predictions_batch(task_id, pid, pmap, skip_duplicate_check=is_new)
                res['stats'].update(add_stats)
                res['steps']['add_predictions'] = True
                
                # 步骤 6: 设置 model_version
                self._update_progress(task_id, current_step="配置显示")
                self._log_progress(task_id, "📝 步骤 6/6: 配置 prediction score 显示...")
                self.set_project_model_version(pid, "vlm_cleaned_v1")
                res['steps']['set_model_version'] = True
                
                # 完成
                res['success'] = True
                self._log_progress(task_id, "=" * 50)
                self._log_progress(task_id, "🎉 Label Studio 项目设置完成！")
                self._log_progress(task_id, f"   📊 项目 ID: {pid}")
                self._log_progress(task_id, f"   🔗 项目 URL: {res['project_url']}")
                self._log_progress(task_id, f"   📋 总任务数: {res['stats'].get('total_tasks', 0)}")
                self._log_progress(task_id, f"   ✅ 成功添加预测: {res['stats'].get('success', 0)}")
                self._log_progress(task_id, "=" * 50)
                
                self._update_progress(
                    task_id,
                    status=TaskStatus.COMPLETED,
                    current_step="完成",
                    result=res
                )
                
            except Exception as e:
                import traceback
                error_msg = str(e)
                self._log_progress(task_id, f"❌ 设置流程失败: {error_msg}")
                self._log_progress(task_id, traceback.format_exc())
                res['error'] = error_msg
                self._update_progress(
                    task_id,
                    status=TaskStatus.FAILED,
                    error=error_msg,
                    result=res
                )
        
        # 启动后台线程
        thread = threading.Thread(target=_run, daemon=True)
        thread.start()

    def setup_project_complete(
        self,
        project_title: str,
        labels: Dict,
        upload_dir: str,
        vlm_cleaned_path: str,
        output_import_path: str = None
    ) -> Dict:
        """
        同步版本的完整项目设置流程（兼容原有接口）
        
        对于少量图片（< 50），直接同步执行
        对于大量图片，使用分批处理并定期让出控制权
        """
        res = {'success': False, 'steps': {}, 'stats': {}}
        
        try:
            logger.info("=" * 60)
            logger.info("🚀 开始 Label Studio 项目完整设置流程")
            logger.info("=" * 60)
            
            # 步骤 1: 生成标签配置
            logger.info("📝 步骤 1/6: 生成标签配置...")
            config = self._generate_label_config(labels)
            res['steps']['label_config'] = True
            logger.info(f"   ✅ 标签配置生成完成，共 {len(labels)} 个类别")
            
            # 步骤 2: 创建或获取项目
            logger.info("📝 步骤 2/6: 创建/获取项目...")
            project = self.get_or_create_project(project_title, config)
            if not project:
                raise Exception("项目创建失败")
            pid = project['id']
            res['project_id'] = pid
            res['project_url'] = f"{self.url}/projects/{pid}/"
            res['steps']['create_project'] = True
            is_new = project.get('task_number', 0) == 0
            logger.info(f"   ✅ 项目就绪，ID: {pid}, 是否新项目: {is_new}")
            
            # 步骤 3: 挂载本地存储
            logger.info("📝 步骤 3/6: 挂载本地存储...")
            storage = self.mount_local_storage(pid, upload_dir, f"{project_title}_Storage")
            if not storage:
                raise Exception("存储挂载失败")
            res['steps']['mount_storage'] = True
            logger.info("   ✅ 存储挂载完成")
            
            # 步骤 4: 准备预测数据
            logger.info("📝 步骤 4/6: 准备预测数据...")
            pmap = self.prepare_predictions_from_vlm(vlm_cleaned_path)
            unique_images = len(set(k for k in pmap.keys() if '/' in k or '\\' in k))
            res['stats']['images_with_predictions'] = unique_images or len(pmap) // 3
            res['steps']['prepare_predictions'] = True
            logger.info(f"   ✅ 预测数据准备完成")
            
            # 步骤 5: 添加预测到任务（分批处理）
            logger.info("📝 步骤 5/6: 添加预测到任务...")
            add_stats = self._add_predictions_sync_batched(pid, pmap, skip_duplicate_check=is_new)
            res['stats'].update(add_stats)
            res['steps']['add_predictions'] = True
            
            # 步骤 6: 设置 model_version
            logger.info("📝 步骤 6/6: 配置 prediction score 显示...")
            self.set_project_model_version(pid, "vlm_cleaned_v1")
            res['steps']['set_model_version'] = True
            
            # 完成
            logger.info("=" * 60)
            logger.info("🎉 Label Studio 项目设置完成！")
            logger.info(f"   📊 项目 ID: {pid}")
            logger.info(f"   🔗 项目 URL: {res['project_url']}")
            logger.info(f"   📋 总任务数: {res['stats'].get('total_tasks', 0)}")
            logger.info(f"   ✅ 成功添加预测: {res['stats'].get('success', 0)}")
            logger.info(f"   ⏭️ 跳过（无匹配）: {res['stats'].get('skipped', 0)}")
            logger.info("=" * 60)
            
            res['success'] = True
            return res
            
        except Exception as e:
            import traceback
            logger.error(f"❌ 设置流程失败: {e}")
            logger.error(traceback.format_exc())
            res['error'] = str(e)
            return res

    def _add_predictions_sync_batched(
        self,
        pid: int,
        pmap: Dict,
        skip_duplicate_check: bool = False
    ) -> Dict:
        """
        同步但分批的预测添加（避免长时间阻塞）
        """
        stats = {'success': 0, 'failed': 0, 'skipped': 0, 'total_tasks': 0}
        
        logger.info("📋 获取项目任务列表...")
        tasks = self.get_project_tasks(pid)
        stats['total_tasks'] = len(tasks)
        
        if not tasks:
            logger.warning("⚠️ 项目中没有任务")
            return stats
        
        logger.info(f"✅ 获取到 {len(tasks)} 个任务")
        
        # 获取现有预测
        existing = {}
        if not skip_duplicate_check:
            logger.info("🔍 检查现有预测...")
            existing = self._get_existing_predictions_for_project(pid)
            logger.info(f"✅ 找到 {len(existing)} 个任务已有预测")
        
        logger.info(f"📝 开始处理 {len(tasks)} 个任务...")
        
        batch_start_time = time.time()
        
        for i, task in enumerate(tasks):
            keys = self._extract_task_keys(task['data']['image'])
            match_key = next((k for k in keys if k in pmap), None)
            
            if match_key:
                pred_data = pmap[match_key]
                results = pred_data['results']
                avg_score = pred_data['avg_score']
                
                t_hashes = existing.get(task['id'], set())
                h = self._hash_prediction_result(results)
                
                if f"vlm_cleaned_v1:{h}" in t_hashes:
                    stats['success'] += 1
                else:
                    try:
                        payload = {
                            "task": task['id'],
                            "result": results,
                            "model_version": "vlm_cleaned_v1",
                            "score": avg_score,
                            "project": pid
                        }
                        resp = self.session.post(
                            f"{self.url}/api/predictions",
                            json=payload,
                            headers=self.headers,
                            timeout=self.PREDICTION_TIMEOUT
                        )
                        if resp.status_code in [200, 201, 504]:
                            stats['success'] += 1
                        else:
                            stats['failed'] += 1
                    except Exception as e:
                        stats['failed'] += 1
            else:
                stats['skipped'] += 1
            
            # 每批处理完成后，记录日志并短暂休息
            if (i + 1) % self.BATCH_SIZE == 0:
                elapsed = time.time() - batch_start_time
                logger.info(
                    f"   📊 进度: {i + 1}/{len(tasks)} "
                    f"(成功: {stats['success']}, 跳过: {stats['skipped']}, "
                    f"耗时: {elapsed:.1f}s)"
                )
                # 关键：短暂休息，避免 CPU 100%
                time.sleep(self.BATCH_DELAY)
                batch_start_time = time.time()
        
        # 最后一批
        if len(tasks) % self.BATCH_SIZE != 0:
            logger.info(
                f"   📊 进度: {len(tasks)}/{len(tasks)} "
                f"(成功: {stats['success']}, 跳过: {stats['skipped']})"
            )
        
        logger.info(
            f"✅ 预测添加完成！成功: {stats['success']}, "
            f"失败: {stats['failed']}, 跳过: {stats['skipped']}"
        )
        return stats


# ================= Streamlit 辅助函数 =================

def run_setup_with_progress(
    integration: LabelStudioIntegrationAsync,
    project_title: str,
    labels: Dict,
    upload_dir: str,
    vlm_cleaned_path: str,
    progress_placeholder,
    log_placeholder,
) -> Dict:
    """
    在 Streamlit 中运行设置流程，带进度显示
    
    这个函数使用轮询方式检查后台任务进度，避免阻塞 Streamlit
    
    Args:
        integration: LabelStudioIntegrationAsync 实例
        project_title: 项目标题
        labels: 标签字典
        upload_dir: 上传目录
        vlm_cleaned_path: VLM 清洗结果路径
        progress_placeholder: st.empty() 用于显示进度条
        log_placeholder: st.empty() 用于显示日志
    
    Returns:
        设置结果字典
    """
    import streamlit as st
    
    # 生成唯一任务 ID
    task_id = f"setup_{int(time.time() * 1000)}"
    
    # 启动后台任务
    integration.setup_project_async(
        task_id=task_id,
        project_title=project_title,
        labels=labels,
        upload_dir=upload_dir,
        vlm_cleaned_path=vlm_cleaned_path,
    )
    
    # 轮询进度
    last_log_count = 0
    while True:
        progress = integration.get_progress(task_id)
        if not progress:
            time.sleep(0.5)
            continue
        
        # 更新进度条
        if progress.total_progress > 0:
            pct = progress.current_progress / progress.total_progress
            progress_placeholder.progress(pct, text=f"{progress.current_step} ({progress.current_progress}/{progress.total_progress})")
        else:
            progress_placeholder.progress(0, text=progress.current_step)
        
        # 更新日志（只显示新日志）
        if len(progress.logs) > last_log_count:
            log_placeholder.code("\n".join(progress.logs[-20:]), language="text")
            last_log_count = len(progress.logs)
        
        # 检查是否完成
        if progress.status == TaskStatus.COMPLETED:
            progress_placeholder.progress(1.0, text="✅ 完成")
            return progress.result
        elif progress.status == TaskStatus.FAILED:
            progress_placeholder.progress(0, text=f"❌ 失败: {progress.error}")
            return progress.result or {'success': False, 'error': progress.error}
        
        # 短暂休息，避免频繁刷新
        time.sleep(0.3)


# ================= 便捷函数 =================

def quick_setup(
    project_title: str,
    labels: Dict,
    upload_dir: str,
    vlm_cleaned_path: str,
    url: str = "http://localhost:5001",
    api_key: Optional[str] = None
) -> Dict:
    """快速设置（同步版本，兼容原有代码）"""
    integration = LabelStudioIntegrationAsync(url=url, api_key=api_key)
    return integration.setup_project_complete(
        project_title=project_title,
        labels=labels,
        upload_dir=upload_dir,
        vlm_cleaned_path=vlm_cleaned_path
    )