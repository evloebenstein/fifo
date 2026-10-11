-- =============================================================================
-- FIFO VOICE PIPELINE - DATOS DE PRUEBA MULTI-USUARIO (5 Usuarios Completos)
-- Usuarios incluidos:
--   1. usr_lucia_01     - Lucía González (68 años, Santiago)
--   2. usr_sofia_02     - Sofía Rojas (66 años, Providencia)
--   3. usr_mateo_03     - Mateo Silva (72 años, Ñuñoa)
--   4. usr_valentina_04 - Valentina Morales (64 años, Las Condes)
--   5. usr_diego_05     - Diego Herrera (70 años, La Reina)
-- =============================================================================

SET NAMES utf8mb4;
USE fifo_db;

-- -----------------------------------------------------------------------------
-- 1. USUARIOS (Tabla: users)
-- -----------------------------------------------------------------------------
INSERT INTO users (
    id, full_name, email, birth_date, birth_year, estimated_age, gender_identity, city, avatar_bg_hex, short_quote
) VALUES
    (
        'usr_lucia_01', 'Lucía González', 'lucia.gonzalez@correo.cl',
        '14 de Mayo, 1958', 1958, 68, 'Mujer', 'Santiago, Chile',
        4293514751, 'Disfruto mis orquídeas, un buen libro histórico y la música de piano.'
    ),
    (
        'usr_sofia_02', 'Sofía Rojas', 'sofia.rojas@correo.cl',
        '22 de Agosto, 1960', 1960, 66, 'Mujer', 'Providencia, Santiago',
        4293514751, 'Un libro y una buena charla, mi plan ideal.'
    ),
    (
        'usr_mateo_03', 'Mateo Silva', 'mateo.silva@correo.cl',
        '10 de Marzo, 1954', 1954, 72, 'Hombre', 'Ñuñoa, Santiago',
        4291623921, 'Me encanta descubrir rutas y recetas nuevas.'
    ),
    (
        'usr_valentina_04', 'Valentina Morales', 'valentina.morales@correo.cl',
        '05 de Noviembre, 1962', 1962, 64, 'Mujer', 'Las Condes, Santiago',
        4294962645, 'Siempre con una canción o un dibujo en mente.'
    ),
    (
        'usr_diego_05', 'Diego Herrera', 'diego.herrera@correo.cl',
        '19 de Julio, 1956', 1956, 70, 'Hombre', 'La Reina, Santiago',
        4294897802, 'Busco compañía para caminar sin prisa.'
    )
ON DUPLICATE KEY UPDATE
    full_name = VALUES(full_name),
    estimated_age = VALUES(estimated_age),
    short_quote = VALUES(short_quote);

-- -----------------------------------------------------------------------------
-- 2. PERFILES ENRIQUECIDOS POR IA (Tabla: user_profiles)
-- -----------------------------------------------------------------------------
INSERT INTO user_profiles (
    user_id, bio_ai, emergency_contact_name, emergency_contact_phone, preferred_address, continuous_listening_enabled
) VALUES
    (
        'usr_lucia_01',
        'Amante de las novelas de historia, la música clásica de piano y las mañanas tranquilas con café. Disfruta compartir con Fifo anécdotas de su familia, preparar recetas caseras y cuidar las orquídeas de su jardín.',
        'Carmen (Hija)', '+56987654321', 'Av. Providencia 1234, Providencia, Santiago', 0
    ),
    (
        'usr_sofia_02',
        'Profesora jubilada de literatura con gran amor por los conciertos de piano y las tertulias de tarde con café de grano. Le encanta recomendar novelas latinoamericanas en el club de lectura del barrio.',
        'Andrés (Hijo)', '+56976543210', 'Eliodoro Yáñez 1890, Providencia, Santiago', 0
    ),
    (
        'usr_mateo_03',
        'Apasionado de las caminatas matutinas junto al río, el cine clásico italiano y la cocina chilena de antaño. Siempre está probando nuevas preparaciones al horno para sorprender a sus nietos los domingos.',
        'Claudia (Sobrina)', '+56965432109', 'Av. Irarrázaval 3450, Ñuñoa, Santiago', 0
    ),
    (
        'usr_valentina_04',
        'Espíritu creativo que disfruta pintar acuarelas botánicas en su terraza mientras escucha boleros y música de cámara. Participa activamente en talleres de pintura y clubes de lectura.',
        'Javier (Hermano)', '+56954321098', 'Av. Apoquindo 4500, Las Condes, Santiago', 0
    ),
    (
        'usr_diego_05',
        'Fotógrafo aficionado que retrata aves y parques de Santiago con su cámara réflex. Disfruta las caminatas sin prisa, las películas clásicas de misterio y conversar en banca de plaza.',
        'Elena (Hija)', '+56943210987', 'Av. Príncipe de Gales 6200, La Reina, Santiago', 0
    )
ON DUPLICATE KEY UPDATE
    bio_ai = VALUES(bio_ai),
    preferred_address = VALUES(preferred_address);

