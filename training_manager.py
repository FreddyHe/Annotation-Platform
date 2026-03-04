"""
训练管理器模块 - 支持组织隔离版本
负责从 Label Studio 导出标注数据并启动 YOLO 训练

功能:
1. 从 Label Studio 导出已完成标注的数据
2. 转换为 YOLO 训练格式
3. 生成训练配置文件
4. 调用训练脚本（在 xingmu_yolo 环境中）

安全特性:
- 每个组织的训练数据和模型相互隔离
- 训练记录按组织分目录存储
"""
import json
import os
import shutil
import subprocess
import yaml
import time
import logging
import re
from pathlib import Path
from typing import Dict, List, Any, Optional, Callable, Tuple
from datetime import datetime
from urllib.parse import unquote
import requests

logger = logging.getLogger(__name__)


def normalize_org_name(name: str) -> str:
    """标准化组织名称"""
    if not name:
        return "default"
    normalized = re.sub(r'[^\w\u4e00-\u9fff]', '_', name.strip())
    return normalized.lower() if normalized else "default"


class TrainingManager:
    """训练管理器 - 管理从 Label Studio 到 YOLO 训练的完整流程（支持组织隔离）"""
    
    # 训练环境配置
    TRAIN_CONDA_ENV = "xingmu_yolo"
    TRAIN_SCRIPT_PATH = "/root/autodl-fs/web_biaozhupingtai/scripts/train_yolo.py"
    
    # 训练输出基础目录
    TRAIN_OUTPUT_BASE = "/root/autodl-fs/web_biaozhupingtai/training_runs"
    
    def __init__(
        self,
        label_studio_url: str = "http://localhost:5001",
        api_key: Optional[str] = None,
        organization_name: Optional[str] = None,  # 新增：组织名称
        progress_callback: Optional[Callable[[str, str], None]] = None
    ):
        """
        初始化训练管理器
        
        Args:
            label_studio_url: Label Studio URL
            api_key: API Token
            organization_name: 组织名称（用于隔离训练数据）
            progress_callback: 进度回调函数 (message, status) -> None
        """
        self.ls_url = label_studio_url.rstrip('/')
        self.api_key = api_key
        self.organization_name = normalize_org_name(organization_name) if organization_name else None
        self.progress_callback = progress_callback
        
        # 创建 Session
        self.session = requests.Session()
        self.session.proxies = {'http': None, 'https': None}
        self.session.trust_env = False
        
        if self.api_key:
            self.headers = {"Authorization": f"Token {self.api_key}"}
        else:
            self.headers = {}
        
        # 确保输出目录存在
        self._ensure_output_dir()
    
    def _get_org_output_dir(self) -> Path:
        """获取组织的训练输出目录"""
        base = Path(self.TRAIN_OUTPUT_BASE)
        if self.organization_name:
            return base / self.organization_name
        return base
    
    def _ensure_output_dir(self):
        """确保组织的输出目录存在"""
        self._get_org_output_dir().mkdir(parents=True, exist_ok=True)
    
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
    
    def get_project_annotations(self, project_id: int) -> List[Dict[str, Any]]:
        """
        从 Label Studio 获取项目的所有已标注任务
        
        Args:
            project_id: Label Studio 项目 ID
        
        Returns:
            已标注任务列表
        """
        self._log(f"📋 获取项目 {project_id} 的标注数据...", "info")
        
        annotated_tasks = []
        page = 1
        
        try:
            while True:
                response = self.session.get(
                    f"{self.ls_url}/api/projects/{project_id}/tasks",
                    headers=self.headers,
                    params={
                        "page": page, 
                        "page_size": 100,
                        "fields": "all"
                    },
                    timeout=60
                )
                
                if response.status_code != 200:
                    self._log(f"❌ 获取任务失败: {response.status_code}", "error")
                    break
                
                data = response.json()
                
                # 处理分页数据
                if isinstance(data, dict):
                    tasks = data.get('tasks', [])
                else:
                    tasks = data
                
                if not tasks:
                    break
                
                # 筛选有标注的任务
                for task in tasks:
                    annotations = task.get('annotations', [])
                    if annotations:
                        # 只取最新的标注
                        latest_annotation = max(annotations, key=lambda x: x.get('id', 0))
                        annotated_tasks.append({
                            'task': task,
                            'annotation': latest_annotation
                        })
                
                # 检查是否还有更多页
                if isinstance(data, dict) and not data.get('tasks'):
                    break
                if isinstance(data, list) and len(tasks) < 100:
                    break
                    
                page += 1
            
            self._log(f"✅ 获取到 {len(annotated_tasks)} 个已标注任务", "success")
            return annotated_tasks
            
        except Exception as e:
            self._log(f"❌ 获取标注数据异常: {e}", "error")
            return []
    
    def export_to_yolo_format(
        self,
        project_id: int,
        output_dir: str,
        labels_map: Dict[str, int],
        image_source_dir: str
    ) -> Tuple[bool, Dict[str, Any]]:
        """
        将 Label Studio 标注导出为 YOLO 格式
        
        Args:
            project_id: Label Studio 项目 ID
            output_dir: 输出目录
            labels_map: 类别名称到 ID 的映射 {"class_name": 0, ...}
            image_source_dir: 原始图片目录
        
        Returns:
            (success, stats_dict)
        """
        self._log("🔄 开始导出 YOLO 格式数据...", "info")
        
        output_path = Path(output_dir)
        stats = {
            'total_tasks': 0,
            'exported_images': 0,
            'total_annotations': 0,
            'skipped': 0,
            'errors': []
        }
        
        try:
            # 创建 YOLO 目录结构
            for split in ['train', 'valid', 'test']:
                (output_path / split / 'images').mkdir(parents=True, exist_ok=True)
                (output_path / split / 'labels').mkdir(parents=True, exist_ok=True)
            
            # 获取标注数据
            annotated_tasks = self.get_project_annotations(project_id)
            stats['total_tasks'] = len(annotated_tasks)
            
            if not annotated_tasks:
                self._log("⚠️ 没有找到已标注的任务", "warning")
                return False, stats
            
            # 按 8:1:1 划分数据集
            import random
            random.shuffle(annotated_tasks)
            
            total = len(annotated_tasks)
            train_end = int(total * 0.8)
            valid_end = int(total * 0.9)
            
            splits = {
                'train': annotated_tasks[:train_end],
                'valid': annotated_tasks[train_end:valid_end],
                'test': annotated_tasks[valid_end:]
            }
            
            self._log(f"📊 数据集划分: train={len(splits['train'])}, valid={len(splits['valid'])}, test={len(splits['test'])}", "info")
            
            # 处理每个任务
            for split_name, tasks in splits.items():
                self._log(f"📝 处理 {split_name} 集...", "info")
                
                for task_data in tasks:
                    task = task_data['task']
                    annotation = task_data['annotation']
                    
                    # 获取图片路径
                    image_url = task.get('data', {}).get('image', '')
                    image_path = self._resolve_image_path(image_url, image_source_dir)
                    
                    if not image_path or not Path(image_path).exists():
                        stats['skipped'] += 1
                        continue
                    
                    # 复制图片
                    image_name = Path(image_path).name
                    dest_image = output_path / split_name / 'images' / image_name
                    shutil.copy2(image_path, dest_image)
                    
                    # 转换标注
                    yolo_annotations = self._convert_to_yolo(
                        annotation.get('result', []),
                        labels_map
                    )
                    
                    # 保存标注文件
                    label_name = Path(image_name).stem + '.txt'
                    label_path = output_path / split_name / 'labels' / label_name
                    
                    with open(label_path, 'w') as f:
                        f.write('\n'.join(yolo_annotations))
                    
                    stats['exported_images'] += 1
                    stats['total_annotations'] += len(yolo_annotations)
            
            # 生成 data.yaml
            self._generate_data_yaml(output_path, labels_map)
            
            self._log(f"✅ 导出完成！共 {stats['exported_images']} 张图片，{stats['total_annotations']} 个标注框", "success")
            return True, stats
            
        except Exception as e:
            self._log(f"❌ 导出失败: {e}", "error")
            stats['errors'].append(str(e))
            return False, stats
    
    def _resolve_image_path(self, image_url: str, source_dir: str) -> Optional[str]:
        """解析图片实际路径"""
        if not image_url:
            return None
        
        # 处理 Label Studio 的 URL 格式
        if '?d=' in image_url:
            # 格式: /data/local-files/?d=xxx
            path = unquote(image_url.split('?d=')[-1])
            if Path(path).exists():
                return path
        
        # 尝试从文件名匹配
        filename = Path(image_url).name
        source_path = Path(source_dir)
        
        # 直接匹配
        direct_path = source_path / filename
        if direct_path.exists():
            return str(direct_path)
        
        # 递归搜索
        for p in source_path.rglob(filename):
            return str(p)
        
        return None
    
    def _convert_to_yolo(self, results: List[Dict], labels_map: Dict[str, int]) -> List[str]:
        """
        将 Label Studio 标注结果转换为 YOLO 格式
        
        Returns:
            YOLO 格式的标注行列表
        """
        yolo_lines = []
        
        for result in results:
            if result.get('type') != 'rectanglelabels':
                continue
            
            value = result.get('value', {})
            labels = value.get('rectanglelabels', [])
            
            if not labels:
                continue
            
            label = labels[0]
            
            # 获取类别 ID
            if label not in labels_map:
                continue
            
            class_id = labels_map[label]
            
            # 获取边界框（Label Studio 使用百分比坐标）
            x = value.get('x', 0) / 100
            y = value.get('y', 0) / 100
            w = value.get('width', 0) / 100
            h = value.get('height', 0) / 100
            
            # 转换为 YOLO 格式（中心点坐标）
            x_center = x + w / 2
            y_center = y + h / 2
            
            # 确保在 [0, 1] 范围内
            x_center = max(0, min(1, x_center))
            y_center = max(0, min(1, y_center))
            w = max(0, min(1, w))
            h = max(0, min(1, h))
            
            yolo_lines.append(f"{class_id} {x_center:.6f} {y_center:.6f} {w:.6f} {h:.6f}")
        
        return yolo_lines
    
    def _generate_data_yaml(self, output_path: Path, labels_map: Dict[str, int]):
        """生成 YOLO 训练配置文件"""
        # 反转映射得到 {id: name}
        id_to_name = {v: k for k, v in labels_map.items()}
        names = [id_to_name[i] for i in sorted(id_to_name.keys())]
        
        yaml_content = {
            'path': str(output_path),
            'train': 'train/images',
            'val': 'valid/images',
            'test': 'test/images',
            'nc': len(names),
            'names': names
        }
        
        yaml_path = output_path / 'data.yaml'
        with open(yaml_path, 'w') as f:
            yaml.dump(yaml_content, f, sort_keys=False, allow_unicode=True)
        
        self._log(f"✅ 已生成配置文件: {yaml_path}", "success")
    
    def start_training(
        self,
        data_yaml_path: str,
        run_name: str,
        epochs: int = 100,
        batch_size: int = 16,
        imgsz: int = 640,
        model: str = "yolov8n.pt",
        device: str = "0"
    ) -> Tuple[bool, str, Optional[subprocess.Popen]]:
        """
        启动 YOLO 训练（在 xingmu_yolo 环境中）
        
        Args:
            data_yaml_path: data.yaml 路径
            run_name: 训练运行名称
            epochs: 训练轮数
            batch_size: 批次大小
            imgsz: 图片尺寸
            model: 预训练模型
            device: GPU 设备
        
        Returns:
            (success, log_file_path, process)
        """
        self._log(f"🚀 启动训练: {run_name}", "info")
        
        # 创建训练输出目录（在组织目录下）
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        run_dir = self._get_org_output_dir() / f"{run_name}_{timestamp}"
        run_dir.mkdir(parents=True, exist_ok=True)
        
        # 日志文件
        log_file = run_dir / "training.log"
        
        # 构建训练命令
        train_script = self.TRAIN_SCRIPT_PATH
        
        # 确保训练脚本存在
        if not Path(train_script).exists():
            self._log(f"❌ 训练脚本不存在: {train_script}", "error")
            return False, str(log_file), None
        
        # 构建命令
        cmd_str = f"""
        source /root/miniconda3/etc/profile.d/conda.sh && \
        conda activate {self.TRAIN_CONDA_ENV} && \
        python {train_script} \
            --data {data_yaml_path} \
            --name {run_name} \
            --epochs {epochs} \
            --batch {batch_size} \
            --imgsz {imgsz} \
            --model {model} \
            --device {device} \
            --project {str(run_dir)}
        """

        cmd = ["bash", "-c", cmd_str]
        
        self._log(f"📋 训练命令: {' '.join(cmd)}", "info")
        
        try:
            # 启动训练进程
            with open(log_file, 'w') as f:
                process = subprocess.Popen(
                    cmd,
                    stdout=f,
                    stderr=subprocess.STDOUT,
                    cwd=str(run_dir),
                    env={**os.environ, 'CUDA_VISIBLE_DEVICES': device}
                )
            
            self._log(f"✅ 训练已启动，PID: {process.pid}", "success")
            self._log(f"📄 日志文件: {log_file}", "info")
            
            # 保存训练信息
            info_file = run_dir / "training_info.json"
            with open(info_file, 'w') as f:
                json.dump({
                    'run_name': run_name,
                    'organization': self.organization_name,  # 记录组织信息
                    'data_yaml': data_yaml_path,
                    'epochs': epochs,
                    'batch_size': batch_size,
                    'imgsz': imgsz,
                    'model': model,
                    'device': device,
                    'started_at': datetime.now().isoformat(),
                    'pid': process.pid,
                    'status': 'running'
                }, f, indent=2)
            
            return True, str(log_file), process
            
        except Exception as e:
            self._log(f"❌ 启动训练失败: {e}", "error")
            return False, str(log_file), None
    
    def check_training_status(self, run_dir: str) -> Dict[str, Any]:
        """检查训练状态"""
        run_path = Path(run_dir)
        info_file = run_path / "training_info.json"
        
        if not info_file.exists():
            return {'status': 'not_found'}
        
        with open(info_file, 'r') as f:
            info = json.load(f)
        
        # 首先检查 info 中是否已经有最终状态
        current_status = info.get('status', 'unknown')
        
        # 如果状态已经是终态，直接返回
        if current_status in ['completed', 'failed', 'interrupted']:
            return info
        
        # 否则检查进程是否还在运行
        pid = info.get('pid')
        if pid:
            try:
                os.kill(pid, 0)  # 不发送信号，只检查进程是否存在
                info['status'] = 'running'
            except OSError:
                # 进程不存在，检查是否有 best.pt 来判断成功还是失败
                run_name = info.get('run_name', '')
                
                # 可能的 best.pt 路径
                possible_paths = [
                    run_path / run_name / 'weights' / 'best.pt',
                    run_path / 'weights' / 'best.pt',
                ]
                
                best_pt_found = False
                for p in possible_paths:
                    if p.exists():
                        best_pt_found = True
                        info['best_model'] = str(p)
                        break
                
                if best_pt_found:
                    info['status'] = 'completed'
                    
                    # 尝试读取测试结果
                    test_results_paths = [
                        run_path / run_name / 'test_results.json',
                        run_path / 'test_results.json',
                    ]
                    for trp in test_results_paths:
                        if trp.exists():
                            try:
                                with open(trp, 'r') as f:
                                    info['test_results'] = json.load(f)
                            except:
                                pass
                            break
                else:
                    info['status'] = 'failed'
        
        # 读取最新日志
        log_file = run_path / "training.log"
        if log_file.exists():
            try:
                with open(log_file, 'r') as f:
                    lines = f.readlines()
                    info['last_log_lines'] = lines[-20:] if len(lines) > 20 else lines
            except:
                pass
        
        return info

    def list_training_runs(self) -> List[Dict[str, Any]]:
        """列出当前组织的所有训练运行"""
        runs = []
        base_path = self._get_org_output_dir()
        
        if not base_path.exists():
            return runs
        
        for run_dir in sorted(base_path.iterdir(), reverse=True):
            if run_dir.is_dir():
                status = self.check_training_status(str(run_dir))
                if status.get('status') != 'not_found':
                    # 验证组织归属（双重检查）
                    if self.organization_name:
                        run_org = status.get('organization', '')
                        if run_org and run_org != self.organization_name:
                            continue  # 跳过不属于当前组织的训练
                    
                    status['run_dir'] = str(run_dir)
                    runs.append(status)
        
        return runs
    
    def full_training_pipeline(
        self,
        project_id: int,
        project_name: str,
        labels_def: Dict[str, str],
        image_source_dir: str,
        epochs: int = 100,
        batch_size: int = 16
    ) -> Dict[str, Any]:
        """
        完整训练流程
        
        Args:
            project_id: Label Studio 项目 ID
            project_name: 项目名称
            labels_def: 类别定义 {name: description}
            image_source_dir: 图片源目录
            epochs: 训练轮数
            batch_size: 批次大小
        
        Returns:
            训练结果信息
        """
        result = {
            'success': False,
            'steps': {},
            'errors': [],
            'organization': self.organization_name
        }
        
        try:
            # 步骤 1: 创建输出目录（在组织目录下）
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            run_name = f"{project_name}_{timestamp}"
            output_dir = self._get_org_output_dir() / run_name / "dataset"
            output_dir.mkdir(parents=True, exist_ok=True)
            
            self._log(f"📁 创建输出目录: {output_dir}", "info")
            result['run_dir'] = str(output_dir.parent)
            result['steps']['create_dir'] = True
            
            # 步骤 2: 构建类别映射
            labels_map = {name: idx for idx, name in enumerate(labels_def.keys())}
            self._log(f"📊 类别映射: {labels_map}", "info")
            result['labels_map'] = labels_map
            result['steps']['labels_map'] = True
            
            # 步骤 3: 导出数据
            self._log("🔄 步骤 3/4: 导出 YOLO 格式数据...", "info")
            export_success, export_stats = self.export_to_yolo_format(
                project_id=project_id,
                output_dir=str(output_dir),
                labels_map=labels_map,
                image_source_dir=image_source_dir
            )
            
            result['export_stats'] = export_stats
            result['steps']['export_data'] = export_success
            
            if not export_success:
                result['errors'].append("数据导出失败")
                return result
            
            if export_stats['exported_images'] == 0:
                result['errors'].append("没有可用的训练数据")
                return result
            
            # 步骤 4: 启动训练
            self._log("🚀 步骤 4/4: 启动训练...", "info")
            data_yaml = output_dir / "data.yaml"
            
            train_success, log_file, process = self.start_training(
                data_yaml_path=str(data_yaml),
                run_name=run_name,
                epochs=epochs,
                batch_size=batch_size
            )
            
            result['steps']['start_training'] = train_success
            result['log_file'] = log_file
            
            if process:
                result['pid'] = process.pid
            
            result['success'] = train_success
            
            if train_success:
                self._log("🎉 训练已成功启动！", "success")
            else:
                result['errors'].append("训练启动失败")
            
            return result
            
        except Exception as e:
            self._log(f"❌ 训练流程异常: {e}", "error")
            result['errors'].append(str(e))
            return result