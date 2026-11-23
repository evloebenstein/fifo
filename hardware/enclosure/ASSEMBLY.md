# Guía de Montaje · Fifo (carcasa redondeada 38 × 58 × 21,5)

> **Modelo 3D:** `hardware/enclosure/fifo_round.blend` (Blender 5.2+)
> **Renders:** `render_fifo_round.png` (hero), `render_fifo_round_top.png`
> (planta), `render_fifo_round_face.png` (cara frontal), `render_fifo_round_exploded.png`,
> `render_fifo_round_body.png` (interior del cuerpo), `render_fifo_round_lid.png`
> (interior de la tapa), `render_fifo_round_harness.png` (todo montado con
> carcasa fantasma), `render_fifo_round_mic.png` (INMP441 y su bota),
> `render_fifo_round_power.png` (solo la alimentación, con el LiPo fantasma),
> `render_fifo_round_xiao.png` (pads del canto inferior del XIAO),
> `render_fifo_round_under.png` (los hilos BAT± bajo el XIAO y el pocket),
> `render_fifo_round_usb.png` (cable USB-C insertado) y
> `render_fifo_round_spk.png` (bandeja del altavoz desde abajo)
> **Script generador:** `hardware/enclosure/build_fifo_round.py` (fuente de verdad)
> **STL:** `hardware/enclosure/print/`

La carcasa son dos piezas con una junta horizontal a Z 15,5 mm: **cuerpo**
(Z 0..15,5) y **tapa** (Z 15,5..21,5, con una lengueta de 1,05 mm que entra en
la ranura del cuerpo). No hay torres ni tornillos: la junta centra las piezas y
el cierre se asegura con cinta o unas gotas de adhesivo (ver §5.4).

---

## 1. Lista de materiales (BOM)

| Ref | Componente | Cant. | Dimensiones (mm) | Notas |
|-----|------------|:-----:|------------------|-------|
| **MCU** | XIAO ESP32-S3 8 MB o ESP32-C3 | 1 | 17,8 × 21,0 × 1,6 (PCB) + conector | USB-C nativo sobre la cara superior, boca a 0,10 mm de la pared; conector IPEX/U.FL para antena FPC |
| **Pantalla** | OLED SSD1306 0,96" I²C (GM009605 v4.3) | 1 | 27,0 × 27,0 × 2,6 | PCB 1,4 + cristal 1,2; área activa 21,8 × 11,0; header de 4 pines en el borde delantero |
| **Mic** | INMP441 I²S redondo (bottom port) | 1 | Ø14,0 · PCB 0,8 + lata 1,8 | 6 pads pasantes en **dos filas de 3** (paso 2,54, filas ±3,75). El puerto va en la cara de soldadura: esa cara mira a la ventana y la lata al cuerpo, sobre la bota acústica |
| **Amp** | MAX98357A I²S Class-D | 1 | 17,8 × 19,4 × 2,6 | **Girado 90° y sin el borne verde**: se desuelda y el altavoz va directo a los pads. BCLK=GPIO1, LRC=GPIO2, DIN=GPIO4, SD=3V3 |
| **Parlante** | Mini plano 8 Ω 1 W (1609/1511/1508) | 1 | 15,0 × 8,0 × 2,9 | Bajo la rejilla Ø17,4; soldado directo al amp |
| **Batería** | LiPo 902030 ~500 mAh 3,7 V | 1 | 20,0 × 30,0 × 8,85 | **Girada** (20 en X, 30 en Y); sobre **3 postes**; patas en la cara +X |
| **GNSS** | ATGM336H GPS + BeiDou (breakout) | 1 | 16,0 × 13,5 × 5,3 sin pines | PCB 1,0 + lata 2,4 abajo + SMD/pila/U.FL arriba; 5 pads a 2,54: VCC GND TX RX PPS. **Sin parche integrado** |
| **Antena GPS** | Cerámica 20 × 6 con pigtail U.FL | 1 | 20,0 × 6,0 × 4,2 | PCB 0,8 + cerámica 3,4; va aparte, plana en el piso trasero |
| **Switch** | SS12D07 deslizante | 1 | 3,4 × 8,0 × 4,0 | Pared −X, en Y=6,0 |
| **Pulsadores** | Tact 6 × 6 × 5 | 2 | 6,0 × 6,0 × 5,0 | Cuerpo 4,5 + pulsador Ø3,5 de 0,5; pegados en el asiento 8 × 8 de cada botón (tapa) |
| **Cable** | Silicona AWG30 (0,6 mm) | ~1,5 m | 36 curvas en el modelo (12 de señal + 16 de 3V3/GND + 5 de batería/switch + 1 de antena + 2 de altavoz) | Rojo/negro/azul/amarillo/verde/blanco/naranja/gris/morado/marrón/rosa |
| **Cinta** | Kapton 5 mm | 2 vueltas | 0,08 mm | Sujeta los hilos a la batería |
| **Caja** | PETG/ASA impreso | 2 piezas | **38 × 58 × 21,5** | Cuerpo 15,5 + tapa 7,05 (incluye lengueta) |
| **Lente** | Acrílico transparente 1 mm | 1 | 25,6 × 13,6 · R3,5 | Se rebaja a 0,64 mm; no se imprime |
| **Ant** | FPC A-02 para XIAO (opcional) | 1 | 7 × 4 × 0,4 | U.FL/IPEX; se pega en la pared izquierda |

---

## 2. Pinout XIAO ESP32-S3 (resumen)

> Los números de GPIO son los del **ESP32-S3**. En el **ESP32-C3** la I²S y el
> UART cambian de pines: respeta el `pins_arduino.h` de tu variante. La carcasa
> es la misma para ambas placas (mismo footprint de 17,8 × 21 mm).

