import pytest
import time
from conftest import TestConfig, wait_for_task_completion


def test_user_registration_and_project_creation(authenticated_session):
    response = authenticated_session.get(f"{TestConfig.BACKEND_BASE_URL}/users/me")
    response.raise_for_status()
    user_data = response.json()
    assert user_data["success"], "Failed to get current user"
    assert user_data["data"]["username"] == TestConfig.TEST_USER["username"]
    
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"E2E Test Project {int(time.time())}",
            "labels": ["person", "car", "bicycle"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    assert project_data["success"], f"Project creation failed: {project_data.get('message', 'Unknown error')}"
    
    project = project_data["data"]
    assert project["name"].startswith("E2E Test Project")
    assert project["labels"] == ["person", "car", "bicycle"]
    assert project["status"] == "DRAFT"
    
    project_id = project["id"]
    
    get_project_response = authenticated_session.get(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")
    get_project_response.raise_for_status()
    get_project_data = get_project_response.json()
    assert get_project_data["success"], "Failed to retrieve project"
    assert get_project_data["data"]["id"] == project_id
    
    projects_list_response = authenticated_session.get(f"{TestConfig.BACKEND_BASE_URL}/projects")
    projects_list_response.raise_for_status()
    projects_list_data = projects_list_response.json()
    assert projects_list_data["success"], "Failed to list projects"
    assert any(p["id"] == project_id for p in projects_list_data["data"])
    
    delete_response = authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")
    delete_response.raise_for_status()
    delete_data = delete_response.json()
    assert delete_data["success"], "Failed to delete project"
