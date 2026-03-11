import pytest
import time
from conftest import TestConfig


def test_invalid_token(authenticated_session):
    session = authenticated_session
    original_headers = session.headers.copy()
    
    try:
        session.headers.update({"Authorization": "Bearer invalid_token_xyz"})
        
        response = session.get(f"{TestConfig.BACKEND_BASE_URL}/users/me")
        
        if response.status_code == 200:
            assert not response.json().get("success", True), "Expected business failure"
        else:
            assert response.status_code in [400, 404, 401, 403, 500]
            
    finally:
        session.headers.update(original_headers)


def test_update_nonexistent_project(authenticated_session):
    nonexistent_id = 999999999
    
    response = authenticated_session.put(
        f"{TestConfig.BACKEND_BASE_URL}/projects/{nonexistent_id}",
        json={
            "name": "Updated Name",
            "labels": ["test"]
        }
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_delete_nonexistent_project(authenticated_session):
    nonexistent_id = 999999999
    
    response = authenticated_session.delete(
        f"{TestConfig.BACKEND_BASE_URL}/projects/{nonexistent_id}"
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_get_nonexistent_task(authenticated_session, algorithm_session):
    nonexistent_task_id = "nonexistent_task_id_99999"
    
    response = algorithm_session.get(
        f"{TestConfig.ALGORITHM_BASE_URL}/algo/dino/status/{nonexistent_task_id}"
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_cancel_nonexistent_training(authenticated_session):
    nonexistent_id = 999999999
    
    response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/api/v1/training/cancel/{nonexistent_id}"
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_invalid_project_name(authenticated_session):
    response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": "",
            "labels": ["test"]
        }
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_invalid_project_labels(authenticated_session):
    response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Invalid Labels Test {int(time.time())}",
            "labels": []
        }
    )
    
    assert response.status_code == 200, "Empty labels should be accepted"
    assert response.json().get("success", False), "Success should be True"


def test_get_nonexistent_user(authenticated_session):
    nonexistent_id = 999999999
    
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/users/{nonexistent_id}"
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_get_nonexistent_training_record(authenticated_session):
    nonexistent_id = 999999999
    
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/api/v1/training/record/{nonexistent_id}"
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_invalid_training_request_missing_fields(authenticated_session):
    response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/api/v1/training/start",
        json={}
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_get_nonexistent_test_results(authenticated_session):
    nonexistent_task_id = "nonexistent_test_task"
    
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/api/v1/test/results/{nonexistent_task_id}"
    )
    
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]
