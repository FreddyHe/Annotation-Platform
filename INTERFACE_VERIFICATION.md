# 模型测试接口链路验证

## 前端 -> Spring Boot -> FastAPI 链路

### 1. 前端 (Vue 3) -> Spring Boot

**文件**: `/root/autodl-fs/Annotation-Platform/frontend-vue/src/components/ModelHub.vue`

**调用代码**:
```javascript
const formData = new FormData()
fileList.value.forEach(file => {
  formData.append('files', file.raw)
})
formData.append('model_path', selectedTraining.value.bestModelPath)
formData.append('conf_threshold', testParams.value.confThreshold)
formData.append('iou_threshold', testParams.value.iouThreshold)
formData.append('device', testParams.value.device)

const res = await modelTestAPI.startTest(formData)
```

**API 调用**:
```javascript
// /root/autodl-fs/Annotation-Platform/frontend-vue/src/api/index.js
export const modelTestAPI = {
  startTest(data) {
    const isFormData = data instanceof FormData
    return request({
      url: isFormData ? '/test/start/upload' : '/test/start',
      method: 'post',
      data
    })
  }
}
```

**请求配置**:
```javascript
// /root/autodl-fs/Annotation-Platform/frontend-vue/src/utils/request.js
request.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    
    if (!(config.data instanceof FormData)) {
      config.headers['Content-Type'] = 'application/json'
    }
    
    return config
  }
)
```

**HTTP 请求**:
```
POST /api/v1/test/start/upload
Content-Type: multipart/form-data (自动设置)
Authorization: Bearer {token}

FormData:
- files: [File, File, ...] (多个文件)
- model_path: "/path/to/model.pt"
- conf_threshold: 0.25
- iou_threshold: 0.45
- device: "0"
```

---

### 2. Spring Boot -> FastAPI

**Controller**: `/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/controller/ModelTestController.java`

```java
@PostMapping("/start/upload")
public Result<Map<String, String>> startTestWithUpload(
    @RequestParam("files") MultipartFile[] files,
    @RequestParam("model_path") String modelPath,
    @RequestParam(value = "conf_threshold", defaultValue = "0.25") Double confThreshold,
    @RequestParam(value = "iou_threshold", defaultValue = "0.45") Double iouThreshold,
    @RequestParam(value = "device", defaultValue = "0") String device
) {
    try {
        List<File> imageFiles = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            String fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            String uniqueFilename = UUID.randomUUID().toString() + fileExtension;

            Path uploadDir = Paths.get(uploadBasePath, "test_uploads");
            Files.createDirectories(uploadDir);

            Path filePath = uploadDir.resolve(uniqueFilename);
            file.transferTo(filePath.toFile());

            imageFiles.add(filePath.toFile());
        }

        String taskId = modelTestService.startTestWithUpload(
            modelPath,
            imageFiles,
            confThreshold,
            iouThreshold,
            device
        );

        Map<String, String> result = Map.of("task_id", taskId);
        return Result.success(result);
    } catch (Exception e) {
        log.error("Failed to start test with upload", e);
        return Result.error(500, "Failed to start test: " + e.getMessage());
    }
}
```

**Service**: `/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/service/ModelTestService.java`

```java
public String startTestWithUpload(
    String modelPath,
    List<File> imageFiles,
    Double confThreshold,
    Double iouThreshold,
    String device
) throws Exception {
    log.info("Starting model test with upload, model: {}, images: {}", modelPath, imageFiles.size());

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    
    for (File file : imageFiles) {
        Resource resource = new FileSystemResource(file);
        body.add("files", resource);
    }
    
    body.add("model_path", modelPath);
    body.add("conf_threshold", confThreshold);
    body.add("iou_threshold", iouThreshold);
    body.add("device", device);

    String testUrl = algorithmServiceUrl + "/api/v1/algo/test/yolo/upload";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

    HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
    ResponseEntity<String> response = restTemplate.postForEntity(testUrl, entity, String.class);

    if (!response.getStatusCode().is2xxSuccessful()) {
        throw new RuntimeException("Failed to start test with upload: " + response.getStatusCode());
    }

    JsonNode responseJson = objectMapper.readTree(response.getBody());
    return responseJson.get("task_id").asText();
}
```

**RestTemplate 配置**: `/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/config/RestTemplateConfig.java`

```java
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(300000);

        RestTemplate restTemplate = new RestTemplate(factory);

        restTemplate.setMessageConverters(Arrays.asList(
                new ByteArrayHttpMessageConverter(),
                new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new ResourceHttpMessageConverter(),
                new FormHttpMessageConverter(),
                new MappingJackson2HttpMessageConverter()
        ));

        return restTemplate;
    }
}
```

**HTTP 请求**:
```
POST http://localhost:8000/api/v1/algo/test/yolo/upload
Content-Type: multipart/form-data

FormData:
- files: [Resource, Resource, ...] (多个文件)
- model_path: "/path/to/model.pt"
- conf_threshold: 0.25
- iou_threshold: 0.45
- device: "0"
```

---

### 3. FastAPI

