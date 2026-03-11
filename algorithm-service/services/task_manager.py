import asyncio
from typing import Dict, Any, Optional
from datetime import datetime
from enum import Enum
from loguru import logger


class TaskStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class TaskInfo:
    """任务信息类"""
    
    def __init__(
        self,
        task_id: str,
        task_type: str,
        project_id: int,
        total_images: int,
        parameters: Dict[str, Any]
    ):
        self.task_id = task_id
        self.task_type = task_type
        self.project_id = project_id
        self.total_images = total_images
        self.processed_images = 0
        self.status = TaskStatus.PENDING
        self.parameters = parameters
        self.results = []
        self.error_message = None
        self.created_at = datetime.now()
        self.started_at = None
        self.completed_at = None
        self.cancelled = False
    
    def to_dict(self) -> Dict[str, Any]:
        """转换为字典"""
        return {
            "task_id": self.task_id,
            "task_type": self.task_type,
            "project_id": self.project_id,
            "status": self.status.value,
            "total_images": self.total_images,
            "processed_images": self.processed_images,
            "progress": int((self.processed_images / self.total_images * 100)) if self.total_images > 0 else 0,
            "parameters": self.parameters,
            "error_message": self.error_message,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None
        }
    
    def update_progress(self, processed: int):
        """更新进度"""
        self.processed_images = processed
        logger.info(f"Task {self.task_id}: Progress {processed}/{self.total_images} ({self.to_dict()['progress']}%)")
    
    def set_running(self):
        """设置为运行中"""
        self.status = TaskStatus.RUNNING
        self.started_at = datetime.now()
        logger.info(f"Task {self.task_id}: Status changed to RUNNING")
    
    def set_completed(self):
        """设置为已完成"""
        self.status = TaskStatus.COMPLETED
        self.completed_at = datetime.now()
        logger.info(f"Task {self.task_id}: Status changed to COMPLETED")
    
    def set_failed(self, error_message: str):
        """设置为失败"""
        self.status = TaskStatus.FAILED
        self.error_message = error_message
        self.completed_at = datetime.now()
        logger.error(f"Task {self.task_id}: Status changed to FAILED - {error_message}")
    
    def set_cancelled(self):
        """设置为已取消"""
        self.status = TaskStatus.CANCELLED
        self.cancelled = True
        self.completed_at = datetime.now()
        logger.info(f"Task {self.task_id}: Status changed to CANCELLED")


class TaskManager:
    """任务管理器 - 使用内存存储任务状态"""
    
    def __init__(self):
        self.tasks: Dict[str, TaskInfo] = {}
        self._lock = asyncio.Lock()
    
    async def create_task(
        self,
        task_id: str,
        task_type: str,
        project_id: int,
        total_images: int,
        parameters: Dict[str, Any]
    ) -> TaskInfo:
        """创建新任务"""
        async with self._lock:
            task = TaskInfo(
                task_id=task_id,
                task_type=task_type,
                project_id=project_id,
                total_images=total_images,
                parameters=parameters
            )
            self.tasks[task_id] = task
            logger.info(f"Task {task_id}: Created - {task_type}")
            return task
    
    async def get_task(self, task_id: str) -> Optional[TaskInfo]:
        """获取任务信息"""
        async with self._lock:
            return self.tasks.get(task_id)
    
    async def update_task_progress(self, task_id: str, processed: int):
        """更新任务进度"""
        async with self._lock:
            task = self.tasks.get(task_id)
            if task:
                task.update_progress(processed)
    
    async def set_task_running(self, task_id: str):
        """设置任务为运行中"""
        async with self._lock:
            task = self.tasks.get(task_id)
            if task:
                task.set_running()
    
    async def set_task_completed(self, task_id: str):
        """设置任务为已完成"""
        async with self._lock:
            task = self.tasks.get(task_id)
            if task:
                task.set_completed()
    
    async def set_task_failed(self, task_id: str, error_message: str):
        """设置任务为失败"""
        async with self._lock:
            task = self.tasks.get(task_id)
            if task:
                task.set_failed(error_message)
    
    async def set_task_cancelled(self, task_id: str):
        """设置任务为已取消"""
        async with self._lock:
            task = self.tasks.get(task_id)
            if task:
                task.set_cancelled()
    
    async def add_task_result(self, task_id: str, result: Dict[str, Any]):
        """添加任务结果"""
        async with self._lock:
            task = self.tasks.get(task_id)
            if task:
                task.results.append(result)
    
    async def get_task_results(self, task_id: str) -> Optional[list]:
        """获取任务结果"""
        async with self._lock:
            task = self.tasks.get(task_id)
            if task:
                return task.results
            return None
    
    async def is_task_cancelled(self, task_id: str) -> bool:
        """检查任务是否已取消"""
        async with self._lock:
            task = self.tasks.get(task_id)
            if task:
                return task.cancelled
            return False
    
    async def cleanup_old_tasks(self, max_age_hours: int = 24):
        """清理旧任务"""
        async with self._lock:
            from datetime import timedelta
            cutoff_time = datetime.now() - timedelta(hours=max_age_hours)
            
            to_remove = []
            for task_id, task in self.tasks.items():
                if task.completed_at and task.completed_at < cutoff_time:
                    to_remove.append(task_id)
            
            for task_id in to_remove:
                del self.tasks[task_id]
                logger.info(f"Task {task_id}: Cleaned up (old task)")
    
    async def get_all_tasks(self) -> Dict[str, TaskInfo]:
        """获取所有任务"""
        async with self._lock:
            return self.tasks.copy()


# 全局任务管理器实例
task_manager = TaskManager()
