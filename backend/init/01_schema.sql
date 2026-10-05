-- =============================================================================
-- FIFO VOICE PIPELINE - ESQUEMA RELACIONAL MYSQL 8.4 LTS
-- Basado en FIFO_DATABASE_MAP.md (Multi-Usuario + Comunidad + Memoria Doble Capa)
-- Codificación: utf8mb4 / Collation: utf8mb4_es_0900_ai_ci
-- =============================================================================

SET NAMES utf8mb4;
SET time_zone = '-03:00';

USE fifo_db;

-- -----------------------------------------------------------------------------
-- 2.1. Tabla: users (Identidad principal y datos demográficos)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'Identificador único del usuario (ej: usr_lucia_01)',
    full_name VARCHAR(150) NOT NULL COMMENT 'Nombre completo del usuario',
    email VARCHAR(180) NOT NULL UNIQUE COMMENT 'Correo electrónico de la cuenta',
    birth_date VARCHAR(80) NOT NULL COMMENT 'Fecha de cumpleaños legible (ej: 14 de Mayo, 1958)',
    birth_year SMALLINT UNSIGNED NOT NULL COMMENT 'Año de nacimiento para cálculo automático de edad',
    estimated_age TINYINT UNSIGNED NOT NULL COMMENT 'Edad estimada actual',
    gender_identity VARCHAR(40) NOT NULL DEFAULT 'Mujer' COMMENT 'Identidad para trato afectuoso (Mujer, Hombre, No binario)',
    city VARCHAR(120) NOT NULL DEFAULT 'Santiago, Chile' COMMENT 'Ciudad o comuna de residencia',
    avatar_url VARCHAR(512) NULL DEFAULT NULL COMMENT 'URL opcional de foto o avatar',
    avatar_bg_hex BIGINT UNSIGNED NOT NULL DEFAULT 4293514751 COMMENT 'Color de fondo del avatar en ARGB hex',
    short_quote VARCHAR(255) NULL DEFAULT NULL COMMENT 'Frase corta de presentación para Fifo Amigos',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.2. Tabla: user_profiles (Información enriquecida por IA y contactos de apoyo)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'FK hacia users.id',
    bio_ai TEXT NOT NULL COMMENT 'Biografía cálida redactada automáticamente por Fifo',
    bio_last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    emergency_contact_name VARCHAR(120) NULL DEFAULT NULL COMMENT 'Nombre del familiar o contacto de emergencia (ej: Carmen (Hija))',
    emergency_contact_phone VARCHAR(40) NULL DEFAULT NULL COMMENT 'Teléfono de emergencia en formato E.164',
    preferred_address VARCHAR(255) NULL DEFAULT NULL COMMENT 'Dirección habitual para navegación de regreso a casa',
    continuous_listening_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Estado del modo escucha continua',
    CONSTRAINT fk_user_profiles_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.3. Tabla: tastes (Gustos e intereses descubiertos orgánicamente)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tastes (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL COMMENT 'Nombre del pasatiempo o interés positivo',
    category VARCHAR(60) NOT NULL DEFAULT 'general' COMMENT 'musica, arte, naturaleza, lectura, cocina, bienestar',
    is_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 = activo, 0 = dado de baja por el usuario',
    source_conversation_id VARCHAR(64) NULL DEFAULT NULL COMMENT 'Conversación donde se aprendió el gusto',
    learned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_user_taste_name (user_id, name),
    INDEX idx_tastes_user_active (user_id, is_active),
    CONSTRAINT fk_tastes_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.4. Tabla: taste_stories (Gustos en Detalle / Mini-Blogs curados por Fifo)
-- Incluye validación de privacidad (Sección 6 de FIFO_DATABASE_MAP.md)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS taste_stories (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(180) NOT NULL,
    subtitle VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    tags JSON NOT NULL COMMENT 'Arreglo JSON de etiquetas temáticas, ej: ["Familia", "Mayo"]',
    icon_category VARCHAR(40) NOT NULL DEFAULT 'heart' COMMENT 'celebration, music, book, nature, restaurant, walk, heart',
    is_sensitive TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0 = registro positivo estándar, 1 = información sensible',
    explicit_consent TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Requiere 1 si is_sensitive = 1',
    privacy_level ENUM('public_profile', 'private') NOT NULL DEFAULT 'public_profile',
    learned_from VARCHAR(255) NOT NULL DEFAULT 'Conversación cotidiana con Fifo',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_taste_stories_user (user_id, created_at DESC),
    CONSTRAINT chk_taste_stories_consent
        CHECK (is_sensitive = 0 OR explicit_consent = 1 OR privacy_level = 'private'),
    CONSTRAINT fk_taste_stories_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.5. Tabla: social_posts (Comunidad Fifo Amigos)
-- Regla estricta: nunca publicar datos sensibles sin explicit_consent = 1
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_posts (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    author_name VARCHAR(100) NOT NULL,
    author_age TINYINT UNSIGNED NOT NULL,
    relation_label VARCHAR(120) NOT NULL DEFAULT 'Comunidad Fifo' COMMENT 'Ej: Amiga cercana · Lectura y paseos',
    category VARCHAR(80) NOT NULL DEFAULT 'Bienestar',
    content TEXT NOT NULL,
    time_ago_label VARCHAR(60) NOT NULL DEFAULT 'Hace poco',
    is_sensitive TINYINT(1) NOT NULL DEFAULT 0,
    explicit_consent TINYINT(1) NOT NULL DEFAULT 0,
    likes_count INT UNSIGNED NOT NULL DEFAULT 0,
    comments_count INT UNSIGNED NOT NULL DEFAULT 0,
    accent_color_hex BIGINT UNSIGNED NOT NULL DEFAULT 4281908728 COMMENT 'Color ARGB hex (ej: 0xFF38BDF8)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_social_posts_feed (created_at DESC),
    INDEX idx_social_posts_user (user_id, created_at DESC),
    CONSTRAINT chk_social_posts_consent
        CHECK (is_sensitive = 0 OR explicit_consent = 1),
    CONSTRAINT fk_social_posts_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.6. Tabla: reminders_and_events (Recordatorios de medicamentos y calendario)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reminders_and_events (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL COMMENT 'Ej: Tomar pastilla de la presión (Enalapril)',
    time_str VARCHAR(40) NOT NULL COMMENT 'Hora legible o HH:mm (ej: 20:00)',
    due_datetime DATETIME NULL DEFAULT NULL COMMENT 'Fecha y hora exacta programada',
    repeat_rule ENUM('once', 'daily', 'weekly', 'monthly') NOT NULL DEFAULT 'daily',
    category ENUM('medication', 'hobby', 'family', 'doctor', 'health') NOT NULL DEFAULT 'medication',
    is_completed TINYINT(1) NOT NULL DEFAULT 0,
    calendar_event_id BIGINT NULL DEFAULT NULL COMMENT 'ID en CalendarContract de Android si fue sincronizado',
    spoken_text TEXT NOT NULL COMMENT 'Frase exacta con la que Fifo anuncia el recordatorio',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_reminders_user_pending (user_id, is_completed, time_str),
    CONSTRAINT fk_reminders_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.7A. Tabla: past_conversations (Bitácora resumida para la UI del perfil)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS past_conversations (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(180) NOT NULL,
    date_label VARCHAR(60) NOT NULL DEFAULT 'Hoy',
    duration_label VARCHAR(40) NOT NULL DEFAULT '5 min',
    duration_seconds INT UNSIGNED NOT NULL DEFAULT 300,
    summary TEXT NOT NULL,
    topic_tag VARCHAR(80) NOT NULL DEFAULT 'Conversación',
    icon_name VARCHAR(40) NOT NULL DEFAULT 'heart',
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_past_conv_user (user_id, recorded_at DESC),
    CONSTRAINT fk_past_conv_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.7B. Tabla: memories (Recuerdos puntuales entrañables aprendidos por Fifo)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memories (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    emoji VARCHAR(16) NOT NULL DEFAULT '⭐',
    title VARCHAR(180) NOT NULL,
    detail TEXT NOT NULL,
    learned_date_label VARCHAR(80) NOT NULL DEFAULT 'Aprendido recientemente',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_memories_user (user_id, created_at DESC),
    CONSTRAINT fk_memories_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 7.2. Tabla: conversations_full (Capa Servidor: Transcripción íntegra)
-- Soporta búsqueda FULLTEXT para la skill recall_past_context
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversations_full (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP NULL DEFAULT NULL,
    duration_seconds INT UNSIGNED NOT NULL DEFAULT 0,
    turns_json JSON NOT NULL COMMENT 'Historial secuencial de turnos [{speaker, text, timestamp}]',
    full_transcript LONGTEXT NOT NULL COMMENT 'Texto completo unificado para búsqueda profunda',
    extracted_topics JSON NOT NULL COMMENT 'Lista JSON de temas detectados',
    named_entities JSON NOT NULL COMMENT 'Lista JSON de personas, lugares o fechas mencionadas',
    sentiment_trend VARCHAR(60) NOT NULL DEFAULT 'neutral',
    INDEX idx_conv_full_user_date (user_id, started_at DESC),
    FULLTEXT KEY ft_conv_full_transcript (full_transcript),
    CONSTRAINT fk_conv_full_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 7.3. Tabla: conversation_fragments (Capa Compacta sincronizable al celular)
-- Ventana rápida de contexto (≤150 palabras por fragmento)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation_fragments (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    server_conversation_id VARCHAR(64) NULL DEFAULT NULL,
    key_topics JSON NOT NULL COMMENT 'Temas clave ["orquídeas", "riego por inmersión"]',
    named_entities JSON NOT NULL COMMENT 'Personas y lugares ["Lucía", "Tomás"]',
    detected_mood VARCHAR(60) NOT NULL DEFAULT 'neutral',
    compact_summary TEXT NOT NULL COMMENT 'Resumen compacto de <= 150 palabras',
    primary_tag VARCHAR(80) NOT NULL DEFAULT 'General',
    duration_seconds INT UNSIGNED NOT NULL DEFAULT 0,
    contains_sensitive_health TINYINT(1) NOT NULL DEFAULT 0,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fragments_user_recent (user_id, recorded_at DESC),
    FULLTEXT KEY ft_fragments_summary (compact_summary, primary_tag),
    CONSTRAINT fk_fragments_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_fragments_conv_full
        FOREIGN KEY (server_conversation_id) REFERENCES conversations_full(id)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.8. Tabla: device_locations (Rastreo del Robot físico ESP32 y última conexión GPS)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_locations (
    device_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'ID BLE del ESP32-S3 (ej: FIFO-S3-ESP32)',
    user_id VARCHAR(64) NOT NULL,
    is_connected TINYINT(1) NOT NULL DEFAULT 0,
    last_connected_time VARCHAR(80) NOT NULL DEFAULT 'Desconectado',
    last_known_latitude DOUBLE NOT NULL DEFAULT -33.4255,
    last_known_longitude DOUBLE NOT NULL DEFAULT -70.6143,
    last_known_address VARCHAR(255) NOT NULL DEFAULT 'Av. Providencia 1234, Santiago',
    last_known_room VARCHAR(120) NOT NULL DEFAULT 'Cerca del Living / Mesa de noche',
    signal_strength_rssi INT NOT NULL DEFAULT -64,
    is_beeping TINYINT(1) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_device_user (user_id),
    CONSTRAINT fk_device_locations_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.9. Tabla: user_friendships (Conexiones de amigos en Fifo Amigos)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_friendships (
    user_id VARCHAR(64) NOT NULL,
    friend_user_id VARCHAR(64) NOT NULL,
    relation_description VARCHAR(120) NOT NULL DEFAULT 'Amistad en Fifo',
    shared_tastes_count TINYINT UNSIGNED NOT NULL DEFAULT 2,
    status ENUM('connected', 'Suggested', 'invited') NOT NULL DEFAULT 'connected',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, friend_user_id),
    CONSTRAINT fk_friendship_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friendship_friend FOREIGN KEY (friend_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2.10. Tabla: community_activities (Catálogo de Actividades Comunitarias)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS community_activities (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    spots_label VARCHAR(80) NOT NULL,
    date_label VARCHAR(80) NOT NULL,
    location_name VARCHAR(160) NOT NULL,
    distance_label VARCHAR(60) NOT NULL,
    icon_category VARCHAR(40) NOT NULL DEFAULT 'book' COMMENT 'book, music, park, movie, restaurant',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

CREATE TABLE IF NOT EXISTS activity_participants (
    activity_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    status ENUM('joined', 'invited') NOT NULL DEFAULT 'joined',
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (activity_id, user_id),
    CONSTRAINT fk_act_part_activity FOREIGN KEY (activity_id) REFERENCES community_activities(id) ON DELETE CASCADE,
    CONSTRAINT fk_act_part_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_es_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 6.3. Triggers de Salvaguarda Ética: Bloqueo de contenido sexual en BD
-- -----------------------------------------------------------------------------
DELIMITER $$

CREATE TRIGGER trg_social_posts_safety_check
BEFORE INSERT ON social_posts
FOR EACH ROW
BEGIN
    IF LOWER(NEW.content) REGEXP '\\b(sexo|sexual|erotico|erótico|porno|pornografia|pornografía|genitales)\\b' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'BLOQUEO DE SEGURIDAD FIFO: Contenido con connotación sexual prohibido.';
    END IF;
END$$

CREATE TRIGGER trg_taste_stories_safety_check
BEFORE INSERT ON taste_stories
FOR EACH ROW
BEGIN
    IF LOWER(NEW.description) REGEXP '\\b(sexo|sexual|erotico|erótico|porno|pornografia|pornografía|genitales)\\b' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'BLOQUEO DE SEGURIDAD FIFO: Contenido con connotación sexual prohibido.';
    END IF;
END$$

-- -----------------------------------------------------------------------------
-- Procedimiento Almacenado para el Skill: recall_past_context
-- Busca en fragmentos compactos y transcripciones completas del servidor
-- -----------------------------------------------------------------------------
CREATE PROCEDURE sp_recall_past_context(
    IN p_user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_es_0900_ai_ci,
    IN p_query VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_es_0900_ai_ci
)
BEGIN
    SELECT
        cf.id AS fragment_id,
        cf.primary_tag,
        cf.detected_mood,
        cf.compact_summary,
        cf.key_topics,
        cf.named_entities,
        cfull.full_transcript,
        cf.recorded_at
    FROM conversation_fragments cf
    LEFT JOIN conversations_full cfull ON cfull.id = cf.server_conversation_id
    WHERE cf.user_id = p_user_id
      AND (
          MATCH(cf.compact_summary, cf.primary_tag) AGAINST(p_query IN NATURAL LANGUAGE MODE)
          OR cf.compact_summary LIKE CONCAT('%', p_query, '%')
          OR (CAST(cf.key_topics AS CHAR) COLLATE utf8mb4_es_0900_ai_ci) LIKE CONCAT('%', p_query, '%')
          OR (CAST(cf.named_entities AS CHAR) COLLATE utf8mb4_es_0900_ai_ci) LIKE CONCAT('%', p_query, '%')
          OR (cfull.full_transcript IS NOT NULL AND MATCH(cfull.full_transcript) AGAINST(p_query IN NATURAL LANGUAGE MODE))
          OR (cfull.full_transcript IS NOT NULL AND cfull.full_transcript LIKE CONCAT('%', p_query, '%'))
      )
    ORDER BY cf.recorded_at DESC
    LIMIT 5;
END$$

DELIMITER ;
