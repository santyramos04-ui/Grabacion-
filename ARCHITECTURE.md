# Arquitectura de OWL

La app está dividida en **módulos Gradle** independientes. Cada uno se compila por separado
y solo puede usar lo que le dan los módulos de los que depende. Al final, `:app` los une en
un solo APK.

```
            :app  (une todo: inicio, Application, FileProvider)
           /  |  \
   :vault  :collection   ← nunca se importan entre sí
       \      /
       :recorder   (cámara en segundo plano)
           |
         :core     (piezas compartidas)
```

| Módulo | Paquete | Qué contiene |
|---|---|---|
| `:core` | `com.santy.grabacion.core` | `Routes` (navegación entre módulos), `VaultGate` (vault abierto/cerrado), `TapSequence` (gesto de 5 toques), `AppInfo`, `UpdateManager`, utilidades de interfaz, iconos, fuentes y textos comunes. |
| `:recorder` | `com.santy.grabacion.recorder` | `RecordingController` (**única** forma de iniciar, pausar, reanudar y finalizar), `RecordingSettings` + `RecordingSettingsStore` (ajustes guardados), `RecorderCapabilities` (lo que admite la cámara), `CameraService` (servicio en primer plano y notificación «Procesando»). |
| `:vault` | `com.santy.grabacion.vault` | Pantallas del vault: cámara y sus ajustes, galería, reproductor, editor, ajustes. Tema oscuro «Visor». |
| `:collection` | `com.santy.grabacion.collection` | Colección biológica: inventario, ficha, formulario, mapa, editor de exportación del mapa, estadísticas, exportación. Tema «Etiqueta de colección». |
| `:app` | `com.santy.grabacion` | `OwlApplication` (instala las rutas), `SplashActivity` (siempre abre la colección y cierra el vault). |

## Cómo se comunican

- **Navegación:** el vault y la colección no se conocen. Para ir de uno al otro usan
  `Routes.collection(ctx)` / `Routes.vault(ctx)`; `OwlApplication` les dice a qué pantalla
  corresponde cada ruta.
- **Grabación:** los dos módulos usan solo `RecordingController`. El estado real
  (grabando, en pausa, tiempo grabado, límite) llega en `RecordingController.status` y lo
  confirma CameraX; nada es simulado.
- **Ajustes de grabación:** el vault los edita y los guarda con `RecordingSettingsStore`;
  la colección los lee al iniciar una grabación con el gesto.

## Accesos ocultos

| Dónde | Gesto | Qué hace |
|---|---|---|
| Colección → Ajustes | 5 toques rápidos en la misma zona (menos de 2 s) | Abre el vault (`VaultGate.unlock`). |
| Colección → pantalla principal (inventario) | 5 toques rápidos en la misma zona | Inicia el video en segundo plano con los ajustes del vault. No abre el vault. |

Un arrastre, un scroll, un toque largo o un segundo dedo reinician la cuenta, para evitar
activaciones accidentales. `TapSequenceLayout` solo observa los toques: los botones y la
lista siguen funcionando.

## Límites del sistema (Android)

- Android siempre muestra el punto verde de privacidad y una notificación mientras se usa
  la cámara; la app la hace discreta («Procesando», icono de punto, oculta en la pantalla de
  bloqueo), pero no puede eliminarla.
- La grabación solo puede *empezar* con la app visible; después sigue en segundo plano.
- El permiso de cámara se pide la primera vez; no se puede omitir.
- CameraX 1.4 graba siempre en MP4 (H.264, o HEVC 10 bits para HDR cuando la cámara lo
  admite).

## Cómo pedir cambios en un módulo

Di qué módulo quieres cambiar; así el cambio no toca lo demás:

- «En el **vault**, …» → `vault/`
- «En la **colección** / el mapa / las estadísticas, …» → `collection/`
- «En la **grabación** / la notificación, …» → `recorder/` (afecta a los dos módulos)
- «En el **inicio** de la app, …» → `app/`
