"""
组织项目管理器
管理每个组织（而非用户）的项目数据
同一组织内的所有用户共享项目池

更新：添加获取组织上传根目录的方法，用于文件上传服务
"""
import json
import os
import re
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Any, Optional
import logging

logger = logging.getLogger(__name__)


class OrganizationProjectManager:
    """
    组织项目管理器 - 管理组织级别的项目
    同一组织内的所有用户共享项目池
    """
    
    # 组织上传文件的基础目录（与 file_upload_server.py 保持一致）
    ORG_UPLOAD_BASE_DIR = "/root/autodl-fs/uploads"
    
    def __init__(self, organization_name: str, base_data_dir: str = "/root/autodl-fs/web_biaozhupingtai/data"):
        """
        初始化组织项目管理器
        
        Args:
            organization_name: 组织名称
            base_data_dir: 数据根目录
        """
        # 将组织名称标准化（去除特殊字符，转小写）
        self.organization_name = self._normalize_name(organization_name)
        self.base_dir = Path(base_data_dir)
        self.org_dir = self.base_dir / "organizations" / self.organization_name
        self.projects_file = self.org_dir / "projects.json"
        self._ensure_directories()
    
    def _normalize_name(self, name: str) -> str:
        """标准化名称，去除特殊字符"""
        # 保留中文、字母、数字、下划线
        normalized = re.sub(r'[^\w\u4e00-\u9fff]', '_', name.strip())
        return normalized.lower() if normalized else "default_org"
    
    def _ensure_directories(self):
        """确保组织目录存在"""
        dirs_to_create = [
            self.org_dir,
            self.org_dir / "uploads",
            self.org_dir / "processed",
            self.org_dir / "annotations",
        ]
        for d in dirs_to_create:
            d.mkdir(parents=True, exist_ok=True)
        
        # 同时确保组织的上传根目录存在
        self.get_org_upload_root().mkdir(parents=True, exist_ok=True)
        
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
    
    def get_org_upload_root(self) -> Path:
        """
        获取组织的上传文件根目录
        这是组织用于上传大文件的目录，与项目无关
        
        Returns:
            Path: 组织上传根目录路径
        """
        path = Path(self.ORG_UPLOAD_BASE_DIR) / self.organization_name
        path.mkdir(parents=True, exist_ok=True)
        return path
    
    def create_project(self, name: str, labels_def: Dict[str, str], created_by: str = None) -> tuple:
        """
        创建新项目
        
        Args:
            name: 项目名称
            labels_def: 类别定义 {类别名: 描述}
            created_by: 创建者用户名
        
        Returns:
            (success: bool, message: str)
        """
        projects = self._load_projects()
        
        if name in projects:
            return False, "项目已存在"
        
        # 创建项目目录
        project_dirs = {
            'uploads': self.org_dir / "uploads" / name / "extracted",
            'processed': self.org_dir / "processed" / name,
            'annotations': self.org_dir / "annotations" / name,
        }
        
        for dir_path in project_dirs.values():
            dir_path.mkdir(parents=True, exist_ok=True)
        
        # 保存项目配置
        projects[name] = {
            'name': name,
            'organization': self.organization_name,
            'created_at': datetime.now().isoformat(),
            'updated_at': datetime.now().isoformat(),
            'created_by': created_by,
            'labels': labels_def,
            'total_images': 0,
            'processed_images': [],
            'status': 'active',
            'label_studio_project_id': None,
            'label_studio_project_url': None,
        }
        
        self._save_projects(projects)
        logger.info(f"✅ 项目创建成功: {self.organization_name}/{name} (by {created_by})")
        return True, "项目创建成功"
    
    def get_project(self, name: str) -> Optional[Dict[str, Any]]:
        """获取项目信息"""
        projects = self._load_projects()
        return projects.get(name)
    
    def list_projects(self) -> List[Dict[str, Any]]:
        """列出组织内所有项目"""
        projects = self._load_projects()
        return list(projects.values())
    
    def update_project(self, name: str, updates: Dict[str, Any], updated_by: str = None) -> bool:
        """更新项目信息"""
        projects = self._load_projects()
        
        if name not in projects:
            return False
        
        # 不允许更新的字段
        protected = {'name', 'organization', 'created_at', 'created_by'}
        
        for key, value in updates.items():
            if key not in protected:
                projects[name][key] = value
        
        projects[name]['updated_at'] = datetime.now().isoformat()
        if updated_by:
            projects[name]['last_updated_by'] = updated_by
            
        self._save_projects(projects)
        return True
    
    def update_project_labels(self, name: str, labels_def: Dict[str, str], updated_by: str = None) -> bool:
        """更新项目的类别定义"""
        projects = self._load_projects()
        
        if name not in projects:
            return False
        
        projects[name]['labels'] = labels_def
        projects[name]['updated_at'] = datetime.now().isoformat()
        if updated_by:
            projects[name]['last_updated_by'] = updated_by
            
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
    
    def delete_project(self, name: str, deleted_by: str = None) -> bool:
        """删除项目（仅删除配置，不删除数据文件）"""
        projects = self._load_projects()
        
        if name not in projects:
            return False
        
        del projects[name]
        self._save_projects(projects)
        logger.info(f"✅ 项目删除成功: {self.organization_name}/{name} (by {deleted_by})")
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
    
    def mark_images_processed_batch(self, project_name: str, image_paths: List[str]):
        """批量标记图片已处理"""
        projects = self._load_projects()
        
        if project_name not in projects:
            return
        
        if 'processed_images' not in projects[project_name]:
            projects[project_name]['processed_images'] = []
        
        existing = set(projects[project_name]['processed_images'])
        new_images = [p for p in image_paths if p not in existing]
        
        if new_images:
            projects[project_name]['processed_images'].extend(new_images)
            self._save_projects(projects)
    
    def get_processed_images(self, project_name: str) -> List[str]:
        """获取已处理的图片列表"""
        project = self.get_project(project_name)
        return project.get('processed_images', []) if project else []
    
    def get_upload_dir(self, project_name: str) -> Path:
        """获取项目的上传目录（项目级别）"""
        path = self.org_dir / "uploads" / project_name / "extracted"
        path.mkdir(parents=True, exist_ok=True)
        return path
    
    def get_processed_dir(self, project_name: str) -> Path:
        """获取项目的处理结果目录"""
        path = self.org_dir / "processed" / project_name
        path.mkdir(parents=True, exist_ok=True)
        return path
    
    def get_annotation_dir(self, project_name: str) -> Path:
        """获取项目的标注目录"""
        path = self.org_dir / "annotations" / project_name
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


# 兼容性别名 - 保持与旧代码兼容
UserProjectManager = OrganizationProjectManager