import json
import os
import uuid
import re
import urllib.request
import urllib.error
from typing import List, Optional, Any, Dict
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import mysql.connector
from mysql.connector import Error as MySQLError

app = FastAPI(
    title="Fifo Voice Pipeline - Lightweight REST API",
    description="Microservicio REST para conectar la aplicación Android Fifo, los Skills de voz (Claude / Gemini / Groq) y el contenedor MySQL 8.4.",
    version="1.1.0"
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
DB_NAME = os.getenv("DB_NAME", "fifo_db")
DB_USER = os.getenv("DB_USER", "fifo_app")
DB_PASSWORD = os.getenv("DB_PASSWORD", "fifo_app_secret_2026")

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")


def get_db_connection():
    return mysql.connector.connect(
        host=DB_HOST,
        port=DB_PORT,
        database=DB_NAME,
        user=DB_USER,
        password=DB_PASSWORD,
        charset="utf8mb4",
        collation="utf8mb4_es_0900_ai_ci",
        use_unicode=True,
        autocommit=True
    )


def ensure_schema_migrations():
    """Garantiza de forma idempotente que las tablas y columnas necesarias existan."""
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        cursor.execute("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = %s AND TABLE_NAME = 'user_contacts_relational' AND COLUMN_NAME = 'linked_conversation_ids'
        """, (DB_NAME,))
        has_col = cursor.fetchone()[0] > 0
        if not has_col:
            cursor.execute("ALTER TABLE user_contacts_relational ADD COLUMN linked_conversation_ids JSON NULL DEFAULT NULL")
            print("[SchemaMigration] Columna 'linked_conversation_ids' creada con éxito.")

        cursor.execute("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = %s AND TABLE_NAME = 'user_profiles' AND COLUMN_NAME = 'interest_tags'
        """, (DB_NAME,))
        has_tags = cursor.fetchone()[0] > 0
        if not has_tags:
            cursor.execute("ALTER TABLE user_profiles ADD COLUMN interest_tags JSON NULL DEFAULT NULL")
            print("[SchemaMigration] Columna 'interest_tags' en user_profiles creada con éxito.")

        cursor.execute("""
            CREATE TABLE IF NOT EXISTS contact_conversation_mentions (
                id VARCHAR(64) NOT NULL PRIMARY KEY,
                user_id VARCHAR(64) NOT NULL,
                contact_id VARCHAR(64) NOT NULL,
                conversation_id VARCHAR(64) NOT NULL,
                snippet_mention TEXT NULL DEFAULT NULL,
                mentioned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_contact_conv (contact_id, conversation_id),
                INDEX idx_conv_user (user_id, conversation_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;
        """)
        cursor.close()
        conn.close()
    except Exception as e:
        print(f"[SchemaMigration] Aviso en migración defensiva: {e}")


@app.on_event("startup")
def on_startup():
    ensure_schema_migrations()


def call_ai_engine(prompt: str, system_prompt: str, user_groq_key: Optional[str] = None) -> Optional[Dict[str, Any]]:
    """Invoca un modelo LLM analítico libre de costo (Groq Qwen/Llama o Gemini) para conectar patrones."""
    effective_groq_key = (user_groq_key or "").strip() or GROQ_API_KEY.strip()
    if effective_groq_key:
        models = ["llama-3.3-70b-versatile", "qwen/qwen3.8-27b", "llama-3.1-8b-instant"]
        for m in models:
            try:
                payload = {
                    "model": m,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": prompt}
                    ],
                    "temperature": 0.2,
                    "response_format": {"type": "json_object"}
                }
                req = urllib.request.Request(
                    "https://api.groq.com/openai/v1/chat/completions",
                    data=json.dumps(payload).encode("utf-8"),
                    headers={
                        "Authorization": f"Bearer {effective_groq_key}",
                        "Content-Type": "application/json",
                        "User-Agent": "FifoBackend/1.1"
                    }
                )
                with urllib.request.urlopen(req, timeout=18) as resp:
                    resp_data = json.loads(resp.read().decode("utf-8"))
                    content_str = resp_data["choices"][0]["message"]["content"]
                    return json.loads(content_str)
            except Exception as e:
                print(f"[AIEngine] Aviso con Groq {m}: {e}")
                continue

    effective_gemini_key = GEMINI_API_KEY.strip()
    if effective_gemini_key:
        try:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={effective_gemini_key}"
            payload = {
                "systemInstruction": {"parts": [{"text": system_prompt}]},
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {
                    "temperature": 0.2,
                    "responseMimeType": "application/json"
                }
            }
            req = urllib.request.Request(
                url,
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "User-Agent": "FifoBackend/1.1"
                }
            )
            with urllib.request.urlopen(req, timeout=18) as resp:
                resp_data = json.loads(resp.read().decode("utf-8"))
                text_content = resp_data["candidates"][0]["content"]["parts"][0]["text"]
                return json.loads(text_content)
        except Exception as e:
            print(f"[AIEngine] Aviso con Gemini: {e}")

    return None


def parse_json_col(val: Any, default: Any = None):
    if val is None:
        return default if default is not None else []
    if isinstance(val, (list, dict)):
        return val
    try:
        return json.loads(val)
    except Exception:
        return default if default is not None else []


# -----------------------------------------------------------------------------
# Modelos Pydantic para Peticiones de Skills y App Android
# -----------------------------------------------------------------------------
class DemographicsUpdateRequest(BaseModel):
    full_name: Optional[str] = None
    birth_date: Optional[str] = None
    birth_year: Optional[int] = None
    gender_identity: Optional[str] = None
    city: Optional[str] = None


class BioUpdateRequest(BaseModel):
    bio_ai: str


class TasteActionRequest(BaseModel):
    action: str = Field(default="add", description="add o remove")
    taste_name: str
    category: str = "general"


class TasteStoryCreateRequest(BaseModel):
    title: str
    subtitle: str
    description: str
    tags: List[str] = []
    icon_category: str = "heart"
    is_sensitive: bool = False
    explicit_consent: bool = False
    privacy_level: str = "public_profile"
    learned_from: str = "Conversación con Fifo"


class SocialPostCreateRequest(BaseModel):
    content: str
    category: str = "Bienestar"
    is_sensitive: bool = False
    explicit_consent: bool = False
    accent_color_hex: int = 0xFF38BDF8


class ReminderCreateRequest(BaseModel):
    title: str
    time_str: str
    category: str = "medication"
    repeat_rule: str = "daily"
    spoken_text: Optional[str] = None


class ReminderUpdateRequest(BaseModel):
    title: Optional[str] = None
    time_str: Optional[str] = None
    category: Optional[str] = None
    repeat_rule: Optional[str] = None
    is_completed: Optional[bool] = None
    spoken_text: Optional[str] = None


class InterestTagsUpdateRequest(BaseModel):
    interest_tags: List[str]


class MemoryCreateRequest(BaseModel):
    emoji: str = "⭐"
    title: str
    detail: str
    learned_date_label: str = "Aprendido recién"


class RelationalContactCreateRequest(BaseModel):
    contact_name: str
    phone_number: Optional[str] = None
    relationship_role: str = "Contacto del teléfono"
    closeness_score: float = Field(default=0.5, ge=0.0, le=1.0)
    trust_tier: int = Field(default=2, ge=1, le=4)
    emotional_valence: str = "afectuoso"
    contextual_memory: Optional[str] = None
    alias: Optional[str] = None
    linked_conversation_ids: Optional[List[str]] = None


class RelationalContactUpdateRequest(BaseModel):
    closeness_score: Optional[float] = Field(default=None, ge=0.0, le=1.0)
    relationship_role: Optional[str] = None
    phone_number: Optional[str] = None
    emotional_valence: Optional[str] = None
    contextual_memory: Optional[str] = None
    alias: Optional[str] = None
    linked_conversation_ids: Optional[List[str]] = None


class DailyRoutineCreateRequest(BaseModel):
    routine_name: str
    category: str = "medication"
    time_anchor: str = "morning"
    typical_time_str: Optional[str] = None
    frequency_rule: str = "daily"
    confidence_score: float = Field(default=0.8, ge=0.0, le=1.0)
    notes: Optional[str] = None


class DailyRoutineUpdateRequest(BaseModel):
    confidence_score: Optional[float] = None
    typical_time_str: Optional[str] = None
    notes: Optional[str] = None
    is_active: Optional[bool] = None


class ConversationSaveRequest(BaseModel):
    title: str
    summary: str
    primary_tag: str = "Conversación"
    duration_seconds: int = 180
    detected_mood: str = "tranquilo"
    key_topics: List[str] = []
    named_entities: List[str] = []
    turns: List[Dict[str, Any]] = []
    full_transcript: Optional[str] = None


class ConversationAnalyzeAndConsolidateRequest(BaseModel):
    turns: List[Dict[str, Any]]
    session_id: Optional[str] = None
    duration_seconds: int = 180
    primary_tag: str = "Conversación"
    mentioned_persons: Optional[List[Dict[str, Any]]] = None
    groq_api_key: Optional[str] = None


class RecallContextRequest(BaseModel):
    query: str


class DeviceLocationUpdateRequest(BaseModel):
    is_connected: Optional[bool] = None
    last_connected_time: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    address: Optional[str] = None
    room_hint: Optional[str] = None
    rssi: Optional[int] = None
    is_beeping: Optional[bool] = None