```
              lejos del USB (+Y)
            +-------------------------------+
      5V ---|o                             o|--- D0   GPIO1
     GND ---|o                             o|--- D1   GPIO2
     3V3 ---|o       XIAO ESP32-S3        o|--- D2   GPIO3
     D10 ---|o                             o|--- D3   GPIO4
      D9 ---|o                             o|--- D4   GPIO5
      D8 ---|o                             o|--- D5   GPIO6
      D7 ---|o                             o|--- D6   GPIO43
            +------------ USB-C ------------+
              cerca del USB (-Y)

  izquierda            vista superior            derecha
```

> Los pads del XIAO son **castellados en el canto inferior** de la placa: no
> están en la cara superior. El hilo se suelda en la media luna del canto, así
> que el mazo sale a ras de suelo y la batería puede ir justo encima de la
> placa. En la cara inferior quedan además los pads **BAT+ / BAT−** (se cablean
> en paralelo con 5V y GND, ver §3) y los test points, que no se usan.

> La serigrafía de la placa usa **D0..D10**, no GPIO. El mapeo real del
> firmware (`esp32-firmware/src/config.h`) es:

```
  5V   = VBUS (entrada USB / salida 5 V)      3V3 = salida del LDO
  D0   = GPIO1  -> BCLK  (MAX98357A)
  D1   = GPIO2  -> LRC   (MAX98357A)
  D2   = GPIO3  -> pulsadores (INPUT_PULLUP)
  D3   = GPIO4  -> DIN   (MAX98357A)
  D4   = GPIO5  -> SDA   (OLED)
  D5   = GPIO6  -> SCL   (OLED)
  D6   = GPIO43 -> TX    -> RX del GPS
  D7   = GPIO44 -> RX    <- TX del GPS
  D8   = GPIO7  -> SCK   (INMP441)
  D9   = GPIO8  -> WS    (INMP441)
  D10  = GPIO9  -> SD    (INMP441)
```

> El MAX98357A lleva **GAIN al aire** (9 dB) y **SD a 3V3** (habilitado).
> El INMP441 lleva **L/R a GND** (canal izquierdo). Los dos táctiles van en
> paralelo a D2/GND: el firmware solo define `BUTTON_PIN 3`.

---

## 3. Esquema de conexiones

```
┌──────────────────────────────────────────────────────────────┐
│                       XIAO ESP32-S3                          │
│  borde derecho                    borde izquierdo            │
│  D0  GPIO1  ── BCLK ─┐            5V  ── switch ── LiPo +    │
│  D1  GPIO2  ── LRC  ─┤            3V3 ── rail 3V3            │
│  D3  GPIO4  ── DIN  ─┼─► MAX98357A GND ── rail GND           │
│  D4  GPIO5  ── SDA  ───► OLED      D7  GPIO44 ◄── TX GPS     │
│  D5  GPIO6  ── SCL  ───► OLED      D8  GPIO7  ── SCK MIC     │
│  D2  GPIO3  ── BTN  ───► 2× tact   D9  GPIO8  ── WS  MIC     │
│  D6  GPIO43 ─ TX   ───► RX GPS     D10 GPIO9  ── SD  MIC     │
└──────────────────────────────────────────────────────────────┘
      MAX98357A: Vin=3V3, SD=3V3, GND=GND, GAIN=libre
                 pads del altavoz ──► driver 8 Ω · 1 W

Alimentación:
  LiPo 3,7 V ──► switch SS12D07 ──► 5V (VBUS) del XIAO ──► LDO 3V3
                                └──► BAT+ (en paralelo, bajo la placa)
  LiPo − ───────────────────────────► GND
                                └──► BAT− (en paralelo, bajo la placa)
  3V3 del XIAO ──► OLED, INMP441, MAX98357A (Vin y SD) y GPS

Pulsadores (INPUT_PULLUP, GPIO3):
  tact 1 A ──┐
  tact 2 A ──┴──► D2 (GPIO3)
  tact 1 B ──┐
  tact 2 B ──┴──► GND
```

> **Carga:** los pads BAT+ / BAT− se cablean en paralelo con 5V y GND por el
> pocket del piso, así que el cargador de la placa queda dentro del circuito.
> El switch SS12D07 corta la batería de 5V y de BAT+; con el switch en OFF la
> LiPo queda aislada. El LDO trabaja con solo ~0,4–0,9 V de margen, así que con
> la LiPo por debajo de ~3,8 V el riel de 3V3 puede caer: para recargar, enciende
> el switch y usa un cargador USB-C.

---

## 4. Reparto interno (cavidad 34,4 × 54,4 × 18,2)

Origen en el centro de la caja. X: −19..19 · Y: −29..29 · Z: 0..21,5.
Piso útil a Z 1,8; bajo el XIAO el pocket baja a Z 1,0 (0,8 mm de rebaje).
Techo interior de la tapa a Z 20,0.

```
Vista superior (tapa quitada, Z = 1,8 .. 15,0):

      +Y (29) ────────────────────────────────────────────►
       │   ┌──────────────────────────────────────────┐
       │   │  antena GPS 20×6 (0, 23,5)  Z 1,8..6,0   │
       │   │  GPS ATGM336H 16×13,5×5,3  (0, 13)       │
       │   │  lata abajo + PCB + SMD/pila/U.FL arriba │
       │   │  5 pads VCC GND TX RX PPS en Y 6,85      │
       │   └──────────────────────────────────────────┘
       │
       │   ┌──────────────────────────────────────────┐
       │   │  LiPo 902030 20×30×8,85  (−3,5, −12)     │
       │   │  Z 7,25 .. 16,10  sobre 3 postes         │
       │   │  ─────────────────────────────────────   │
       │   │  USB-C 8,94×7,35×3,26 · XIAO 17,8×21×1,6 │
       │   │  Z 2,30 .. 3,90 (PCB) · antena U.FL      │
       │   └──────────────────────────────────────────┘
       │
       │  [switch −X]                              [USB-C −Y]
      −Y (−29) ◄──────────────────────────────────────────
```

