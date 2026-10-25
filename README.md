# owl

App Android con dos módulos:

- **Colección** (módulo principal, se abre siempre): inventario de colección biológica.
  Los especímenes se guardan en una base de datos local con número de catálogo
  (`OWL-0001`…), foto (cámara o galería), fecha, localidad, colector y notas; se pueden
  buscar, editar y eliminar. Las fotos se guardan en el almacenamiento privado de la app,
  fuera de la galería del teléfono.
- **Vault**: la app de cámara en segundo plano descrita abajo, sin cambios en su
  funcionamiento. Se entra con un gesto discreto y se sale con *Cerrar vault* en su menú.
  Al abrir la app desde el escritorio siempre empieza en Colección.

Estructura del código (`app/src/main/java/com/santy/grabacion/`):

- `gate/`: pantalla de inicio, gesto de desbloqueo, estado del vault y destinos.
- `main/`: módulo principal (inventario).
- El resto de archivos de ese paquete: el vault.

## Vault: cámara en segundo plano

Graba video o toma fotos en **timelapse** mientras sigues usando el teléfono (otras apps,
pantalla bloqueada, etc.).

### Secciones del vault

El vault tiene tres pestañas abajo:

- **Cámara**
  - Video en 720p, 1080p o 4K, con o sin audio.
  - Timelapse: una foto cada N segundos, con límite opcional de fotos.
  - Cámara trasera o frontal, vista previa para encuadrar.
  - Notificación permanente con cronómetro y botón **Detener**.
- **Galería**
  - *Videos*: reproduce, edita, comparte o elimina lo que grabaste.
  - *Timelapses*: cada sesión de fotos; ábrela para ver las fotos, compartirlas,
    eliminarlas o **crear un video** con ellas (eliges las fotos por segundo).
  - **Edición rápida**: recortar inicio/fin, quitar el audio y rotar 90°. Siempre se
    guarda una copia nueva; el original no se toca.
- **Ajustes**
  - **Buscar actualizaciones** e instalarlas desde la app.
  - Revisar actualizaciones automáticamente al abrir.
  - Atajo para desactivar la optimización de batería.

Archivos en la galería del teléfono:
- Videos (grabados y editados): `Movies/Owl`
- Fotos: `Pictures/Owl/TL_<fecha>` (una carpeta por sesión)

## Instalar

1. Abre la sección **Releases** del repositorio y descarga el último `owl-N.apk`.
2. Ábrelo en el teléfono (permite instalar apps de orígenes desconocidos).
3. Acepta los permisos de cámara, micrófono, notificaciones y fotos/videos.

Después, las nuevas versiones se instalan desde **Ajustes → Buscar actualizaciones**.

## Cómo funcionan las actualizaciones

Cada vez que se suben cambios a la rama principal, GitHub Actions compila el APK y publica
una versión `build-N`. La app consulta la última versión en GitHub y, si `N` es mayor que
la suya, descarga el APK y abre el instalador de Android.

Para que cada versión se instale encima de la anterior, todos los APK se firman con la
misma clave. La clave **no** está en el repositorio: GitHub Actions la toma de dos secretos
(*Settings → Secrets and variables → Actions → New repository secret*):

- `OWL_KEYSTORE_BASE64`: el archivo de la clave en base64.
- `OWL_KEYSTORE_PASSWORD`: su contraseña.

Sin esos secretos el APK se compila igual (se puede descargar desde *Actions*), pero no se
publica como actualización.

## Limitaciones

- Solo Android 10 o superior. iOS no permite usar la cámara en segundo plano.
- Mientras graba, otra app que use la cámara (la cámara del sistema, una videollamada)
  se la quitará; el video se guarda hasta ese momento.
- Algunos fabricantes (Xiaomi, Huawei, Samsung…) cierran apps agresivamente: si la
  captura se detiene sola, desactiva la optimización de batería para *owl*.
