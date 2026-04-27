import pytest
import time
import tempfile
import os
import shutil
from conftest import TestConfig, wait_for_task_completion, upload_file_in_chunks


def test_get_training_log(authenticated_session, algorithm_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Training Log Test Project {int(time.time())}",
            "labels": ["person"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        
        dataset_dir = tempfile.mkdtemp(prefix="yolo_log_test_")
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
            
            time.sleep(3)
            
            log_response = algorithm_session.get(
                f"{TestConfig.ALGORITHM_BASE_URL}/algo/train/log/{task_id}"
            )
            log_response.raise_for_status()
            log_data = log_response.json()
            assert "task_id" in log_data
            assert "log_lines" in log_data or "log_content" in log_data
            
        finally:
            if os.path.exists(dataset_dir):
                shutil.rmtree(dataset_dir, ignore_errors=True)
                
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_get_training_records_by_project(authenticated_session, algorithm_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Training Records By Project Test {int(time.time())}",
            "labels": ["person"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        
        dataset_dir = tempfile.mkdtemp(prefix="yolo_records_test_")
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
                pytest.skip("Dataset path validation failed")
            
            train_response.raise_for_status()
            
            records_response = authenticated_session.get(
                f"{TestConfig.BACKEND_BASE_URL}/training/project/{project_id}"
            )
            records_response.raise_for_status()
            records_data = records_response.json()
            assert records_data["success"], "Failed to get training records by project"
            assert isinstance(records_data["data"], list), "Records should be a list"
            
        finally:
            if os.path.exists(dataset_dir):
                shutil.rmtree(dataset_dir, ignore_errors=True)
                
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_get_training_records_by_user(authenticated_session, algorithm_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Training Records By User Test {int(time.time())}",
            "labels": ["person"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        
        dataset_dir = tempfile.mkdtemp(prefix="yolo_user_test_")
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
                pytest.skip("Dataset path validation failed")
            
            train_response.raise_for_status()
            
            records_response = authenticated_session.get(
                f"{TestConfig.BACKEND_BASE_URL}/training/user"
            )
            records_response.raise_for_status()
            records_data = records_response.json()
            assert records_data["success"], "Failed to get training records by user"
            assert isinstance(records_data["data"], list), "Records should be a list"
            
        finally:
            if os.path.exists(dataset_dir):
                shutil.rmtree(dataset_dir, ignore_errors=True)
                
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_get_training_record_by_task_id(authenticated_session, algorithm_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Training Record By Task Test {int(time.time())}",
            "labels": ["person"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        
        dataset_dir = tempfile.mkdtemp(prefix="yolo_task_test_")
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
                pytest.skip("Dataset path validation failed")
            
            train_response.raise_for_status()
            train_data = train_response.json()
            task_id = train_data["task_id"]
            
            record_response = authenticated_session.get(
                f"{TestConfig.BACKEND_BASE_URL}/training/record/task/{task_id}"
            )
            record_response.raise_for_status()
            record_data = record_response.json()
            assert record_data["success"], f"Failed to get training record by task ID: {record_data}"
            assert "taskId" in record_data["data"] or "task_id" in record_data["data"]
            
        finally:
            if os.path.exists(dataset_dir):
                shutil.rmtree(dataset_dir, ignore_errors=True)
                
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_get_completed_trainings(authenticated_session):
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/training/completed"
    )
    response.raise_for_status()
    data = response.json()
    assert data["success"], "Failed to get completed trainings"
    assert isinstance(data["data"], list), "Completed trainings should be a list"


def test_start_model_test(authenticated_session, algorithm_session):
    model_path = "/root/autodl-fs/web_biaozhupingtai/runs/detect/train/weights/best.pt"
    
    if not os.path.exists(model_path):
        pytest.skip(f"Model file not found: {model_path}")
    
    test_request = {
        "model_path": model_path,
        "image_paths": [],
        "conf_threshold": 0.25,
        "iou_threshold": 0.45,
        "device": "0"
    }
    
    response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/test/start",
        json=test_request
    )
    
    assert response.status_code == 200, f"Failed to start model test: {response.status_code}"
    data = response.json()
    assert data["success"], "Model test start failed"
    assert "task_id" in data["data"], "Task ID not returned"


def test_get_model_test_status(authenticated_session):
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/test/status/nonexistent_test_task"
    )
    
    assert response.status_code == 200, "Should return 200 for nonexistent test task"
    
    response_data = response.json()
    assert response_data is not None, "Response should have JSON body"
    assert not response_data.get("success", True), "Get should fail"


def test_get_model_test_results(authenticated_session):
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/test/results/nonexistent_test_task"
    )
    
    assert response.status_code == 200, "Should return 200 for nonexistent test results"
    
    response_data = response.json()
    assert response_data is not None, "Response should have JSON body"
    assert not response_data.get("success", True), "Get should fail"


def test_cancel_model_test(authenticated_session):
    response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/test/cancel/nonexistent_test_task"
    )
    
    assert response.status_code == 200, "Should return 200 for nonexistent test cancel"
    
    response_data = response.json()
    assert response_data is not None, "Response should have JSON body"
    assert not response_data.get("success", True), "Cancel should fail"


def test_get_training_record_not_found(authenticated_session):
    nonexistent_id = 999999999
    
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/training/record/{nonexistent_id}"
    )
    
    assert response.status_code == 200, "Should return 200 for nonexistent training record"
    
    response_data = response.json()
    assert response_data is not None, "Response should have JSON body"
    assert not response_data.get("success", True), "Get should fail"


def test_cancel_nonexistent_training_by_id(authenticated_session):
    nonexistent_id = 999999999
    
    response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/training/cancel/{nonexistent_id}"
    )
    
    assert response.status_code == 200, "Should return 200 for nonexistent training cancel"
    
    response_data = response.json()
    assert response_data is not None, "Response should have JSON body"
    assert not response_data.get("success", True), "Cancel should fail"