# -----------------------------------------------------------------------------
# Endpoints de Salud y Listado General de Usuarios
# -----------------------------------------------------------------------------
@app.get("/health")
def health_check():
    try:
        conn = get_db_connection()
        cursor = conn.cursor(dictionary=True)
        cursor.execute("SELECT COUNT(*) AS total_users FROM users")
        users_cnt = cursor.fetchone()["total_users"]
        cursor.execute("SELECT COUNT(*) AS total_posts FROM social_posts")
        posts_cnt = cursor.fetchone()["total_posts"]
        cursor.execute("SELECT COUNT(*) AS total_fragments FROM conversation_fragments")
        frags_cnt = cursor.fetchone()["total_fragments"]
        cursor.close()
        conn.close()
        return {
            "status": "ok",
            "database": DB_NAME,
            "counts": {
                "users": users_cnt,
                "social_posts": posts_cnt,
                "conversation_fragments": frags_cnt
            }
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Database connection error: {str(e)}")


@app.get("/users")
def list_all_users():
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("""
        SELECT u.*, p.bio_ai, p.emergency_contact_name, p.emergency_contact_phone, p.preferred_address
        FROM users u
        LEFT JOIN user_profiles p ON p.user_id = u.id
        ORDER BY u.id ASC
    """)
    users = cursor.fetchall()
    for u in users:
        cursor.execute("SELECT name FROM tastes WHERE user_id = %s AND is_active = 1", (u["id"],))
        u["tastes"] = [r["name"] for r in cursor.fetchall()]
    cursor.close()
    conn.close()
    return {"users": users}


# -----------------------------------------------------------------------------
# Endpoint Maestro de Sincronización para FifoDataRepository.kt (Android)
# -----------------------------------------------------------------------------
@app.get("/users/{user_id}/sync")
def sync_user_bundle(user_id: str):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    # 1. Usuario y perfil
    cursor.execute("""
        SELECT u.*, p.bio_ai, p.emergency_contact_name, p.emergency_contact_phone,
               p.preferred_address, p.continuous_listening_enabled, p.interest_tags
        FROM users u
        LEFT JOIN user_profiles p ON p.user_id = u.id
        WHERE u.id = %s
    """, (user_id,))
    user = cursor.fetchone()
    if not user:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail=f"Usuario '{user_id}' no encontrado")

    # 2. Gustos activos
    cursor.execute("""
        SELECT id, name, category FROM tastes
        WHERE user_id = %s AND is_active = 1
        ORDER BY learned_at ASC
    """, (user_id,))
    tastes = [row["name"] for row in cursor.fetchall()]

    # 3. Historias detalladas de gustos (taste_stories)
    cursor.execute("""
        SELECT id, title, subtitle, description, tags, icon_category, learned_from
        FROM taste_stories
        WHERE user_id = %s
        ORDER BY created_at DESC
    """, (user_id,))
    taste_stories = []
    for row in cursor.fetchall():
        row["tags"] = parse_json_col(row["tags"], [])
        taste_stories.append(row)

    # 4. Publicaciones sociales del usuario y feed general de Fifo Amigos
    cursor.execute("""
        SELECT id, user_id, author_name AS author, author_age AS age, relation_label AS relation,
               category, time_ago_label AS timeAgo, content, likes_count AS likes,
               comments_count AS commentsCount, accent_color_hex AS accentColorHex
        FROM social_posts
        ORDER BY created_at DESC
    """)
    all_social_posts = cursor.fetchall()
    user_social_posts = [p for p in all_social_posts if p["user_id"] == user_id]

    # 5. Recuerdos puntuales (memories)
    cursor.execute("""
        SELECT id, emoji, title, detail, learned_date_label AS learnedDate
        FROM memories
        WHERE user_id = %s
        ORDER BY created_at DESC
    """, (user_id,))
    memories = cursor.fetchall()

    # 6. Recordatorios y alarmas
    cursor.execute("""
        SELECT id, title, time_str AS timeStr, category, is_completed AS isCompleted, repeat_rule, spoken_text
        FROM reminders_and_events
        WHERE user_id = %s
        ORDER BY is_completed ASC, time_str ASC
    """, (user_id,))
    reminders = []
    for r in cursor.fetchall():
        r["isCompleted"] = bool(r["isCompleted"])
        reminders.append(r)

    # 7. Conversaciones pasadas resumidas (UI)
    cursor.execute("""
        SELECT id, title, date_label AS date, duration_label AS duration,
               summary, topic_tag AS tag, icon_name AS iconName
        FROM past_conversations
        WHERE user_id = %s
        ORDER BY recorded_at DESC
    """, (user_id,))
    past_conversations = cursor.fetchall()

    # 8. Fragmentos compactos para el LLM (conversation_fragments)
    cursor.execute("""
        SELECT id, server_conversation_id AS serverConversationId,
               key_topics AS keyTopics, named_entities AS namedEntities,
               detected_mood AS detectedMood, compact_summary AS compactSummary,
               primary_tag AS primaryTag, duration_seconds AS durationSeconds,
               CAST(recorded_at AS CHAR) AS recordedAt
        FROM conversation_fragments
        WHERE user_id = %s
        ORDER BY recorded_at DESC
    """, (user_id,))
    fragments = []
    for f in cursor.fetchall():
        f["keyTopics"] = parse_json_col(f["keyTopics"], [])
        f["namedEntities"] = parse_json_col(f["namedEntities"], [])
        fragments.append(f)

    # 9. Ubicación del dispositivo físico ESP32
    cursor.execute("""
        SELECT device_id AS deviceId, is_connected AS isConnected,
               last_connected_time AS lastConnectedTime,
               last_known_latitude AS lastKnownLatitude,
               last_known_longitude AS lastKnownLongitude,
               last_known_address AS lastKnownAddress,
               last_known_room AS lastKnownRoom,
               signal_strength_rssi AS signalStrengthRssi,
               is_beeping AS isBeeping
        FROM device_locations
        WHERE user_id = %s
        LIMIT 1
    """, (user_id,))
    device_loc = cursor.fetchone()
    if device_loc:
        device_loc["isConnected"] = bool(device_loc["isConnected"])
        device_loc["isBeeping"] = bool(device_loc["isBeeping"])

    # 10. Comunidad: Perfiles de otros usuarios y Actividades
    cursor.execute("""
        SELECT u.id, u.full_name, u.estimated_age AS age, u.short_quote AS quote,
               u.avatar_bg_hex AS avatarBgHex,
               uf.status AS friendshipStatus, uf.shared_tastes_count AS sharedTastesCount
        FROM users u
        LEFT JOIN user_friendships uf ON uf.user_id = %s AND uf.friend_user_id = u.id
        WHERE u.id != %s
        ORDER BY u.id ASC
    """, (user_id, user_id))
    community_profiles = cursor.fetchall()
    for cp in community_profiles:
        cursor.execute("SELECT name FROM tastes WHERE user_id = %s AND is_active = 1", (cp["id"],))
        cp["tags"] = [t["name"] for t in cursor.fetchall()]

    cursor.execute("""
        SELECT a.id, a.title, a.spots_label AS spots, a.date_label AS date,
               a.location_name AS location, a.distance_label AS distance, a.icon_category AS iconCategory,
               CASE WHEN ap.status = 'joined' THEN 1 ELSE 0 END AS isJoined
        FROM community_activities a
        LEFT JOIN activity_participants ap ON ap.activity_id = a.id AND ap.user_id = %s
        ORDER BY a.id ASC
    """, (user_id,))
    activities = []
    for act in cursor.fetchall():
        act["isJoined"] = bool(act["isJoined"])
        activities.append(act)

    # 11. Grafo Social & Contactos Relacionales con Puntuación de Cercanía
    cursor.execute("""
        SELECT id, contact_name AS contactName, phone_number AS phoneNumber,
               relationship_role AS relationshipRole, closeness_score AS closenessScore,
               trust_tier AS trustTier, emotional_valence AS emotionalValence,
               contextual_memory AS contextualMemory, mention_count AS mentionCount,
               linked_conversation_ids AS linkedConversationIds,
               CAST(last_mentioned_at AS CHAR) AS lastMentionedAt
        FROM user_contacts_relational
        WHERE user_id = %s
        ORDER BY closeness_score DESC, mention_count DESC
    """, (user_id,))
    relational_contacts = cursor.fetchall()
    for rc in relational_contacts:
        rc["linkedConversationIds"] = parse_json_col(rc.get("linkedConversationIds"), [])

    # 12. Hábitos y Patrones de Vida Diaria (Rutinas Circadianas)
    cursor.execute("""
        SELECT id, routine_name AS routineName, category, time_anchor AS timeAnchor,
        typical_time_str AS typicalTimeStr, frequency_rule AS frequencyRule,
        confidence_score AS confidenceScore, notes, is_active AS isActive
        FROM user_daily_routines
        WHERE user_id = %s AND is_active = 1
        ORDER BY typical_time_str ASC, confidence_score DESC
    """, (user_id,))
    daily_routines = []
    for r in cursor.fetchall():
        r["isActive"] = bool(r["isActive"])
        daily_routines.append(r)

    cursor.close()
    conn.close()

    return {
        "userProfile": {
            "id": user["id"],
            "fullName": user["full_name"],
            "email": user["email"],
            "birthDate": user["birth_date"],
            "birthYear": user["birth_year"],
            "estimatedAge": user["estimated_age"],
            "genderIdentity": user["gender_identity"],
            "city": user["city"],
            "bioAi": user["bio_ai"] or "",
            "emergencyContactName": user["emergency_contact_name"] or "",
            "emergencyContactPhone": user["emergency_contact_phone"] or "",
            "preferredAddress": user["preferred_address"] or "",
            "interestTags": parse_json_col(user.get("interest_tags"), [])
        },
        "tastes": tastes,
        "tasteStories": taste_stories,
        "userSocialPosts": user_social_posts,
        "communityFeedPosts": all_social_posts,
        "memories": memories,
        "reminders": reminders,
        "pastConversations": past_conversations,
        "conversationFragments": fragments,
        "deviceLocation": device_loc,
        "communityProfiles": community_profiles,
        "activities": activities,
        "relationalContacts": relational_contacts,
        "dailyRoutines": daily_routines
    }



# -----------------------------------------------------------------------------
# Endpoints de Mutaciones (Skills de Fifo y Acciones de Usuario)
# -----------------------------------------------------------------------------
@app.patch("/users/{user_id}/demographics")
def update_demographics(user_id: str, req: DemographicsUpdateRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM users WHERE id = %s", (user_id,))
    current = cursor.fetchone()
    if not current:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail="Usuario no encontrado")

    new_name = req.full_name if req.full_name else current["full_name"]
    new_bdate = req.birth_date if req.birth_date else current["birth_date"]
    new_byear = req.birth_year if req.birth_year else current["birth_year"]
    new_age = (2026 - req.birth_year) if req.birth_year else current["estimated_age"]
    new_gender = req.gender_identity if req.gender_identity else current["gender_identity"]
    new_city = req.city if req.city else current["city"]

    cursor.execute("""
        UPDATE users
        SET full_name = %s, birth_date = %s, birth_year = %s,
            estimated_age = %s, gender_identity = %s, city = %s
        WHERE id = %s
    """, (new_name, new_bdate, new_byear, new_age, new_gender, new_city, user_id))
    cursor.close()
    conn.close()
    return {"status": "updated", "user_id": user_id, "estimated_age": new_age}