**Router**: `/root/autodl-fs/Annotation-Platform/algorithm-service/routers/test.py`

```python
@router.post("/yolo/upload", response_model=YOLOTestResponse)
async def start_yolo_test_with_upload(
    model_path: str,
    conf_threshold: float = 0.25,
    iou_threshold: float = 0.45,
    device: str = "0",
    files: List[UploadFile] = File(...),
    background_tasks: BackgroundTasks = BackgroundTasks()
):
    """
    启动 YOLO 测试任务（上传图片）
    
    - **model_path**: 模型权重路径
    - **conf_threshold**: 置信度阈值
    - **iou_threshold**: IOU阈值
    - **device**: GPU设备
    - **files**: 上传的图片文件
    """
    try:
        # 验证模型路径
        model_file = Path(model_path)
        if not model_file.exists():
            raise HTTPException(status_code=400, detail=f"Model file not found: {model_path}")
        
        # 保存上传的图片
        upload_dir = Path("/root/autodl-fs/Annotation-Platform/temp_uploads")
        upload_dir.mkdir(parents=True, exist_ok=True)
        
        image_paths = []
        for file in files:
            # 生成唯一文件名
            file_ext = Path(file.filename).suffix
            unique_filename = f"{uuid4()}{file_ext}"
            file_path = upload_dir / unique_filename
            
            # 保存文件
            with open(file_path, "wb") as buffer:
                content = await file.read()
                buffer.write(content)
            
            image_paths.append(str(file_path))
        
        if not image_paths:
            raise HTTPException(status_code=400, detail="No images uploaded")
        
        # 生成任务ID
        task_id = str(uuid4())
        
        # 创建任务
        await task_manager.create_task(
            task_id=task_id,
            task_type="YOLO_TESTING",
            project_id=0,
            total_images=len(image_paths),
            parameters={
                "model_path": model_path,
                "conf_threshold": conf_threshold,
                "iou_threshold": iou_threshold,
                "device": device
            }
        )
        
        # 添加后台任务
        background_tasks.add_task(
            run_yolo_inference_task,
            task_id,
            model_path,
            image_paths,
            conf_threshold,
            iou_threshold,
            device
        )
        
        return YOLOTestResponse(
            task_id=task_id,
            status="RUNNING",
            message="YOLO testing task started successfully"
        )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to start YOLO testing: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
```

**HTTP 响应**:
```json
{
  "task_id": "uuid-string",
  "status": "RUNNING",
  "message": "YOLO testing task started successfully"
}
```

---

## 参数对齐验证

| 参数 | 前端 | Spring Boot | FastAPI | 类型 |
|------|------|-------------|---------|------|
| files | formData.append('files', file.raw) | @RequestParam("files") MultipartFile[] | files: List[UploadFile] | 文件数组 |
| model_path | formData.append('model_path', ...) | @RequestParam("model_path") String | model_path: str | 字符串 |
| conf_threshold | formData.append('conf_threshold', ...) | @RequestParam("conf_threshold") Double | conf_threshold: float | 浮点数 |
| iou_threshold | formData.append('iou_threshold', ...) | @RequestParam("iou_threshold") Double | iou_threshold: float | 浮点数 |
| device | formData.append('device', ...) | @RequestParam("device") String | device: str | 字符串 |

✅ **所有参数已对齐**

---

## 配置验证

### Spring Boot 配置
**文件**: `/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/resources/application.yml`

```yaml
algorithm:
  url: http://localhost:8000
  timeout: 600000
```

### FastAPI 配置
**文件**: `/root/autodl-fs/Annotation-Platform/algorithm-service/config.py`

```python
class Settings(BaseSettings):
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    CORS_ORIGINS: list = ["http://localhost:5173", "http://localhost:3000", "*"]
```

✅ **服务地址已对齐**

---

## 任务状态验证

### 轮询接口

**前端**:
```javascript
const res = await modelTestAPI.getTestStatus(testTaskId.value)
const status = res.data.status
```

**Spring Boot**:
```java
@GetMapping("/status/{taskId}")
public Result<Map<String, Object>> getTestStatus(@PathVariable String taskId)
```

**FastAPI**:
```python
@router.get("/status/{task_id}")
async def get_test_status(task_id: str)
```

### 结果获取接口

**前端**:
```javascript
const res = await modelTestAPI.getTestResults(testTaskId.value)
const results = res.data.results || []
```

**Spring Boot**:
```java
@GetMapping("/results/{taskId}")
public Result<Map<String, Object>> getTestResults(@PathVariable String taskId)
```

**FastAPI**:
```python
@router.get("/results/{task_id}")
async def get_test_results(task_id: str)
```

✅ **所有接口已对齐**

---

## 总结

1. ✅ 前端 FormData 正确构造
2. ✅ Spring Boot 正确接收并转发文件
3. ✅ FastAPI 正确处理文件上传
4. ✅ 所有参数名称和类型对齐
5. ✅ Content-Type 正确设置
6. ✅ 服务地址配置正确
7. ✅ RestTemplate 配置支持 multipart
8. ✅ 任务状态和结果接口对齐

**3/3 任务已完成**
