from fastapi import APIRouter, HTTPException, Query

from services import model_registry_service

router = APIRouter()


@router.get("/internal/models/registry")
async def list_models(model_type: str | None = Query(default=None)):
    items = model_registry_service.list_models(model_type=model_type)
    return {"success": True, "available": True, "total": len(items), "items": items}


@router.post("/internal/models/registry/sync")
async def sync_registry():
    return model_registry_service.sync_registry()


@router.get("/internal/models/registry/{model_id}")
async def get_model(model_id: str):
    item = model_registry_service.get_model(model_id)
    if not item:
        raise HTTPException(status_code=404, detail={"message": "model not found", "model_id": model_id})
    return {"success": True, "available": True, "item": item}


@router.get("/internal/models/adapters/status")
async def adapter_status():
    return {"success": True, "available": True, "items": model_registry_service.adapter_status()}


@router.get("/internal/models/xingmu/model-groups")
async def xingmu_model_groups():
    items = model_registry_service.xingmu_model_groups()
    return {"success": True, "available": True, "total": len(items), "items": items}