-- -----------------------------------------------------------------------------
-- 3. GUSTOS E INTERESES POR USUARIO (Tabla: tastes)
-- -----------------------------------------------------------------------------
INSERT INTO tastes (id, user_id, name, category, is_active, source_conversation_id) VALUES
    -- Lucía
    ('tst_luc_01', 'usr_lucia_01', 'Lectura histórica', 'lectura', 1, 'srv_conv_001'),
    ('tst_luc_02', 'usr_lucia_01', 'Música clásica', 'musica', 1, 'srv_conv_002'),
    ('tst_luc_03', 'usr_lucia_01', 'Paseos en el parque', 'naturaleza', 1, 'srv_conv_001'),
    ('tst_luc_04', 'usr_lucia_01', 'Jardinería', 'naturaleza', 1, 'srv_conv_001'),
    ('tst_luc_05', 'usr_lucia_01', 'Cocina tradicional', 'cocina', 1, 'srv_conv_003'),
    -- Sofía
    ('tst_sof_01', 'usr_sofia_02', 'Lectura', 'lectura', 1, 'srv_conv_101'),
    ('tst_sof_02', 'usr_sofia_02', 'Música', 'musica', 1, 'srv_conv_101'),
    ('tst_sof_03', 'usr_sofia_02', 'Café', 'cocina', 1, 'srv_conv_102'),
    ('tst_sof_04', 'usr_sofia_02', 'Tertulia literaria', 'lectura', 1, 'srv_conv_101'),
    -- Mateo
    ('tst_mat_01', 'usr_mateo_03', 'Paseos', 'naturaleza', 1, 'srv_conv_201'),
    ('tst_mat_02', 'usr_mateo_03', 'Cine', 'arte', 1, 'srv_conv_202'),
    ('tst_mat_03', 'usr_mateo_03', 'Cocina', 'cocina', 1, 'srv_conv_201'),
    ('tst_mat_04', 'usr_mateo_03', 'Senderismo suave', 'naturaleza', 1, 'srv_conv_201'),
    -- Valentina
    ('tst_val_01', 'usr_valentina_04', 'Música', 'musica', 1, 'srv_conv_301'),
    ('tst_val_02', 'usr_valentina_04', 'Arte', 'arte', 1, 'srv_conv_301'),
    ('tst_val_03', 'usr_valentina_04', 'Lectura', 'lectura', 1, 'srv_conv_301'),
    ('tst_val_04', 'usr_valentina_04', 'Acuarela botánica', 'arte', 1, 'srv_conv_301'),
    -- Diego
    ('tst_die_01', 'usr_diego_05', 'Cine', 'arte', 1, 'srv_conv_401'),
    ('tst_die_02', 'usr_diego_05', 'Fotografía', 'arte', 1, 'srv_conv_401'),
    ('tst_die_03', 'usr_diego_05', 'Paseos', 'naturaleza', 1, 'srv_conv_401'),
    ('tst_die_04', 'usr_diego_05', 'Observación de aves', 'naturaleza', 1, 'srv_conv_401')
ON DUPLICATE KEY UPDATE is_active = VALUES(is_active);

-- -----------------------------------------------------------------------------
-- 4. HISTORIAS DETALLADAS DE GUSTOS / MINI-BLOGS (Tabla: taste_stories)
-- -----------------------------------------------------------------------------
INSERT INTO taste_stories (
    id, user_id, title, subtitle, description, tags, icon_category, is_sensitive, explicit_consent, privacy_level, learned_from
) VALUES
    -- Historias de Lucía
    (
        't_bday', 'usr_lucia_01',
        'Cumpleaños y orígenes familiares',
        'Nacida el 14 de Mayo de 1958 en Santiago',
        'Lucía nació en una fresca mañana de otoño en 1958 en el barrio tradicional de Santiago. Siempre recuerda cómo en su familia celebraban con torta de milhojas casera y música de fondo. Para Fifo es un honor acompañarla y tener siempre presente su día especial.',
        JSON_ARRAY('Cumpleaños', 'Familia', 'Santiago'),
        'celebration', 0, 0, 'public_profile',
        'Charla inicial y configuración de perfil con Fifo'
    ),
    (
        't_gardening', 'usr_lucia_01',
        'Cuidado y amor por las orquídeas',
        'Riego por inmersión y luz filtrada de mañana',
        'En sus charlas con Fifo, Lucía contó que tiene 4 maceteros en su balcón. Disfruta regarlas los miércoles y limpiar con cuidado sus hojas verdes con un paño húmedo mientras escucha la radio.',
        JSON_ARRAY('Jardinería', 'Plantas', 'Paciencia'),
        'nature', 0, 0, 'public_profile',
        'Charla matutina del 2 de Octubre'
    ),
    (
        't_music', 'usr_lucia_01',
        'Conciertos de piano y música clásica',
        'Chopin y las tardes tranquilas de otoño',
        'La música clásica le transmite una paz inmensa. Cuando le pide a Fifo acompañarla a descansar, las melodías de piano de la época romántica son sus predilectas para recordar su juventud.',
        JSON_ARRAY('Música', 'Relajo', 'Piano'),
        'music', 0, 0, 'public_profile',
        'Conversación sobre melodías relajantes'
    ),
    (
        't_food', 'usr_lucia_01',
        'Recetas tradicionales de la abuela',
        'Cazuela de ave y pan amasado con pebre',
        'Un gusto que Fifo descubrió con alegría: Lucía atesora un recetario escrito a mano con más de 40 años de antigüedad. Cocinar despacio y con ingredientes frescos es su forma de dar cariño.',
        JSON_ARRAY('Cocina', 'Recetas', 'Tradición'),
        'restaurant', 0, 0, 'public_profile',
        'Charla de mediodía sobre el almuerzo'
    ),
    (
        't_books', 'usr_lucia_01',
        'Novelas históricas y biografías',
        'Lecturas pausadas con té de manzanilla',
        'Le fascina la historia de Chile y las memorias del siglo XX. Le gusta comentarle a Fifo capítulos interesantes de libros que lee en su sillón favorito cerca de la ventana.',
        JSON_ARRAY('Lectura', 'Historia', 'Tranquilidad'),
        'book', 0, 0, 'public_profile',
        'Charla nocturna de bienestar'
    ),
    (
        't_park', 'usr_lucia_01',
        'Caminatas respirando aire puro',
        'Paseos de 20 minutos por plazas con sombra',
        'El hábito saludable que Fifo más motiva: salir por las mañanas a caminar a paso tranquilo, sintiendo el aire fresco y saludando a los vecinos del barrio.',
        JSON_ARRAY('Salud', 'Paseos', 'Bienestar'),
        'walk', 0, 0, 'public_profile',
        'Plan de envejecimiento activo de Fifo'
    ),
    -- Historias de Sofía
    (
        't_sof_books', 'usr_sofia_02',
        'El club de lectura de los jueves',
        'Novelas de Elena Ferrante y autoras chilenas',
        'Sofía disfruta subrayar frases memorables con lápiz grafito y compartirlas junto a una taza de café recién molido. Para ella cada libro es un puente hacia nuevas amistades.',
        JSON_ARRAY('Lectura', 'Café', 'Tertulia'),
        'book', 0, 0, 'public_profile',
        'Charla sobre sus libros favoritos'
    ),
    -- Historias de Mateo
    (
        't_mat_routes', 'usr_mateo_03',
        'Rutas junto al río y pan recién horneado',
        'Caminatas matutinas y cocina de domingo',
        'Mateo combina su gusto por recorrer senderos arbolados con el arte de hornear empanadas y pastel de choclo para toda la familia.',
        JSON_ARRAY('Paseos', 'Cocina', 'Naturaleza'),
        'walk', 0, 0, 'public_profile',
        'Charla de fin de semana con Fifo'
    ),
    -- Historias de Valentina
    (
        't_val_art', 'usr_valentina_04',
        'Acuarelas de flores chilenas',
        'Pintura al aire libre con música suave',
        'Valentina lleva siempre una libreta de papel algodón para retratar camelias, magnolios y aves que observa en sus paseos.',
        JSON_ARRAY('Arte', 'Acuarela', 'Música'),
        'nature', 0, 0, 'public_profile',
        'Conversación sobre su taller de pintura'
    ),
    -- Historias de Diego
    (
        't_die_photo', 'usr_diego_05',
        'Fotografía de parques y aves urbanas',
        'Mirada paciente con cámara clásica',
        'Diego disfruta salir temprano con su cámara para capturar la luz de la mañana entre los árboles y compartir sus postales con amigos.',
        JSON_ARRAY('Fotografía', 'Paseos', 'Cine'),
        'walk', 0, 0, 'public_profile',
        'Charla sobre fotografía en el parque'
    )
