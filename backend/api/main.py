import os
import json
import logging
from typing import Optional, List, Any, Dict
from datetime import datetime
from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import pymysql
from pymysql.cursors import DictCursor

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("fifo-api")

app = FastAPI(
    title="FIFO Backend Database API",
    description="API REST para persistencia y sincronización de datos de Fifo (MySQL 8.4 LTS)",
    version="1.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

DB_HOST = os.getenv("DB_HOST", "fifo-mysql")
DB_PORT = int(os.getenv("DB_PORT", "3306"))
DB_USER = os.getenv("DB_USER", "fifo_app")
DB_PASSWORD = os.getenv("DB_PASSWORD", "fifo_app_secret_2026")
DB_NAME = os.getenv("DB_NAME", "fifo_db")

def get_db_connection():
    try:
        return pymysql.connect(
            host=DB_HOST,
            port=DB_PORT,
            user=DB_USER,
            password=DB_PASSWORD,
            database=DB_NAME,
            charset="utf8mb4",
            cursorclass=DictCursor,
            autocommit=True
        )
    except Exception as e:
        logger.error(f"Error conectando a MySQL: {e}")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"No se pudo conectar a la base de datos MySQL: {str(e)}"
        )

# ── Modelos Pydantic ──────────────────────────────────────

class DemographicsUpdate(BaseModel):
    full_name: Optional[str] = None
    birth_date: Optional[str] = None
    birth_year: Optional[int] = None
    estimated_age: Optional[int] = None
    gender_identity: Optional[str] = None
    city: Optional[str] = None
    bio_ai: Optional[str] = None
    emergency_contact_name: Optional[str] = None
    emergency_contact_phone: Optional[str] = None

class TasteCreate(BaseModel):
    name: str
    category: Optional[str] = "general"

class TasteStoryCreate(BaseModel):
    id: Optional[str] = None
    title: str
    subtitle: str
    description: str
    tags: List[str] = Field(default_factory=list)
    icon_category: Optional[str] = "heart"
    learned_from: Optional[str] = "Conversación cotidiana con Fifo"

class ReminderCreate(BaseModel):
    id: Optional[str] = None
    title: str
    time_str: str
    repeat_rule: Optional[str] = "daily"
    category: Optional[str] = "medication"
    spoken_text: Optional[str] = ""

class MemoryCreate(BaseModel):
    id: Optional[str] = None
    emoji: Optional[str] = "⭐"
    title: str
    detail: str
    learned_date_label: Optional[str] = "Aprendido recientemente"

class SocialPostCreate(BaseModel):
    id: Optional[str] = None
    user_id: str
    author_name: str
    author_age: int
    category: Optional[str] = "Bienestar"
    content: str
    time_ago_label: Optional[str] = "Hoy"
    accent_color_hex: Optional[int] = 4281908728

class DeviceLocationUpdate(BaseModel):
    is_connected: bool
    last_connected_time: Optional[str] = "Ahora"
    last_known_latitude: Optional[float] = -33.4255
    last_known_longitude: Optional[float] = -70.6143
    last_known_address: Optional[str] = "En esta ubicación"
    last_known_room: Optional[str] = "Cerca del Living / Mesa de noche"
    signal_strength_rssi: Optional[int] = -60
    is_beeping: Optional[bool] = False

class RecallQuery(BaseModel):
    query: str

# ── Endpoints ──────────────────────────────────────────────

@app.get("/health")
def health():
    try:
        conn = get_db_connection()
        with conn.cursor() as cur:
            cur.execute("SELECT 1 AS ok")
            row = cur.fetchone()
        conn.close()
        return {"status": "ok", "db": "connected", "mysql_response": row["ok"]}
    except Exception as e:
        return {"status": "error", "db": str(e)}

# 1. Perfil de Usuario
@app.get("/api/users/{user_id}/profile")
def get_user_profile(user_id: str):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            sql = """
                SELECT u.id, u.full_name, u.email, u.birth_date, u.birth_year, u.estimated_age,
                       u.gender_identity, u.city, u.avatar_url, u.avatar_bg_hex, u.short_quote,
                       p.bio_ai, p.emergency_contact_name, p.emergency_contact_phone,
                       p.preferred_address, p.continuous_listening_enabled
                FROM users u
                LEFT JOIN user_profiles p ON p.user_id = u.id
                WHERE u.id = %s
            """
            cur.execute(sql, (user_id,))
            row = cur.fetchone()
            if not row:
                raise HTTPException(status_code=404, detail="Usuario no encontrado")
            return row
    finally:
        conn.close()

