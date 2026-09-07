from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager
from loguru import logger
import sys

from config import settings, BASE_DIR, LOG_DIR
from routers import dino, vlm, yolo, health, train, test, auto_annotation, single_class_detection, feasibility, training, edge_inference, reinference
from routers import requirement, dataset_profile, model_registry, model_route, auto_label, fusion, label_studio_format, training_orchestration, adapters, annotated_video, video_sampling


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"Starting {settings.APP_NAME} v{settings.APP_VERSION}")
    logger.info(f"API Prefix: {settings.API_PREFIX}")
    logger.info(f"Upload Base Path: {settings.UPLOAD_BASE_PATH}")
    yield
    logger.info(f"Shutting down {settings.APP_NAME}")


app = FastAPI(
    title=settings.APP_NAME,
    version=settings.APP_VERSION,
    description="Annotation Platform Algorithm Service - DINO, VLM, YOLO inference",
    lifespan=lifespan,
    docs_url=f"{settings.API_PREFIX}/docs",
    redoc_url=f"{settings.API_PREFIX}/redoc",
    openapi_url=f"{settings.API_PREFIX}/openapi.json"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=settings.CORS_ALLOW_CREDENTIALS,
    allow_methods=settings.CORS_ALLOW_METHODS,
    allow_headers=settings.CORS_ALLOW_HEADERS,
)

logger.remove()
logger.add(
    sys.stdout,
    level=settings.LOG_LEVEL,
    format="<green>{time:YYYY-MM-DD HH:mm:ss}</green> | <level>{level: <8}</level> | <cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - <level>{message}</level>"
)
logger.add(
    LOG_DIR / settings.LOG_FILE,
    level=settings.LOG_LEVEL,
    format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{function}:{line} - {message}",
    rotation="100 MB",
    retention="30 days"
)

app.include_router(health.router, prefix=settings.API_PREFIX, tags=["Health"])
app.include_router(dino.router, prefix=settings.API_PREFIX, tags=["DINO"])
app.include_router(vlm.router, prefix=settings.API_PREFIX, tags=["VLM"])
app.include_router(yolo.router, prefix=settings.API_PREFIX, tags=["YOLO"])
app.include_router(train.router, prefix=settings.API_PREFIX, tags=["Training"])
app.include_router(test.router, prefix=settings.API_PREFIX, tags=["Testing"])
app.include_router(auto_annotation.router, prefix=settings.API_PREFIX, tags=["AutoAnnotation"])
app.include_router(single_class_detection.router, prefix=settings.API_PREFIX, tags=["SingleClassDetection"])
app.include_router(feasibility.router, prefix=settings.API_PREFIX, tags=["Feasibility"])
app.include_router(edge_inference.router, prefix=settings.API_PREFIX, tags=["EdgeInference"])
app.include_router(reinference.router, prefix=settings.API_PREFIX, tags=["ReInference"])
app.include_router(training.router, tags=["CustomTraining"])
app.include_router(requirement.router, tags=["InternalRequirement"])
app.include_router(dataset_profile.router, tags=["InternalDatasetProfile"])
app.include_router(model_registry.router, tags=["InternalModelRegistry"])
app.include_router(model_route.router, tags=["InternalModelRoute"])
app.include_router(auto_label.router, tags=["InternalAutoLabel"])
app.include_router(fusion.router, tags=["InternalFusion"])
app.include_router(label_studio_format.router, tags=["InternalLabelStudioFormat"])
app.include_router(training_orchestration.router, tags=["InternalTrainingOrchestration"])
app.include_router(adapters.router, tags=["InternalAdapters"])
app.include_router(annotated_video.router, tags=["InternalAnnotatedVideo"])
app.include_router(video_sampling.router, tags=["InternalVideoSampling"])


@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={
            "success": False,
            "message": "Internal server error",
            "error": str(exc)
        }
    )


@app.get("/")
async def root():
    return {
        "name": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "status": "running"
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=True,
        log_level=settings.LOG_LEVEL.lower()
    )