```
Sección lateral (X = −4, por el amplificador y el altavoz):

Z 21,5 ┬──────────────────────────────────────────  cara
       │ lente 0,64 · cristal 1,2 · PCB 1,4  (17,4..20,0)  tapa
Z 20,1 ┤── techo del driver (17,2..20,1) ────────────
       │ driver 2,9 · táctil 6×6×5 (15,2..19,7) · INMP441 Ø14 (16,2..18,8)
       │ bota acústica 19,15..20,1 contra el techo (espuma 0,35)
Z 17,4 ┤── cara inferior del OLED · pines recortados a 16,6 ─
       │ MAX98357A 2,6 (14,7..17,3)  ← pegado, sin borne
Z 15,5 ┼───────────── junta: lengueta 1,05 ──────────
       │ mazo: canalillos X≈±15,5 → bus frontal Y≈−3 (16,55)
Z 16,1 ┤──────────────────── techo de la batería ─────
       │ LiPo 8,85 (7,25..16,10)
Z  7,25┼──────────────────── postes de batería ───────
       │ USB-C 3,26 (3,90..7,16) sobre el PCB
       │ XIAO 1,6 (2,30..3,90) + shield, elevado 0,5
Z  1,8 ┼─────────────────────────────────────────────  piso
       │ pocket 0,8 bajo el XIAO (1,00..1,80) · hilos BAT± a 1,36
Z  1,0 ┴─────────────────────────────────────────────  fondo del pocket
```

| Componente | Centro (X, Y) · Z inferior | Montaje |
|------------|---------------------------|---------|
| XIAO ESP32-C3/S3 | (0, −16, 2,30) | sobre 4 postes y el pocket de 0,8 mm (1,30 mm libres bajo el PCB); USB-C hacia la pared −Y |
| Conector USB-C | (0, −23,4, 3,90) | sobre el PCB, boca a 0,10 mm de la pared interior |
| Antena impresa del XIAO | (0, −7,7, 3,90) | borde trasero de la placa; la celda girada la tapa a 3,05 mm |
| Antena FPC A-02 (recomendada) | (−17,0, 2,0, 8,0) | pared interior izquierda, conector U.FL |
| LiPo 902030 | (−3,5, −12, 7,25) | girada, sobre **3 postes** de Ø2,0 (X −11,7 / 5,3); patas en la cara +X |
| ATGM336H (breakout) | (0, 13, 1,80) | piso trasero; lata 1,8..4,2, PCB 4,2..5,2, SMD/pila/U.FL encima |
| Antena GPS | (0, 23,5, 1,8) | piso trasero, plana; pigtail U.FL hasta el conector del GPS |
| Switch SS12D07 | (−13,9, 6,0, 1,8) | pared −X, palanca al exterior con paddle impreso |
| OLED 0,96" | (0, 13,6, 17,4) | techo de la tapa, cristal contra la ventana |
| Header del OLED | (0, 0,95, 16,6) | 4 pines **recortados a 0,8 mm**, cara inferior, borde delantero |
| MAX98357A | (−0,5, 14,0, 14,7) | bajo el OLED, girado 90°, pegado, **sin borne verde** |
| Driver 8 Ω | (−5,8, −4,8, 17,2) | tapa, sobre la **bandeja de apriete** (16,65..17,15), bajo la rejilla |
| Bandeja del altavoz | (−7,3, −4,45, 16,65) | pieza **aparte** (19,6 × 10,5 × 0,50); larguero izquierdo sobre la repisa de la pared, derecho sobre el pie del carril |
| INMP441 | (0, −20,0, 16,2) | tapa, bottom-port: cara de soldadura a la ventana y lata al cuerpo, sobre la bota acústica |
| 2× táctil 6×6×5 | (11,2, −7,0) y (11,2, −19,0), Z 15,2 | tapa, pegados en el asiento 8 × 8 de cada botón |

Todo el reparto está verificado en el build: ningún componente penetra más de
0,10 mm en la pared, los 42 componentes internos no se interpenetran, las
36 curvas del mazo quedan dentro de la cavidad sin tocar nada, los hilos BAT±
pasan bajo el PCB con ≥ 0,10 mm y un cable USB-C real pasa por el hueco
pasante.

Los tres asientos mecánicos de la tapa tienen chequeo propio: `[btn]` mide la
copa guía y la retención de la pestaña, `[mic]` verifica las repisas, los postes,
las guías y el hueco de 0,35 mm para la espuma, y `[spk]` comprueba que la
bandeja del altavoz apoye en los dos pies (juego 0,10 mm), que el resalte entre
en el bolsillo, que el tope la frene y que **ni un cable ni un componente**
toque el marco. En el cuerpo, `[sw]` mide con rayos el hueco del interruptor
(2,0 mm por lado desde el cuerpo de 8,0), la pista rehundida y el paddle:
recorrido ±1,1 mm y holgura 0,2 mm por lado.

**Antenas:** la impresa del XIAO queda **debajo de la celda** (base 7,25 a
3,05 mm de la antena, techo 16,10), así que con esta batería usa la **FPC en
la pared izquierda** (−17,0, 2,0, Z 8,0): el conector U.FL está en ese mismo
borde. El GPS **no lleva parche integrado**: es el breakout ATGM336H y su
antena es la cerámica de 20 × 6 que va aparte, plana en el piso trasero
(Y 20,5..26,5) y unida por el pigtail U.FL. Queda por debajo del OLED y libre
de metal: no pongas cinta, foil ni cables sobre la cerámica.