ON DUPLICATE KEY UPDATE title = VALUES(title);

-- -----------------------------------------------------------------------------
-- 5. PUBLICACIONES EN FIFO AMIGOS (Tabla: social_posts)
-- Incluye publicaciones de Lucía, Sofía, Mateo, Valentina y Diego
-- -----------------------------------------------------------------------------
INSERT INTO social_posts (
    id, user_id, author_name, author_age, relation_label, category, content, time_ago_label, is_sensitive, explicit_consent, likes_count, comments_count, accent_color_hex
) VALUES
    (
        'post_1', 'usr_lucia_01', 'Lucía', 68, 'Amiga cercana · Lectura y paseos', 'Jardinería',
        '¡Qué felicidad! Hoy abrió la primera flor de mi orquídea blanca. Fifo me recordó regarla ayer por la tarde y valió toda la pena la paciencia.',
        'Hoy, 10:30', 0, 0, 14, 4, 4279286657
    ),
    (
        'post_friends_1', 'usr_lucia_01', 'Lucía', 68, 'Amiga cercana · Lectura y paseos', 'Lectura',
        'Acabo de terminar una novela de Elena Ferrante en el parque. Me encantaría encontrar a alguien para comentarla con calma y luego dar un paseo por el jardín.',
        'Hace 2 h', 0, 0, 18, 6, 4282170872
    ),
    (
        'post_friends_2', 'usr_sofia_02', 'Sofía', 66, 'Amiga cercana · Música y tertulia', 'Música',
        'Esta tarde escuché un concierto de piano en el auditorio y me recordó a mis clases de juventud. ¿Alguien quiere hablar de música clásica y compartir recomendaciones?',
        'Hace 5 h', 0, 0, 24, 9, 4287323382
    ),
    (
        'post_friends_3', 'usr_mateo_03', 'Mateo', 72, 'Amigo cercano · Paseos y lectura', 'Paseos',
        'Ayer descubrí un nuevo sendero cerca del río y me gustaría repetirlo con alguien que disfrute de la naturaleza y la conversación tranquila.',
        'Hace 1 d', 0, 0, 12, 4, 4279286657
    ),
    (
        'post_2', 'usr_lucia_01', 'Lucía', 68, 'Amiga cercana · Lectura y paseos', 'Música',
        'Escuchando los nocturnos de Chopin mientras me tomo un té tibio. Qué lindo es encontrar momentos de calma en la tarde.',
        'Ayer', 0, 0, 19, 6, 4287323382
    ),
    (
        'post_friends_4', 'usr_valentina_04', 'Valentina', 64, 'Amiga · Música y acuarela', 'Arte',
        'Terminé una acuarela de las camelias del parque. Si alguien se anima este sábado, podemos llevar libreta y lápices después del concierto.',
        'Hace 2 d', 0, 0, 21, 7, 4294278155
    ),
    (
        'post_friends_5', 'usr_diego_05', 'Diego', 70, 'Amigo · Fotografía y cine', 'Fotografía',
        'Hoy logré fotografiar un zorzal y un chincol tomando sol en la plaza. Qué maravilla detenerse a mirar con calma.',
        'Hace 2 d', 0, 0, 16, 5, 4282170872
    ),
    (
        'post_3', 'usr_lucia_01', 'Lucía', 68, 'Amiga cercana · Lectura y paseos', 'Cocina',
        'Preparé una sopita casera con zapallo camote y cilantro fresco de la feria. Quedó deliciosa para este día fresco.',
        'Hace 3 días', 0, 0, 23, 8, 4294278155
    )