@app.put("/users/{user_id}/bio")
def update_bio(user_id: str, req: BioUpdateRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT INTO user_profiles (user_id, bio_ai)
        VALUES (%s, %s)
        ON DUPLICATE KEY UPDATE bio_ai = VALUES(bio_ai)
    """, (user_id, req.bio_ai))
    cursor.close()
    conn.close()
    return {"status": "updated", "user_id": user_id}


@app.post("/users/{user_id}/tastes")
def manage_tastes(user_id: str, req: TasteActionRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    if req.action.lower() == "remove":
        cursor.execute("""
            UPDATE tastes SET is_active = 0
            WHERE user_id = %s AND LOWER(name) = LOWER(%s)
        """, (user_id, req.taste_name.strip()))
        affected = cursor.rowcount
        cursor.close()
        conn.close()
        return {"status": "removed", "taste": req.taste_name, "affected": affected}
    else:
        taste_id = f"tst_{uuid.uuid4().hex[:8]}"
        cursor.execute("""
            INSERT INTO tastes (id, user_id, name, category, is_active)
            VALUES (%s, %s, %s, %s, 1)
            ON DUPLICATE KEY UPDATE is_active = 1, category = VALUES(category)
        """, (taste_id, user_id, req.taste_name.strip(), req.category))
        cursor.close()
        conn.close()
        return {"status": "added", "id": taste_id, "taste": req.taste_name}


@app.post("/users/{user_id}/taste-stories")
def create_taste_story(user_id: str, req: TasteStoryCreateRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    story_id = f"t_{uuid.uuid4().hex[:8]}"
    try:
        cursor.execute("""
            INSERT INTO taste_stories (
                id, user_id, title, subtitle, description, tags,
                icon_category, is_sensitive, explicit_consent, privacy_level, learned_from
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """, (
            story_id, user_id, req.title, req.subtitle, req.description,
            json.dumps(req.tags, ensure_ascii=False), req.icon_category,
            1 if req.is_sensitive else 0,
            1 if req.explicit_consent else 0,
            req.privacy_level, req.learned_from
        ))
    except MySQLError as e:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=400, detail=str(e))
    cursor.close()
    conn.close()
    return {"status": "created", "id": story_id}


@app.post("/users/{user_id}/social-posts")
def publish_social_post(user_id: str, req: SocialPostCreateRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT full_name, estimated_age FROM users WHERE id = %s", (user_id,))
    u = cursor.fetchone()
    if not u:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail="Usuario no encontrado")

    first_name = u["full_name"].split(" ")[0]
    post_id = f"post_{uuid.uuid4().hex[:8]}"
    try:
        cursor.execute("""
            INSERT INTO social_posts (
                id, user_id, author_name, author_age, relation_label,
                category, content, time_ago_label, is_sensitive, explicit_consent,
                likes_count, comments_count, accent_color_hex
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, 'Recién publicado', %s, %s, 1, 0, %s)
        """, (
            post_id, user_id, first_name, u["estimated_age"],
            f"Comunidad Fifo · {req.category}", req.category, req.content,
            1 if req.is_sensitive else 0,
            1 if req.explicit_consent else 0,
            req.accent_color_hex
        ))
    except MySQLError as e:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=400, detail=str(e))
    cursor.close()
    conn.close()
    return {"status": "published", "id": post_id, "author": first_name}


@app.post("/users/{user_id}/reminders")
def create_reminder(user_id: str, req: ReminderCreateRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT full_name FROM users WHERE id = %s", (user_id,))
    u = cursor.fetchone()
    first_name = u["full_name"].split(" ")[0] if u else "Amiga"
    spoken = req.spoken_text or f"{first_name}, son las {req.time_str}: le recuerdo {req.title}."
    rem_id = f"rem_{uuid.uuid4().hex[:8]}"
    cursor.execute("""
        INSERT INTO reminders_and_events (
            id, user_id, title, time_str, repeat_rule, category, is_completed, spoken_text
        ) VALUES (%s, %s, %s, %s, %s, %s, 0, %s)
    """, (rem_id, user_id, req.title, req.time_str, req.repeat_rule, req.category, spoken))
    cursor.close()
    conn.close()
    return {"status": "scheduled", "id": rem_id}


@app.patch("/reminders/{reminder_id}/complete")
def complete_reminder(reminder_id: str):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("UPDATE reminders_and_events SET is_completed = 1 WHERE id = %s", (reminder_id,))
    cursor.close()
    conn.close()
    return {"status": "completed", "id": reminder_id}


@app.patch("/reminders/{reminder_id}")
def update_reminder(reminder_id: str, req: ReminderUpdateRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    updates = []
    vals = []
    if req.title is not None:
        updates.append("title = %s")
        vals.append(req.title)
    if req.time_str is not None:
        updates.append("time_str = %s")
        vals.append(req.time_str)
    if req.category is not None:
        updates.append("category = %s")
        vals.append(req.category)
    if req.repeat_rule is not None:
        updates.append("repeat_rule = %s")
        vals.append(req.repeat_rule)
    if req.is_completed is not None:
        updates.append("is_completed = %s")
        vals.append(1 if req.is_completed else 0)
    if req.spoken_text is not None:
        updates.append("spoken_text = %s")
        vals.append(req.spoken_text)

    if updates:
        vals.append(reminder_id)
        cursor.execute(f"UPDATE reminders_and_events SET {', '.join(updates)} WHERE id = %s", vals)

    cursor.close()
    conn.close()
    return {"status": "updated", "id": reminder_id}


@app.delete("/reminders/{reminder_id}")
def delete_reminder(reminder_id: str):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("DELETE FROM reminders_and_events WHERE id = %s", (reminder_id,))
    cursor.close()
    conn.close()
    return {"status": "deleted", "id": reminder_id}


@app.put("/users/{user_id}/interest-tags")
def update_user_interest_tags(user_id: str, req: InterestTagsUpdateRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT INTO user_profiles (user_id, interest_tags)
        VALUES (%s, %s)
        ON DUPLICATE KEY UPDATE interest_tags = VALUES(interest_tags)
    """, (user_id, json.dumps(req.interest_tags, ensure_ascii=False)))
    cursor.close()
    conn.close()
    return {"status": "updated", "interestTags": req.interest_tags}


@app.post("/users/{user_id}/memories")
def create_memory(user_id: str, req: MemoryCreateRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    mem_id = f"m_{uuid.uuid4().hex[:8]}"
    cursor.execute("""
        INSERT INTO memories (id, user_id, emoji, title, detail, learned_date_label)
        VALUES (%s, %s, %s, %s, %s, %s)
    """, (mem_id, user_id, req.emoji, req.title, req.detail, req.learned_date_label))
    cursor.close()
    conn.close()
    return {"status": "saved", "id": mem_id}


@app.post("/users/{user_id}/conversations")
def save_conversation_dual_layer(user_id: str, req: ConversationSaveRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    srv_id = f"srv_conv_{uuid.uuid4().hex[:8]}"
    frag_id = f"frag_{uuid.uuid4().hex[:8]}"
    past_id = f"c_{uuid.uuid4().hex[:8]}"
    transcript = req.full_transcript or req.summary
    mins = max(1, req.duration_seconds // 60)

    cursor.execute("""
        INSERT INTO conversations_full (
            id, user_id, duration_seconds, turns_json, full_transcript,
            extracted_topics, named_entities, sentiment_trend
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
    """, (
        srv_id, user_id, req.duration_seconds,
        json.dumps(req.turns, ensure_ascii=False),
        transcript,
        json.dumps(req.key_topics, ensure_ascii=False),
        json.dumps(req.named_entities, ensure_ascii=False),
        req.detected_mood
    ))

    cursor.execute("""
        INSERT INTO conversation_fragments (
            id, user_id, server_conversation_id, key_topics, named_entities,
            detected_mood, compact_summary, primary_tag, duration_seconds
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
    """, (
        frag_id, user_id, srv_id,
        json.dumps(req.key_topics, ensure_ascii=False),
        json.dumps(req.named_entities, ensure_ascii=False),
        req.detected_mood, req.summary, req.primary_tag, req.duration_seconds
    ))

    cursor.execute("""
        INSERT INTO past_conversations (
            id, user_id, title, date_label, duration_label, duration_seconds, summary, topic_tag
        ) VALUES (%s, %s, %s, 'Hoy', %s, %s, %s, %s)
    """, (
        past_id, user_id, req.title, f"{mins} min", req.duration_seconds, req.summary, req.primary_tag
    ))

    cursor.close()
    conn.close()
    return {
        "status": "saved",
        "server_conversation_id": srv_id,
        "fragment_id": frag_id,
        "past_conversation_id": past_id
    }


@app.post("/users/{user_id}/recall-context")
def recall_past_context(user_id: str, req: RecallContextRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    q = req.query.strip()
    q_lower = q.lower()
    like_pat = f"%{q}%"

    # 1. Búsqueda y Enlace en Grafo de Contactos Relacionales
    cursor.execute("""
        SELECT id, contact_name AS contactName, phone_number AS phoneNumber,
               relationship_role AS relationshipRole, closeness_score AS closenessScore,
               trust_tier AS trustTier, emotional_valence AS emotionalValence,
               contextual_memory AS contextualMemory, mention_count AS mentionCount,
               linked_conversation_ids AS linkedConversationIds
        FROM user_contacts_relational
        WHERE user_id = %s
    """, (user_id,))
    all_contacts = cursor.fetchall()

    matched_contact = None
    for c in all_contacts:
        c_name = (c["contactName"] or "").lower()
        c_role = (c["relationshipRole"] or "").lower()
        tokens = [w for w in (c_name + " " + c_role).split() if len(w) > 3]
        if (c_name and (c_name in q_lower or q_lower in c_name)) or any(t in q_lower for t in tokens):
            matched_contact = c
            break

    linked_conv_details = []
    contact_summary_block = ""

    if matched_contact:
        c_id = matched_contact["id"]
        c_name = matched_contact["contactName"]
        c_role = matched_contact["relationshipRole"]
        c_score = int(float(matched_contact["closenessScore"]) * 100)
        c_mem = matched_contact["contextualMemory"] or "Sin notas previas"

        # Obtener IDs de conversaciones enlazadas (campo JSON y tabla puente)
        linked_ids = parse_json_col(matched_contact["linkedConversationIds"], [])
        cursor.execute("""
            SELECT conversation_id, snippet_mention
            FROM contact_conversation_mentions
            WHERE contact_id = %s AND user_id = %s
        """, (c_id, user_id))
        for m in cursor.fetchall():
            if m["conversation_id"] not in linked_ids:
                linked_ids.append(m["conversation_id"])

        contact_summary_block = f"Contacto encontrado: {c_name} [{c_role}] (Cercanía: {c_score}%). Memoria inmediata: {c_mem}"

        # 2. Recuperar conversaciones completas (con turnos exactos y transcripción pesada)
        if linked_ids:
            fmt = ",".join(["%s"] * len(linked_ids))
            cursor.execute(f"""
                SELECT cfull.id AS server_conversation_id, cfull.duration_seconds,
                       cfull.turns_json, cfull.full_transcript, cfull.sentiment_trend,
                       CAST(cfull.created_at AS CHAR) AS recorded_at,
                       cf.compact_summary, cf.primary_tag, cf.key_topics
                FROM conversations_full cfull
                LEFT JOIN conversation_fragments cf ON cf.server_conversation_id = cfull.id
                WHERE cfull.id IN ({fmt}) AND cfull.user_id = %s
                ORDER BY cfull.created_at DESC
                LIMIT 5
            """, (*linked_ids, user_id))
            for row in cursor.fetchall():
                row["turns"] = parse_json_col(row["turns_json"], [])
                linked_conv_details.append(row)

    # 3. Búsqueda semántica / texto en fragmentos y transcripciones
    cursor.execute("""
        SELECT
            cf.id AS fragment_id,
            cf.primary_tag,
            cf.detected_mood,
            cf.compact_summary,
            cf.key_topics,
            cf.named_entities,
            cfull.full_transcript,
            cfull.turns_json,
            CAST(cf.recorded_at AS CHAR) AS recorded_at
        FROM conversation_fragments cf
        LEFT JOIN conversations_full cfull ON cfull.id = cf.server_conversation_id
        WHERE cf.user_id = %s
          AND (
              MATCH(cf.compact_summary, cf.primary_tag) AGAINST(%s IN NATURAL LANGUAGE MODE)
              OR cf.compact_summary LIKE %s
              OR (CAST(cf.key_topics AS CHAR) COLLATE utf8mb4_es_0900_ai_ci) LIKE %s
              OR (CAST(cf.named_entities AS CHAR) COLLATE utf8mb4_es_0900_ai_ci) LIKE %s
              OR (cfull.full_transcript IS NOT NULL AND MATCH(cfull.full_transcript) AGAINST(%s IN NATURAL LANGUAGE MODE))
              OR (cfull.full_transcript IS NOT NULL AND cfull.full_transcript LIKE %s)
          )
        ORDER BY cf.recorded_at DESC
        LIMIT 5
    """, (user_id, q, like_pat, like_pat, like_pat, q, like_pat))
    general_rows = cursor.fetchall()
    cursor.close()
    conn.close()

    excerpts = []
    entities = []

    if matched_contact:
        excerpts.append(contact_summary_block)
        entities.append(matched_contact["contactName"])
        entities.append(matched_contact["relationshipRole"])

    for r in linked_conv_details:
        if r.get("compact_summary") and r["compact_summary"] not in excerpts:
            excerpts.append(f"[{r.get('primary_tag', 'Charla')}]: {r['compact_summary']}")

    for r in general_rows:
        if r["compact_summary"] not in excerpts:
            excerpts.append(f"[{r['primary_tag']}]: {r['compact_summary']}")
        ents = parse_json_col(r["named_entities"], [])
        for e in ents:
            if e not in entities:
                entities.append(e)

    # 4. Sintetizar respuesta rica para Claude / Groq (incluyendo contexto inmediato + detalles pesados del server)
    if not excerpts and not linked_conv_details:
        synthesized = f"Revisé mis recuerdos y registros pasados pero no encontré datos sobre '{req.query}'."
    else:
        parts = []
        if contact_summary_block:
            parts.append(f"{contact_summary_block}.")

        if linked_conv_details:
            parts.append(f"He recuperado {len(linked_conv_details)} conversación(es) completa(s) asociadas a esta persona:")
            for lc in linked_conv_details:
                parts.append(f"• Fecha: {lc.get('recorded_at') or 'Reciente'} ({lc.get('primary_tag')}). Resumen: {lc.get('compact_summary')}")
                if lc.get("full_transcript"):
                    parts.append(f"  Transcripción servidor completa: \"{lc['full_transcript']}\"")
        elif general_rows:
            parts.append(f"Encontré {len(general_rows)} charla(s) relacionadas:")
            for gr in general_rows:
                parts.append(f"• [{gr['primary_tag']}]: {gr['compact_summary']}")
                if gr.get("full_transcript"):
                    parts.append(f"  Detalle: {gr['full_transcript']}")

        synthesized = "\n".join(parts)

    return {
        "relevantExcerpts": excerpts,
        "foundEntities": entities,
        "synthesizedContext": synthesized,
        "matchedContact": matched_contact,
        "linkedConversationsDetailed": linked_conv_details,
        "conversationsSearched": len(linked_conv_details) + len(general_rows),
        "rawMatches": general_rows
    }


@app.patch("/users/{user_id}/device-location")
def update_device_location(user_id: str, req: DeviceLocationUpdateRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM device_locations WHERE user_id = %s LIMIT 1", (user_id,))
    cur = cursor.fetchone()
    if not cur:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado para este usuario")

    is_conn = (1 if req.is_connected else 0) if req.is_connected is not None else cur["is_connected"]
    last_time = req.last_connected_time or ("Conectado ahora" if req.is_connected else cur["last_connected_time"])
    lat = req.latitude if req.latitude is not None else cur["last_known_latitude"]
    lon = req.longitude if req.longitude is not None else cur["last_known_longitude"]
    addr = req.address if req.address is not None else cur["last_known_address"]
    room = req.room_hint if req.room_hint is not None else cur["last_known_room"]
    rssi = req.rssi if req.rssi is not None else cur["signal_strength_rssi"]
    beep = (1 if req.is_beeping else 0) if req.is_beeping is not None else cur["is_beeping"]

    cursor.execute("""
        UPDATE device_locations
        SET is_connected = %s, last_connected_time = %s,
            last_known_latitude = %s, last_known_longitude = %s,
            last_known_address = %s, last_known_room = %s,
            signal_strength_rssi = %s, is_beeping = %s
        WHERE device_id = %s
    """, (is_conn, last_time, lat, lon, addr, room, rssi, beep, cur["device_id"]))
    cursor.close()
    conn.close()
    return {"status": "updated", "device_id": cur["device_id"]}


# -----------------------------------------------------------------------------
# Endpoints de Grafo Social & Contactos Relacionales (Closeness Scoring)
# -----------------------------------------------------------------------------
@app.get("/users/{user_id}/contacts-relational")
def list_user_relational_contacts(user_id: str):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("""
        SELECT id, contact_name AS contactName, phone_number AS phoneNumber,
               relationship_role AS relationshipRole, closeness_score AS closenessScore,
               trust_tier AS trustTier, emotional_valence AS emotionalValence,
               contextual_memory AS contextualMemory, mention_count AS mentionCount,
               linked_conversation_ids AS linkedConversationIds,
               CAST(last_mentioned_at AS CHAR) AS lastMentionedAt
        FROM user_contacts_relational
        WHERE user_id = %s
        ORDER BY closeness_score DESC, mention_count DESC
    """, (user_id,))
    contacts = []
    for row in cursor.fetchall():
        row["linkedConversationIds"] = parse_json_col(row["linkedConversationIds"], [])
        contacts.append(row)
    cursor.close()
    conn.close()
    return {"contacts": contacts}


@app.post("/users/{user_id}/contacts-relational")
def create_user_relational_contact(user_id: str, req: RelationalContactCreateRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    c_name = req.contact_name.strip()
    c_phone = req.phone_number.strip() if req.phone_number else None

    # Verificar si el contacto ya existe para evitar duplicados
    cursor.execute("""
        SELECT id, closeness_score, contextual_memory, relationship_role, phone_number, linked_conversation_ids
        FROM user_contacts_relational
        WHERE user_id = %s AND (
            LOWER(contact_name) = LOWER(%s)
            OR (%s IS NOT NULL AND phone_number = %s)
        ) LIMIT 1
    """, (user_id, c_name, c_phone, c_phone))
    existing = cursor.fetchone()

    req_linked = req.linked_conversation_ids or []

    if existing:
        contact_id = existing["id"]
        new_score = max(float(existing["closeness_score"]), float(req.closeness_score))
        new_phone = c_phone or existing["phone_number"]
        new_role = req.relationship_role if existing["relationship_role"] in ("Contacto del teléfono", "Desconocido") else existing["relationship_role"]
        new_memory = req.contextual_memory or existing["contextual_memory"]
        existing_linked = parse_json_col(existing["linked_conversation_ids"], [])
        merged_linked = list(dict.fromkeys(existing_linked + req_linked))

        cursor.execute("""
            UPDATE user_contacts_relational
            SET phone_number = %s, relationship_role = %s, closeness_score = %s,
                trust_tier = %s, emotional_valence = %s, contextual_memory = %s,
                linked_conversation_ids = %s
            WHERE id = %s
        """, (new_phone, new_role, new_score, req.trust_tier, req.emotional_valence, new_memory,
              json.dumps(merged_linked, ensure_ascii=False), contact_id))
        cursor.close()
        conn.close()
        return {"status": "updated", "id": contact_id, "linkedConversationIds": merged_linked}
    else:
        contact_id = f"ctc_rel_{uuid.uuid4().hex[:8]}"
        cursor.execute("""
            INSERT INTO user_contacts_relational (
                id, user_id, contact_name, phone_number, relationship_role,
                closeness_score, trust_tier, emotional_valence, contextual_memory,
                mention_count, linked_conversation_ids
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 1, %s)
        """, (
            contact_id, user_id, c_name, c_phone,
            req.relationship_role.strip(), req.closeness_score, req.trust_tier,
            req.emotional_valence, req.contextual_memory,
            json.dumps(req_linked, ensure_ascii=False)
        ))
        cursor.close()
        conn.close()
        return {"status": "created", "id": contact_id, "linkedConversationIds": req_linked}


@app.post("/users/{user_id}/contacts-relational/sync")
def sync_user_relational_contacts_batch(user_id: str, contacts: List[RelationalContactCreateRequest]):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    synced_count = 0
    for req in contacts:
        c_name = req.contact_name.strip()
        c_phone = req.phone_number.strip() if req.phone_number else None
        if not c_name:
            continue
        cursor.execute("""
            SELECT id, relationship_role, phone_number, linked_conversation_ids
            FROM user_contacts_relational
            WHERE user_id = %s AND (
                LOWER(contact_name) = LOWER(%s)
                OR (%s IS NOT NULL AND phone_number = %s)
            ) LIMIT 1
        """, (user_id, c_name, c_phone, c_phone))
        existing = cursor.fetchone()
        req_linked = req.linked_conversation_ids or []

        if existing:
            contact_id = existing["id"]
            new_phone = c_phone or existing["phone_number"]
            new_role = req.relationship_role if existing["relationship_role"] in ("Contacto del teléfono", "Desconocido") else existing["relationship_role"]
            existing_linked = parse_json_col(existing["linked_conversation_ids"], [])
            merged_linked = list(dict.fromkeys(existing_linked + req_linked))

            cursor.execute("""
                UPDATE user_contacts_relational
                SET phone_number = %s, relationship_role = %s, linked_conversation_ids = %s
                WHERE id = %s
            """, (new_phone, new_role, json.dumps(merged_linked, ensure_ascii=False), contact_id))
        else:
            contact_id = f"ctc_rel_{uuid.uuid4().hex[:8]}"
            cursor.execute("""
                INSERT INTO user_contacts_relational (
                    id, user_id, contact_name, phone_number, relationship_role,
                    closeness_score, trust_tier, emotional_valence, contextual_memory,
                    mention_count, linked_conversation_ids
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 0, %s)
            """, (
                contact_id, user_id, c_name, c_phone,
                req.relationship_role.strip(), req.closeness_score, req.trust_tier,
                req.emotional_valence, req.contextual_memory,
                json.dumps(req_linked, ensure_ascii=False)
            ))
        synced_count += 1
    cursor.close()
    conn.close()
    return {"status": "synced", "count": synced_count}


@app.patch("/users/{user_id}/contacts-relational/{contact_id}")
def update_user_relational_contact(user_id: str, contact_id: str, req: RelationalContactUpdateRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM user_contacts_relational WHERE id = %s AND user_id = %s", (contact_id, user_id))
    cur = cursor.fetchone()
    if not cur:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail="Contacto relacional no encontrado")

    score = req.closeness_score if req.closeness_score is not None else cur["closeness_score"]
    role = req.relationship_role if req.relationship_role is not None else cur["relationship_role"]
    phone = req.phone_number if req.phone_number is not None else cur["phone_number"]
    valence = req.emotional_valence if req.emotional_valence is not None else cur["emotional_valence"]
    memory = req.contextual_memory if req.contextual_memory is not None else cur["contextual_memory"]
    existing_linked = parse_json_col(cur.get("linked_conversation_ids"), [])
    new_linked = req.linked_conversation_ids if req.linked_conversation_ids is not None else existing_linked

    cursor.execute("""
        UPDATE user_contacts_relational
        SET closeness_score = %s, relationship_role = %s, phone_number = %s,
            emotional_valence = %s, contextual_memory = %s, linked_conversation_ids = %s
        WHERE id = %s AND user_id = %s
    """, (score, role, phone, valence, memory, json.dumps(new_linked, ensure_ascii=False), contact_id, user_id))
    cursor.close()
    conn.close()
    return {"status": "updated", "id": contact_id}


@app.get("/users/{user_id}/contacts-relational/{contact_id}/conversations")
def get_contact_linked_conversations(user_id: str, contact_id: str):
    """Recupera todas las conversaciones completas asociadas a una persona en el servidor."""
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("""
        SELECT id, contact_name AS contactName, relationship_role AS relationshipRole,
               closeness_score AS closenessScore, contextual_memory AS contextualMemory,
               linked_conversation_ids AS linkedConversationIds
        FROM user_contacts_relational
        WHERE id = %s AND user_id = %s
    """, (contact_id, user_id))
    contact = cursor.fetchone()
    if not contact:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail="Contacto no encontrado")

    linked_ids = parse_json_col(contact["linkedConversationIds"], [])
    cursor.execute("""
        SELECT conversation_id, snippet_mention
        FROM contact_conversation_mentions
        WHERE contact_id = %s AND user_id = %s
    """, (contact_id, user_id))
    for m in cursor.fetchall():
        if m["conversation_id"] not in linked_ids:
            linked_ids.append(m["conversation_id"])

    conversations = []
    if linked_ids:
        fmt = ",".join(["%s"] * len(linked_ids))
        cursor.execute(f"""
            SELECT cfull.id, cfull.duration_seconds AS durationSeconds,
                   cfull.turns_json AS turnsJson, cfull.full_transcript AS fullTranscript,
                   cfull.sentiment_trend AS sentimentTrend,
                   CAST(cfull.created_at AS CHAR) AS createdAt,
                   cf.compact_summary AS compactSummary, cf.primary_tag AS primaryTag
            FROM conversations_full cfull
            LEFT JOIN conversation_fragments cf ON cf.server_conversation_id = cfull.id
            WHERE cfull.id IN ({fmt}) AND cfull.user_id = %s
            ORDER BY cfull.created_at DESC
        """, (*linked_ids, user_id))
        for r in cursor.fetchall():
            r["turns"] = parse_json_col(r["turnsJson"], [])
            conversations.append(r)

    cursor.close()
    conn.close()
    return {
        "contact": contact,
        "conversations": conversations,
        "totalLinked": len(conversations)
    }


# -----------------------------------------------------------------------------
# Endpoints de Rutinas y Patrones Diarios
# -----------------------------------------------------------------------------
@app.get("/users/{user_id}/routines")
def list_user_routines(user_id: str):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("""
        SELECT id, routine_name AS routineName, category, time_anchor AS timeAnchor,
               typical_time_str AS typicalTimeStr, frequency_rule AS frequencyRule,
               confidence_score AS confidenceScore, notes, is_active AS isActive,
               CAST(last_observed_at AS CHAR) AS lastObservedAt
        FROM user_daily_routines
        WHERE user_id = %s
        ORDER BY typical_time_str ASC, confidence_score DESC
    """, (user_id,))
    routines = []
    for r in cursor.fetchall():
        r["isActive"] = bool(r["isActive"])
        routines.append(r)
    cursor.close()
    conn.close()
    return {"routines": routines}


@app.post("/users/{user_id}/routines")
def create_user_routine(user_id: str, req: DailyRoutineCreateRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    routine_id = f"rtn_{uuid.uuid4().hex[:8]}"
    cursor.execute("""
        INSERT INTO user_daily_routines (
            id, user_id, routine_name, category, time_anchor,
            typical_time_str, frequency_rule, confidence_score, notes, is_active
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 1)
    """, (
        routine_id, user_id, req.routine_name.strip(), req.category,
        req.time_anchor, req.typical_time_str, req.frequency_rule,
        req.confidence_score, req.notes
    ))
    cursor.close()
    conn.close()
    return {"status": "created", "id": routine_id}


@app.patch("/users/{user_id}/routines/{routine_id}")
def update_user_routine(user_id: str, routine_id: str, req: DailyRoutineUpdateRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM user_daily_routines WHERE id = %s AND user_id = %s", (routine_id, user_id))
    cur = cursor.fetchone()
    if not cur:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail="Rutina no encontrada")

    conf = req.confidence_score if req.confidence_score is not None else cur["confidence_score"]
    time_str = req.typical_time_str if req.typical_time_str is not None else cur["typical_time_str"]
    notes = req.notes if req.notes is not None else cur["notes"]
    act = (1 if req.is_active else 0) if req.is_active is not None else cur["is_active"]

    cursor.execute("""
        UPDATE user_daily_routines
        SET confidence_score = %s, typical_time_str = %s, notes = %s, is_active = %s
        WHERE id = %s AND user_id = %s
    """, (conf, time_str, notes, act, routine_id, user_id))
    cursor.close()
    conn.close()
    return {"status": "updated", "id": routine_id}


@app.get("/users/{user_id}/routines/anomalies")
def list_user_routine_anomalies(user_id: str):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("""
        SELECT a.id, a.routine_id AS routineId, a.anomaly_description AS anomalyDescription,
               a.severity, a.is_acknowledged AS isAcknowledged,
               CAST(a.detected_at AS CHAR) AS detectedAt,
               r.routine_name AS routineName
        FROM routine_anomalies a
        LEFT JOIN user_daily_routines r ON r.id = a.routine_id
        WHERE a.user_id = %s
        ORDER BY a.detected_at DESC
        LIMIT 20
    """, (user_id,))
    anomalies = []
    for row in cursor.fetchall():
        row["isAcknowledged"] = bool(row["isAcknowledged"])
        anomalies.append(row)
    cursor.close()
    conn.close()
    return {"anomalies": anomalies}


# -----------------------------------------------------------------------------
# Motor de Conexión de Puntos y Síntesis Relacional con IA (Pattern & Dot Connector)
# -----------------------------------------------------------------------------
def ai_connect_dots_and_synthesize(
    user_name: str,
    user_age: int,
    current_bio: str,
    existing_contacts: List[Dict[str, Any]],
    conversation_turns: List[Dict[str, Any]],
    current_conv_id: str,
    primary_tag: str,
    preliminary_mentions: Optional[List[Dict[str, Any]]] = None,
    current_interest_tags: Optional[List[str]] = None,
    groq_key: Optional[str] = None
) -> Dict[str, Any]:
    """
    Analiza con IA gratuita (Groq llama-3.3-70b / qwen / gemini) la conversación para
    conectar puntos sin alucinaciones:
    1. Identifica personas aludidas y genera snippets precisos citando fuentes [Refs: srv_conv_...].
    2. Genera tags específicos para los gustos específicos de la persona (interest_tags).
    3. Sintetiza la biografía del usuario conectando gustos, afectos y rutinas (synthesized_bio).
    4. Detecta recuerdos entrañables (memories_to_add) para la memoria viva inmediata del celular.
    5. Detecta gustos en detalle (taste_stories_to_add) con fragmento corto y descripción rica.
    6. Detecta recordatorios o compromisos (reminders_to_add).
    """
    # Preparar resumen compacto de contactos existentes
    contacts_summary = []
    for c in existing_contacts:
        prev_linked = parse_json_col(c.get("linked_conversation_ids"), [])
        contacts_summary.append({
            "id": c["id"],
            "name": c["contact_name"],
            "role": c["relationship_role"],
            "score": c["closeness_score"],
            "context_memory": c.get("contextual_memory") or "",
            "linked_convs": prev_linked
        })

    # Formatear turnos textuales
    formatted_dialogue = []
    for t in conversation_turns:
        speaker = t.get("role", t.get("speaker", "user"))
        txt = t.get("text", t.get("content", "")).strip()
        if txt:
            formatted_dialogue.append(f"{speaker.capitalize()}: {txt}")
    dialogue_str = "\n".join(formatted_dialogue)

    sys_prompt = """Eres el Motor de Memoria Relacional, Perfiles y Red Fifo Amigos de Fifo, un robot y asistente empático para adultos mayores.
Tu misión es conectar puntos entre lo que el usuario habla, sus gustos específicos, sus recuerdos y su red relacional, de forma 100% FIEL, CÁLIDA y SIN ALUCINAR NADA que no esté en el diálogo o datos provistos.
Debes responder EXCLUSIVAMENTE un JSON válido con la siguiente estructura:
{
  "contacts_insights": [
    {
      "matched_contact_id": "id del contacto existente o null si es nuevo",
      "contact_name": "Nombre de la persona",
      "relationship_role": "Rol relacional (ej: Nieto, Hija mayor, Vecina de confianza, Médico)",
      "is_mentioned": true,
      "context_snippet": "Resumen conciso (máximo 2 oraciones) de quién es y qué se aprendió de ella, citando explícitamente las fuentes disponibles, ej: 'Estudia piano y viene los domingos. [Refs: srv_conv_002, """ + current_conv_id + """]'",
      "closeness_delta": 0.05,
      "emotional_valence": "afectuoso/protector/preocupacion/alegria"
    }
  ],
  "interest_tags": ["Tags bien específicos para los gustos únicos de esa persona, ej: 'Orquídeas blancas', 'Música clásica de Chopin', 'Cazuela casera de ave', 'Paseos vespertinos'"],
  "synthesized_bio": "Biografía actualizada y conectada del usuario (1-2 párrafos cálidos que sintetizan quién es, sus afectos clave, sus pasiones específicas y rutinas aprendidas)",
  "memories_to_add": [
    {
      "emoji": "🌸",
      "title": "Título del recuerdo importante",
      "detail": "Detalle entrañable que Fifo debe saber de inmediato en la memoria de celular"
    }
  ],
  "taste_stories_to_add": [
    {
      "title": "Nombre del gusto o pasión",
      "subtitle": "Fragmento corto de máximo 1-2 líneas con el detalle más específico de cómo lo disfruta",
      "description": "Historia y contexto completo y detallado del gusto",
      "tags": ["Jardinería", "Plantas"],
      "icon_category": "nature"
    }
  ],
  "reminders_to_add": [
    {
      "title": "Título del recordatorio si mencionó un compromiso futuro o toma médica",
      "time_str": "HH:mm o momento amigable",
      "category": "medication/family/health/hobby"
    }
  ],
  "extracted_topics": ["tema1", "tema2"],
  "named_entities": ["entidad1", "entidad2"]
}"""

    usr_prompt = f"""PERFIL DEL USUARIO:
- Nombre: {user_name} ({user_age} años)
- Biografía actual: {current_bio or 'Sin biografía previa'}
- Tags de intereses actuales: {json.dumps(current_interest_tags or [], ensure_ascii=False)}

RED DE CONTACTOS EXISTENTES:
{json.dumps(contacts_summary, ensure_ascii=False, indent=2)}

NUEVA CONVERSACIÓN (ID: {current_conv_id}, Categoría: {primary_tag}):
{dialogue_str}

MENCIONES PREVIAS DEL CLIENTE:
{json.dumps(preliminary_mentions or [], ensure_ascii=False)}

INSTRUCCIONES CLAVE:
1. Conecta puntos: detecta a qué personas alude la charla (por nombre, apodo o parentesco) y actualiza sus notas citando las fuentes disponibles (incluye '{current_conv_id}').
2. Genera tags específicos para los gustos de esa persona (interest_tags): etiquetas descriptivas de lo que ama y disfruta.
3. Sintetiza la biografía (synthesized_bio) conectando lo aprendido en esta charla con lo que ya se sabía sin inventar nada.
4. Si se compartieron anécdotas entrañables o preferencias clave, genera recuerdos (memories_to_add).
5. Si conversaron en profundidad sobre un gusto o pasatiempo, genera una historia detallada (taste_stories_to_add) con un fragmento corto y la descripción completa.
6. Si mencionó compromisos pendientes o tomas de salud, genera un recordatorio sugerido (reminders_to_add)."""

    ai_resp = call_ai_engine(usr_prompt, sys_prompt, user_groq_key=groq_key)
    if ai_resp and isinstance(ai_resp, dict) and "contacts_insights" in ai_resp:
        return ai_resp

    # Fallback heurístico inteligente si no hay conexión o API key configurada
    fallback_insights = []
    user_text_combined = " ".join([t.get("text", "") for t in conversation_turns]).lower()

    if preliminary_mentions:
        for p in preliminary_mentions:
            p_name = p.get("name", "").strip()
            if not p_name:
                continue
            matched = next((
                c for c in existing_contacts
                if c["contact_name"].lower() == p_name.lower() or
                   p_name.lower() in c["contact_name"].lower() or
                   c["contact_name"].lower() in p_name.lower()
            ), None)

            prev_mem = matched.get("contextual_memory") or "" if matched else ""
            p_snip = p.get("context_snippet", "").strip()
            refs_str = f"[Ref: {current_conv_id}]"
            new_mem = f"{prev_mem} · {p_snip} {refs_str}".strip(" ·") if prev_mem and p_snip else f"{p_snip} {refs_str}".strip()

            fallback_insights.append({
                "matched_contact_id": matched["id"] if matched else None,
                "contact_name": matched["contact_name"] if matched else p_name,
                "relationship_role": p.get("inferred_role") or (matched["relationship_role"] if matched else "Persona cercana"),
                "is_mentioned": True,
                "context_snippet": new_mem,
                "closeness_delta": float(p.get("closeness_delta") or 0.05),
                "emotional_valence": p.get("emotional_valence", "afectuoso")
            })

    # Si no hubo menciones estructuradas, buscar por nombres en el texto
    for c in existing_contacts:
        c_id = c["id"]
        if any(fi.get("matched_contact_id") == c_id for fi in fallback_insights):
            continue
        c_name = c["contact_name"].lower()
        c_role = c["relationship_role"].lower()
        if any(tok in user_text_combined for tok in c_name.split() if len(tok) > 3) or \
           any(tok in user_text_combined for tok in c_role.split() if len(tok) > 3):
            prev_mem = c.get("contextual_memory") or ""
            refs_str = f"[Ref: {current_conv_id}]"
            new_mem = f"{prev_mem} · Mencionado(a) en charla sobre {primary_tag} {refs_str}".strip(" ·")
            fallback_insights.append({
                "matched_contact_id": c_id,
                "contact_name": c["contact_name"],
                "relationship_role": c["relationship_role"],
                "is_mentioned": True,
                "context_snippet": new_mem,
                "closeness_delta": 0.03,
                "emotional_valence": "afectuoso"
            })

    # Extraer tags de intereses específicos del texto
    detected_tags = list(current_interest_tags or [])
    tag_keywords = [
        ("orquídeas", "Orquídeas y flores"), ("piano", "Música clásica de piano"),
        ("chopin", "Nocturnos de Chopin"), ("cazuela", "Cazuela tradicional"),
        ("jardinería", "Cuidado de plantas y jardinería"), ("caminata", "Caminatas al aire libre"),
        ("lectura", "Novelas y lectura histórica"), ("té", "Té y momentos de calma")
    ]
    for kw, tag_val in tag_keywords:
        if kw in user_text_combined and tag_val not in detected_tags:
            detected_tags.append(tag_val)

    return {
        "contacts_insights": fallback_insights,
        "interest_tags": detected_tags[:8],
        "synthesized_bio": current_bio,
        "memories_to_add": [],
        "taste_stories_to_add": [],
        "reminders_to_add": [],
        "extracted_topics": [primary_tag],
        "named_entities": [fi["contact_name"] for fi in fallback_insights]
    }


# -----------------------------------------------------------------------------
# Motor de Análisis y Consolidación de Sesiones Conversacionales
# Implementa el procesamiento en backend de perfil, grafo de contactos y rutinas
# -----------------------------------------------------------------------------
@app.post("/users/{user_id}/conversations/analyze-and-consolidate")
def analyze_and_consolidate_conversation(user_id: str, req: ConversationAnalyzeAndConsolidateRequest):
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    cursor.execute("SELECT * FROM users WHERE id = %s", (user_id,))
    user = cursor.fetchone()
    if not user:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail="Usuario no encontrado")

    cursor.execute("SELECT bio_ai, interest_tags FROM user_profiles WHERE user_id = %s", (user_id,))
    profile = cursor.fetchone()
    current_bio = profile["bio_ai"] if profile and profile["bio_ai"] else ""
    current_interest_tags = parse_json_col(profile.get("interest_tags") if profile else [], [])

    user_utterances = []
    assistant_utterances = []
    for turn in req.turns:
        speaker = turn.get("role", turn.get("speaker", "user"))
        text = turn.get("text", turn.get("content", "")).strip()
        if not text:
            continue
        if speaker in ("user", "usuario"):
            user_utterances.append(text)
        else:
            assistant_utterances.append(text)

    full_conversation_text = "\n".join([
        f"{turn.get('role', 'speaker')}: {turn.get('text', turn.get('content', ''))}"
        for turn in req.turns
    ])
    user_text_combined = " ".join(user_utterances).lower()

    # 1. Guardar de inmediato la conversación completa en conversations_full
    srv_id = f"srv_conv_{uuid.uuid4().hex[:8]}"
    frag_id = f"frag_{uuid.uuid4().hex[:8]}"
    past_id = f"c_{uuid.uuid4().hex[:8]}"
    mins = max(1, req.duration_seconds // 60)
    summary_text = f"Charla de {mins} min sobre actividades cotidianas y bienestar."
    if user_utterances:
        summary_text = f"El usuario conversó sobre {user_utterances[0][:120]}..."

    cursor.execute("SELECT * FROM user_contacts_relational WHERE user_id = %s", (user_id,))
    existing_contacts = cursor.fetchall()

    # 2. Motor de Conexión de Puntos con IA (Groq / Gemini / Fallback)
    ai_result = ai_connect_dots_and_synthesize(
        user_name=user["full_name"],
        user_age=user.get("estimated_age") or 68,
        current_bio=current_bio,
        existing_contacts=existing_contacts,
        conversation_turns=req.turns,
        current_conv_id=srv_id,
        primary_tag=req.primary_tag,
        preliminary_mentions=req.mentioned_persons,
        current_interest_tags=current_interest_tags,
        groq_key=req.groq_api_key
    )

    contacts_insights = ai_result.get("contacts_insights", [])
    updated_bio = ai_result.get("synthesized_bio", current_bio).strip()
    extracted_topics = ai_result.get("extracted_topics", [req.primary_tag])
    named_entities = ai_result.get("named_entities", [])
    ai_interest_tags = ai_result.get("interest_tags", [])
    ai_memories = ai_result.get("memories_to_add", [])
    ai_taste_stories = ai_result.get("taste_stories_to_add", [])
    ai_reminders = ai_result.get("reminders_to_add", [])

    # 3. Aplicar y Persistir las Conexiones del Grafo Relacional
    contacts_analyzed = []
    for ci in contacts_insights:
        c_name = ci.get("contact_name", "").strip()
        if not c_name:
            continue
        c_role = ci.get("relationship_role") or "Persona cercana"
        c_snip = ci.get("context_snippet", "")
        c_val = ci.get("emotional_valence", "afectuoso")
        c_delta = float(ci.get("closeness_delta") or 0.05)
        matched_id = ci.get("matched_contact_id")

        matched = next((c for c in existing_contacts if c["id"] == matched_id), None)
        if not matched:
            matched = next((
                c for c in existing_contacts
                if c["contact_name"].lower() == c_name.lower() or
                   c_name.lower() in c["contact_name"].lower() or
                   c["contact_name"].lower() in c_name.lower()
            ), None)

        if matched:
            target_id = matched["id"]
            new_mentions = matched["mention_count"] + 1
            new_closeness = min(1.0, round(float(matched["closeness_score"]) + c_delta, 2))
            existing_linked = parse_json_col(matched.get("linked_conversation_ids"), [])
            if srv_id not in existing_linked:
                existing_linked.append(srv_id)

            cursor.execute("""
                UPDATE user_contacts_relational
                SET mention_count = %s, closeness_score = %s, contextual_memory = %s,
                    relationship_role = %s, emotional_valence = %s,
                    linked_conversation_ids = %s, last_mentioned_at = CURRENT_TIMESTAMP
                WHERE id = %s
            """, (new_mentions, new_closeness, c_snip, c_role, c_val,
                  json.dumps(existing_linked, ensure_ascii=False), target_id))

            # Registrar en tabla puente de menciones
            mention_id = f"ment_{uuid.uuid4().hex[:8]}"
            cursor.execute("""
                INSERT INTO contact_conversation_mentions (
                    id, user_id, contact_id, conversation_id, snippet_mention
                ) VALUES (%s, %s, %s, %s, %s)
            """, (mention_id, user_id, target_id, srv_id, c_snip))

            contacts_analyzed.append({
                "contactId": target_id,
                "name": matched["contact_name"],
                "role": c_role,
                "newCloseness": new_closeness,
                "mentions": new_mentions,
                "contextualMemory": c_snip,
                "linkedConversationIds": existing_linked
            })
        else:
            # Contacto nuevo detectado y conectado
            new_id = f"ctc_rel_{uuid.uuid4().hex[:8]}"
            new_linked = [srv_id]
            cursor.execute("""
                INSERT INTO user_contacts_relational (
                    id, user_id, contact_name, phone_number, relationship_role,
                    closeness_score, trust_tier, emotional_valence, contextual_memory,
                    mention_count, linked_conversation_ids
                ) VALUES (%s, %s, %s, NULL, %s, %s, 3, %s, %s, 1, %s)
            """, (new_id, user_id, c_name, c_role, min(1.0, 0.60 + c_delta), c_val, c_snip,
                  json.dumps(new_linked, ensure_ascii=False)))

            mention_id = f"ment_{uuid.uuid4().hex[:8]}"
            cursor.execute("""
                INSERT INTO contact_conversation_mentions (
                    id, user_id, contact_id, conversation_id, snippet_mention
                ) VALUES (%s, %s, %s, %s, %s)
            """, (mention_id, user_id, new_id, srv_id, c_snip))

            contacts_analyzed.append({
                "contactId": new_id,
                "name": c_name,
                "role": c_role,
                "newCloseness": min(1.0, 0.60 + c_delta),
                "mentions": 1,
                "contextualMemory": c_snip,
                "linkedConversationIds": new_linked
            })

    # 4. Actualizar Tags de Intereses Específicos y Biografía del Usuario
    merged_interest_tags = list(dict.fromkeys(current_interest_tags + ai_interest_tags))[:12]
    bio_to_save = updated_bio if updated_bio else current_bio

    cursor.execute("""
        INSERT INTO user_profiles (user_id, bio_ai, interest_tags)
        VALUES (%s, %s, %s)
        ON DUPLICATE KEY UPDATE
            bio_ai = VALUES(bio_ai),
            interest_tags = VALUES(interest_tags)
    """, (user_id, bio_to_save, json.dumps(merged_interest_tags, ensure_ascii=False)))

    # Asegurar que los tags de interés se registren en la tabla de gustos activos
    for itag in ai_interest_tags:
        itag_clean = itag.strip()
        if itag_clean:
            t_id = f"tst_{uuid.uuid4().hex[:8]}"
            cursor.execute("""
                INSERT INTO tastes (id, user_id, name, category, is_active)
                VALUES (%s, %s, %s, 'general', 1)
                ON DUPLICATE KEY UPDATE is_active = 1
            """, (t_id, user_id, itag_clean))

    # 5. Persistir Recuerdos Importantes (Memoria de Celular Instantánea)
    memories_added = []
    for m in ai_memories:
        m_title = m.get("title", "").strip()
        m_detail = m.get("detail", "").strip()
        if m_title and m_detail:
            m_id = f"m_{uuid.uuid4().hex[:8]}"
            m_emoji = m.get("emoji", "⭐")
            cursor.execute("""
                INSERT INTO memories (id, user_id, emoji, title, detail, learned_date_label)
                VALUES (%s, %s, %s, %s, %s, 'Aprendido recién')
            """, (m_id, user_id, m_emoji, m_title, m_detail))
            memories_added.append({
                "id": m_id,
                "emoji": m_emoji,
                "title": m_title,
                "detail": m_detail,
                "learnedDate": "Aprendido recién"
            })

    # 6. Persistir Gustos en Detalle (Taste Stories con fragmento corto y descripción)
    taste_stories_added = []
    for ts in ai_taste_stories:
        ts_title = ts.get("title", "").strip()
        ts_subtitle = ts.get("subtitle", "").strip()
        ts_desc = ts.get("description", "").strip()
        if ts_title and ts_desc:
            ts_id = f"t_{uuid.uuid4().hex[:8]}"
            ts_tags = ts.get("tags") or [ts_title]
            ts_icon = ts.get("icon_category", "heart")
            cursor.execute("""
                INSERT INTO taste_stories (
                    id, user_id, title, subtitle, description, tags, icon_category, learned_from
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, 'Conversación cotidiana con Fifo')
            """, (ts_id, user_id, ts_title, ts_subtitle or ts_title, ts_desc, json.dumps(ts_tags, ensure_ascii=False), ts_icon))
            taste_stories_added.append({
                "id": ts_id,
                "title": ts_title,
                "subtitle": ts_subtitle or ts_title,
                "description": ts_desc,
                "tags": ts_tags,
                "iconCategory": ts_icon,
                "learnedFrom": "Conversación cotidiana con Fifo"
            })

    # 7. Persistir Recordatorios si surgieron compromisos en la charla
    reminders_added = []
    for rem in ai_reminders:
        rem_title = rem.get("title", "").strip()
        rem_time = rem.get("time_str", "Hoy").strip()
        rem_cat = rem.get("category", "hobby")
        if rem_title:
            r_id = f"rem_{uuid.uuid4().hex[:8]}"
            spoken = f"Le recuerdo {rem_title}"
            cursor.execute("""
                INSERT INTO reminders_and_events (
                    id, user_id, title, time_str, category, is_completed, spoken_text
                ) VALUES (%s, %s, %s, %s, %s, 0, %s)
            """, (r_id, user_id, rem_title, rem_time, rem_cat, spoken))
            reminders_added.append({
                "id": r_id,
                "title": rem_title,
                "timeStr": rem_time,
                "category": rem_cat,
                "isCompleted": False
            })

    # 8. Análisis de Rutinas Diarias y Detección de Anomalías
    cursor.execute("SELECT * FROM user_daily_routines WHERE user_id = %s AND is_active = 1", (user_id,))
    existing_routines = cursor.fetchall()
    routines_analyzed = []
    anomalies_detected = []

    for r in existing_routines:
        r_name = r["routine_name"].lower()
        keywords = [w for w in r_name.split() if len(w) > 4]
        was_relevant = any(kw in user_text_combined for kw in keywords)
        if was_relevant:
            is_anomaly = any(neg in user_text_combined for neg in [
                "no pude", "no alcancé", "se me olvidó", "me dolió", "dolor",
                "mareada", "mareado", "cansada", "cansado", "malestar", "no salí"
            ])
            if is_anomaly:
                anom_id = f"anom_{uuid.uuid4().hex[:8]}"
                desc = f"Interrupción o molestia reportada en '{r['routine_name']}'."
                cursor.execute("""
                    INSERT INTO routine_anomalies (id, user_id, routine_id, anomaly_description, severity)
                    VALUES (%s, %s, %s, %s, 'medium')
                """, (anom_id, user_id, r["id"], desc))
                anomalies_detected.append({"id": anom_id, "routine": r["routine_name"], "description": desc})
            else:
                new_conf = min(1.0, round(float(r["confidence_score"]) + 0.03, 2))
                cursor.execute("""
                    UPDATE user_daily_routines
                    SET confidence_score = %s, last_observed_at = CURRENT_TIMESTAMP
                    WHERE id = %s
                """, (new_conf, r["id"]))
                routines_analyzed.append({"routineId": r["id"], "name": r["routine_name"], "confidence": new_conf})

    # 9. Detección adicional de gustos positivos
    tastes_added = []
    potential_tastes = [
        ("orquídeas", "naturaleza"), ("piano", "musica"), ("cazuela", "cocina"),
        ("chopin", "musica"), ("jardinería", "naturaleza"), ("caminata", "bienestar"),
        ("acuarela", "arte"), ("lectura", "lectura"), ("poesía", "lectura"),
        ("tango", "musica"), ("boleros", "musica"), ("ajedrez", "juegos")
    ]
    for taste_term, cat in potential_tastes:
        if taste_term in user_text_combined:
            t_id = f"tst_{uuid.uuid4().hex[:8]}"
            cursor.execute("""
                INSERT INTO tastes (id, user_id, name, category, is_active)
                VALUES (%s, %s, %s, %s, 1)
                ON DUPLICATE KEY UPDATE is_active = 1
            """, (t_id, user_id, taste_term.capitalize(), cat))
            if cursor.rowcount > 0:
                tastes_added.append(taste_term.capitalize())

    # 10. Persistir conversaciones_full, conversation_fragments y past_conversations
    cursor.execute("""
        INSERT INTO conversations_full (
            id, user_id, duration_seconds, turns_json, full_transcript,
            extracted_topics, named_entities, sentiment_trend
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, 'positivo')
    """, (
        srv_id, user_id, req.duration_seconds,
        json.dumps(req.turns, ensure_ascii=False),
        full_conversation_text,
        json.dumps(extracted_topics + tastes_added, ensure_ascii=False),
        json.dumps(named_entities or [c["name"] for c in contacts_analyzed], ensure_ascii=False)
    ))

    cursor.execute("""
        INSERT INTO conversation_fragments (
            id, user_id, server_conversation_id, key_topics, named_entities,
            detected_mood, compact_summary, primary_tag, duration_seconds
        ) VALUES (%s, %s, %s, %s, %s, 'amable', %s, %s, %s)
    """, (
        frag_id, user_id, srv_id,
        json.dumps(extracted_topics + tastes_added, ensure_ascii=False),
        json.dumps(named_entities or [c["name"] for c in contacts_analyzed], ensure_ascii=False),
        summary_text, req.primary_tag, req.duration_seconds
    ))

    cursor.execute("""
        INSERT INTO past_conversations (
            id, user_id, title, date_label, duration_label, duration_seconds, summary, topic_tag
        ) VALUES (%s, %s, %s, 'Hoy', %s, %s, %s, %s)
    """, (
        past_id, user_id, f"Charla con Fifo", f"{mins} min", req.duration_seconds, summary_text, req.primary_tag
    ))

    cursor.close()
    conn.close()

    return {
        "status": "consolidated",
        "userId": user_id,
        "serverConversationId": srv_id,
        "fragmentId": frag_id,
        "updatedBioAi": bio_to_save,
        "interestTags": merged_interest_tags,
        "contactsAnalyzed": contacts_analyzed,
        "contactsUpdated": contacts_analyzed,
        "memoriesAdded": memories_added,
        "tasteStoriesAdded": taste_stories_added,
        "remindersAdded": reminders_added,
        "routinesAnalyzed": routines_analyzed,
        "anomaliesDetected": anomalies_detected,
        "tastesAdded": tastes_added
    }


# -----------------------------------------------------------------------------
# Red Comunitaria "Fifo Amigos": Recomendaciones por Tags y Afinidad de Gustos
# -----------------------------------------------------------------------------
@app.get("/users/{user_id}/fifo-friends/recommendations")
def get_fifo_friends_recommendations(user_id: str):
    """
    Conecta personas afines en la red de Fifo Amigos:
    Utiliza los interest_tags y gustos de cada usuario para calcular compatibilidad,
    sugerir temas de conversación cálidos y mostrar publicaciones afines.
    """
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    # 1. Obtener perfil, gustos y tags de interés del usuario actual
    cursor.execute("""
        SELECT u.id, u.full_name, p.bio_ai, p.interest_tags
        FROM users u
        LEFT JOIN user_profiles p ON p.user_id = u.id
        WHERE u.id = %s
    """, (user_id,))
    current_u = cursor.fetchone()
    if not current_u:
        cursor.close()
        conn.close()
        raise HTTPException(status_code=404, detail="Usuario no encontrado")

    cursor.execute("SELECT name FROM tastes WHERE user_id = %s AND is_active = 1", (user_id,))
    user_tastes = [r["name"] for r in cursor.fetchall()]
    user_interest_tags = parse_json_col(current_u.get("interest_tags"), [])
    user_all_tags = list(dict.fromkeys(user_interest_tags + user_tastes))
    user_tags_lower = {t.lower().strip() for t in user_all_tags}

    # 2. Consultar otros miembros de la comunidad Fifo Amigos
    cursor.execute("""
        SELECT u.id, u.full_name, u.estimated_age AS age, u.short_quote AS quote,
               u.avatar_bg_hex AS avatarBgHex, p.bio_ai, p.interest_tags,
               uf.status AS friendshipStatus
        FROM users u
        LEFT JOIN user_profiles p ON p.user_id = u.id
        LEFT JOIN user_friendships uf ON uf.user_id = %s AND uf.friend_user_id = u.id
        WHERE u.id != %s
        ORDER BY u.id ASC
    """, (user_id, user_id))
    candidates = cursor.fetchall()

    recommendations = []
    for cand in candidates:
        cand_id = cand["id"]
        cursor.execute("SELECT name FROM tastes WHERE user_id = %s AND is_active = 1", (cand_id,))
        cand_tastes = [r["name"] for r in cursor.fetchall()]
        cand_interest_tags = parse_json_col(cand.get("interest_tags"), [])
        cand_all_tags = list(dict.fromkeys(cand_interest_tags + cand_tastes))

        # Encontrar tags compartidos y afinidad
        shared = []
        for t in cand_all_tags:
            t_low = t.lower().strip()
            if t_low in user_tags_lower or any(ut in t_low or t_low in ut for ut in user_tags_lower):
                shared.append(t)

        shared_unique = list(dict.fromkeys(shared))
        total_unique_tags = max(1, len(user_tags_lower.union({t.lower().strip() for t in cand_all_tags})))
        raw_affinity = (len(shared_unique) / float(total_unique_tags)) * 1.8
        affinity_score = min(0.99, max(0.40, round(raw_affinity, 2))) if shared_unique else 0.35

        # Generar tema de conversación sugerido
        if shared_unique:
            shared_str = " y ".join(shared_unique[:2])
            suggested_topic = f"Ambos comparten gran interés en {shared_str}. ¡Podrían conversar sobre sus experiencias y recomendaciones!"
        else:
            suggested_topic = f"Ambos forman parte de la comunidad Fifo Amigos en Santiago y disfrutan de momentos de tranquilidad."

        recommendations.append({
            "friendId": cand_id,
            "fullName": cand["full_name"],
            "age": cand["age"] or 70,
            "quote": cand["quote"] or "Disfrutando de la comunidad",
            "avatarBgHex": cand["avatarBgHex"] or 4293963402,
            "bioAi": cand["bio_ai"] or "",
            "sharedTags": shared_unique,
            "candTags": cand_all_tags[:6],
            "affinityScore": affinity_score,
            "suggestedTopic": suggested_topic,
            "friendshipStatus": cand["friendshipStatus"] or "suggested"
        })

    recommendations.sort(key=lambda x: (len(x["sharedTags"]) > 0, x["affinityScore"]), reverse=True)

    # 3. Publicaciones recomendadas basadas en los tags
    cursor.execute("""
        SELECT id, user_id, author_name AS author, author_age AS age, relation_label AS relation,
               category, time_ago_label AS timeAgo, content, likes_count AS likes,
               comments_count AS commentsCount, accent_color_hex AS accentColorHex
        FROM social_posts
        ORDER BY created_at DESC
        LIMIT 10
    """)
    all_posts = cursor.fetchall()
    recommended_posts = []
    for post in all_posts:
        p_cat = (post.get("category") or "").lower()
        p_content = (post.get("content") or "").lower()
        matches_tag = any(t in p_cat or t in p_content for t in user_tags_lower)
        if matches_tag or len(recommended_posts) < 3:
            recommended_posts.append(post)

    cursor.close()
    conn.close()

    return {
        "userId": user_id,
        "userInterestTags": user_all_tags,
        "recommendedFriends": recommendations,
        "recommendedPosts": recommended_posts
    }

