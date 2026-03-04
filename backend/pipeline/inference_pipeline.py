"""
推理流水线 - 分阶段处理版本
支持独立运行DINO检测和VLM清洗，避免显存冲突
"""
import os
import json
import subprocess
import time
from pathlib import Path
from typing import List, Dict, Any, Optional

class InferencePipeline:
    """自动标注推理流水线 - 分阶段模式"""
    
    def __init__(self):
        """初始化流水线"""
        from config import (
            DINO_SCRIPT_PATH, VLM_SCRIPT_PATH,
            GD_CONFIG_PATH, GD_CHECKPOINT_PATH, GD_BOX_THRESHOLD,
            VLM_BASE_URL, VLM_MODEL_NAME, CONDA_ENVS
        )
        
        self.dino_script = DINO_SCRIPT_PATH
        self.vlm_script = VLM_SCRIPT_PATH
        self.gd_config = GD_CONFIG_PATH
        self.gd_weights = GD_CHECKPOINT_PATH
        self.gd_threshold = GD_BOX_THRESHOLD
        self.vlm_url = "http://localhost:5008/v1"  # 使用本地VLM
        self.vlm_model = VLM_MODEL_NAME
        self.conda_envs = CONDA_ENVS
    
    def _run_conda_script(self, env_name: str, script_path: str, args: List[str]) -> tuple:
        """在指定conda环境中运行脚本"""
        # 使用 conda 的完整路径，确保能找到命令
        conda_path = "/root/miniconda3/bin/conda"
        python_path = f"/root/miniconda3/envs/{env_name}/bin/python"
        
        # 如果 conda 环境中的 Python 存在，直接使用；否则使用 conda run
        if os.path.exists(python_path):
            cmd = [python_path, script_path] + args
        else:
            cmd = [conda_path, 'run', '-n', env_name, 'python', script_path] + args
        
        try:
            # 设置环境变量，确保能找到必要的库
            env = os.environ.copy()
            env['PATH'] = f"/root/miniconda3/envs/{env_name}/bin:" + env.get('PATH', '')
            env['LD_LIBRARY_PATH'] = f"/root/miniconda3/envs/{env_name}/lib:" + env.get('LD_LIBRARY_PATH', '')
            
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=3600, env=env)
            return (result.returncode == 0, result.stdout, result.stderr)
        except subprocess.TimeoutExpired:
            return (False, "", "脚本执行超时")
        except Exception as e:
            return (False, "", str(e))
    
    def _start_vlm_service(self) -> bool:
        """启动VLM服务"""
        print("\n" + "="*80)
        print("🚀 启动 VLM 服务...")
        print("="*80)
        
        # 检查服务是否已运行
        try:
            import requests
            response = requests.get("http://localhost:5008/health", timeout=2)
            if response.status_code == 200:
                print("✅ VLM服务已在运行")
                return True
        except:
            pass
        
        # 启动服务
        vlm_log = "/root/autodl-fs/web_biaozhupingtai/logs/vlm_service.log"
        os.makedirs(os.path.dirname(vlm_log), exist_ok=True)
        
        cmd = f"""
nohup bash -c '
export PATH=/root/miniconda3/envs/LLM_DL/bin:$PATH
export LD_LIBRARY_PATH=/root/miniconda3/envs/LLM_DL/lib:$LD_LIBRARY_PATH
cd /root/autodl-fs/web_biaozhupingtai
VLLM_USE_MODELSCOPE=true vllm serve Qwen/Qwen3-VL-4B-Instruct \
    --port 5008 \
    --gpu-memory-utilization 0.75 \
    --served-model-name Qwen3-VL-4B-Instruct \
    --max-model-len 16384 \
    --trust-remote-code \
    --enforce-eager
' > {vlm_log} 2>&1 &
echo $! > /tmp/vlm_service.pid
"""
        
        os.system(cmd)
        
        print("⏳ 等待VLM服务启动（约60-90秒）...")
        
        # 等待服务启动（最多等待2分钟）
        import requests
        for i in range(120):
            time.sleep(1)
            try:
                response = requests.get("http://localhost:5008/health", timeout=1)
                if response.status_code == 200:
                    print(f"✅ VLM服务已就绪 (耗时: {i+1}秒)")
                    return True
            except:
                if i % 10 == 0 and i > 0:
                    print(f"   等待中... {i}秒")
        
        print("❌ VLM服务启动超时")
        return False
    
    def _stop_vlm_service(self) -> bool:
        """停止VLM服务"""
        print("\n" + "="*80)
        print("🛑 停止 VLM 服务，释放显存...")
        print("="*80)
        
        # 通过PID文件停止
        pid_file = "/tmp/vlm_service.pid"
        if os.path.exists(pid_file):
            try:
                with open(pid_file, 'r') as f:
                    pid = f.read().strip()
                os.system(f"kill {pid} 2>/dev/null")
                time.sleep(3)
                os.system(f"kill -9 {pid} 2>/dev/null")
                os.remove(pid_file)
                print("✅ VLM服务已停止")
            except:
                pass
        
        # 通过端口停止
        os.system("lsof -ti:5008 | xargs kill -9 2>/dev/null")
        
        # 等待显存释放
        print("⏳ 等待显存释放...")
        time.sleep(5)
        print("✅ 显存已释放")
        
        return True
    
    def run_dino_detection(
        self,
        image_dir: str,
        target_labels: List[str],
        output_file: str
    ) -> tuple:
        """运行Grounding DINO检测"""
        print("\n" + "="*80)
        print("🎯 阶段 1: Grounding DINO 检测")
        print("="*80)
        print(f"📂 图片目录: {image_dir}")
        print(f"🏷️  目标类别: {', '.join(target_labels)}")
        print(f"💾 输出文件: {output_file}")
        
        args = [
            '--config', self.gd_config,
            '--weights', self.gd_weights,
            '--image-dir', image_dir,
            '--labels', ','.join(target_labels),
            '--output', output_file,
            '--box-threshold', str(self.gd_threshold)
        ]
        
        success, stdout, stderr = self._run_conda_script(
            self.conda_envs['dino'],
            self.dino_script,
            args
        )
        
        if success:
            print("✅ Grounding DINO 检测完成")
            if stdout:
                print(stdout)
        else:
            print("❌ Grounding DINO 检测失败")
            if stderr:
                print(f"错误: {stderr}")
        
        return success, stdout, stderr
    
    def run_vlm_cleaning(
        self,
        detection_file: str,
        definitions_file: str,
        output_file: str,
        workers: int = 8,
        auto_start_stop: bool = True
    ) -> tuple:
        """
        运行VLM清洗
        
        Args:
            auto_start_stop: 是否自动启动和停止VLM服务
        """
        if auto_start_stop:
            # 启动VLM服务
            if not self._start_vlm_service():
                return False, "", "VLM服务启动失败"
        
        try:
            print("\n" + "="*80)
            print("🧹 阶段 2: VLM 清洗")
            print("="*80)
            
            args = [
                '--input', detection_file,
                '--definitions', definitions_file,
                '--output', output_file,
                '--vlm-url', self.vlm_url,
                '--vlm-model', self.vlm_model,
                '--workers', str(workers)
            ]
            
            success, stdout, stderr = self._run_conda_script(
                self.conda_envs['vlm'],
                self.vlm_script,
                args
            )
            
            if success:
                print("✅ VLM 清洗完成")
                if stdout:
                    print(stdout)
            else:
                print("❌ VLM 清洗失败")
                if stderr:
                    print(f"错误: {stderr}")
            
            return success, stdout, stderr
        
        finally:
            if auto_start_stop:
                # 停止VLM服务，释放显存
                self._stop_vlm_service()
    
    def process_images_stage1_only(
        self,
        image_dir: str,
        target_labels: List[str],
        output_dir: str
    ) -> Dict[str, Any]:
        """
        只执行阶段1: DINO检测
        适合显存不足的情况
        """
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)
        
        dino_output = output_dir / "dino_detections.json"
        
        success, stdout, stderr = self.run_dino_detection(
            image_dir=image_dir,
            target_labels=target_labels,
            output_file=str(dino_output)
        )
        
        if not success or not dino_output.exists():
            return {
                'success': False,
                'error': f"DINO检测失败: {stderr}",
                'stage': 'detection'
            }
        
        try:
            with open(dino_output, 'r') as f:
                dino_results = json.load(f)
        except Exception as e:
            return {
                'success': False,
                'error': f'无法读取DINO结果: {str(e)}',
                'stage': 'detection'
            }
        
        return {
            'success': True,
            'total_detections': len(dino_results),
            'final_kept': len(dino_results),
            'output_file': str(dino_output),
            'stage': 'detection_only',
            'message': '✅ DINO检测完成，可以在结果页面点击"清洗"按钮进行VLM清洗'
        }
    
    def process_images_stage2_only(
        self,
        detection_file: str,
        label_definitions: Dict[str, str],
        output_dir: str,
        workers: int = 8
    ) -> Dict[str, Any]:
        """
        只执行阶段2: VLM清洗
        用于对已有的DINO检测结果进行清洗
        """
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)
        
        # 保存类别定义
        definitions_file = output_dir / "definitions.json"
        with open(definitions_file, 'w', encoding='utf-8') as f:
            json.dump(label_definitions, f, ensure_ascii=False, indent=2)
        
        vlm_output = output_dir / "vlm_cleaned.json"
        
        # 运行VLM清洗（自动启动和停止服务）
        success, stdout, stderr = self.run_vlm_cleaning(
            detection_file=str(detection_file),
            definitions_file=str(definitions_file),
            output_file=str(vlm_output),
            workers=workers,
            auto_start_stop=True  # 自动管理VLM服务
        )
        
        if not success or not vlm_output.exists():
            return {
                'success': False,
                'error': f"VLM清洗失败: {stderr}",
                'stage': 'cleaning'
            }
        
        try:
            with open(vlm_output, 'r') as f:
                cleaned_results = json.load(f)
        except Exception as e:
            return {
                'success': False,
                'error': f'无法读取VLM结果: {str(e)}',
                'stage': 'cleaning'
            }
        
        kept_results = [r for r in cleaned_results if r.get('vlm_decision') == 'keep']
        
        return {
            'success': True,
            'total_detections': len(cleaned_results),
            'final_kept': len(kept_results),
            'output_file': str(vlm_output),
            'stage': 'completed'
        }
    
    def process_images(
        self,
        image_dir: str,
        target_labels: List[str],
        label_definitions: Dict[str, str],
        output_dir: str,
        enable_vlm_clean: bool = True
    ) -> Dict[str, Any]:
        """
        完整处理流程 - 但只在需要时才启动VLM
        """
        if not enable_vlm_clean:
            # 只运行DINO检测
            return self.process_images_stage1_only(
                image_dir=image_dir,
                target_labels=target_labels,
                output_dir=output_dir
            )
        else:
            # 先运行DINO
            result = self.process_images_stage1_only(
                image_dir=image_dir,
                target_labels=target_labels,
                output_dir=output_dir
            )
            
            if not result['success']:
                return result
            
            # 再运行VLM清洗
            dino_output = result['output_file']
            return self.process_images_stage2_only(
                detection_file=dino_output,
                label_definitions=label_definitions,
                output_dir=output_dir
            )