ON DUPLICATE KEY UPDATE likes_count = VALUES(likes_count), content = VALUES(content);

-- -----------------------------------------------------------------------------
-- 6. RECORDATORIOS Y EVENTOS MULTI-USUARIO (Tabla: reminders_and_events)
-- -----------------------------------------------------------------------------
INSERT INTO reminders_and_events (
    id, user_id, title, time_str, due_datetime, repeat_rule, category, is_completed, spoken_text
) VALUES
    -- Lucía
    (
        'rem_1', 'usr_lucia_01',
        'Tomar pastilla de la presión (Enalapril)',
        '20:00', '2026-10-04 20:00:00', 'daily', 'medication', 0,
        'Lucía, son las ocho de la noche: es hora de su pastilla de la presión con un vaso de agua.'
    ),
    (
        'rem_2', 'usr_lucia_01',
        'Llamar a Carmen (Hija)',
        '17:30', '2026-10-04 17:30:00', 'once', 'family', 0,
        'Lucía, le recuerdo llamar a su hija Carmen para contarle cómo estuvo su día.'
    ),
    (
        'rem_3', 'usr_lucia_01',
        'Paseo de 15 minutos por la plaza',
        '11:00', '2026-10-04 11:00:00', 'daily', 'health', 1,
        'Lucía, hace una linda mañana para dar su paseo tranquilo de quince minutos por la plaza.'
    ),
    -- Sofía
    (
        'rem_sof_1', 'usr_sofia_02',
        'Tomar vitamina D con el desayuno',
        '09:30', '2026-10-04 09:30:00', 'daily', 'medication', 1,
        'Sofía, buen día. Recuerde tomar su cápsula de vitamina D junto con el desayuno.'
    ),
    (
        'rem_sof_2', 'usr_sofia_02',
        'Club de lectura en Biblioteca Central',
        '17:00', '2026-10-08 17:00:00', 'weekly', 'hobby', 0,
        'Sofía, hoy a las cinco de la tarde tiene su encuentro del Club de Lectura.'
    ),
    -- Mateo
    (
        'rem_mat_1', 'usr_mateo_03',
        'Tomar Losartán 50mg',
        '08:30', '2026-10-04 08:30:00', 'daily', 'medication', 1,
        'Don Mateo, son las ocho y media: recuerde su pastilla de Losartán.'
    ),
    (
        'rem_mat_2', 'usr_mateo_03',
        'Caminata por el Parque Bicentenario',
        '10:30', '2026-10-04 10:30:00', 'daily', 'health', 0,
        'Mateo, es buena hora para salir a caminar junto al río con calzado cómodo.'
    ),
    -- Valentina
    (
        'rem_val_1', 'usr_valentina_04',
        'Preparar materiales de acuarela',
        '16:00', '2026-10-04 16:00:00', 'weekly', 'hobby', 0,
        'Valentina, recuerde alistar sus pinceles y papel de algodón para la tarde de pintura.'
    ),
    -- Diego
    (
        'rem_die_1', 'usr_diego_05',
        'Control oftalmológico anual',
        '15:00', '2026-10-06 15:00:00', 'once', 'doctor', 0,
        'Diego, recuerde su consulta con el oftalmólogo a las tres de la tarde.'
    )
ON DUPLICATE KEY UPDATE is_completed = VALUES(is_completed);

-- -----------------------------------------------------------------------------
-- 7A. CONVERSACIONES PASADAS RESUMIDAS PARA LA UI (Tabla: past_conversations)
-- -----------------------------------------------------------------------------
INSERT INTO past_conversations (
    id, user_id, title, date_label, duration_label, duration_seconds, summary, topic_tag, icon_name
) VALUES
    -- Lucía
    (
        'c1', 'usr_lucia_01',
        'Charla sobre orquídeas y flores', 'Hoy', '4 min', 240,
        'Lucía le contó a Fifo cómo cuida sus plantas en maceteros de greda y lo feliz que le hace verlas florecer.',
        'Jardinería', 'heart'
    ),
    (
        'c2', 'usr_lucia_01',
        'Planes de fin de semana y música', 'Ayer', '6 min', 360,
        'Conversaron sobre conciertos de piano clásico y la visita que le hará su nieto este domingo.',
        'Música', 'music'
    ),
    (
        'c3', 'usr_lucia_01',
        'Recetas de cocina tradicional', 'Hace 3 días', '5 min', 300,
        'Fifo aprendió sobre la cazuela de ave casera y el secreto de dorar la cebolla con comino suave.',
        'Cocina', 'restaurant'
    ),
    -- Sofía
    (
        'c_sof_1', 'usr_sofia_02',
        'Comentario sobre novela de Elena Ferrante', 'Hoy', '7 min', 420,
        'Sofía reflexionó con Fifo sobre la amistad femenina en la saga napolitana y planeó comentarla con Lucía.',
        'Lectura', 'book'
    ),
    -- Mateo
    (
        'c_mat_1', 'usr_mateo_03',
        'Receta de pastel de choclo en paila de greda', 'Ayer', '5 min', 310,
        'Mateo explicó cómo prepara el pino con albahaca fresca para el almuerzo familiar del domingo.',
        'Cocina', 'restaurant'
    ),
    -- Valentina
    (
        'c_val_1', 'usr_valentina_04',
        'Colores de primavera para acuarela', 'Hace 2 días', '6 min', 350,
        'Valentina conversó sobre cómo mezclar tonos carmín y verde oliva para pintar las camelias del jardín.',
        'Arte', 'heart'
    ),
    -- Diego
    (
        'c_die_1', 'usr_diego_05',
        'Avistamiento de aves en La Reina', 'Hace 2 días', '5 min', 290,
        'Diego relató su paseo matutino fotografiando loicas y chincoles cerca de la cordillera.',
        'Fotografía', 'walk'
    )
