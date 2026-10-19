# Lector OCR (Android, Kotlin + Jetpack Compose)

App que abre **PDF, imágenes, archivos de texto, hojas de cálculo y documentos**, y **extrae el texto**.
La lectura y el OCR funcionan dentro del celular, sin internet y sin costos. Solo la publicidad necesita conexión.

## Qué lee
| Tipo | Formatos | Cómo |
|---|---|---|
| PDF | `.pdf` | Si la página trae texto lo lee directo; si es un escaneo, usa OCR |
| Imágenes | jpg, png, webp, bmp, gif, heic | OCR en el dispositivo (ML Kit) |
| Texto | txt, csv, tsv, md, json, xml, log, html, código… | Lectura directa (detecta UTF-8 / Windows-1252) |
| Hojas de cálculo | `.xlsx`, `.xlsm`, `.ods` | Todas las hojas; una fila por línea, celdas separadas por tabulación |
| Documentos | `.docx`, `.odt` | Texto y tablas |
| Presentaciones | `.pptx`, `.odp` | Texto de cada diapositiva |

No soporta los formatos viejos `.xls`, `.doc`, `.ppt` (guardalos como `.xlsx`, `.docx`, `.pptx`).
En las planillas, las fechas aparecen como el número interno de Excel y las fórmulas muestran su último resultado guardado.

## Escanear con la cámara
El botón **Escanear** abre el escáner de documentos de Google (gratis): recorta y endereza la hoja, permite varias páginas e importar desde la galería, y la app extrae el texto de todas. La primera vez descarga un módulo por internet y requiere Google Play Services.

## Compartir hacia Lector OCR
Desde WhatsApp, la galería o el gestor de archivos, tocá **Compartir** sobre una foto o un PDF y elegí **Lector OCR**: la app lo abre y extrae el texto sola.

## Datos detectados
Si el texto contiene teléfonos, correos, enlaces, CUIT/CUIL (se valida el dígito verificador), CBU o alias de pago, aparece el botón **Datos detectados (N)**. Cada dato se copia con un toque y, según el tipo, se puede llamar, escribir o abrir.

## Buscar en el texto
En la pestaña **Texto**, tocá la **lupa** y escribí la palabra: se resaltan todas las coincidencias (la actual en naranja), aparece el contador (ej. `3/12`) y con las flechas ▲ ▼ saltás de una a otra. Ignora mayúsculas y tildes. Para copiar la palabra, mantenela apretada sobre el texto y elegí Copiar.

## Borrar y empezar otra lectura
El botón **Borrar** (arriba a la derecha) quita el documento y el texto, previa confirmación, para empezar de nuevo.

## Traductor
Con **Traducir con la cámara** elegís el idioma, escaneás la hoja y se lee y traduce de una vez.

En la pestaña Texto, **Traducir** elige el idioma de destino (el de origen se detecta solo) con ML Kit, gratis y dentro del celular. Cada idioma se descarga una vez (hace falta internet esa primera vez); después funciona sin conexión. Antes de traducir vuelve a unir los renglones cortados del OCR y de los PDF (y las palabras partidas con guion), porque traducir renglón por renglón da frases sin sentido; los títulos, listas y tablas se dejan aparte. Traduce hasta 50.000 caracteres y **Ver original** vuelve al texto sin traducir.

## Leer en voz alta
En la pestaña Texto, **Leer en voz alta** usa la voz del celular (gratis, sin internet). Detecta el idioma del texto; si falta esa voz, se instala en Ajustes > Idioma > Salida de texto a voz. Se detiene al tocar **Detener** o al cambiar de pestaña.

## Historial
Cada lectura, escaneo o traducción se guarda en el celular (las últimas 30). El botón **Historial** (arriba) permite volver a abrirlas, borrarlas o vaciar todo. No se sube a ningún servidor.

## Correrlo en tu celular
1. Abrí la carpeta en **Android Studio** y dejá que sincronice Gradle
   (si ofrece actualizar versiones de Gradle/AGP/Kotlin, aceptá; también genera `gradlew`).
2. Conectá el celular (depuración USB) o abrí un emulador y tocá **Run**.

## Subir al repositorio
```bash
git init -b main
git add .
git commit -m "Lector OCR"
git remote add origin https://github.com/TU_USUARIO/TU_REPO.git
git push -u origin main
```
El `.gitignore` ya excluye `local.properties` y las claves de firma (`*.jks`, `*.keystore`).

## Generar el APK
**Con GitHub Actions (automático):** cada `push` a `main` ejecuta `.github/workflows/build-apk.yml`.
Al terminar, el APK está en la pestaña **Actions → la ejecución → Artifacts → LectorOCR-apk**.

