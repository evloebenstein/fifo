import json
import os
import uuid
from typing import List, Optional, Any, Dict
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import mysql.connector
from mysql.connector import Error as MySQLError

app = FastAPI(
    title="Fifo Voice Pipeline - Lightweight REST API",
    description="Microservicio REST para conectar la aplicación Android Fifo, los Skills de voz (Claude / Gemini) y el contenedor MySQL 8.4.",
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
DB_NAME = os.getenv("DB_NAME", "fifo_db")
DB_USER = os.getenv("DB_USER", "fifo_app")
DB_PASSWORD = os.getenv("DB_PASSWORD", "fifo_app_secret_2026")


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


class MemoryCreateRequest(BaseModel):
    emoji: str = "⭐"
    title: str
    detail: str
    learned_date_label: str = "Aprendido recién"


class RelationalContactCreateRequest(BaseModel):
    contact_name: str
    phone_number: Optional[str] = None
    relationship_role: str
    closeness_score: float = Field(default=0.5, ge=0.0, le=1.0)
    trust_tier: int = Field(default=2, ge=1, le=4)
    emotional_valence: str = "afectuoso"
    contextual_memory: Optional[str] = None


class RelationalContactUpdateRequest(BaseModel):
    closeness_score: Optional[float] = Field(default=None, ge=0.0, le=1.0)
    relationship_role: Optional[str] = None
    phone_number: Optional[str] = None
    emotional_valence: Optional[str] = None
    contextual_memory: Optional[str] = None


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
               p.preferred_address, p.continuous_listening_enabled
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
               CAST(last_mentioned_at AS CHAR) AS lastMentionedAt
        FROM user_contacts_relational
        WHERE user_id = %s
        ORDER BY closeness_score DESC, mention_count DESC
    """, (user_id,))
    relational_contacts = cursor.fetchall()

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
            "preferredAddress": user["preferred_address"] or ""
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
    like_pat = f"%{q}%"
    cursor.execute("""
        SELECT
            cf.id AS fragment_id,
            cf.primary_tag,
            cf.detected_mood,
            cf.compact_summary,
            cf.key_topics,
            cf.named_entities,
            cfull.full_transcript,
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
    rows = cursor.fetchall()
    cursor.close()
    conn.close()

    excerpts = []
    entities = []
    for r in rows:
        excerpts.append(r["compact_summary"])
        ents = parse_json_col(r["named_entities"], [])
        for e in ents:
            if e not in entities:
                entities.append(e)

    if not rows:
        synthesized = f"No encontré conversaciones anteriores sobre '{req.query}'."
    else:
        parts = [f"Encontré {len(rows)} charla(s) en la base de datos sobre '{req.query}'."]
        for r in rows:
            parts.append(f"[{r['primary_tag']}]: {r['compact_summary']}")
            if r.get("full_transcript"):
                parts.append(f"Transcripción servidor: {r['full_transcript']}")
        synthesized = " ".join(parts)

    return {
        "relevantExcerpts": excerpts,
        "foundEntities": entities,
        "synthesizedContext": synthesized,
        "conversationsSearched": len(rows),
        "rawMatches": rows
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
               CAST(last_mentioned_at AS CHAR) AS lastMentionedAt
        FROM user_contacts_relational
        WHERE user_id = %s
        ORDER BY closeness_score DESC, mention_count DESC
    """, (user_id,))
    contacts = cursor.fetchall()
    cursor.close()
    conn.close()
    return {"contacts": contacts}


@app.post("/users/{user_id}/contacts-relational")
def create_user_relational_contact(user_id: str, req: RelationalContactCreateRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    contact_id = f"ctc_rel_{uuid.uuid4().hex[:8]}"
    cursor.execute("""
        INSERT INTO user_contacts_relational (
            id, user_id, contact_name, phone_number, relationship_role,
            closeness_score, trust_tier, emotional_valence, contextual_memory, mention_count
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 1)
    """, (
        contact_id, user_id, req.contact_name.strip(), req.phone_number,
        req.relationship_role.strip(), req.closeness_score, req.trust_tier,
        req.emotional_valence, req.contextual_memory
    ))
    cursor.close()
    conn.close()
    return {"status": "created", "id": contact_id}


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

    cursor.execute("""
        UPDATE user_contacts_relational
        SET closeness_score = %s, relationship_role = %s, phone_number = %s,
            emotional_valence = %s, contextual_memory = %s
        WHERE id = %s AND user_id = %s
    """, (score, role, phone, valence, memory, contact_id, user_id))
    cursor.close()
    conn.close()
    return {"status": "updated", "id": contact_id}


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

    cursor.execute("SELECT bio_ai FROM user_profiles WHERE user_id = %s", (user_id,))
    profile = cursor.fetchone()
    current_bio = profile["bio_ai"] if profile and profile["bio_ai"] else ""

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

    # 1. Análisis de Contactos y Grafo Relacional
    cursor.execute("SELECT * FROM user_contacts_relational WHERE user_id = %s", (user_id,))
    existing_contacts = cursor.fetchall()
    contacts_analyzed = []

    for c in existing_contacts:
        c_name = c["contact_name"].lower()
        c_role = c["relationship_role"].lower()
        is_mentioned = any(
            token in user_text_combined
            for token in c_name.split() if len(token) > 3
        ) or any(
            token in user_text_combined
            for token in c_role.split() if len(token) > 3
        )
        if is_mentioned:
            new_mentions = c["mention_count"] + 1
            new_closeness = min(1.0, round(float(c["closeness_score"]) + 0.02, 2))
            cursor.execute("""
                UPDATE user_contacts_relational
                SET mention_count = %s, closeness_score = %s, last_mentioned_at = CURRENT_TIMESTAMP
                WHERE id = %s
            """, (new_mentions, new_closeness, c["id"]))
            contacts_analyzed.append({
                "contactId": c["id"],
                "name": c["contact_name"],
                "newCloseness": new_closeness,
                "mentions": new_mentions
            })

    # 2. Análisis de Rutinas Diarias y Detección de Anomalías
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

    # 3. Detección de Gustos Positivos Nuevos
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

    # 4. Guardado de conversación en Doble Capa
    srv_id = f"srv_conv_{uuid.uuid4().hex[:8]}"
    frag_id = f"frag_{uuid.uuid4().hex[:8]}"
    past_id = f"c_{uuid.uuid4().hex[:8]}"
    mins = max(1, req.duration_seconds // 60)
    summary_text = f"Charla de {mins} min sobre actividades cotidianas y bienestar."
    if user_utterances:
        summary_text = f"El usuario conversó sobre {user_utterances[0][:120]}..."

    cursor.execute("""
        INSERT INTO conversations_full (
            id, user_id, duration_seconds, turns_json, full_transcript,
            extracted_topics, named_entities, sentiment_trend
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, 'positivo')
    """, (
        srv_id, user_id, req.duration_seconds,
        json.dumps(req.turns, ensure_ascii=False),
        full_conversation_text,
        json.dumps([t for t in tastes_added], ensure_ascii=False),
        json.dumps([c["name"] for c in contacts_analyzed], ensure_ascii=False)
    ))

    cursor.execute("""
        INSERT INTO conversation_fragments (
            id, user_id, server_conversation_id, key_topics, named_entities,
            detected_mood, compact_summary, primary_tag, duration_seconds
        ) VALUES (%s, %s, %s, %s, %s, 'amable', %s, %s, %s)
    """, (
        frag_id, user_id, srv_id,
        json.dumps([t for t in tastes_added], ensure_ascii=False),
        json.dumps([c["name"] for c in contacts_analyzed], ensure_ascii=False),
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
        "contactsAnalyzed": contacts_analyzed,
        "routinesAnalyzed": routines_analyzed,
        "anomaliesDetected": anomalies_detected,
        "tastesAdded": tastes_added
    }

