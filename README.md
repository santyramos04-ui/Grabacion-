# owl

App Android para usar la cámara **en segundo plano**: graba video o toma fotos en
**timelapse** mientras sigues usando el teléfono (otras apps, pantalla bloqueada, etc.).

## Funciones

- **Video** en 720p, 1080p o 4K, con o sin audio.
- **Timelapse**: una foto cada N segundos, con límite opcional de fotos.
- Cámara trasera o frontal.
- Vista previa para encuadrar antes de empezar.
- Notificación permanente con cronómetro y botón **Detener**.
- Archivos guardados en la galería:
  - Videos: `Movies/Owl`
  - Fotos: `Pictures/Owl/TL_<fecha>` (una carpeta por sesión)

## Cómo funciona

La captura corre en un *foreground service* de tipo `camera` (y `microphone` si grabas
audio), que es la forma permitida por Android para usar la cámara sin que la app esté
visible. Un *wake lock* parcial mantiene la CPU activa para que el timelapse siga con la
pantalla apagada.

## Instalar

1. Ve a la pestaña **Actions** del repositorio, abre la última ejecución de
   "Compilar APK" y descarga el artefacto `owl-apk`.
2. Descomprime el zip y abre `app-debug.apk` en el teléfono (permite instalar apps de
   orígenes desconocidos).
3. Acepta los permisos de cámara, micrófono y notificaciones.

O compílala tú mismo con Android Studio / `./gradlew assembleDebug`.

## Limitaciones

- Solo Android (8.0 o superior). iOS no permite usar la cámara en segundo plano.
- Mientras graba, otra app que use la cámara (p. ej. la cámara del sistema o una
  videollamada) se la quitará; el video se guarda hasta ese momento.
- Algunos fabricantes (Xiaomi, Huawei, Samsung…) cierran apps agresivamente: si la
  captura se detiene sola, desactiva la optimización de batería para *owl*.
