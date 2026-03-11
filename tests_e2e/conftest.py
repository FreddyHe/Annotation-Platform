import pytest
import requests
import tempfile
import os
import time
from typing import Dict, Any, Generator
from pathlib import Path


class TestConfig:
    BACKEND_BASE_URL = os.getenv("BACKEND_BASE_URL", "http://localhost:8080/api/v1")
    ALGORITHM_BASE_URL = os.getenv("ALGORITHM_BASE_URL", "http://localhost:8000/api/v1")
    TEST_USER = {
        "username": f"test_user_e2e_{int(time.time())}",
        "password": "Test@123456",
        "email": f"test_e2e_{int(time.time())}@example.com",
        "displayName": "E2E Test User"
    }
    TIMEOUT = 30
    POLL_INTERVAL = 2


@pytest.fixture(scope="session")
def backend_session() -> Generator[requests.Session, None, None]:
    session = requests.Session()
    session.headers.update({
        "Content-Type": "application/json",
        "Accept": "application/json"
    })
    yield session
    session.close()


@pytest.fixture(scope="session")
def algorithm_session() -> Generator[requests.Session, None, None]:
    session = requests.Session()
    session.headers.update({
        "Content-Type": "application/json",
        "Accept": "application/json"
    })
    yield session
    session.close()


@pytest.fixture(scope="session")
def auth_token(backend_session: requests.Session) -> str:
    register_and_login(backend_session)
    response = backend_session.post(
        f"{TestConfig.BACKEND_BASE_URL}/auth/login",
        json={
            "username": TestConfig.TEST_USER["username"],
            "password": TestConfig.TEST_USER["password"]
        }
    )
    response.raise_for_status()
    data = response.json()
    assert data["success"], f"Login failed: {data.get('message', 'Unknown error')}"
    token = data["data"]["token"]
    return token


@pytest.fixture
def authenticated_session(backend_session: requests.Session, auth_token: str) -> Generator[requests.Session, None, None]:
    session = requests.Session()
    session.headers.update({
        "Accept": "application/json",
        "Authorization": f"Bearer {auth_token}"
    })
    yield session
    session.close()


def register_and_login(session: requests.Session, username: str = None, email: str = None, password: str = None):
    if username is None:
        username = TestConfig.TEST_USER["username"]
    if email is None:
        email = TestConfig.TEST_USER["email"]
    if password is None:
        password = TestConfig.TEST_USER["password"]
    
    response = session.post(
        f"{TestConfig.BACKEND_BASE_URL}/auth/register",
        json={
            "username": username,
            "password": password,
            "email": email,
            "displayName": TestConfig.TEST_USER["displayName"]
        }
    )
    if response.status_code == 400:
        pass


@pytest.fixture
def temp_large_file() -> Generator[Path, None, None]:
    chunk_size = 5 * 1024 * 1024
    temp_file = tempfile.NamedTemporaryFile(mode='wb', delete=False, suffix='.bin')
    try:
        data = os.urandom(chunk_size)
        temp_file.write(data)
        temp_file.flush()
        temp_file.close()
        yield Path(temp_file.name)
    finally:
        if os.path.exists(temp_file.name):
            os.unlink(temp_file.name)


@pytest.fixture
def temp_image_file() -> Generator[Path, None, None]:
    temp_file = tempfile.NamedTemporaryFile(mode='wb', delete=False, suffix='.jpg')
    try:
        from PIL import Image
        import numpy as np
        
        img_array = np.random.randint(0, 255, (640, 640, 3), dtype=np.uint8)
        img = Image.fromarray(img_array)
        img.save(temp_file.name, 'JPEG')
        temp_file.close()
        yield Path(temp_file.name)
    finally:
        if os.path.exists(temp_file.name):
            os.unlink(temp_file.name)


def wait_for_task_completion(
    session: requests.Session,
    task_id: str,
    base_url: str,
    task_type: str,
    timeout: int = TestConfig.TIMEOUT
) -> Dict[str, Any]:
    start_time = time.time()
    while time.time() - start_time < timeout:
        response = session.get(f"{base_url}/algo/{task_type}/status/{task_id}")
        response.raise_for_status()
        task_data = response.json()
        
        status = task_data.get("status", "").lower()
        if status == "completed":
            return task_data
        elif status in ["failed", "cancelled"]:
            raise AssertionError(f"Task {task_id} failed with status: {status}")
        
        time.sleep(TestConfig.POLL_INTERVAL)
    
    raise TimeoutError(f"Task {task_id} did not complete within {timeout} seconds")


def upload_file_in_chunks(
    session: requests.Session,
    file_path: Path,
    project_id: int,
    chunk_size: int = 5 * 1024 * 1024
) -> str:
    file_size = file_path.stat().st_size
    filename = file_path.name
    file_id = f"{filename}_{int(time.time())}"
    total_chunks = (file_size + chunk_size - 1) // chunk_size
    
    with open(file_path, 'rb') as f:
        for chunk_index in range(total_chunks):
            chunk_data = f.read(chunk_size)
            
            files = {'file': (filename, chunk_data, 'application/octet-stream')}
            data = {
                'fileId': file_id,
                'filename': filename,
                'chunkIndex': chunk_index,
                'totalChunks': total_chunks,
                'fileSize': file_size,
                'projectId': project_id
            }
            
            response = session.post(
                f"{TestConfig.BACKEND_BASE_URL}/upload/chunk",
                files=files,
                data=data
            )
            response.raise_for_status()
    
    merge_response = session.post(
        f"{TestConfig.BACKEND_BASE_URL}/upload/merge",
        json={
            "fileId": file_id,
            "filename": filename,
            "totalChunks": total_chunks,
            "projectId": project_id
        }
    )
    merge_response.raise_for_status()
    merge_data = merge_response.json()
    assert merge_data["success"], f"Merge failed: {merge_data.get('message', 'Unknown error')}"
    
    return merge_data["data"]


@pytest.fixture(scope="session", autouse=True)
def check_services_health():
    try:
        backend_response = requests.get(
            f"{TestConfig.BACKEND_BASE_URL}/actuator/health",
            timeout=5
        )
        backend_response.raise_for_status()
    except Exception as e:
        pytest.skip(f"Backend service not available: {e}")
    
    try:
        algo_response = requests.get(
            f"{TestConfig.ALGORITHM_BASE_URL}/health",
            timeout=5
        )
        algo_response.raise_for_status()
    except Exception as e:
        pytest.skip(f"Algorithm service not available: {e}")
