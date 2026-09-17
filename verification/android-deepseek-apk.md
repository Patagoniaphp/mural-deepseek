# APK de prueba Android con DeepSeek

Versión posterior: [Android DeepSeek 0.1.1: texto y recuperación de voz](android-deepseek-0.1.1.md). Este documento conserva los resultados del APK 0.1.

Fecha: 2026-09-17. Repositorio: `Patagoniaphp/mural-deepseek`.

## Código y artefacto

- Rama: `feat/android-deepseek`.
- Código compilado: `5011692dc5dfe503d1c076f6e8e4ccc28f0ea80a`.
- Archivo entregado: `mural-deepseek-debug.apk` (15 720 346 bytes).
- SHA-256 del APK: `e5fbf17d3d9feafc62b720a67f5274a92f139664e517b88d517db5ff54f0e273`.
- Aplicación: `chat.mural.android`, versión `0.1`, código `8`.
- Android mínimo: API 26 (Android 8); destino y compilación: API 36.
- Arquitecturas incluidas: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- Firma de depuración RSA 2048, APK Signature Scheme v2 verificado con `apksigner`.
- SHA-256 del certificado: `ab09947b991a1d09c5276178bab6552b9d5a2dde276b9630530edfe5ec90a14c`.

El APK y un respaldo privado de su firma se entregaron como archivos descargables.
La clave de firma no se publica en el repositorio. Para mantener la identidad en
futuras actualizaciones locales, colocar ese respaldo en
`apps/android/.signing/debug.keystore`, ubicación que ya admite el proyecto.
Este APK es para pruebas mediante instalación directa, no una publicación en Play Store.

## Compilación y comprobaciones

Herramientas: JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.10.1,
Kotlin 2.1.20, Android SDK 36 y Build Tools 35.0.0.

Desde `apps/android`, se ejecutaron las tareas equivalentes a:

```sh
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug
```

- `assembleDebug`: aprobado; compilación completa, incluidas las pantallas Compose.
- `testDebugUnitTest`: 340 pruebas, 44 suites, cero fallos, errores o pruebas omitidas.
- `lintDebug`: aprobado; 0 errores y 56 advertencias.
- Integridad ZIP y firma del APK: verificadas.
- `zipalign -c -P 16 4`: aprobado (alineación del contenedor APK).
- El manifiesto final confirma API mínima, paquete y arquitecturas anteriores.

La compilación completa detectó dos pruebas que aún dependían de
`VoiceConnectionRecovery`, parte del transporte WebRTC eliminado. Se retiraron
esas pruebas obsoletas y sus imports; se conservaron las pruebas de rutas de audio.
No fue necesario modificar código de producción para construir el APK.

## Instalación y configuración

1. Descargar el APK y abrirlo desde Descargas en Android.
2. Permitir la instalación desde la aplicación que abre el archivo si Android lo solicita.
3. En Mural, abrir Ajustes > Avanzado > Usar tu propia clave de API > Guardar clave.
4. Introducir una clave de DeepSeek, aceptar el consentimiento de IA y conceder el micrófono para conversar por voz.

La clave no forma parte del APK ni del repositorio. Se guarda cifrada mediante
Android Keystore en el teléfono. La app envía texto a DeepSeek con el modelo
`deepseek-chat`; el reconocimiento y la reproducción de voz usan servicios Android.
No se utilizaron claves reales ni se realizaron llamadas facturables durante la verificación.

## Servidor y límites de la verificación

No requiere despliegue de servidor: esta variante se conecta directamente a
DeepSeek y deshabilita la integración alojada, el inicio de sesión y las compras
de minutos. iOS y el servidor existente no se modificaron. No se fusionó el PR.

Quedan pendientes la instalación y las pruebas instrumentadas en un dispositivo,
la llamada real con la clave del usuario, los idiomas y voces instalados,
los permisos, silencio, mute, Bluetooth y cierre durante una conversación.
La verificación local no demuestra por sí sola esos comportamientos en el Motorola.
