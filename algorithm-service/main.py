from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager
from loguru import logger
import sys

from config import settings, BASE_DIR, LOG_DIR
from routers import dino, vlm, yolo, health, train, test, auto_annotation, single_class_detection, feasibility, training


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
app.include_router(yolo.router, prefix=settings.API_PREFIX, tags=["YOLO"])
app.include_router(train.router, prefix=settings.API_PREFIX, tags=["Training"])
app.include_router(test.router, prefix=settings.API_PREFIX, tags=["Testing"])
app.include_router(auto_annotation.router, prefix=settings.API_PREFIX, tags=["AutoAnnotation"])
app.include_router(single_class_detection.router, prefix=settings.API_PREFIX, tags=["SingleClassDetection"])
app.include_router(feasibility.router, prefix=settings.API_PREFIX, tags=["Feasibility"])
app.include_router(training.router, tags=["CustomTraining"])


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
