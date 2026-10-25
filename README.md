# owl

App Android con dos módulos:

- **Colección** (módulo principal, se abre siempre): inventario de colección biológica.
  Los especímenes se guardan en una base de datos local con código de catálogo por grupo
  (3 letras del grupo + número: `COL-0001` para Coleoptera, `IND-0001` sin grupo), foto
  (cámara o galería), fecha, localidad, colector y notas; se pueden buscar, editar y
  eliminar. Al escribir el nombre común, el formulario sugiere especies de
  [iNaturalist](https://www.inaturalist.org) observadas en la localidad y rellena el nombre
  científico y el grupo.
  Al añadir un espécimen se toma automáticamente el punto GPS (coordenadas, altitud y
  precisión) y, si la localidad está vacía, se llena con el nombre del lugar. Con GPS, las
  sugerencias buscan especies observadas a menos de 30 km. La ficha muestra el punto en un
  mapa de [OpenStreetMap](https://www.openstreetmap.org).
  - **Mapa de la colección**: marcadores con el color de cada grupo, filtro por grupo, capa
    estándar, topográfica o **satelital** (botón rápido que no mueve el mapa), punto de
    ubicación pequeño que late suavemente, polígono de extensión de presencia (EOO) y resumen de puntos,
    especies, área y altitud.
  - **Estadísticas**: riqueza observada y estimada (Chao1), completitud, Shannon, Simpson,
    Pielou, curva de acumulación de especies, registros por grupo, especie, mes, altitud y
    localidad, y distribución geográfica.
  - **Exportar**: CSV con columnas Darwin Core (Excel, GBIF), GeoJSON (QGIS), KML (Google
    Earth) e imagen del mapa con leyenda. La imagen pasa por un editor: proporción, tamaño
    (hasta 4096 px), detalle, formato (PNG, JPEG, WebP), nombre y vista previa. Los archivos se guardan en `Descargas/OWL` (la
    imagen en `Imágenes/OWL`) y después se pueden compartir o abrir.
  - Barra inferior con Colección, Mapa, **Añadir** (botón central), Estadísticas y Ajustes.
    El inventario se ve como lista o como galería.
  - Al elegir una foto de la galería se leen su fecha y coordenadas (EXIF). Las fotos se guardan en el almacenamiento privado de la app,
  fuera de la galería del teléfono.
- **Vault**: la app de cámara en segundo plano descrita abajo. Se entra con **5 toques
  rápidos en Colección → Ajustes** y se sale con *Cerrar vault*. Al abrir la app desde el
  escritorio siempre empieza en Colección.
- **5 toques rápidos en la pantalla principal de la Colección** inician el video en segundo
  plano con los ajustes del vault, sin entrar al vault.

Estructura del código: módulos `core`, `recorder`, `vault`, `collection` y `app`. Ver
[ARCHITECTURE.md](ARCHITECTURE.md).

## Vault: cámara en segundo plano

Graba video o toma fotos en **timelapse** mientras sigues usando el teléfono (otras apps,
pantalla bloqueada, etc.).

### Secciones del vault

El vault tiene tres pestañas abajo:

- **Cámara**
  - Video con duración (sin límite, 1, 2, 5, 10 min o personalizada), formato (MP4 o MP4
    HDR si la cámara lo admite), calidad, fps, estabilización, tasa de bits, audio,
    cámara y linterna. Solo se ofrece lo que admite el teléfono; se guarda entre sesiones.
  - Pausar, reanudar y finalizar; la duración cuenta solo el tiempo grabado.
  - Timelapse: una foto cada N segundos, con límite opcional de fotos.
  - Cámara trasera o frontal, vista previa para encuadrar.
  - Notificación discreta «Procesando» con botones **Pausar/Reanudar** y **Finalizar**.
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
