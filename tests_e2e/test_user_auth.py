import pytest
import time
from conftest import TestConfig


def test_get_current_user(authenticated_session):
    response = authenticated_session.get(f"{TestConfig.BACKEND_BASE_URL}/users/me")
    response.raise_for_status()
    user_data = response.json()
    assert user_data["success"], "Failed to get current user"
    assert "id" in user_data["data"]
    assert "username" in user_data["data"]
    assert "email" in user_data["data"]
    assert "displayName" in user_data["data"]


def test_get_user_by_id(authenticated_session):
    response = authenticated_session.get(f"{TestConfig.BACKEND_BASE_URL}/users/me")
    response.raise_for_status()
    current_user = response.json()["data"]
    user_id = current_user["id"]
    
    user_response = authenticated_session.get(f"{TestConfig.BACKEND_BASE_URL}/users/{user_id}")
    user_response.raise_for_status()
    user_data = user_response.json()
    assert user_data["success"], "Failed to get user by ID"
    assert user_data["data"]["id"] == user_id


@pytest.mark.skip(reason="Admin only")
def test_get_users_list(authenticated_session):
    response = authenticated_session.get(f"{TestConfig.BACKEND_BASE_URL}/users")
    response.raise_for_status()
    users_data = response.json()
    assert users_data["success"], "Failed to get users list"
    assert isinstance(users_data["data"], list), "Users should be a list"
    assert len(users_data["data"]) >= 0, "Should have zero or more users"


def test_get_users_by_organization(authenticated_session):
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/users",
        params={"organizationId": 1}
    )
    response.raise_for_status()
    users_data = response.json()
    assert users_data["success"], "Failed to get users by organization"
    assert isinstance(users_data["data"], list), "Users should be a list"


def test_logout(authenticated_session):
    response = authenticated_session.post(f"{TestConfig.BACKEND_BASE_URL}/auth/logout")
    response.raise_for_status()
    logout_data = response.json()
    assert logout_data["success"], "Logout failed"


def test_refresh_token(authenticated_session):
    response = authenticated_session.post(f"{TestConfig.BACKEND_BASE_URL}/auth/refresh")
    response.raise_for_status()
    refresh_data = response.json()
    assert refresh_data["success"], "Token refresh failed"
    assert "data" in refresh_data, "New token not returned"


def test_login_with_invalid_credentials(backend_session):
    response = backend_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/auth/login",
        json={
            "username": "nonexistent_user",
            "password": "wrong_password"
        }
    )
    
    assert response.status_code == 200, f"Expected 200 for invalid credentials, got {response.status_code}"
    
    data = response.json()
    assert data is not None, "Response should have JSON body"
    assert not data.get("success", True), "Login should fail with invalid credentials"


def test_register_duplicate_user(backend_session):
    username = f"dup_test_user_{int(time.time())}"
    
    response = backend_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/auth/register",
        json={
            "username": username,
            "password": "Test@123456",
            "email": f"{username}@example.com",
            "displayName": "Duplicate Test User"
        }
    )
    
    assert response.status_code == 200, "First registration should succeed"
    
    second_response = backend_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/auth/register",
        json={
            "username": username,
            "password": "Test@123456",
            "email": f"{username}@example.com",
            "displayName": "Duplicate Test User"
        }
    )
    
    assert second_response.status_code == 200, "Second registration should return 200"
    
    data = second_response.json()
    assert data is not None, "Response should have JSON body"
    assert not data.get("success", True), "Duplicate registration should fail"


def test_register_invalid_email(backend_session):
    response = backend_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/auth/register",
        json={
            "username": f"invalid_email_user_{int(time.time())}",
            "password": "Test@123456",
            "email": "invalid-email",
            "displayName": "Invalid Email Test User"
        }
    )
    
    assert response.status_code == 400, "Should return 400 for invalid email format"


def test_register_missing_fields(backend_session):
    response = backend_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/auth/register",
        json={
            "username": f"missing_fields_user_{int(time.time())}"
        }
    )
    
    assert response.status_code == 400, "Should return 400 for missing required fields"


def test_get_nonexistent_user_by_id(authenticated_session):
    nonexistent_id = 999999999
    
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/users/{nonexistent_id}"
    )
    
    assert response.status_code == 404, "Should return 404 for nonexistent user"


@pytest.mark.skip(reason="Env issue")
def test_sync_user_to_label_studio(authenticated_session):
    response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/label-studio/sync-user"
    )
    response.raise_for_status()
    data = response.json()
    assert data["success"], "Failed to sync user to Label Studio"


def test_get_label_studio_login_url(authenticated_session):
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/label-studio/login-url"
    )
    response.raise_for_status()
    data = response.json()
    assert data["success"], "Failed to get Label Studio login URL"
    assert "data" in data, "Login URL not returned"
    assert isinstance(data["data"], str), "Login URL should be a string"


@pytest.mark.skip(reason="Env issue")
def test_get_label_studio_user_info(authenticated_session):
    response = authenticated_session.get(
        f"{TestConfig.BACKEND_BASE_URL}/label-studio/user-info",
        params={"lsToken": "test_token"}
    )
    response.raise_for_status()
    data = response.json()
    assert data["success"], "Failed to get Label Studio user info"


@pytest.mark.skip(reason="Env issue")
def test_sync_project_to_label_studio(authenticated_session):
    project_response = authenticated_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/projects",
        json={
            "name": f"Sync Project Test {int(time.time())}",
            "labels": ["test"]
        }
    )
    project_response.raise_for_status()
    project_data = project_response.json()
    project_id = project_data["data"]["id"]
    
    try:
        sync_response = authenticated_session.post(
            f"{TestConfig.BACKEND_BASE_URL}/label-studio/sync-project/{project_id}"
        )
        sync_response.raise_for_status()
        sync_data = sync_response.json()
        assert sync_data["success"], "Failed to sync project to Label Studio"
        
    finally:
        authenticated_session.delete(f"{TestConfig.BACKEND_BASE_URL}/projects/{project_id}")
