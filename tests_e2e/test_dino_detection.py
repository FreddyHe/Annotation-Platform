import pytest
import time
from conftest import TestConfig, wait_for_task_completion, upload_file_in_chunks


def test_dino_detection_workflow(authenticated_session, algorithm_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"DINO Detection Test Project {int(time.time())}",
            "labels": ["person", "car", "dog", "cat"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    assert project_data["success"], f"Project creation failed: {project_data.get('message', 'Unknown error')}"
    
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        assert file_path is not None, "File upload failed"
        
        image_paths = [file_path]
        
        dino_request = {
            "project_id": project_id,
            "image_paths": image_paths,
            "labels": ["person", "car"],
            "box_threshold": 0.3,
            "text_threshold": 0.25
        }
        
        dino_response = algorithm_session.post(
            f"{TestConfig.ALGORITHM_BASE_URL}/algo/dino/detect",
            json=dino_request
        )
        dino_response.raise_for_status()
        dino_data = dino_response.json()
        assert dino_data["success"], f"DINO detection failed: {dino_data.get('message', 'Unknown error')}"
        
        task_id = dino_data["task_id"]
        assert task_id is not None, "Task ID not returned"
        assert dino_data["status"] == "RUNNING", "Task should be in RUNNING state"
        
        task_status = wait_for_task_completion(
            algorithm_session,
            task_id,
            TestConfig.ALGORITHM_BASE_URL,
            "dino",
            timeout=60
        )
        
        assert task_status["status"] == "completed", f"Task did not complete successfully: {task_status}"
        assert task_status["processed_images"] == len(image_paths), "Not all images were processed"
        
        results_response = algorithm_session.get(
            f"{TestConfig.ALGORITHM_BASE_URL}/algo/dino/results/{task_id}"
        )
        results_response.raise_for_status()
        results_data = results_response.json()
        assert results_data["success"], "Failed to get DINO results"
        assert "results" in results_data, "Results not found in response"
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_dino_task_cancellation(authenticated_session, algorithm_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"DINO Cancel Test Project {int(time.time())}",
            "labels": ["person", "car"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    assert project_data["success"], f"Project creation failed: {project_data.get('message', 'Unknown error')}"
    
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        
        dino_request = {
            "project_id": project_id,
            "image_paths": [file_path],
            "labels": ["person"],
            "box_threshold": 0.3,
            "text_threshold": 0.25
        }
        
        dino_response = algorithm_session.post(
            f"{TestConfig.ALGORITHM_BASE_URL}/algo/dino/detect",
            json=dino_request
        )
        dino_response.raise_for_status()
        dino_data = dino_response.json()
        task_id = dino_data["task_id"]
        
        time.sleep(1)
        
        cancel_response = algorithm_session.post(
            f"{TestConfig.ALGORITHM_BASE_URL}/algo/dino/cancel/{task_id}"
        )
        cancel_response.raise_for_status()
        cancel_data = cancel_response.json()
        assert cancel_data["success"], f"Task cancellation failed: {cancel_data.get('message', 'Unknown error')}"
        
        status_response = algorithm_session.get(
            f"{TestConfig.ALGORITHM_BASE_URL}/algo/dino/status/{task_id}"
        )
        status_response.raise_for_status()
        status_data = status_response.json()
        assert status_data["status"] in ["cancelled", "completed"], f"Task status unexpected: {status_data['status']}"
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")