---

## 5. Secuencia de montaje

### 5.1 Preparación de cables y PCB

1. **XIAO**: soldar los hilos directos a los pads **castellados del canto
   inferior** (o headers hembra de perfil bajo). El mazo sale por los dos
   bordes a ras de suelo; deja 25 mm libres. No hace falta dar la vuelta a la
   placa: la media luna del canto se suelda desde el borde.
2. **OLED**: 4 hilos (SDA, SCK, VDD, GND), 40 mm AWG30, soldados a la **punta**
   de los pines del header. Vistos desde la ventana el orden es SDA, SCK, VDD,
   GND de izquierda a derecha (GND en +X). **Recorta los pines a 0,8 mm**
   (punta en Z 16,60): el hilo se suelda a esa punta, en el pasillo de 16,55,
   por debajo del driver del altavoz. Con el header abajo y el firmware en
   `U8G2_R0` la cara se ve derecha; no hay que girar la pantalla.
3. **INMP441**: 6 hilos (VDD=3V3, GND, L/R=GND, SD=D10, WS=D9, SCK=D8).
   L/R a GND selecciona el canal izquierdo. Los pads van en dos filas de 3
   (paso 2,54 mm, filas a ±3,75 mm del centro): SCK/WS/L/R arriba y SD/VDD/GND
   abajo, mirando el módulo desde la ventana.
4. **MAX98357A**: desoldar el borne verde. 6 hilos (Vin=3V3, GND, SD=3V3,
   BCLK=D0, LRC=D1, DIN=D3); GAIN se queda al aire (9 dB). Los 2 hilos del
   altavoz van directos a los pads del borne, sin terminal.
5. **GPS**: 4 hilos (VCC=3V3, GND, RX=D6, TX=D7) al borde delantero. El
   modulo es el breakout ATGM336H: la antena ceramica de 20 × 6 va **aparte**,
   en el piso trasero, y se une a su U.FL con el pigtail.
6. **Batería**: patas en la cara +X; + al switch y a BAT+, − a GND y a BAT−. Las
   dos derivaciones BAT± bajan por el pocket del piso y se sueldan a los pads de
   la cara inferior antes de montar la placa.
7. **Switch**: 2 hilos, en serie con el positivo (5V del XIAO).
8. **Pulsadores**: cada tact con A a D2 y B a GND; los dos en paralelo.
9. Medir continuidad y comprobar que no hay cortos entre 3V3 y GND.

**El mazo cruza la junta por dentro.** La cavidad del cuerpo se vacía hasta
Z 16,0 y la de la tapa empieza en Z 15,3: se solapan, así que los cables pasan
de una pieza a otra por el hueco interior sin taladrar la pared. Los pines del
XIAO miran al suelo: baja cada hilo por el borde de la placa, llévalo por el
canalillo de 2,2 mm entre la batería y la pared y súbelo por delante del XIAO
(Y ≈ −3) hasta el bus frontal de la tapa. Ahí reparte: 3V3 y GND a cada carga,
I2C al header del OLED, I2S al borde derecho del amplificador, I2S del micro
bajo el driver y los táctiles por el borde derecho. Sujeta el mazo con las dos
vueltas de Kapton a los lados de la batería (X −13,54 / +6,54), sin apretar
contra la pared.

### 5.2 Cuerpo (Z 0..15,5)

1. **XIAO** sobre los **4 postes** del piso, centro (0, −16), con el conector
   USB-C **dentro del hueco pasante** de la pared −Y. La PCB (1,6 mm) va de
   2,30 a 3,90; el pocket de 0,8 mm deja 1,30 mm libres bajo el PCB para los dos
   hilos BAT± y las 4 patas del USB-C. El conector
   va sobre su cara superior (3,90..7,16), con la boca a 0,10 mm de la pared
   interior. Fijar con 2 puntos de cianocrilato en los bordes de la PCB, sin
   tapar el conector. Antes de pegar, enchufa un cable USB-C y comprueba que
   entra hasta el fondo y que el sobre-molde no roza el bisel.
2. **GPS** en la zona trasera, centro (0, 13). Apoya la **lata** en el piso
   (1,80..4,20) y deja el PCB arriba (4,20..5,20) con los SMD, la pila de
   respaldo y el U.FL mirando a la tapa. Los 5 pads (VCC GND TX RX PPS) quedan
   en el borde delantero, a 2,54 mm. Pega la **antena cerámica de 20 × 6** en el
   piso trasero (centro Y 23,5, franja delantera libre para el pigtail) y une
   su U.FL al del módulo. Nada de metal sobre la cerámica.
3. **Switch** en la pared −X (Y = 6,0): entra desde dentro y se pega al piso;
   la palanca (3,0 mm fuera del cuerpo) atraviesa el hueco de 4,0 × 3,4 mm
   (2,0 mm por lado desde el cuerpo de 8,0). Pega el **paddle impreso**
   (`fifo_round_switch_slider.stl`) en la punta de la palanca con 0,05 mm de
   apriete: corre dentro de la pista rehundida de 7,4 × 3,8 × 1,0 y queda
   0,05 mm bajo la cara exterior, como un slider de mute. Fijar el cuerpo con
   una gota de hot-melt.
4. **Batería** sobre los **3 postes** (X −11,7 y 5,3), centro (−3,5, −12), Z
   **7,25** (7,25..16,10). Va **girada**: 20 mm en X y 30 en Y, con las patas en
   la cara +X. Deja 0,09 mm sobre el conector USB-C, 0,10 mm bajo la lata del
   micrófono y libera X 6,5..14,2 para que los táctiles de 5 mm cuelguen sin
   tocarla. Pega la cinta **fina** y no apiles Kapton debajo. Dos vueltas de
   Kapton alrededor (X −13,54 / +6,54, Y −23..−5), nunca sobre la cerámica del
   GPS ni sobre la antena FPC.
