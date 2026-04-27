import pytest
import time
from conftest import TestConfig, upload_file_in_chunks


def test_update_project(authenticated_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Update Test Project {int(time.time())}",
            "labels": ["person", "car"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    assert project_data["success"], f"Project creation failed: {project_data.get('message', 'Unknown error')}"
    
    project_id = project_data["data"]["id"]
    
    try:
        update_response = authenticated_session.put(
            f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}",
            json={
                "name": f"Updated Project Name {int(time.time())}",
                "labels": ["person", "car", "bicycle", "dog"]
            }
        )
        update_response.raise_for_status()
        update_data = update_response.json()
        assert update_data["success"], f"Project update failed: {update_data.get('message', 'Unknown error')}"
        assert update_data["data"]["name"].startswith("Updated Project Name")
        assert update_data["data"]["labels"] == ["person", "car", "bicycle", "dog"]
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_get_project_images(authenticated_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Project Images Test {int(time.time())}",
            "labels": ["test"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        assert file_path is not None, "File upload failed"
        
        images_response = authenticated_session.get(
            f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}/images",
            params={"page": 0, "size": 10}
        )
        images_response.raise_for_status()
        images_data = images_response.json()
        assert images_data["success"], "Failed to get project images"
        assert isinstance(images_data["data"], dict), "Images data should include list and total"
        assert isinstance(images_data["data"].get("images"), list), "Images should be a list"
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_get_project_stats(authenticated_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Project Stats Test {int(time.time())}",
            "labels": ["test"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        stats_response = authenticated_session.get(
            f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}/stats"
        )
        stats_response.raise_for_status()
        stats_data = stats_response.json()
        assert stats_data["success"], "Failed to get project stats"
        stats = stats_data["data"]
        assert "totalImages" in stats
        assert "uploadedImages" in stats
        assert "processedImages" in stats
        assert "status" in stats
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_get_projects_by_status(authenticated_session):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Status Filter Test {int(time.time())}",
            "labels": ["test"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        projects_response = authenticated_session.get(
            f"{TestConfig.BACKEND_BASE_URL}/projects",
            params={"status": "DRAFT"}
        )
        projects_response.raise_for_status()
        projects_data = projects_response.json()
        assert projects_data["success"], "Failed to get projects by status"
        
        projects = projects_data["data"]
        assert isinstance(projects, list), "Projects should be a list"
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")


def test_delete_project(authenticated_session):
    import time
    project_name = f"Delete_Target_{int(time.time())}"
    create_res = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={"name": project_name, "description": "to be deleted", "labels": ["test"]}
    )
    assert create_res.status_code == 200
    project_id = create_res.json()["data"]["id"]
    
    delete_res = authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")
    if delete_res.status_code == 200:
        assert delete_res.json().get("success", True)
    else:
        assert False, f"Delete failed with {delete_res.status_code}"


def test_get_nonexistent_project(authenticated_session):
    nonexistent_id = 999999999
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/projects/{nonexistent_id}"
    )
    if response.status_code == 200:
        assert not response.json().get("success", True), "Expected business failure"
    else:
        assert response.status_code in [400, 404, 401, 403, 500]


def test_get_project_images_pagination(authenticated_session, temp_image_file):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Pagination Test Project {int(time.time())}",
            "labels": ["test"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        file_path = upload_file_in_chunks(authenticated_session, temp_image_file, project_id)
        assert file_path is not None, "File upload failed"
        
        images_response = authenticated_session.get(
            f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}/images",
            params={"page": 0, "size": 1}
        )
        images_response.raise_for_status()
        images_data = images_response.json()
        assert images_data["success"], "Failed to get paginated images"
        assert isinstance(images_data["data"], dict), "Images data should include list and total"
        assert isinstance(images_data["data"].get("images"), list), "Images should be a list"
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")