ON DUPLICATE KEY UPDATE summary = VALUES(summary);

-- -----------------------------------------------------------------------------
-- 7B. RECUERDOS PUNTUALES APRENDIDOS POR FIFO (Tabla: memories)
-- -----------------------------------------------------------------------------
INSERT INTO memories (id, user_id, emoji, title, detail, learned_date_label) VALUES
    -- Lucía
    ('m1', 'usr_lucia_01', '🌺', 'Flor favorita: Orquídeas blancas', 'Le recuerdan el patio iluminado de su infancia en el sur.', 'Aprendido hace 2 días'),
    ('m2', 'usr_lucia_01', '☕', 'Costumbre de café suave a las 11:00', 'Siempre con poca azúcar y una galleta de avena.', 'Aprendido ayer'),
    ('m3', 'usr_lucia_01', '📻', 'Radio Beethoven por las tardes', 'Le acompaña a descansar mientras teje o lee.', 'Aprendido hoy'),
    -- Sofía
    ('m_sof_1', 'usr_sofia_02', '📚', 'Autora favorita: Elena Ferrante', 'Disfruta leer sus novelas junto a la ventana con luz natural.', 'Aprendido ayer'),
    ('m_sof_2', 'usr_sofia_02', '🎹', 'Estudió piano en su juventud', 'Su pieza predilecta es el Claro de Luna de Debussy.', 'Aprendido hace 3 días'),
    -- Mateo
    ('m_mat_1', 'usr_mateo_03', '🌿', 'Ingrediente secreto: Albahaca fresca', 'Siempre agrega hojas frescas de su huerta al pastel de choclo.', 'Aprendido ayer'),
    -- Valentina
    ('m_val_1', 'usr_valentina_04', '🎨', 'Pinta en papel 100% algodón', 'Prefiere pintar por las mañanas cuando la luz es más suave.', 'Aprendido hace 2 días'),
    -- Diego
    ('m_die_1', 'usr_diego_05', '📷', 'Cámara favorita: Nikon clásica', 'La conserva impecable desde su viaje al sur en 1988.', 'Aprendido hace 4 días')
ON DUPLICATE KEY UPDATE detail = VALUES(detail);

