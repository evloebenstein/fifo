# Enclosure 3D · Fifo (concepto redondeado)

Carcasa paramétrica de la Fifo (kit XIAO ESP32-C3/S3) con forma de pastilla:
**38 × 58 × 21,5 mm**, esquinas **R9**, pared **1,8 mm** y cantos redondeados
(1,6 mm abajo, 3,4 mm arriba). Dos piezas con **una sola junta horizontal**:
cuerpo (Z 0..15,5) + tapa (Z 15,5..21,5).

Sustituye al modelo anterior de 40 × 60 × 26 mm (`build_fifo.py`, que se
conserva como referencia). Mismo orden de la cara que
el STL del usuario: pantalla arriba, altavoz abajo-izquierda, dos botones a la
derecha.

### Sobre el STL de referencia

`Glorious Sango-Migelo.stl` son **dos cascarones de 34 × 58 × 14 y
34 × 58 × 7,75 mm** puestos uno al lado del otro. Dentro solo hay 4 placas de
3–4 mm, 6 postes y 7 cubos de 1,27 mm; **no contiene XIAO, batería, OLED, GPS,
amplificador ni altavoz**. Sirve para el reparto de la cara (pantalla arriba,
altavoz abajo-izquierda, botones a la derecha), no para heredar medidas. Por
eso este modelo reconstruye los internos con las medidas reales de la tabla de
abajo.

## Ejecutar

```powershell
& "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\build_fifo_round.py
```

Genera en un solo paso (≈ 20–30 min con Cycles en CPU; las vistas de interior
con material fantasma son las más lentas):

- `render_fifo_round.png` — hero 1280×1000
- `render_fifo_round_top.png` — vista superior ortográfica (prueba de alineación)
- `render_fifo_round_face.png` — cara frontal recta: confirma que la cara del
  OLED se ve derecha y que «FIFO» queda arriba
- `render_fifo_round_usb.png` — un cable USB-C real insertado: casquillo dentro
  del hueco de 9,4 × 3,7 y sobre-molde fuera del bisel
- `render_fifo_round_exploded.png` — tapa +40 mm, sin rotar y **sin cables**
  (el mazo completo se ve en `harness`)
- `render_fifo_round_body.png` — interior del cuerpo sin tapa ni batería
- `render_fifo_round_lid.png` — interior de la tapa, ortográfica desde abajo
- `render_fifo_round_harness.png` — todo montado con la carcasa fantasma: se ve
  el mazo de 36 curvas cruzando la junta y el reparto a cada módulo
- `render_fifo_round_mic.png` — el INMP441 desde arriba, sin la tapa ni el resto
  de la carcasa: dos filas de 3 pads con su rótulo, lata, ranura del puerto,
  vía central y la llegada de los seis hilos
- `render_fifo_round_spk.png` — la bandeja del altavoz desde abajo (teñida de
  verde solo aquí): el marco bajo el driver, el hueco del travesaño delantero y
  los dos hilos subiendo a los pads
- `render_fifo_round_power.png` — solo la alimentación: tapa fuera, LiPo en
  material fantasma y los cinco hilos LiPo+ → switch → 5V, LiPo− → GND y las
  dos derivaciones BAT+ / BAT−
- `render_fifo_round_xiao.png` — el XIAO desde abajo: pads castellados del
  canto, BAT/test y salida del mazo
- `render_fifo_round_under.png` — vista baja con el cuerpo fantasma: el pocket
  de 0,8 mm, los 1,30 mm libres bajo el PCB y los dos hilos BAT+ / BAT− hasta
  sus pads
- `fifo_round.blend` — escena generada (no editar a mano)
- `print/*.stl` — 14 piezas listas para laminar

Para iterar una sola vista sin esperar los trece renders:

```powershell
$env:FIFO_RENDER = "xiao"    # o under, power, harness, usb, face...
& "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\build_fifo_round.py
```