@app.put("/api/users/{user_id}/profile")
def update_user_profile(user_id: str, data: DemographicsUpdate):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            # Actualizar demographics en users
            user_updates = []
            user_params = []
            if data.full_name is not None:
                user_updates.append("full_name = %s")
                user_params.append(data.full_name)
            if data.birth_date is not None:
                user_updates.append("birth_date = %s")
                user_params.append(data.birth_date)
            if data.birth_year is not None:
                user_updates.append("birth_year = %s")
                user_params.append(data.birth_year)
            if data.estimated_age is not None:
                user_updates.append("estimated_age = %s")
                user_params.append(data.estimated_age)
            if data.gender_identity is not None:
                user_updates.append("gender_identity = %s")
                user_params.append(data.gender_identity)
            if data.city is not None:
                user_updates.append("city = %s")
                user_params.append(data.city)

            if user_updates:
                user_params.append(user_id)
                cur.execute(f"UPDATE users SET {', '.join(user_updates)} WHERE id = %s", user_params)

            # Actualizar perfil en user_profiles
            profile_updates = []
            profile_params = []
            if data.bio_ai is not None:
                profile_updates.append("bio_ai = %s")
                profile_params.append(data.bio_ai)
            if data.emergency_contact_name is not None:
                profile_updates.append("emergency_contact_name = %s")
                profile_params.append(data.emergency_contact_name)
            if data.emergency_contact_phone is not None:
                profile_updates.append("emergency_contact_phone = %s")
                profile_params.append(data.emergency_contact_phone)

            if profile_updates:
                profile_params.append(user_id)
                cur.execute(f"UPDATE user_profiles SET {', '.join(profile_updates)} WHERE user_id = %s", profile_params)

            return {"success": True, "message": "Perfil actualizado correctamente"}
    finally:
        conn.close()

# 2. Gustos e Intereses
@app.get("/api/users/{user_id}/tastes")
def get_tastes(user_id: str):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, name, category, learned_at FROM tastes WHERE user_id = %s AND is_active = 1", (user_id,))
            return cur.fetchall()
    finally:
        conn.close()

@app.post("/api/users/{user_id}/tastes")
def add_taste(user_id: str, data: TasteCreate):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            import uuid
            taste_id = f"t_{uuid.uuid4().hex[:8]}"
            sql = """
                INSERT INTO tastes (id, user_id, name, category, is_active)
                VALUES (%s, %s, %s, %s, 1)
                ON DUPLICATE KEY UPDATE is_active = 1, category = VALUES(category)
            """
            cur.execute(sql, (taste_id, user_id, data.name.strip(), data.category))
            return {"success": True, "taste_id": taste_id, "name": data.name}
    finally:
        conn.close()

@app.delete("/api/users/{user_id}/tastes/{taste_name}")
def remove_taste(user_id: str, taste_name: str):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("UPDATE tastes SET is_active = 0 WHERE user_id = %s AND name = %s", (user_id, taste_name))
            return {"success": True, "removed": taste_name}
    finally:
        conn.close()

# 3. Historias de Gustos (Mini-Blogs)
@app.get("/api/users/{user_id}/stories")
def get_stories(user_id: str):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM taste_stories WHERE user_id = %s ORDER BY created_at DESC", (user_id,))
            rows = cur.fetchall()
            for r in rows:
                if isinstance(r.get("tags"), str):
                    try:
                        r["tags"] = json.loads(r["tags"])
                    except Exception:
                        r["tags"] = []
            return rows
    finally:
        conn.close()

@app.post("/api/users/{user_id}/stories")
def create_story(user_id: str, story: TasteStoryCreate):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            import uuid
            story_id = story.id or f"story_{uuid.uuid4().hex[:8]}"
            sql = """
                INSERT INTO taste_stories (id, user_id, title, subtitle, description, tags, icon_category, learned_from)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """
            cur.execute(sql, (
                story_id, user_id, story.title, story.subtitle, story.description,
                json.dumps(story.tags, ensure_ascii=False), story.icon_category, story.learned_from
            ))
            return {"success": True, "story_id": story_id}
    finally:
        conn.close()

# 4. Recordatorios y Alarmas
@app.get("/api/users/{user_id}/reminders")
def get_reminders(user_id: str):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM reminders_and_events WHERE user_id = %s ORDER BY is_completed ASC, time_str ASC", (user_id,))
            return cur.fetchall()
    finally:
        conn.close()