-- -----------------------------------------------------------------------------
-- 8. CONVERSACIONES COMPLETAS EN SERVIDOR (Tabla: conversations_full)
-- -----------------------------------------------------------------------------
INSERT INTO conversations_full (
    id, user_id, started_at, ended_at, duration_seconds, turns_json, full_transcript, extracted_topics, named_entities, sentiment_trend
) VALUES
    (
        'srv_conv_001', 'usr_lucia_01',
        '2026-10-03 10:30:00', '2026-10-03 10:34:00', 240,
        JSON_ARRAY(
            JSON_OBJECT('speaker', 'user', 'text', 'Hola Fifo, hoy estuve regando mis 4 maceteros de orquídeas en el balcón.'),
            JSON_OBJECT('speaker', 'assistant', 'text', 'Qué lindo, Lucía. Las orquídeas agradecen mucho el cariño. ¿Cómo las riega usted?'),
            JSON_OBJECT('speaker', 'user', 'text', 'Las riego por inmersión los miércoles y limpio sus hojas con un paño húmedo escuchando la radio.')
        ),
        'Lucía: Hola Fifo, hoy estuve regando mis 4 maceteros de orquídeas en el balcón. Fifo: Qué lindo, Lucía. Las orquídeas agradecen mucho el cariño. Lucía: Las riego por inmersión los miércoles y limpio sus hojas con un paño húmedo escuchando la radio.',
        JSON_ARRAY('orquídeas', 'riego por inmersión', 'balcón', 'maceteros de greda'),
        JSON_ARRAY('Lucía', 'Fifo'),
        'contenta'
    ),
    (
        'srv_conv_002', 'usr_lucia_01',
        '2026-10-02 16:00:00', '2026-10-02 16:06:00', 360,
        JSON_ARRAY(
            JSON_OBJECT('speaker', 'user', 'text', 'Fifo, el domingo viene a verme mi nieto Tomás y quiero ponerle los nocturnos de Chopin en piano.'),
            JSON_OBJECT('speaker', 'assistant', 'text', 'Qué maravilla, Lucía. Compartir la música clásica con su nieto Tomás será un recuerdo inolvidable.')
        ),
        'Lucía: Fifo, el domingo viene a verme mi nieto Tomás y quiero ponerle los nocturnos de Chopin en piano. Fifo: Qué maravilla, Lucía. Compartir la música clásica con su nieto Tomás será un recuerdo inolvidable.',
        JSON_ARRAY('Chopin', 'conciertos de piano', 'nieto Tomás', 'visita domingo'),
        JSON_ARRAY('Lucía', 'Tomás', 'Chopin'),
        'ilusionada'
    ),
    (
        'srv_conv_003', 'usr_lucia_01',
        '2026-09-30 12:15:00', '2026-09-30 12:20:00', 300,
        JSON_ARRAY(
            JSON_OBJECT('speaker', 'user', 'text', 'Hoy hice la cazuela de ave del recetario manuscrito de mi abuela Rosa. El secreto es dorar la cebolla con una pizca de comino suave antes del caldo.'),
            JSON_OBJECT('speaker', 'assistant', 'text', 'Ese secreto de su abuela Rosa suena delicioso, Lucía. Guardaré ese detalle de la cebolla con comino suave.')
        ),
        'Lucía: Hoy hice la cazuela de ave del recetario manuscrito de mi abuela Rosa. El secreto es dorar la cebolla con una pizca de comino suave antes del caldo. Fifo: Ese secreto de su abuela Rosa suena delicioso, Lucía.',
        JSON_ARRAY('cazuela de ave', 'comino', 'recetario viejo', 'feria del barrio'),
        JSON_ARRAY('Lucía', 'abuela Rosa'),
        'nostálgica'
    ),
    (
        'srv_conv_101', 'usr_sofia_02',
        '2026-10-03 17:00:00', '2026-10-03 17:07:00', 420,
        JSON_ARRAY(
            JSON_OBJECT('speaker', 'user', 'text', 'Fifo, terminé el segundo tomo de Elena Ferrante y quiero comentarlo el jueves en la biblioteca con Lucía.'),
            JSON_OBJECT('speaker', 'assistant', 'text', 'Excelente idea, Sofía. A Lucía también le encantó esa novela.')
        ),
        'Sofía: Fifo, terminé el segundo tomo de Elena Ferrante y quiero comentarlo el jueves en la biblioteca con Lucía. Fifo: Excelente idea, Sofía.',
        JSON_ARRAY('Elena Ferrante', 'club de lectura', 'biblioteca central'),
        JSON_ARRAY('Sofía', 'Lucía', 'Elena Ferrante'),
        'entusiasmada'
    ),
    (
        'srv_conv_201', 'usr_mateo_03',
        '2026-10-03 11:00:00', '2026-10-03 11:05:10', 310,
        JSON_ARRAY(
            JSON_OBJECT('speaker', 'user', 'text', 'Hoy caminé por el sendero nuevo del río Mapocho en Providencia y compré choclos tiernos para el domingo.'),
            JSON_OBJECT('speaker', 'assistant', 'text', 'Qué buen paseo, don Mateo. Ese pastel de choclo del domingo quedará espectacular.')
        ),
        'Mateo: Hoy caminé por el sendero nuevo del río Mapocho en Providencia y compré choclos tiernos para el domingo. Fifo: Qué buen paseo, don Mateo.',
        JSON_ARRAY('sendero río', 'pastel de choclo', 'albahaca'),
        JSON_ARRAY('Mateo'),
        'animado'
    ),
    (
        'srv_conv_301', 'usr_valentina_04',
        '2026-10-02 15:00:00', '2026-10-02 15:05:50', 350,
        JSON_ARRAY(
            JSON_OBJECT('speaker', 'user', 'text', 'Estuve pintando camelias rosadas con acuarela mientras escuchaba un trío de cuerdas.'),
            JSON_OBJECT('speaker', 'assistant', 'text', 'Qué combinación más inspiradora, Valentina.')
        ),
        'Valentina: Estuve pintando camelias rosadas con acuarela mientras escuchaba un trío de cuerdas.',
        JSON_ARRAY('acuarela', 'camelias rosadas', 'música de cámara'),
        JSON_ARRAY('Valentina'),
        'inspirada'
    ),
    (
        'srv_conv_401', 'usr_diego_05',
        '2026-10-02 09:30:00', '2026-10-02 09:34:50', 290,
        JSON_ARRAY(
            JSON_OBJECT('speaker', 'user', 'text', 'Saqué fotografías preciosas de un zorzal y una loica en el parque de La Reina.'),
            JSON_OBJECT('speaker', 'assistant', 'text', 'Qué buena captura, Diego. Las mañanas son ideales para fotografiar aves.')
        ),
        'Diego: Saqué fotografías preciosas de un zorzal y una loica en el parque de La Reina.',
        JSON_ARRAY('fotografía', 'zorzal', 'loica', 'parque La Reina'),
        JSON_ARRAY('Diego'),
        'tranquilo'
    )
ON DUPLICATE KEY UPDATE duration_seconds = VALUES(duration_seconds);

