"""
用户项目管理器
管理每个用户独立的项目数据
"""
import json
import os
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Any, Optional
import logging

logger = logging.getLogger(__name__)


class UserProjectManager:
    """用户项目管理器 - 管理单个用户的项目"""
    
    def __init__(self, username: str, base_data_dir: str = "/root/autodl-fs/web_biaozhupingtai/data"):
        self.username = username.lower()
        self.base_dir = Path(base_data_dir)
        self.user_dir = self.base_dir / "users" / self.username
        self.projects_file = self.user_dir / "projects.json"
        self._ensure_directories()
    
    def _ensure_directories(self):
        """确保用户目录存在"""
        dirs_to_create = [
            self.user_dir,
            self.user_dir / "uploads",
            self.user_dir / "processed",
            self.user_dir / "annotations",
        ]
        for d in dirs_to_create:
            d.mkdir(parents=True, exist_ok=True)
        
        if not self.projects_file.exists():
            self._save_projects({})
    
    def _load_projects(self) -> Dict[str, Any]:
        """加载项目配置"""
        try:
            with open(self.projects_file, 'r', encoding='utf-8') as f:
                return json.load(f)
        except:
            return {}
    
    def _save_projects(self, projects: Dict[str, Any]):
        """保存项目配置"""
        with open(self.projects_file, 'w', encoding='utf-8') as f:
            json.dump(projects, f, ensure_ascii=False, indent=2)
    
    def create_project(self, name: str, labels_def: Dict[str, str]) -> tuple:
        """
        创建新项目
        
        Args:
            name: 项目名称
            labels_def: 类别定义 {类别名: 描述}
        
        Returns:
            (success: bool, message: str)
        """
        projects = self._load_projects()
        
        if name in projects:
            return False, "项目已存在"
        
        # 创建项目目录
        project_dirs = {
            'uploads': self.user_dir / "uploads" / name / "extracted",
            'processed': self.user_dir / "processed" / name,
            'annotations': self.user_dir / "annotations" / name,
        }
        
        for dir_path in project_dirs.values():
            dir_path.mkdir(parents=True, exist_ok=True)
        
        # 保存项目配置
        projects[name] = {
            'name': name,
            'created_at': datetime.now().isoformat(),
            'updated_at': datetime.now().isoformat(),
            'labels': labels_def,
            'total_images': 0,
            'processed_images': [],
            'status': 'active',
            'label_studio_project_id': None,  # 关联的 Label Studio 项目 ID
            'label_studio_project_url': None,
        }
        
        self._save_projects(projects)
        logger.info(f"✅ 项目创建成功: {self.username}/{name}")
        return True, "项目创建成功"
    
    def get_project(self, name: str) -> Optional[Dict[str, Any]]:
        """获取项目信息"""
        projects = self._load_projects()
        return projects.get(name)
    
    def list_projects(self) -> List[Dict[str, Any]]:
        """列出所有项目"""
        projects = self._load_projects()
        return list(projects.values())
    
    def update_project(self, name: str, updates: Dict[str, Any]) -> bool:
        """更新项目信息"""
        projects = self._load_projects()
        
        if name not in projects:
            return False
        
        # 不允许更新的字段
        protected = {'name', 'created_at'}
        
        for key, value in updates.items():
            if key not in protected:
                projects[name][key] = value
        
        projects[name]['updated_at'] = datetime.now().isoformat()
        self._save_projects(projects)
        return True
    
    def update_project_labels(self, name: str, labels_def: Dict[str, str]) -> bool:
        """更新项目的类别定义"""
        projects = self._load_projects()
        
        if name not in projects:
            return False
        
        projects[name]['labels'] = labels_def
        projects[name]['updated_at'] = datetime.now().isoformat()
        self._save_projects(projects)
        
        # 同时写入 definitions.json
        try:
            processed_dir = self.get_processed_dir(name)
            definitions_file = processed_dir / "definitions.json"
            with open(definitions_file, 'w', encoding='utf-8') as f:
                json.dump(labels_def, f, ensure_ascii=False, indent=2)
        except Exception as e:
            logger.warning(f"写入 definitions.json 失败: {e}")
        
        return True
    
    def delete_project(self, name: str) -> bool:
        """删除项目（仅删除配置，不删除数据文件）"""
        projects = self._load_projects()
        
        if name not in projects:
            return False
        
        del projects[name]
        self._save_projects(projects)
        logger.info(f"✅ 项目删除成功: {self.username}/{name}")
        return True
    
    def mark_image_processed(self, project_name: str, image_path: str):
        """标记图片已处理"""
        projects = self._load_projects()
        
        if project_name not in projects:
            return
        
        if image_path not in projects[project_name].get('processed_images', []):
            if 'processed_images' not in projects[project_name]:
                projects[project_name]['processed_images'] = []
            projects[project_name]['processed_images'].append(image_path)
            self._save_projects(projects)
    
    def get_processed_images(self, project_name: str) -> List[str]:
        """获取已处理的图片列表"""
        project = self.get_project(project_name)
        return project.get('processed_images', []) if project else []
    
    def get_upload_dir(self, project_name: str) -> Path:
        """获取项目的上传目录"""
        path = self.user_dir / "uploads" / project_name / "extracted"
        path.mkdir(parents=True, exist_ok=True)
        return path
    
    def get_processed_dir(self, project_name: str) -> Path:
        """获取项目的处理结果目录"""
        path = self.user_dir / "processed" / project_name
        path.mkdir(parents=True, exist_ok=True)
        return path
    
    def get_annotation_dir(self, project_name: str) -> Path:
        """获取项目的标注目录"""
        path = self.user_dir / "annotations" / project_name
        path.mkdir(parents=True, exist_ok=True)
        return path
    
    def set_label_studio_project(self, project_name: str, ls_project_id: int, ls_project_url: str):
        """设置关联的 Label Studio 项目"""
        self.update_project(project_name, {
            'label_studio_project_id': ls_project_id,
            'label_studio_project_url': ls_project_url,
        })
    
    def get_label_studio_project(self, project_name: str) -> tuple:
        """获取关联的 Label Studio 项目信息"""
        project = self.get_project(project_name)
        if project:
            return (
                project.get('label_studio_project_id'),
                project.get('label_studio_project_url')
            )
        return None, None