@app.post("/api/users/{user_id}/reminders")
def create_reminder(user_id: str, rem: ReminderCreate):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            import uuid
            rem_id = rem.id or f"rem_{uuid.uuid4().hex[:8]}"
            spoken = rem.spoken_text or f"Lucía, es hora de su recordatorio: {rem.title}"
            sql = """
                INSERT INTO reminders_and_events (id, user_id, title, time_str, repeat_rule, category, is_completed, spoken_text)
                VALUES (%s, %s, %s, %s, %s, %s, 0, %s)
            """
            cur.execute(sql, (rem_id, user_id, rem.title, rem.time_str, rem.repeat_rule, rem.category, spoken))
            return {"success": True, "reminder_id": rem_id}
    finally:
        conn.close()

@app.put("/api/users/{user_id}/reminders/{reminder_id}/complete")
def complete_reminder(user_id: str, reminder_id: str):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("UPDATE reminders_and_events SET is_completed = 1 WHERE user_id = %s AND id = %s", (user_id, reminder_id))
            return {"success": True, "completed": reminder_id}
    finally:
        conn.close()

# 5. Recuerdos
@app.get("/api/users/{user_id}/memories")
def get_memories(user_id: str):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM memories WHERE user_id = %s ORDER BY created_at DESC", (user_id,))
            return cur.fetchall()
    finally:
        conn.close()

@app.post("/api/users/{user_id}/memories")
def add_memory(user_id: str, mem: MemoryCreate):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            import uuid
            mem_id = mem.id or f"mem_{uuid.uuid4().hex[:8]}"
            sql = """
                INSERT INTO memories (id, user_id, emoji, title, detail, learned_date_label)
                VALUES (%s, %s, %s, %s, %s, %s)
            """
            cur.execute(sql, (mem_id, user_id, mem.emoji, mem.title, mem.detail, mem.learned_date_label))
            return {"success": True, "memory_id": mem_id}
    finally:
        conn.close()

# 6. Red Social "Fifo Amigos"
@app.get("/api/social/posts")
def get_social_posts():
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM social_posts ORDER BY created_at DESC LIMIT 30")
            return cur.fetchall()
    finally:
        conn.close()

@app.post("/api/social/posts")
def create_social_post(post: SocialPostCreate):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            import uuid
            post_id = post.id or f"post_{uuid.uuid4().hex[:8]}"
            sql = """
                INSERT INTO social_posts (id, user_id, author_name, author_age, category, content, time_ago_label, accent_color_hex)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """
            cur.execute(sql, (
                post_id, post.user_id, post.author_name, post.author_age, post.category,
                post.content, post.time_ago_label, post.accent_color_hex
            ))
            return {"success": True, "post_id": post_id}
    finally:
        conn.close()

# 7. Localizador de Dispositivo (Robot ESP32)
@app.get("/api/device/{device_id}/location")
def get_device_location(device_id: str):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM device_locations WHERE device_id = %s", (device_id,))
            row = cur.fetchone()
            if not row:
                raise HTTPException(status_code=404, detail="Dispositivo no encontrado")
            return row
    finally:
        conn.close()

@app.put("/api/device/{device_id}/location")
def update_device_location(device_id: str, loc: DeviceLocationUpdate):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            sql = """
                INSERT INTO device_locations (
                    device_id, user_id, is_connected, last_connected_time, last_known_latitude,
                    last_known_longitude, last_known_address, last_known_room, signal_strength_rssi, is_beeping
                ) VALUES (%s, 'usr_lucia_01', %s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    is_connected = VALUES(is_connected),
                    last_connected_time = VALUES(last_connected_time),
                    last_known_latitude = VALUES(last_known_latitude),
                    last_known_longitude = VALUES(last_known_longitude),
                    last_known_address = VALUES(last_known_address),
                    last_known_room = VALUES(last_known_room),
                    signal_strength_rssi = VALUES(signal_strength_rssi),
                    is_beeping = VALUES(is_beeping)
            """
            cur.execute(sql, (
                device_id, int(loc.is_connected), loc.last_connected_time, loc.last_known_latitude,
                loc.last_known_longitude, loc.last_known_address, loc.last_known_room,
                loc.signal_strength_rssi, int(loc.is_beeping or False)
            ))
            return {"success": True, "device_id": device_id}
    finally:
        conn.close()

# 8. Procedimiento Almacenado de Memoria Profunda: sp_recall_past_context
@app.post("/api/users/{user_id}/recall")
def recall_past_context(user_id: str, req: RecallQuery):
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.callproc("sp_recall_past_context", (user_id, req.query))
            results = cur.fetchall()
            return {"query": req.query, "results": results}
    except Exception as e:
        logger.warning(f"Error ejecutando sp_recall_past_context: {e}")
        return {"query": req.query, "results": [], "note": "No se encontraron coincidencias o tabla vacía"}
    finally:
        conn.close()