-- -----------------------------------------------------------------------------
-- 9. FRAGMENTOS COMPACTOS PARA EL CELULAR (Tabla: conversation_fragments)
-- -----------------------------------------------------------------------------
INSERT INTO conversation_fragments (
    id, user_id, server_conversation_id, key_topics, named_entities, detected_mood, compact_summary, primary_tag, duration_seconds, contains_sensitive_health
) VALUES
    (
        'frag_01', 'usr_lucia_01', 'srv_conv_001',
        JSON_ARRAY('orquídeas', 'riego por inmersión', 'balcón', 'maceteros de greda'),
        JSON_ARRAY('Lucía', 'Fifo'),
        'contenta',
        'Lucía contó que tiene 4 maceteros de orquídeas en su balcón. Las riega por inmersión los miércoles y limpia las hojas con un paño húmedo mientras escucha la radio. Se mostró alegre hablando de sus plantas.',
        'Jardinería', 240, 0
    ),
    (
        'frag_02', 'usr_lucia_01', 'srv_conv_002',
        JSON_ARRAY('Chopin', 'conciertos de piano', 'nieto Tomás', 'visita domingo'),
        JSON_ARRAY('Lucía', 'Tomás', 'Chopin'),
        'ilusionada',
        'Conversaron sobre la música clásica y los nocturnos de Chopin. Lucía mencionó que su nieto Tomás la visitará el domingo y quiere enseñarle a escuchar piano. Se notó ilusionada por la visita.',
        'Música', 360, 0
    ),
    (
        'frag_03', 'usr_lucia_01', 'srv_conv_003',
        JSON_ARRAY('cazuela de ave', 'comino', 'recetario viejo', 'feria del barrio'),
        JSON_ARRAY('Lucía', 'abuela Rosa'),
        'nostálgica',
        'Fifo aprendió sobre la cazuela de ave casera con el secreto de dorar la cebolla con comino suave. Lucía habló del recetario manuscrito de su abuela Rosa con más de 40 años y de los ingredientes frescos de la feria.',
        'Cocina', 300, 0
    ),
    (
        'frag_sof_01', 'usr_sofia_02', 'srv_conv_101',
        JSON_ARRAY('Elena Ferrante', 'club de lectura', 'biblioteca central'),
        JSON_ARRAY('Sofía', 'Lucía', 'Elena Ferrante'),
        'entusiasmada',
        'Sofía terminó el segundo tomo de Elena Ferrante y planea comentarlo con Lucía el jueves en el club de lectura de la Biblioteca Central.',
        'Lectura', 420, 0
    ),
    (
        'frag_mat_01', 'usr_mateo_03', 'srv_conv_201',
        JSON_ARRAY('sendero río', 'pastel de choclo', 'albahaca'),
        JSON_ARRAY('Mateo'),
        'animado',
        'Mateo recorrió un nuevo sendero junto al río y compró choclos frescos y albahaca para cocinar pastel de choclo en paila de greda el domingo.',
        'Paseos', 310, 0
    ),
    (
        'frag_val_01', 'usr_valentina_04', 'srv_conv_301',
        JSON_ARRAY('acuarela', 'camelias rosadas', 'música de cámara'),
        JSON_ARRAY('Valentina'),
        'inspirada',
        'Valentina pintó camelias rosadas en papel de algodón mientras escuchaba música de cámara en su terraza.',
        'Arte', 350, 0
    ),
    (
        'frag_die_01', 'usr_diego_05', 'srv_conv_401',
        JSON_ARRAY('fotografía', 'zorzal', 'loica', 'parque La Reina'),
        JSON_ARRAY('Diego'),
        'tranquilo',
        'Diego salió temprano con su cámara réflex a fotografiar aves nativas como zorzales y loicas en el parque de La Reina.',
        'Fotografía', 290, 0
    )
ON DUPLICATE KEY UPDATE compact_summary = VALUES(compact_summary);

-- -----------------------------------------------------------------------------
-- 10. UBICACIÓN DE DISPOSITIVOS FÍSICOS ESP32-S3 (Tabla: device_locations)
-- -----------------------------------------------------------------------------
INSERT INTO device_locations (
    device_id, user_id, is_connected, last_connected_time, last_known_latitude, last_known_longitude, last_known_address, last_known_room, signal_strength_rssi, is_beeping
) VALUES
    ('FIFO-S3-ESP32', 'usr_lucia_01', 0, 'Hoy a las 18:30', -33.4255, -70.6143, 'Av. Providencia 1234, Providencia, Santiago', 'Cerca del Living / Mesa de noche', -64, 0),
    ('FIFO-S3-SOFIA', 'usr_sofia_02', 1, 'Conectado ahora', -33.4310, -70.6045, 'Eliodoro Yáñez 1890, Providencia, Santiago', 'Escritorio de lectura', -52, 0),
    ('FIFO-S3-MATEO', 'usr_mateo_03', 0, 'Hoy a las 16:15', -33.4540, -70.5980, 'Av. Irarrázaval 3450, Ñuñoa, Santiago', 'Comedor principal', -71, 0),
    ('FIFO-S3-VALEN', 'usr_valentina_04', 1, 'Conectado ahora', -33.4120, -70.5780, 'Av. Apoquindo 4500, Las Condes, Santiago', 'Terraza taller de pintura', -58, 0),
    ('FIFO-S3-DIEGO', 'usr_diego_05', 0, 'Ayer a las 21:00', -33.4430, -70.5560, 'Av. Príncipe de Gales 6200, La Reina, Santiago', 'Mesa junto al sillón', -68, 0)
ON DUPLICATE KEY UPDATE last_known_address = VALUES(last_known_address);

-- -----------------------------------------------------------------------------
-- 11. AMISTADES Y CONEXIONES (Tabla: user_friendships)
-- -----------------------------------------------------------------------------
INSERT INTO user_friendships (user_id, friend_user_id, relation_description, shared_tastes_count, status) VALUES
    ('usr_lucia_01', 'usr_sofia_02', 'Amiga cercana · Música y tertulia', 3, 'connected'),
    ('usr_lucia_01', 'usr_mateo_03', 'Amigo cercano · Paseos y cocina', 2, 'connected'),
    ('usr_lucia_01', 'usr_valentina_04', 'Amiga sugerida · Música y lectura', 2, 'Suggested'),
    ('usr_lucia_01', 'usr_diego_05', 'Amigo sugerido · Paseos tranquilos', 2, 'Suggested'),
    ('usr_sofia_02', 'usr_lucia_01', 'Amiga cercana · Lectura y música', 3, 'connected'),
    ('usr_mateo_03', 'usr_lucia_01', 'Amiga cercana · Paseos y cocina', 2, 'connected')