5. **Cableado del cuerpo**: batería → switch → XIAO por el pasillo trasero de
   la celda (Y ≈ 4,6, entre la celda y el GPS); batería − → GND cruzando por
   detrás del XIAO (Y ≈ −4,6); GPS → XIAO por delante del GPS (Y ≈ −1). Las
   derivaciones BAT+ / BAT− entran al pocket, corren a Z 1,36 apoyadas en su
   fondo bajo la placa y
   suben a sus pads sin cruzar los 4 postes.
6. **Mazo a la tapa**: los hilos salen de los dos bordes del XIAO, bajan por el
   canalillo de 2,2 mm entre la batería y la pared (X ≈ ±15,5), corren por
   delante del XIAO y suben por Y ≈ −3 hasta el bus frontal a **Z 16,55** (por
   encima de la celda y por debajo del driver). No los cruces sobre la antena
   del GPS ni sobre la cerámica.

### 5.3 Tapa (Z 15,5..21,5) — vista desde dentro

```
        +X (19)
          │   ┌──────────────────────────────────────┐
          │   │      OLED 27×27 · ventana 24,4×12,4    │
          │   │      (0, 14) · LCD contra la ventana     │
          │   └──────────────────────────────────────┘
          │   ┌──────────────────────────────────────┐
          │   │ MAX98357A 17,8×19,4  (−0,5, 14,0)     │
          │   │ girado 90°, pegado bajo el OLED       │
          │   │ sin borne verde                       │
          │   └──────────────────────────────────────┘
          │   ┌────────────────┐   ┌────────────────┐
          │   │ TACT + (11,2, −7) │ │ TACT − (11,2, −19) │
          │   │ 6×6×5 · Z 15,2..19,7                  │
          │   └────────────────┘   └────────────────┘
          │   ┌──────────────────────────────────────┐
          │   │   DRIVER 15×8×2,9  (−5,8, −4,8)      │
          │   │   bajo la rejilla Ø17,4              │
          │   │   bandeja de apriete aparte 16,65..17,15│
          │   └──────────────────────────────────────┘
          │   [INMP441 Ø14 (0, −20)]  ↑ mazo entra por X≈±15,5, bus Y≈−3 (16,55)
         −X (−19)
```

1. **Driver.** Suelda los dos hilos del altavoz al amplificador **antes** de
   pegar el amp: bajan a 15,50, corren por debajo de la bandeja (paso 8) y
   suben por el hueco del travesano delantero hasta los dos pads del driver
   (X −8,0 y −6,2, Y −8,86, Z 17,55). El driver apoya sobre la bandeja con el
   **diafragma hacia la rejilla** y el imán abajo; **no va pegado ni con
   hot-melt**: al cerrar, la espuma de 0,5 mm sobre la rejilla lo aprieta contra
   la bandeja.
2. **MAX98357A** pegado con cinta de doble cara fina **bajo el OLED**, centro
   (−0,5, **14,0**) y **girado 90°**: el header de 7 pines queda en el borde
   derecho (X = 8,4) y los dos pads del altavoz en el izquierdo (X = −9,4),
   mirando al driver. El cuerpo de la placa queda en Z 14,70..17,30 y deja
   0,10 mm hasta el PCB del OLED (17,40); cuelga 0,6 mm por debajo del suelo de
   la tapa, dentro de la cavidad continua del cuerpo. Se corre a Y=14,0 para
   que su canto delantero (Y 4,3) deje 1,3 mm sobre la celda (Y 3).
   **Desuelda el borne verde** y suelda los hilos del driver directos a los
   pads; con el borne puesto el conjunto sube ~7 mm y no entra.
3. **OLED** pegado al techo interior, con el área activa centrada en la ventana
   (0, 14) y el header hacia el borde delantero. El PCB (1,4 mm) va de 17,4 a
   18,8 y el cristal (1,2 mm) de 18,8 a 20,0, contra la ventana. Los 4 pines
   se **recortan a 0,8 mm** (punta en Z 16,60): con los 2,5 mm de serie se
   hundirían en la celda, que girada llega a Y 3 con el techo en 16,10. Va
   **después** del amp, porque el amplificador se pega en su cara inferior. El
   header queda en el borde **inferior** de la pantalla y el «arriba» de la
   imagen es +Y: con `U8G2_R0` la cara se ve derecha, sin invertir.
4. **INMP441** con la **cara de soldadura hacia la ventana** (PCB en
   18,00..18,80, pads y puerto arriba) y la **lata hacia el cuerpo**
   (16,20..18,00). El puerto acústico está en la cara de soldadura, así que
   esta es la única orientación que no lo deja sordo. Pon la **arandela de
   espuma de 0,5 mm** del módulo entre la cara y la bota (hueco de 0,35 mm) y
   déjalo caer en su asiento: **no va pegado**. Lo sujetan dos **repisas**
   impresas (Z 17,70..18,00) bajo el canto de la PCB, cuatro **postes** al
   techo y dos **guías** laterales. La bota acústica impresa en la tapa (anillo
   8,8 × 5,0, interior 7,2 × 3,4) baja hasta 19,15 y sella el puerto contra el
   taladro Ø1,1, con la espuma de 0,5 mm comprimida en los 0,35 mm que quedan
   hasta la PCB. Deja libres los dos costados: los hilos llegan por el lado
   izquierdo y por detrás.