El script es la fuente de verdad: no edites el `.blend` a mano. Antes de
renderizar corre once comprobaciones y aborta si algo no cuadra (ver
[Comprobaciones](#comprobaciones)).

## Piezas imprimibles (`print/`)

| STL | Pieza | Dimensiones (mm) | Notas |
|-----|-------|------------------|-------|
| `fifo_round_body.stl` | Cuerpo | 38 × 58 × 15,5 | Ranura de junta, **hueco USB-C pasante**, hueco del switch con pista rehundida, **3 postes de batería**, 4 postes del XIAO y **pocket de 0,8 mm bajo la placa** |
| `fifo_round_lid.stl` | Tapa | 38 × 58 × 7,05 | Lengueta de junta unida por boolean, ventana, bota del micrófono, asientos de táctil, rebajes de los aros, rebaje y carriles del altavoz |
| `fifo_round_body_full.stl` | Cuerpo completo | 38 × 58,6 × 15,5 | Cuerpo **con el bisel USB ya fusionado**: una sola pieza para imprimir |
| `fifo_round_lid_full.stl` | Tapa completa | 38 × 58 × 7,57 | Tapa **con junta de pantalla, rejilla, aro del altavoz y aros de los botones fusionados**: una sola pieza para imprimir |
| `fifo_round_speaker_grille.stl` | Rejilla | Ø17,4 × 0,9 | 37 taladros Ø1,0 en tres coronas + centro |
| `fifo_round_speaker_ring.stl` | Aro del altavoz | Ø19,4 × 1,07 | En relieve sobre la cara |
| `fifo_round_speaker_retainer.stl` | Bandeja de apriete del altavoz | 19,6 × 10,5 × 0,50 | **Pieza aparte**: se desliza bajo el driver y lo retiene |
| `fifo_round_screen_gasket.stl` | Junta de pantalla | 26,2 × 14,2 × 0,25 | Marco entre lente y bisel |
| `fifo_round_usb_bezel.stl` | Bisel USB-C | 10,6 × 4,9 × 0,62 | Marco exterior del hueco pasante (9,4 × 3,7) |
| `fifo_round_switch_slider.stl` | Paddle del switch | 4,8 × 3,4 × 0,4 | Slider tipo mute: se pega en la palanca y corre en la pista rehundida |
| `fifo_round_button_up.stl` | Botón + | Ø9,2 × 2,4 | Verde: tapa Ø8,2 + pestaña Ø9,2 + copa Ø4,7 / Ø3,7 |
| `fifo_round_button_down.stl` | Botón - | Ø9,2 × 2,4 | Rojo: tapa Ø8,2 + pestaña Ø9,2 + copa Ø4,7 / Ø3,7 |
| `fifo_round_button_bezel_up.stl` | Aro del botón + | Ø10,2 × 1,1 | Retiene la pestaña; faldón de 0,50 en el rebaje de la cara |
| `fifo_round_button_bezel_down.stl` | Aro del botón − | Ø10,2 × 1,1 | Retiene la pestaña; faldón de 0,50 en el rebaje de la cara |

La lente acrílica no se imprime: se corta de acrílico de 1 mm (25,6 × 13,6,
R3,5) y se rebaja a 0,64 mm de espesor para quedar 0,06 mm sobre la cara.

**Opción «todo unido».** Los dos `*_full` son las mismas piezas con sus
accesorios ya fusionados por boolean (sólido único, sin caras interiores), para
imprimir cada mitad en una sola tirada:

- `fifo_round_body_full` = cuerpo + bisel USB. El bisel entra 0,3 mm en la
  pared y su boca crece 0,02 mm por lado para no dejar caras coplanares con el
  agujero pasante; el escalón queda dentro del puerto y no se ve.
- `fifo_round_lid_full` = tapa + junta de pantalla + rejilla + aro del
  altavoz + los dos aros de los botones. La junta pasa a ser un labio rígido
  del mismo material (sigue separando lente y bisel, ya no es una junta
  blanda); el aro del altavoz crece 0,05 mm de radio y baja 0,35 mm dentro de
  la tapa; la rejilla crece 0,15 mm para morder el aro; los aros de los botones
  muerden 0,05 mm el fondo de su rebaje con el faldón. Nada de eso cambia lo
  que se ve.
- Los **botones siguen sueltos**: se meten desde dentro por el aro Ø8,4 y la
  pestaña queda retenida igual que en la versión por piezas.
- La **bandeja del altavoz** (`fifo_round_speaker_retainer`) queda suelta en
  las dos versiones: es la pieza que aprieta el driver y se imprime aparte.

La tapa completa se imprime **cara arriba** (lengueta en la cama), porque los
aros fusionados sobresalen 0,5 mm de la cara y ya no dejan apoyarla plana; los
soportes van solo dentro de la cavidad y bajo la rejilla. Detalle en
[ASSEMBLY.md](ASSEMBLY.md#7-impresión-3d).

## Geometría de la carcasa

| Parámetro | Valor |
|-----------|-------|
| Exterior | 38 × 58 × 21,5 mm |
| Radio de esquina (planta) | R9 |
| Pared / piso | 1,8 / 1,8 mm |
| Pocket bajo el XIAO | 19,8 × 22,7 × 0,8 mm · piso 1,0 mm · 1,30 mm libres bajo el PCB |
| Cavidad útil | 34,4 × 54,4 × 18,2 mm (Z 1,8..20,0) |
| Canto inferior / superior | 1,6 / 3,4 mm |
| Junta | ranura 0,65 mm en el cuerpo + lengueta 0,35 mm en la tapa |
| Juego de junta | 0,15 mm por lado, 0,10 mm al fondo, 1,05 mm de penetración |
| Labios de la ranura | 0,60 mm exterior / 0,55 mm interior |

La lengueta se une a la tapa con un boolean `UNION` (sólido único, sin caras
interiores) y la ranura se talla en el cuerpo con el mismo retranqueo, así que
la tapa entra sin interferir (el build lo verifica por intersección de volúmenes).

## Cara exterior

| Elemento | Centro (X, Y) | Medidas |
|----------|---------------|---------|
| Ventana de pantalla | (0, 14) | 24,4 × 12,4 · R3,4 (rebaje 26,4 × 14,4 × 0,9) |
| Lente acrílica | (0, 14) | 25,6 × 13,6 · R3,5 · 0,64 mm (0,06 mm sobre la cara) |
| Altavoz | (−5,8, −4,8) | rebaje Ø19,4 · abertura Ø17,5 · rejilla Ø17,4 |
| Botón «+ SUBIR VOL» | (11,2, −7,0) | pozo Ø9,4 · aro Ø10,2 · tapa Ø8,2 |
| Botón «− BAJAR VOL» | (11,2, −19,0) | pozo Ø9,4 · aro Ø10,2 · tapa Ø8,2 |
| Micrófono | (0, −20) | taladro Ø1,1 |
| USB-C | pared −Y | **hueco pasante 9,4 × 3,7 · R1,3** en Z=5,53 + bisel 10,6 × 4,9 |
| Switch | pared −X | hueco pasante 4,0 × 3,4 en Y=6,0 (Z 3,1..6,5), 2,0 por lado desde el cuerpo de 8,0 · pista rehundida 7,4 × 3,8 × 1,0 con paddle impreso 4,8 × 3,4 × 0,4 (slider tipo mute) |

La pantalla está centrada en X y alineada con los ejes del cuerpo: la ventana,
el rebaje, la junta y la lente comparten el mismo centro, así que no hay
desviación ni giro respecto a la carcasa.

### Puerto USB-C

La pared −Y no lleva un remarcado: tiene un **hueco pasante** de **9,4 × 3,7 mm
con R1,3**, centrado en la boca real del conector (Z 5,53) y un bisel exterior
de 10,6 × 4,9 × 0,62 mm que tapa el canto vivo. El conector se modela a su
medida real (8,94 × 7,35 × 3,26) sobre la cara superior del PCB, con la boca a
0,10 mm de la pared interior y una cavidad oscura de 8,34 × 2,56.

El hueco se mide sobre la **carcasa** del conector, no sobre la boca: deja
**0,23 mm por lado** (el cálculo de 0,25 mm por lado da 9,46 × 3,67). Es lo más
cerrado que aguanta FDM, donde los agujeros salen 0,1–0,2 mm pequeños; con
9,0 × 3,4 quedarían 0,03 mm por lado y el conector rozaría el canto. El radio
R1,3 es el del propio receptáculo, así que la holgura es constante también en
las esquinas. Por el hueco solo pasa el casquillo metálico; si tu cable trae el
arranque de goma muy adelantado, la pieza que roza es el bisel (va aparte, se
lima o se cambia sin reimprimir el cuerpo).

El build lo comprueba con un cable real (`[usb]`): el casquillo
(8,25 × 2,40 × 6,50) entra 6,5 de los 7,35 mm del puerto con 0,58/0,65 mm por
lado en el hueco, y el alivio (9,00 × 3,30) y el sobre-molde (12,20 × 6,40)
arrancan 4,0 mm por fuera del bisel, como en un cable de verdad.
`render_fifo_round_usb.png` lo muestra insertado.

### Botones y asiento del táctil

Cada táctil 6×6×5 se pega en un **asiento impreso en la tapa**: un anillo de
8,0 × 8,0 (pared 0,9 mm) que cuelga del techo y se ancla con un ala de 10,4 mm.
El cuerpo del táctil ocupa 15,20..19,70 (4,5 mm dentro del anillo) y su pulsador
queda al ras del pozo del asiento (19,70..20,20). El botón es **una sola pieza**
(tapa Ø8,2 + pestaña Ø9,2 + copa guía Ø4,7): la pestaña se atrapa bajo el aro
Ø10,2 y la copa empuja el pulsador a través del pozo Ø9,4.

**Conexión por forma, sin snap de interferencia.** El pulsador del 6×6×5 es un
cilindro liso de Ø3,5 que sobresale 0,50 mm, sin rebaje ni labio donde enganchar
un clip; un clip con interferencia frenaría su recorrido y precargaría el
muelle. Por eso el botón lo **envuelve con una copa guía**: interior Ø3,7
(holgura radial 0,10 mm), techo interior apoyado plano en la cara del pulsador
(20,20) y borde a 20,00, es decir 0,30 mm sobre el cuerpo del táctil — más que
el recorrido útil (≈0,25 mm). La copa centra y conecta el botón con el pulsador
sin fricción ni precarga; la retención frente a caídas sigue siendo la pestaña
Ø9,2 bajo el aro Ø10,2, y el botón vuelve por el muelle del propio switch.
El aro asienta en un **rebaje anular de 0,55 mm** (Ø10,4) con un faldón de
0,50 mm que apoya con 0,05 de apriete (cola o ajuste ligero): sin ese rebaje
el aro se embutía 0,10 mm en la cara maciza y no tenía donde caer.

## Reparto interno

La cavidad es continua: el cuerpo se vacía hasta Z 16,0 y la tapa desde
Z 15,3, así que **el mazo de cables cruza la junta por dentro sin taladrar la
pared**. La junta solo separa las paredes, no el volumen interior.

```
Z 21,5 ┌────────────────────────────── tapa (techo 1,5 mm)
       │ OLED 27×27×2,6 (PCB 17,4..18,8 · cristal 18,8..20,0) · lente
       │ MAX98357A 17,8×19,4×2,6  ← 14,7..17,3, girado 90°, bajo el OLED
       │ driver 15×8×2,9 · táctil 6×6×5 (15,2..19,7) en asiento 8×8
       │ INMP441 Ø14: lata 16,2..18,0 · PCB 18,0..18,8 · bota 19,15..20,1
Z 16,1 │ LiPo 902030 20×30×8,85 (7,25..16,10; girada, deja libre el lado +X)
Z 15,5 ├────────────── junta (lengueta 1,05 mm) ──────────────
       │ mazo: 2 canalillos (X≈±15,5) → bus frontal Y≈−3 (Z 16,55) → tapa
Z  7,25├──────────────────────────────
       │ conector USB-C 8,94×7,35×3,26 (3,90..7,16)
       │ XIAO ESP32-C3/S3 17,8×21×1,6 (2,30..3,90) + shield
       │ GPS ATGM336H 16×13,5×5,3 (1,80..7,10) · antena 20×6 (1,80..6,00)
Z  1,8 └──────────────────────────────
         pocket 0,8 mm bajo el XIAO (1,00..1,80) · piso 1,00
```

El reparto es por delante y por detrás: la celda ocupa Y −27..3 y el GPS con su
antena Y 6,25..26,5, así que no comparten altura. Entre ambos queda el pasillo
Y 3..6,25 por donde cruza el cable del interruptor.

| Componente | Centro (X, Y) · Z inferior | Medidas reales | Montaje |
|------------|------------------|----------------|---------|
| XIAO ESP32-C3/S3 | (0, −16, 2,30) | 17,8 × 21 × 1,6 (PCB) | pocket de 0,8 mm y 4 postes (1,30 mm libres bajo el PCB), USB-C al frente, **pads castellados en el canto inferior** |
| Conector USB-C | (0, −23,4, 3,90) | 8,94 × 7,35 × 3,26 | sobre el PCB, boca a 0,10 mm de la pared |
| Antena impresa del XIAO | (0, −7,7, 3,90) | 10 × 4 × 0,3 | borde trasero de la placa; **tapada por la celda** a 3,05 mm |
| Antena FPC A-02 (recomendada) | (−17,0, 2,0, 8,0) | 0,4 × 7 × 4 | pared interior izquierda, conector U.FL |
| LiPo 902030 (~500 mAh) | (−3,5, −12, 7,25) | 20 × 30 × 8,85 | **girada** (20 en X, 30 en Y) sobre **3 postes**; patas en la cara +X |
| ATGM336H (breakout) | (0, 13,0, 1,80) | 16,0 × 13,5 × 5,3 | piso trasero: lata 1,8..4,2, PCB 4,2..5,2 y SMD + pila + U.FL arriba |
| Pads del GPS | borde delantero, Z 5,20 | 5 pads a 2,54: VCC GND TX RX PPS | cara superior del PCB |
| Antena GPS cerámica | (0, 23,5, 1,80) | 20 × 6 × 4,2 (PCB 0,8 + cerámica 3,4) | piso trasero, **aparte**, con pigtail U.FL |
| Switch SS12D07 | (−13,9, 6,0, 1,8) | 3,4 × 8 × 4 | pared −X, palanca al exterior con paddle impreso |
| OLED 0,96" SSD1306 | (0, 13,6, 17,4) | 27 × 27 × 2,6 (PCB 1,4 + cristal 1,2) | techo de la tapa, cristal contra la ventana |
| Header del OLED | (0, 0,95, 16,6) | 4 pines de 0,64 × 0,64 × **0,8** | cara inferior, borde delantero, **recortados** |
| MAX98357A | (−0,5, 14,0, 14,7) | 17,8 × 19,4 × 2,6 | bajo el OLED, girado 90°, sin borne verde |
| Driver 8 Ω 1 W | (−5,8, −4,8, 17,2) | 15 × 8 × 2,9 | tapa, bajo la rejilla |
| INMP441 redondo | (0, −20, 16,2) | Ø14 · PCB 0,8 + lata 1,8 | tapa, **bottom-port**: cara de soldadura a la ventana, lata al cuerpo |
| 2× táctil 6×6×5 | botones, Z 15,2 | 6 × 6 × 5 | tapa, pegados en el asiento 8 × 8 de cada botón |

El OLED va con el **cristal contra la ventana** y el header de 4 pines en el
borde delantero, colgando hacia el cuerpo. Visto desde la ventana el orden es
SDA, SCK, VDD, GND (GND en +X). Los pines se **recortan a 0,8 mm** (punta en
Z 16,60): con los 2,5 mm de serie se hundirían en la celda, que girada llega a
Y 3 con el techo en 16,10.

El header queda en el borde **inferior** de la pantalla (−Y) y el «arriba» de
la pantalla es +Y. Con el firmware en `U8G2_R0` (rotación 0) la cara se ve
**derecha** desde la ventana: los ojos arriba, la boca abajo y «FIFO» en la
barra superior. No hay que invertir nada; `render_fifo_round_face.png` sirve
para comprobarlo de un vistazo.

### Antenas

- La antena del XIAO va en el borde **trasero** de la placa (el opuesto al
  USB-C), en Y −9,7..−5,7 sobre la cara superior (Z 3,90). La celda girada
  pasa **por encima** (base 7,25 a 3,05 mm de la antena, techo 16,10), así
  que la antena impresa queda tapada por el aluminio de la bolsa. Para la
  unidad real usa la variante con **U.FL** y la **antena FPC** en la pared
  izquierda; el conector está en ese mismo borde de la placa.
- **El GPS no lleva parche integrado.** El ATGM336H es un breakout de
  16,0 × 13,5 × 5,3 mm: la lata del chip va soldada abajo, el PCB encima y en
  la cara de componentes quedan los SMD, la pila de respaldo redonda (con su
  «+») y el conector U.FL. La antena es una **cerámica de 20 × 6 mm aparte**,
  plana en el piso trasero (Y 20,5..26,5) y unida por un **pigtail U.FL**.
- El parche cerámico mira hacia arriba bajo la tapa; el OLED está por delante
  (Y 0,1..27,1) pero a Z 17,4 (11,4 mm por encima de la antena), así que no lo
  tapa. Deja la antena libre de cinta, foil o tornillos.
- La antena FPC se pega en la pared izquierda (−17,0, 2,0, Z 8,0), a 3,3 mm de
  la celda y lejos del altavoz: es la opción recomendada con esta batería.

### Cables (AWG30, silicona)

El mazo sigue el pinout real del XIAO y de cada módulo (tabla completa en
[ASSEMBLY.md](ASSEMBLY.md) §2). El modelo dibuja **36 curvas** de 0,6 mm
(radio 0,3 mm): 12 de señal, 16 de 3V3/GND, 5 de batería/switch, 1 de antena
y 2 de altavoz. Cada curva sale de un pad y llega a un pad; el build comprueba
que ninguna toca un componente ni la pared.

Los pads del XIAO son **castellados en el canto inferior** de la placa, así que
el hilo sale a ras de suelo (Z ≈ 2,6), no de la cara superior. La placa va
elevada 0,5 mm sobre 4 postes para que las 4 patas del USB-C y los filamentos
de soldadura no toquen el piso. Los cables de la cara inferior (BAT+ / BAT−)
van por un **pocket de 0,8 mm** en el piso: deja **1,30 mm libres** bajo el PCB,
sale 1,0 mm por los lados y por el fondo de la placa, y el hilo corre a Z 1,36
apoyado en el fondo del pocket (0,06 mm de aire) hasta su pad, donde sube a 1,94
para tocar el cobre sin entrar en la placa. La batería tapa la placa por arriba; cada hilo del mazo baja por
el borde, corre por el canalillo de 2,2 mm entre la batería y la pared
(X ≈ ±15,5) y sube por delante del XIAO hasta el bus frontal de la tapa
(Y ≈ −3,0, **Z 16,55**). Desde ahí se reparte. Ese 16,55 es el único pasillo
que cruza de lado a lado: por encima de la celda (16,10) y su etiqueta (16,14)
y por debajo del driver del altavoz (17,20). Los cuatro hilos del OLED se
sueltan a la **punta** de sus pines, recortados a 0,8 mm (Z 16,60).

El micro tiene su propio recorrido: SCK y SD suben por el costado izquierdo y
corren **por encima** del PCB hasta su fila; WS llega por el lado trasero
(Y = −14,55, hacia el GPS); VDD por el delantero (Y = −25,45, hacia el USB);
L/R y GND pasan **por debajo** del PCB y suben
por el costado derecho, entre la lata y el canto. Así ningún hilo cruza el
módulo ni el táctil, y los dos tramos de arriba pasan **por dentro** de los
rótulos de las filas para no taparlos.

| Grupo | Curvas | Detalle |
|-------|--------|---------|
| 3V3 | 7 | XIAO → bus; ramas a micro, GPS, amplificador (Vin y SD) y OLED |
| GND | 9 | XIAO → bus; ramas a micro (GND y L/R), GPS, amplificador, OLED y los dos táctiles |
| I2C | 2 | D4 → SDA, D5 → SCL |
| I2S amplificador | 3 | D0 → BCLK, D1 → LRC, D3 → DIN |
| I2S micro | 3 | D8 → SCK, D9 → WS, D10 → SD |
| Pulsadores | 2 | D2 al primer táctil y puente al segundo (en paralelo) |
| GPS UART | 2 | D6 → RX, D7 → TX |
| Antena GPS | 1 | pigtail U.FL de la cerámica al módulo |
| Altavoz | 2 | pads del amplificador al driver, ambos en la tapa |
| Batería / switch | 5 | + → switch → 5V y BAT+; − → GND y BAT− (las dos derivaciones van bajo el XIAO) |

Dos vueltas de **Kapton** de 0,08 mm sujetan los hilos a los lados de la
batería (X −13,54 y +6,54). El resto del mazo se puede fijar con una gota de
silicona en el canalillo, sin apretar contra la pared.

### Micrófono y bota acústica

El INMP441 es **bottom-port**: el puerto acústico está en la cara de soldadura,
no en la lata. Por eso el módulo se monta con la **cara de soldadura hacia la
ventana** (PCB en 18,00..18,80, pads arriba) y la **lata hacia el cuerpo**
(16,20..18,00). Si lo montas al revés, la lata tapa el puerto y el micro queda
sordo.

La tapa lleva una **bota acústica** impresa (anillo exterior 8,8 × 5,0, interior
7,2 × 3,4, pared 0,8 mm) que va de Z 19,15 a 20,10 y se une a la tapa en el
mismo boolean de la lengueta. Lleva el sonido del puerto al taladro Ø1,1 de la
cara sin abrir la cavidad al exterior.

Entre la cara de soldadura y la boca de la bota se deja un hueco de **0,35 mm**
(`MIC_BOOT_Z0 = 19.15`): ahí va una **arandela de espuma de 0,5 mm** (la que
trae el propio módulo INMP441) que hace de junta y absorbe la tolerancia. Sin
ella el puerto acústico queda enfrentado al aire de la cavidad y el micro
recoge el zumbido de dentro.

El módulo **no va pegado**: lo retiene la propia tapa.

- Dos **repisas** impresas (Z 17,70..18,00) bajo el canto de la PCB, con 0,60 mm
  de solape: cargan el peso y fijan la altura.
- Cuatro **postes** al techo, fuera del disco Ø14, que evitan que la PCB bascule.
- Dos **guías** laterales a 0,20 mm del canto que la centran.

Se monta dejando caer el micro en su asiento antes de cerrar; la PCB no se
suelda a nada.

Los 6 pads van en **dos filas de 3**, con paso 2,54 mm y filas a ±3,75 mm del
centro. Vistos desde la ventana (el módulo tal como se monta):

```
        SCK    WS    L/R        ← fila superior (−X a +X)
        SD     VDD   GND        ← fila inferior
             (puerto Ø1,1)
```

L/R a GND selecciona el canal izquierdo.

### Altavoz y bandeja de apriete

El driver (15 × 8 × 2,9) sube vertical al hueco Ø17,5 de la tapa y no se
sostiene solo, así que lo aprieta una **bandeja aparte**
(`fifo_round_speaker_retainer.stl`) que se desliza desde el frente por debajo
del driver. No lleva lengüetas impresas en la tapa ni hace falta pegamento:

- La bandeja es un **marco** de 19,6 × 10,5 × 0,50: dos largueros y dos
  travesanos bajo los cantos delantero y trasero del driver. El centro va
  abierto y el travesano delantero lleva un hueco de 3,9 mm por el que suben
  los dos cables del altavoz a sus pads. Su cara superior queda a 17,15, 0,05
  por debajo del driver (17,20).
- El larguero izquierdo apoya en una **repisa pegada a la pared** (Z
  16,20..16,65); el derecho, en el **pie** de un carril que cuelga del techo.
  Los dos asientos dejan **0,10 mm de juego** lateral: la bandeja se guía sola.
- Un **resalte Ø0,44** en cada pie entra en un **bolsillo de 0,26 mm** del
  larguero y la retiene contra el deslizamiento hacia fuera; un **tope trasero**
  la frena hacia dentro (0,10 mm antes de tocar el OLED).
- El carril derecho y su larguero existen **solo delante del bus** de cables
  (Y −3,60): a 16,55 cruzan los hilos de la OLED y el 3V3 y cualquier carril
  que bajara a 16,20 por esa zona los atravesaría. Por el mismo motivo el
  larguero derecho se une al travesano trasero por un **puente fino** que pasa
  a 0,05 mm por encima del bus.

El driver va **sin el borne verde**: se desuelda y los dos hilos del altavoz se
soldan directos a los pads. Se monta la bandeja sobre los cables, luego el
driver y por último se enrosca la tapa: la espuma de 0,5 mm sobre la rejilla
empuja el driver contra la bandeja y el conjunto queda firme sin tornillos.

### Ocupación

Con las medidas de la tabla, el build reporta:

| Concepto | Volumen | % de la cavidad |
|----------|---------|-----------------|
| Cavidad (cuerpo + tapa) | 34 528 mm³ | 100 % |
| Componentes | 10 230 mm³ | 29,6 % |
| Cables (36 curvas) | 396 mm³ | 1,1 % |
| **Libre** | **23 902 mm³** | **69,2 %** |

El volumen libre es amplio; el límite no es el volumen sino la **altura**.
El MAX98357A de 2,6 mm va pegado bajo el OLED (techo 17,30 contra el PCB 17,40)
y cuelga 0,6 mm por debajo del suelo de la tapa (15,3), dentro de la cavidad
continua del cuerpo (hasta 16,0). El micrófono redondo cuelga del techo en
(0, −20), el GPS con su antena ocupa el piso trasero y el altavoz sigue bajo la
rejilla. El reparto no necesita crecer más allá de 38 × 58 × 21,5.

> **Nota de montaje:** el MAX98357A real (19,4 × 17,8 mm) **sí entra**, pero
> solo bajo la pantalla, girado 90° (header al borde derecho, pads del altavoz
> al izquierdo), corrido a Y=14,0 para dejar 1,3 mm sobre la celda y **sin el
> borne verde**: hay que desoldarlo y soldar los hilos del altavoz directos a
> los pads. El INMP441 es el módulo redondo de Ø14 mm con 6 pads en dos filas y
> puerto inferior; va con la cara de soldadura hacia la ventana, sobre la bota
> acústica de la tapa. El GPS es el **breakout ATGM336H** de 16,0 × 13,5 × 5,3
> con 5 pads a 2,54 y su antena cerámica de 20 × 6 aparte, unida por U.FL.

## Comprobaciones

El build imprime once chequeos y aborta si alguno falla:

| Chequeo | Qué garantiza |
|---------|----------------|
| `[mesh]` | `Fifo_Body` y `Fifo_Lid` no tienen aristas no-manifold |
| `[seam]` | Labios 0,60/0,55 mm, juego 0,15/0,15 mm y **0 mm³ de interferencia** tapa/cuerpo |
| `[clearance]` | Ningún componente penetra más de 0,10 mm en la pared interior |
| `[overlap]` | Los 42 componentes internos no se interpenetran (AABB, choques > 0,05 mm) |
| `[cables]` | Las 36 curvas quedan dentro de la cavidad, sobre el piso o el pocket, y no tocan ningún componente ni la pared (cajas infladas por el radio del hilo) |
| `[under]` | Los hilos BAT+ / BAT− pasan bajo el PCB con ≥ 0,10 mm, no se hunden en el pocket y dejan ≥ 0,20 mm a los postes |
| `[usb]` | El hueco atraviesa la pared, no tapa la carcasa del conector (≥ 0,15 mm por lado), el casquillo del cable entra con juego y el alivio queda fuera del bisel |
| `[sw]` | Hueco pasante de 4,0 × 3,4 medido con rayos sobre la malla (2,0 mm por lado desde el cuerpo de 8,0), palanca 3,0 mm fuera del cuerpo, paddle rehundido ≤ 0,15 mm en la pista de 7,4 × 3,8 × 1,0 con holgura 0,10..0,30 y recorrido ±1,1 mm |
| `[btn]` | La copa del botón envuelve el pulsador con holgura 0,05..0,15 mm (medida sobre la malla), su techo apoya en la cara del pulsador, el borde deja ≥ 0,30 mm al cuerpo del táctil, la pestaña queda retenida por el aro y centrada en el pozo, y el faldón del aro entra ≥ 0,40 mm en su rebaje |
| `[mic]` | La PCB del INMP441 apoya en las dos repisas, la bota deja el hueco de la espuma, y los postes y guías no pisan el disco ni el canto |
| `[spk]` | La bandeja apoya en los dos pies con juego lateral, el resalte entra en el bolsillo, el tope frena el deslizamiento y **ningún cable ni componente** toca el marco |

`report_packing()` imprime la tabla de ocupación de arriba para detectar
regresiones de volumen. `check_print_stls.py` reimporta los 14 STL y verifica
que cada pieza sea cerrada, manifold y de una sola isla:

```powershell
& "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\check_print_stls.py
```

## Impresión

- **Cuerpo:** piso en la cama (boca arriba). El hueco USB-C deja un puente de
  9,4 mm en su borde superior: añade un soporte bajo esa franja o lima el
  rebabeo. El hueco del switch (4,0 mm) queda como puente corto y la pista
  rehundida de la pared vertical vuela 1,0 mm: sin soportes.
- **Tapa:** **cara abajo**, lengueta arriba. El techo de 1,5 mm se imprime
  primero y la bota acústica del micro y los asientos de los táctiles crecen
  desde él; al revés, el techo de 34,4 × 54,4 mm quedaría en el aire. El canto
  redondeado de 3,4 mm sale como un vuelo de hasta 45°: revisa las primeras
  capas o añade un borde de soporte de 1 mm. La lengueta de 0,35 mm es la
  última capa.
- **Rejilla, aros, botones y paddle:** planos. El botón se imprime **con la cara en la
  cama**: la tapa Ø8,2 primero, la pestaña Ø9,2 encima (con su chaflán) y la
  copa Ø4,7 abierta hacia arriba, sin soportes. El paddle del switch va con su
  cara en la cama (0,4 mm de espesor).
- **Bandeja del altavoz:** plana, **con los bolsillos hacia arriba** (el lado
  liso en la cama). Es un marco de 0,50 mm: los dos puentes del travesano
  delantero y del larguero derecho salen solos, sin soportes.
- PETG o ASA, capa 0,16 mm, 3 perímetros, relleno 15 % gyroid, brim 6 mm.

Detalle de montaje, cableado y solución de problemas en
**[ASSEMBLY.md](ASSEMBLY.md)**.

## Archivos

| Archivo | Rol |
|---------|-----|
| `build_fifo_round.py` | Fuente de verdad del modelo actual (commitear) |
| `build_fifo.py` | Helpers, materiales y estudio compartidos + modelo anterior |
| `check_print_stls.py` | Verificación de los STL exportados |
| `fifo_round.blend`, `render_fifo_round*.png`, `print/*.stl` | Generados (no se commitean) |
| `Glorious Sango-Migelo.stl` | STL de referencia del usuario |
| `ASSEMBLY.md` | Guía de montaje |

Los `.blend`, `.blend1` y `.png` están en `.gitignore`.