ON DUPLICATE KEY UPDATE shared_tastes_count = VALUES(shared_tastes_count);

-- -----------------------------------------------------------------------------
-- 12. ACTIVIDADES COMUNITARIAS Y PARTICIPANTES (Tablas: community_activities / activity_participants)
-- -----------------------------------------------------------------------------
INSERT INTO community_activities (id, title, spots_label, date_label, location_name, distance_label, icon_category) VALUES
    ('act_1', 'Club de lectura', '12 plazas disponibles', 'Jueves 8 oct · 17:00', 'Biblioteca central', 'A 800 m', 'book'),
    ('act_2', 'Música clásica', '8 plazas disponibles', 'Sábado 10 oct · 11:30', 'Auditorio municipal', 'A 1.2 km', 'music'),
    ('act_3', 'Paseo por el jardín', '15 plazas disponibles', 'Domingo 11 oct · 10:00', 'Parque central', 'A 600 m', 'park'),
    ('act_4', 'Taller de acuarela botánica', '10 plazas disponibles', 'Martes 13 oct · 16:00', 'Centro Cultural Providencia', 'A 950 m', 'art'),
    ('act_5', 'Tarde de cine clásico', '20 plazas disponibles', 'Viernes 16 oct · 18:00', 'Cine Arte Normandie', 'A 1.5 km', 'movie')
ON DUPLICATE KEY UPDATE spots_label = VALUES(spots_label);

INSERT INTO activity_participants (activity_id, user_id, status) VALUES
    ('act_1', 'usr_lucia_01', 'joined'),
    ('act_1', 'usr_sofia_02', 'joined'),
    ('act_1', 'usr_valentina_04', 'invited'),
    ('act_2', 'usr_sofia_02', 'joined'),
    ('act_2', 'usr_valentina_04', 'joined'),
    ('act_3', 'usr_mateo_03', 'joined'),
    ('act_3', 'usr_diego_05', 'joined'),
    ('act_4', 'usr_valentina_04', 'joined'),
    ('act_5', 'usr_mateo_03', 'joined'),
    ('act_5', 'usr_diego_05', 'joined')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- -----------------------------------------------------------------------------
-- 13. GRAFO SOCIAL Y CONTACTOS RELACIONALES (Tabla: user_contacts_relational)
-- -----------------------------------------------------------------------------
INSERT INTO user_contacts_relational (
    id, user_id, contact_name, phone_number, relationship_role, closeness_score,
    trust_tier, emotional_valence, contextual_memory, mention_count
) VALUES
    (
        'ctc_rel_01', 'usr_lucia_01', 'Carmen González', '+56987654321',
        'Hija mayor (contacto prioritario de apoyo)', 0.98, 1, 'afectuoso',
        'Llama los domingos por la tarde y suele visitarla cada dos semanas. La acompaña a controles médicos.', 14
    ),
    (
        'ctc_rel_02', 'usr_lucia_01', 'Dr. Álvaro Muñoz', '+56922334455',
        'Médico geriatra de cabecera', 0.85, 2, 'protector',
        'Controla la presión arterial y receta Enalapril. Atiende en el centro médico cercano.', 6
    ),
    (
        'ctc_rel_03', 'usr_lucia_01', 'Rosa Martínez', '+56933445566',
        'Vecina de confianza y amiga', 0.78, 3, 'afectuoso',
        'Vecina del departamento del frente. Toman té y comparten esquejes de plantas y recetas caseras.', 8
    ),
    (
        'ctc_rel_04', 'usr_lucia_01', 'Tomás', '+56944556677',
        'Nieto universitario', 0.92, 2, 'afectuoso',
        'Estudia ingeniería y la visita los fines de semana. Le gusta que su abuela le prepare queque de limón.', 5
    )
ON DUPLICATE KEY UPDATE closeness_score = VALUES(closeness_score);

-- -----------------------------------------------------------------------------
-- 14. RUTINAS Y PATRONES DE VIDA DIARIA (Tabla: user_daily_routines)
-- -----------------------------------------------------------------------------
INSERT INTO user_daily_routines (
    id, user_id, routine_name, category, time_anchor, typical_time_str,
    frequency_rule, confidence_score, notes
) VALUES
    (
        'rtn_01', 'usr_lucia_01', 'Toma de medicamento de la presión (Enalapril)',
        'medication', 'morning', '08:30', 'daily', 0.95,
        'Tomar junto con el desayuno y un vaso de agua.'
    ),
    (
        'rtn_02', 'usr_lucia_01', 'Cuidado y riego de orquídeas en el balcón',
        'hobby', 'morning', '10:00', 'weekly', 0.85,
        'Riego por inmersión los días miércoles mientras escucha música clásica.'
    ),
    (
        'rtn_03', 'usr_lucia_01', 'Caminata suave por la plaza',
        'exercise', 'afternoon', '16:30', 'weekdays', 0.80,
        'Paseo de 20 a 30 minutos cuando baja el sol.'
    ),
    (
        'rtn_04', 'usr_lucia_01', 'Llamada familiar de los domingos',
        'social', 'evening', '18:00', 'weekly', 0.90,
        'Conversación habitual con su hija Carmen.'
    )
ON DUPLICATE KEY UPDATE confidence_score = VALUES(confidence_score);