5. **Tact switches 6×6×5** en su **asiento impreso** bajo cada pozo de botón,
   centros (11,2, −7,0) y (11,2, −19,0), cuerpo **Z 15,20..19,70** y pulsador
   hasta 20,20. El asiento es un anillo de 8,0 × 8,0 (pared 0,9 mm) anclado con
   un ala de 10,4 mm al techo de la tapa: mete el táctil 4,5 mm dentro del
   anillo, con los terminales hacia el borde derecho, y pégalo con una gota de
   cianocrilato en el ala. El pulsador Ø3,5 queda al ras del pozo del asiento
   (20,20) y la copa guía del botón (interior Ø3,7) lo envuelve 0,20 mm con
   holgura 0,10 a través del pozo Ø9,4. No pegues el táctil solo al techo: el
   ala es lo que evita que se despegue al pulsar.
6. **Reparte el mazo** antes de cerrar: 3V3 y GND suben por el bus frontal
   (Y ≈ −3) y desde ahí ramifican a OLED, amp (Vin y SD) y micro; el I²S del
   amp va al header del borde derecho (X = 8,4), el del micro llega por el
   costado izquierdo y se reparte: SCK y SD por encima del PCB, WS por el lado
   trasero (Y ≈ −14,55), VDD por el delantero (Y ≈ −25,45), y L/R y GND por debajo
   del PCB hasta el costado derecho (X = 7,0). Los táctiles bajan por el borde
   derecho.
7. **Rejilla y aro** del altavoz a presión desde fuera.
8. **Bandeja del altavoz**: con el driver ya soldado, desliza la bandeja desde
   el frente por debajo de él, pasando las dos subidas de cable por el hueco
   del travesano delantero, hasta que el **tope trasero** la frene. Los
   **resaltes Ø0,44** de los dos pies entran en los bolsillos del larguero: la
   bandeja queda retenida y el driver apoyado en ella a 0,05 mm. Es la última
   pieza antes de cerrar; **no lleva tornillos ni pegamento**.
9. **Botones**: el botón es una sola pieza (tapa Ø8,2 + pestaña Ø9,2 +
   copa guía Ø4,7). Primero **introduce el botón desde dentro**, copa primero,
   por el pozo Ø9,4 hasta que la tapa Ø8,2 asome por la cara; la pestaña Ø9,2
   queda dentro del pozo. Después **pon el aro Ø10,2** sobre la cara, pasándolo
   por encima de la tapa (su interior es Ø8,4): es el aro el que atrapa la
   pestaña, así que va al final y no antes. Su faldón entra en el **rebaje
   Ø10,4 × 0,55** de la cara y apoya con 0,05 de apriete (una gota de cola lo
   fija): no queda embutido en la cara ni baila. La tapa asoma 0,9 mm sobre la cara.
   Verde arriba, rojo abajo; los rótulos «+ SUBIR VOL» / «− BAJAR VOL» no forman
   parte de la pieza, van impresos o pegados sobre la cara. La copa **envuelve
   el pulsador sin aprisionarlo** (interior Ø3,7 sobre Ø3,5, holgura 0,10): su
   techo empuja la cara del pulsador y el borde queda a 0,30 mm del cuerpo, más
   que el recorrido; la retención la hace la pestaña bajo el aro.
10. **Lente acrílica** al final, pegada en el rebaje de la ventana con UV glue:
   queda 0,06 mm sobre la cara.

### 5.4 Cierre

1. Comprobar que la lengueta de la tapa (1,05 mm) entra en la ranura del cuerpo
   sin forzar: el juego es de 0,15 mm por lado.
2. Bajar la tapa alineando el altavoz con el lado izquierdo y los botones con
   el derecho; debe asentarse sola hasta el tope de la junta.
3. **Retención:** la junta es de centrado, no un clip. Poner 4 trozos de cinta
   fina de doble cara (o 3 gotas de cianocrilato) sobre el labio interior de la
   ranura antes de cerrar.
4. Verificar: pantalla sin velo, rejilla libre, botones con recorrido, USB-C
   accesible, switch operable.

---

## 6. Colecciones del `.blend` y vistas

| Colección | Contenido |
|-----------|-----------|
| `00_Estudio` | Fondo + luces Key/Fill/Rim/Bounce/Inspect + cámaras del build |
| `01_Cuerpo` | `Fifo_Body`, bisel USB-C, postes de batería |
| `02_Tapa` | `Fifo_Lid`, junta de pantalla, lente, rejilla, aro, **bandeja del altavoz** (`Spk_Retainer`, pieza aparte), botones y sus aros |
| `06_Internos` | XIAO, shield, LiPo, GPS + antena, switch, OLED, driver, amplificador, mic, tact, pads de cada pin, cables y Kapton |

El render explotado sube **todo lo montado en la tapa** +40 mm sin rotar
(`EXPLODE_DZ`), así que la lengueta y los componentes cuelgan alineados. Los
objetos `Cable_*` se ocultan en esa vista: el mazo completo se ve en el render
del arnés, y si no quedarían hilos colgando del cuerpo. Las vistas
adicionales son:

- `render_fifo_round_body.png`: oculta la tapa para ver el XIAO, el GPS, el
  switch y los pads de cada pin desde arriba.
- `render_fifo_round_lid.png`: cámara **ortográfica desde abajo** (rotación
  π sobre X); el `Backdrop` del estudio se oculta para que no tape la luz.
- `render_fifo_round_harness.png`: carcasa en material fantasma (94 % de
  transmisión, `transparent_max_bounces = 16`) para ver las 36 curvas del mazo
  y el reparto a cada módulo. Oculta la batería y el Kapton para que no tapen
  el XIAO ni el canalillo.
- `render_fifo_round_mic.png`: solo el INMP441 y sus cables, desde arriba, sin
  la tapa. Se ven las dos filas de 3 pads, la lata, la ranura del puerto y la
  vía central. La bota acústica se ve en `render_fifo_round_lid.png`.
