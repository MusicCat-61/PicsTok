import uuid
import asyncio
import json
import redis.asyncio as redis
from fastapi import FastAPI, BackgroundTasks, HTTPException, Request
from pydantic import BaseModel
from tiktok_handler import tiktok_handler

app = FastAPI()
# Подключаемся к Redis
r = redis.Redis(host='localhost', port=6379, decode_responses=True)

class TikTokRequest(BaseModel):
    url: str

async def perform_parsing(task_id: str, url: str):
    try:
        # 1. Стартовый статус с нулевыми счетчиками
        await r.set(task_id, json.dumps({
            "status": "processing", 
            "progress": "Ищем ID видео...", 
            "processed_comments": 0, 
            "found_images": 0
        }))
        
        video_id = await tiktok_handler.extract_video_id(url)
        if not video_id:
            await r.set(task_id, json.dumps({"status": "error", "message": "ID не найден"}))
            return

        # 2. Передаем r и task_id внутрь, чтобы там шел пуш данных
        urls = await tiktok_handler.get_tiktok_comments_media(
            video_id=video_id, 
            task_id=task_id, 
            redis_inst=r
        )
        
        # 3. Финальный результат
        result = {
            "status": "completed",
            "progress": f"Готово! Найдено {len(urls)}",
            "urls": urls,
            "processed_comments": len(urls), # для верности
            "found_images": len(urls)
        }
        await r.set(task_id, json.dumps(result), ex=3600)
        
    except Exception as e:
        await r.set(task_id, json.dumps({"status": "error", "message": str(e)}))

@app.post("/parse")
async def parse_video(request: TikTokRequest, background_tasks: BackgroundTasks):
    video_url = request.url
    
    # Генерируем уникальный ID задачи
    task_id = str(uuid.uuid4())
    
    # Запускаем парсинг в фоне
    background_tasks.add_task(perform_parsing, task_id, video_url)
    
    return {
        "task_id": task_id,
        "status": "started",
        "message": "Парсинг запущен"
    }

@app.get("/status/{task_id}")
async def get_status(task_id: str):
    data = await r.get(task_id)
    if not data:
        raise HTTPException(status_code=404, detail="Task not found")
    return json.loads(data)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)