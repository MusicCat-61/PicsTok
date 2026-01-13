import asyncio
import logging
import re
import random
from urllib.parse import urlparse
from typing import List, Optional, Set, Any
import aiohttp
import json
from aiohttp import AsyncResolver

logger = logging.getLogger(__name__)

class TikTokHandler:
    def __init__(self):
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Accept": "*/*",
            "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer": "https://www.tiktok.com/"
        }

    async def is_valid_url(self, session: aiohttp.ClientSession, url: str) -> bool:
        """Проверка ссылки: статус 200 и размер контента > 0 байт"""
        try:
            async with session.head(url, timeout=3, allow_redirects=True) as resp:
                if resp.status == 200:
                    size = int(resp.headers.get('Content-Length', 0))
                    return size > 0
                return False
        except:
            return False

    async def extract_video_id(self, video_url: str) -> Optional[str]:
        if "tiktok.com" not in video_url: return None
        try:
            async with aiohttp.ClientSession(headers=self.headers) as session:
                async with session.head(video_url, allow_redirects=True, timeout=5) as resp:
                    final_url = str(resp.url)
                    match = re.search(r'/video/(\.d+)', final_url) or re.search(r'/video/(\d+)', final_url)
                    if match: return match.group(1)
                    parts = final_url.split('/')
                    for p in parts:
                        if p.isdigit(): return p
        except: pass
        return None

    def _extract_image_id(self, url: str) -> Optional[str]:
        """Твой поиск уникального ID картинки"""
        try:
            match = re.search(r'/([a-f0-9]{32})~tplv', url)
            if match: return match.group(1)
            match = re.search(r'/([a-f0-9]{32})', url)
            if match: return match.group(1)
        except: pass
        return None

    def _extract_urls_from_item(self, item) -> List[str]:
        """Твоя логика: ТОЛЬКО origin_url"""
        urls = []
        seen_image_ids = set()
        if isinstance(item, dict):
            if 'origin_url' in item and isinstance(item['origin_url'], dict):
                url_list = item['origin_url'].get('url_list', [])
                if isinstance(url_list, list):
                    for url in url_list:
                        if isinstance(url, str) and url.startswith('http'):
                            image_id = self._extract_image_id(url)
                            if image_id and image_id not in seen_image_ids:
                                seen_image_ids.add(image_id)
                                urls.append(url)
            elif 'origin_url' in item and isinstance(item['origin_url'], str) and item['origin_url'].startswith('http'):
                url = item['origin_url']
                image_id = self._extract_image_id(url)
                if image_id and image_id not in seen_image_ids:
                    seen_image_ids.add(image_id)
                    urls.append(url)
        return urls

    def _process_media_data(self, media_data) -> List[str]:
        """Твоя обработка форматов медиа"""
        urls = []
        if isinstance(media_data, list):
            for item in media_data:
                urls.extend(self._extract_urls_from_item(item))
        elif isinstance(media_data, dict):
            urls.extend(self._extract_urls_from_item(media_data))
        elif isinstance(media_data, str) and media_data.startswith('http'):
            urls.append(media_data)
        return list(set(urls))

    async def extract_media_from_comment(self, comment: dict) -> List[str]:
        """Твое извлечение медиа с учетом полей и реплаев"""
        media_urls = []
        media_fields = ['image_list', 'images', 'sticker', 'media', 'imagePost', 'imageUrl', 'video', 'gif']
        for field in media_fields:
            if field in comment and comment[field] is not None:
                media_urls.extend(self._process_media_data(comment[field]))
        
        # Реплаи (рекурсивно)
        if comment.get('reply_comment') and isinstance(comment['reply_comment'], list):
            for reply in comment['reply_comment']:
                reply_media = await self.extract_media_from_comment(reply)
                media_urls.extend(reply_media)
        return list(set(media_urls))

    async def get_tiktok_comments_media(self, video_id: str, max_comments: int = 1000, task_id: str = None, redis_inst = None) -> List[str]:
        media_urls = []
        seen_urls = set()
        cursor = 0
        total_processed = 0
        
        # УБРАН AsyncResolver для стабильности загрузки
        connector = aiohttp.TCPConnector(limit=15, enable_cleanup_closed=True)
        async with aiohttp.ClientSession(headers=self.headers, connector=connector) as session:
            try:
                while total_processed < max_comments:
                    api_url = f"https://www.tiktok.com/api/comment/list/?aid=1988&aweme_id={video_id}&count=50&cursor={cursor}"
                    async with session.get(api_url, timeout=15) as response:
                        if response.status != 200: break
                        data = await response.json()
                        comments = data.get('comments', [])
                        if not comments: break

                        for comment in comments:
                            if total_processed >= max_comments: break
                            comment_media = await self.extract_media_from_comment(comment)
                            for url in comment_media:
                                if url not in seen_urls:
                                    # ФИЛЬТРАЦИЯ БИТЫХ ССЫЛОК (0 байт)
                                    if await self.is_valid_url(session, url):
                                        seen_urls.add(url)
                                        media_urls.append(url)
                            total_processed += 1
                        
                        # LIVE ПУШ В REDIS (обновление списка ссылок в реальном времени)
                        if task_id and redis_inst:
                            await redis_inst.set(task_id, json.dumps({
                                "status": "processing",
                                "progress": "Парсим комментарии...",
                                "processed_comments": total_processed,
                                "found_images": len(media_urls),
                                "urls": media_urls
                            }), ex=600)

                        if not data.get('has_more', 0): break
                        cursor = data.get('cursor', cursor + len(comments))
                        await asyncio.sleep(0.2)
            except Exception as e:
                logger.error(f"Parsing error: {e}")
        return media_urls
tiktok_handler = TikTokHandler()