- `render_fifo_round_power.png`: tapa fuera y LiPo en material fantasma (88 %
  de transmisión); quedan los cinco hilos de potencia, así que se ve el
  recorrido LiPo+ → switch → 5V y LiPo− → GND, más las dos derivaciones BAT±
  por el pasillo delantero.
- `render_fifo_round_xiao.png`: el XIAO desde **abajo**, sin carcasa, para ver
  los pads castellados, los pads BAT/test y cómo sale cada hilo del canto.
- `render_fifo_round_under.png`: cuerpo fantasma y cámara baja desde abajo;
  se ve el pocket de 0,8 mm, los 1,30 mm libres bajo el PCB, los 4 postes y
  los dos hilos BAT± corriendo a Z 1,36 hasta sus pads.
- `render_fifo_round_face.png`: cámara ortográfica frontal sobre la ventana;
  comprueba que la cara del OLED y «FIFO» salen derechos.
- `render_fifo_round_spk.png`: solo la tapa, la bandeja (`Spk_Retainer`, teñida
  de verde solo en esta vista), el driver y sus dos cables, en **ortográfica
  desde abajo**. El marco tapa el driver justo por donde aprieta y deja ver el
  hueco del travesaño delantero con las dos subidas de cable.

Ojo al renderizar: con `transparent_max_bounces` bajo (2) los rayos mueren
dentro del fantasma y la carcasa sale opaca; las luces de área con
`to_track_quat` degenerado tampoco aportan, por eso las vistas de interior usan
luces puntuales (`bulb()` en el script).

---

## 7. Impresión 3D

| Parámetro | Valor |
|-----------|-------|
| Material | PETG (térmico) o ASA (UV) |
| Altura de capa | 0,16 mm (calidad) / 0,20 mm (rápido) |
| Perímetros | 3 |
| Relleno | 15 % gyroid |
| Soportes | Cuerpo: ninguno. Tapa: solo dentro de la cavidad (y borde de 1 mm en el canto si se imprime por piezas) |
| Adhesión | Brim 6 mm |
| Temperatura | PETG 240/80 °C · ASA 260/100 °C |
| Tiempo estimado | ~2 h cuerpo + ~1,5 h tapa |

**Orientación**

- **Cuerpo:** piso en la cama, boca arriba. La cavidad no lleva soportes. El
  **hueco USB-C pasante** de 9,4 mm deja un puente en su borde superior: pon
  un soporte bajo esa franja (o un puente de sacrificio) y lima el rebabeo
  antes de montar, porque el conector debe entrar sin rozar. El hueco del
  switch (4,0 mm) es un puente corto y la pista rehundida de la pared vuela
  1,0 mm: sin soportes. El **pocket de 0,8 mm** bajo el XIAO se imprime
  como un rebaje abierto hacia arriba: no necesita soporte y deja 1,0 mm de
  piso; los 4 postes crecen desde su fondo.
- **Tapa:** **cara abajo** (la cara visible sobre la cama) y lengueta arriba.
  Así el techo de 1,5 mm se imprime primero y la bota acústica del micro y los
  **asientos de los táctiles** crecen desde él; al revés, el techo de
  34,4 × 54,4 mm quedaría en el aire. El canto redondeado de 3,4 mm sale como
  un vuelo de hasta 45° en el borde: revisa las primeras capas o añade un borde
  de soporte de 1 mm. La cara visible toma la textura de la cama; usa plancha
  lisa si te importa el acabado. La lengueta de 0,35 mm es la última capa.
- **Botones:** con la **cara del botón en la cama**: la tapa Ø8,2 primero, la
  pestaña Ø9,2 con su chaflán encima y la copa Ø4,7 abierta hacia arriba.
  No necesitan soportes.
- **Rejilla, aros y junta:** planos, tal como se exportan.
- **Paddle del switch:** plano, con la cara visible en la cama (0,4 mm de
  espesor). Se pega en la punta de la palanca con el switch ya montado.
- **Bandeja del altavoz:** plana, con los **bolsillos hacia arriba** (el lado
  liso en la cama). Es un marco de 0,50 mm de grosor, 19,6 × 10,5 mm: los
  puentes del travesano delantero y del larguero derecho salen solos, sin
  soportes. Imprímela en la misma tirada que el resto.

### 7.1 Opción «todo unido» (`*_full`)

`fifo_round_body_full.stl` y `fifo_round_lid_full.stl` traen los accesorios ya
fusionados (bisel USB; junta de pantalla, rejilla, aro del altavoz y aros de
los botones) para imprimir cada mitad de una sola vez. Los botones siguen
sueltos y se montan igual. La lente acrílica sigue siendo aparte.

- **Cuerpo completo:** se imprime como el cuerpo, con el piso en la cama. El
  bisel USB ya forma parte de la pared (no hay que pegarlo); deja un alero de
  0,6 mm que sale solo. Se mantiene el puente de 9,4 mm sobre el hueco USB-C.
- **Tapa completa:** se imprime **cara arriba** (la lengueta en la cama, la
  cara visible mirando al cielo). Los aros fusionados sobresalen 0,5 mm de la
  cara, así que apoyarla boca abajo dejaría toda la cara en el aire. En esta
  orientación la junta, la rejilla y los aros crecen hacia arriba sin soportes;
  solo piden soporte la cavidad (el techo de 20,0, los asientos de los táctiles
  y la bota del micro, todo oculto) y una islita bajo la rejilla dentro del
  hueco del altavoz. Pon el brim de 6 mm: la lengueta de 0,35 mm es la única
  superficie de contacto.