**En tu PC:** `Build > Generate Signed App Bundle / APK > APK` en Android Studio,
o por consola: `./gradlew assembleRelease`.

## Firmar el APK
Un APK tiene que estar firmado para instalarse. Si no cargás tu keystore, el APK sale firmado con una clave de depuración: **se instala y sirve para uso personal**, pero no se puede subir a Google Play, y si más adelante instalás uno firmado con tu keystore tenés que desinstalar el anterior primero. Para firmar con tu propia clave:

1. **Crear el keystore (una sola vez):**
   ```bash
   keytool -genkeypair -v -keystore lector-release.jks -alias lector \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Guardalo en un lugar seguro y hacé una copia de respaldo: **si lo perdés, no podés publicar actualizaciones** de la misma app. No lo subas al repositorio.

2. **Firmar en GitHub Actions:** en el repo, `Settings > Secrets and variables > Actions > New repository secret`, creá:
   - `KEYSTORE_BASE64`: contenido del keystore en base64 (`base64 -w0 lector-release.jks`; en Mac: `base64 -i lector-release.jks`)
   - `KEYSTORE_PASSWORD`: contraseña del keystore
   - `KEY_ALIAS`: `lector`
   - `KEY_PASSWORD`: contraseña de la clave

   Desde ese momento el workflow genera `app-release.apk` ya firmado.

3. **Firmar en tu PC con Gradle:** definí las mismas variables antes de compilar:
   ```bash
   export KEYSTORE_PATH=/ruta/lector-release.jks
   export KEYSTORE_PASSWORD=...
   export KEY_ALIAS=lector
   export KEY_PASSWORD=...
   ./gradlew assembleRelease
   ```

4. **Firmar un APK ya generado (alternativa):**
   ```bash
   zipalign -p -f 4 app-release-unsigned.apk alineado.apk
   apksigner sign --ks lector-release.jks --out LectorOCR.apk alineado.apk
   apksigner verify LectorOCR.apk
   ```
   (`zipalign` y `apksigner` están en `Android/Sdk/build-tools/<versión>/`.)

## Instalar
Pasá el APK al celular y abrilo; Android te pedirá permitir "instalar apps de orígenes desconocidos" para ese gestor de archivos.

## Publicidad (AdMob)
- **Banner** siempre fijo abajo y **anuncio a pantalla completa** (imagen o video, lo elige AdMob) una vez cada 2 lecturas, escaneos o traducciones, al terminar (nunca mientras se lee). Para que salgan anuncios con video, en AdMob dejá el bloque del intersticial con formato «Imagen y video» (es el predeterminado).
- Además hay otro banner al final del texto extraído. Para cambiar cada cuántas lecturas sale, editá `EVERY` en `Ads.kt`.
- Los builds **debug** usan siempre anuncios de prueba de Google. Los **release** usan tus IDs; si no los cargás, también salen de prueba.
- En AdMob: registrá la app Android con el paquete `com.lectorocr` y creá dos bloques de anuncios (Banner e Intersticial). Vas a tener 3 IDs:
  - `ADMOB_APP_ID` (termina en `~número`)
  - `ADMOB_BANNER_ID` (termina en `/número`)
  - `ADMOB_INTERSTITIAL_ID` (termina en `/número`)
- **GitHub Actions:** cargalos en `Settings > Secrets and variables > Actions > Variables` con esos mismos nombres.
- **En tu PC:** agregalos a `gradle.properties` (o definí las variables de entorno) antes de `./gradlew assembleRelease`.
- Nunca toques tus propios anuncios reales: AdMob puede suspender la cuenta.

## Licencias y costos
- Android Studio, Kotlin, Jetpack Compose, GitHub Actions (repos públicos): gratis.
- PdfBox-Android: Apache 2.0. ML Kit Text Recognition: uso gratuito, corre en el dispositivo.
- Solo si publicás en Google Play hay un pago único de USD 25; repartir el APK por tu cuenta no cuesta nada.

## Estructura
- `MainActivity.kt` – interfaz (Compose)
- `MainViewModel.kt` – abrir archivos y lógica de extracción
- `TextExtractor.kt` – texto, hojas de cálculo, documentos y presentaciones
- `PdfSource.kt` – dibujar páginas de PDF
- `OcrEngine.kt` – reconocimiento de texto con ML Kit
- `Ads.kt` – banner y anuncio a pantalla completa (AdMob)
- `TextSearch.kt` – búsqueda dentro del texto extraído (lupa)
- `DataDetector.kt` / `DataDialog.kt` – detección y lista de datos (teléfonos, CUIT, CBU…)
