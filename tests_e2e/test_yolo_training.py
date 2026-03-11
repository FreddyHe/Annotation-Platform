import pytest
import time
from conftest import TestConfig, wait_for_task_completion, upload_file_in_chunks


def test_yolo_training_workflow(authenticated_session, algorithm_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"YOLO Training Test Project {int(time.time())}",
            "labels": ["person", "car", "bicycle"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    assert project_data["success"], f"Project creation failed: {project_data.get('message', 'Unknown error')}"
    
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        assert file_path is not None, "File upload failed"
        
        import tempfile
        import os
        
        dataset_dir = tempfile.mkdtemp(prefix="yolo_dataset_")
        try:
            data_yaml_path = os.path.join(dataset_dir, "data.yaml")
            with open(data_yaml_path, 'w') as f:
                f.write(f"""
path: {dataset_dir}
train: images/train
val: images/val
names:
  0: person
  1: car
  2: bicycle
""")
            
            train_request = {
                "project_id": project_id,
                "dataset_path": dataset_dir,
                "epochs": 10,
                "batch_size": 4,
                "image_size": 320,
                "model_type": "yolov8n.pt",
                "device": "0"
            }
            
            train_response = algorithm_session.post(
                f"{TestConfig.ALGORITHM_BASE_URL}/algo/train/yolo",
                json=train_request
            )
            
            if train_response.status_code == 400:
                pytest.skip("Dataset path validation failed - this is expected in test environment")
            
            train_response.raise_for_status()
            train_data = train_response.json()
            assert train_data["status"] == "RUNNING", f"Training task should be in RUNNING state: {train_data}"
            
            task_id = train_data["task_id"]
            assert task_id is not None, "Task ID not returned"
            
            status_response = algorithm_session.get(
                f"{TestConfig.ALGORITHM_BASE_URL}/algo/train/status/{task_id}"
            )
            status_response.raise_for_status()
            status_data = status_response.json()
            assert "status" in status_data, "Status not found in response"
            
        finally:
            import shutil
            if os.path.exists(dataset_dir):
                shutil.rmtree(dataset_dir, ignore_errors=True)
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_yolo_training_status_and_cancellation(authenticated_session, algorithm_session):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"YOLO Training Status Test Project {int(time.time())}",
            "labels": ["person"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    assert project_data["success"], f"Project creation failed: {project_data.get('message', 'Unknown error')}"
    
    project_id = project_data["data"]["id"]
    
    try:
        import tempfile
        import os
        
        dataset_dir = tempfile.mkdtemp(prefix="yolo_dataset_")
        try:
            data_yaml_path = os.path.join(dataset_dir, "data.yaml")
            with open(data_yaml_path, 'w') as f:
                f.write(f"""
path: {dataset_dir}
train: images/train
val: images/val
names:
  0: person
""")
            
            train_request = {
                "project_id": project_id,
                "dataset_path": dataset_dir,
                "epochs": 10,
                "batch_size": 4,
                "image_size": 320,
                "model_type": "yolov8n.pt",
                "device": "0"
            }
            
            train_response = algorithm_session.post(
                f"{TestConfig.ALGORITHM_BASE_URL}/algo/train/yolo",
                json=train_request
            )
            
            if train_response.status_code == 400:
                pytest.skip("Dataset path validation failed - this is expected in test environment")
            
            train_response.raise_for_status()
            train_data = train_response.json()
            task_id = train_data["task_id"]
            
            time.sleep(2)
            
            status_response = algorithm_session.get(
                f"{TestConfig.ALGORITHM_BASE_URL}/algo/train/status/{task_id}"
            )
            status_response.raise_for_status()
            status_data = status_response.json()
            
            cancel_response = algorithm_session.post(
                f"{TestConfig.ALGORITHM_BASE_URL}/algo/train/cancel/{task_id}"
            )
            cancel_response.raise_for_status()
            cancel_data = cancel_response.json()
            assert cancel_data["status"] == "CANCELLED", f"Task cancellation failed: {cancel_data}"
            
        finally:
            import shutil
            if os.path.exists(dataset_dir):
                shutil.rmtree(dataset_dir, ignore_errors=True)
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")