- **Antes de cerrar**, mete cada botón **desde dentro** por su aro Ø8,4: la
  tapa Ø8,2 pasa y la pestaña Ø9,2 queda retenida bajo el aro, apoyada en el
  pozo Ø9,4. El aro ya es parte de la tapa, así que el botón no se puede meter
  desde fuera ni sustituir con la carcasa cerrada. La **bandeja del altavoz**
  también es la misma pieza suelta: se desliza desde el frente aunque la tapa
  venga con los aros fusionados.
- La junta de pantalla fusionada es un labio rígido del mismo material (deja
  de ser una junta blanda). La lente se apoya igual en ella: 0,02 mm de aire.

Los 14 STL se pueden verificar antes de laminar:

```powershell
& "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\check_print_stls.py
```

---

## 8. Firmware y puesta en marcha

```bash
# 1. Compilar firmware (PlatformIO)
cd esp32-firmware
pio run -e seeed_xiao_esp32s3

# 2. Flashear (BOOT + RST → modo descarga)
pio run -e seeed_xiao_esp32s3 -t upload

# 3. Monitor serie (115200 8N1)
pio device monitor -e seeed_xiao_esp32s3

# 4. Android: compilar app y conceder permisos BLE/Ubicación
cd ../android-app
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Pruebas rápidas**

- OLED: «FIFO» + cara al encender.
- Mic: hablar cerca → nivel RMS en logcat `BleVoiceClient`.
- Parlante: TTS desde la app → sale por el MAX98357A.
- GPS: al aire libre → fix en < 60 s, notificación BLE `ff15` cada 1 s.
- Botones: evento BLE `ff14`, código 1 (corta) / 2 (larga).

---

## 9. Solución de problemas

| Síntoma | Causa probable | Acción |
|---------|----------------|--------|
| OLED no enciende | I²C invertido o sin pull-ups | Revisar SDA/SCL; añadir 4,7 kΩ a 3V3 |
| Mic mudo o con ruido | I2S0 mal cableado, puerto tapado o módulo al revés | SCK=7, WS=8, SD=9; la **cara de soldadura** debe mirar a la ventana (la lata al cuerpo); taladro Ø1,1 y bota libres |
| Parlante mudo | MAX98357A sin SD a 3V3 o mal I2S | BCLK=1, LRC=2, DIN=4; GND común |
| GPS sin fix | Antena tapada por metal, cables o el módulo mal orientado | La cerámica de 20 × 6 va **plana y arriba** en el piso trasero, con el pigtail U.FL conectado y sin cinta ni foil encima; el módulo apoya la lata en el piso y los SMD hacia la tapa. Al aire libre, fix en < 60 s |
| BLE con poco alcance | La celda tapa la antena impresa del XIAO | Con la batería girada la antena queda debajo del aluminio de la bolsa: usa la variante con **U.FL** y pega la **FPC** en la pared izquierda (−17,0, 2,0, Z 8,0), sin metal encima |
| BLE no conecta | UUIDs o MTU | Servicio `ff10`, MTU 517 |
| Batería no carga | Switch en OFF o BAT± sin soldar | Con BAT± en paralelo el cargador queda dentro del circuito; revisa las soldaduras de BAT± y prueba con el switch en ON |
| USB-C no entra | XIAO desplazado o rebaba del puente | El conector va en (0, −23,4, 3,90..7,16) sobre el PCB, con la boca a 0,10 mm de la pared interior; el hueco pasante mide **9,4 × 3,7 (R1,3)** y el bisel 10,6 × 4,9. Deja 0,23 mm por lado sobre la carcasa (8,94 × 3,26): no lo agrandes a ojo, lima solo el rebabeo del puente superior y comprueba el asiento del XIAO con un cable antes de pegar |
| El cable hace tope antes de fondo | Alivio de goma muy adelantado | El puerto solo admite 7,35 mm de casquillo; el alivio (9,0 × 3,3) y el sobre-molde (12,2 × 6,4) arrancan ~4 mm por fuera del bisel. Si tu cable trae la goma pegada a la punta, roza el bisel (pieza aparte: lima o reimprime solo esa) |
| Táctil se despega | Se pegó solo al techo | Pegar el táctil dentro del asiento 8 × 8 y una gota en el ala de 10,4; el ala es el anclaje |
| Botón no vuelve | Pestaña fuera del aro o copa descentrada | Meter el botón desde dentro (copa primero) **antes** de poner el aro Ø10,2; la copa Ø3,7 debe envolver el pulsador Ø3,5 sin tocarlo de lado (holgura 0,10), centrada en el pozo Ø9,4 |
| La tapa no asienta | Algo cruza la junta | El MAX98357A sin borne mide 2,6 mm y debe quedar bajo el OLED (14,70..17,30); el mazo, dentro del canalillo |
| Switch duro | Palanca rozando la ranura o el paddle rozando la pista | Limpiar rebaba del corte de 4,0 × 3,4 y de la pista de 7,4 × 3,8; el paddle debe correr ±1,1 mm sin rozar |

---

## 10. Archivos

```
hardware/enclosure/
├── build_fifo_round.py       # FUENTE DE VERDAD (commitear)
├── build_fifo.py             # helpers, materiales y estudio
├── check_print_stls.py       # verificación de los STL (commitear)
├── fifo_round.blend          # generado (NO commitear)
├── render_fifo_round*.png    # generados (NO commitear)
├── print/*.stl               # generados (NO commitear)
├── ASSEMBLY.md               # esta guía
└── README.md                 # resumen del modelo
```

**Regenerar todo:**

```powershell
& "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\build_fifo_round.py
```

---

## 11. Licencia y crédito

Hardware: CERN-OHL-S-2.0 · Firmware: Apache-2.0 · Modelo 3D: CC-BY-4.0
(«Fifo Robot Team»)

---

*Documentación generada desde `build_fifo_round.py` — 2026-11-22*
