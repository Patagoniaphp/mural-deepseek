# Android DeepSeek 0.1.1: texto y recuperación de voz

## Cambios observables

- «Escribir» está disponible desde el inicio, después de terminar y después de un fallo. Abre el editor de mensajes y permite iniciar una conversación sin micrófono.
- «Ver conversación» abre el historial. «Terminar» sigue disponible durante la conversación.
- Si el reconocimiento local falla por idioma, modelo o servicio, la app intenta el servicio de reconocimiento predeterminado del sistema y conserva esa selección durante la conversación.
- Los reintentos por servicio ocupado son limitados. Los errores de permisos o de frecuencia no provocan reintentos automáticos.
- Si no se puede recuperar el reconocimiento, se libera el audio y se conserva la conversación para continuar por texto, con un aviso específico en español o inglés.

El botón anterior «Texto» abría la transcripción, mientras que el editor solo aparecía durante una conversación activa o ante un aviso de permisos. Esa restricción impedía usar texto después de un fallo de voz.

La disponibilidad del servicio local no garantiza que esté instalado el idioma solicitado. Android distingue entre idioma no admitido e idioma temporalmente no disponible, por ejemplo por faltar su descarga: [documentación de SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer). La captura del Motorola no incluye el código de error, así que no demuestra cuál de esos casos ocurrió en ese teléfono.

## APK y actualización

- Código compilado: `26417d0835d3c656cb0976eea4a9a536cb2005e0`.
- Archivo: `mural-deepseek-0.1.1.apk`, 15723862 bytes.
- Paquete: `chat.mural.android`; versión `0.1.1`, código `9`.
- SHA-256 del APK: `f2f064a78ee112349cbda88d858f39d794ff747922a065e58e03eb41d89d3008`.
- Certificado SHA-256: `ab09947b991a1d09c5276178bab6552b9d5a2dde276b9630530edfe5ec90a14c`.
- Android mínimo API 26, destino API 36; incluye arm64-v8a, armeabi-v7a, x86, x86_64.
- Compilación de depuración para instalación y pruebas personales.

La firma coincide con la del APK 0.1 entregado anteriormente. Instalar encima de esa versión, sin desinstalar, para conservar la clave y los datos. No se incorporó ninguna clave API al APK ni al repositorio.

## Verificación realizada

Herramientas: JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.1.20, SDK 36 y Build Tools 35.0.0.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleUiTestAndroidTest
```

- Compilación completa del APK aprobada, incluida la interfaz Compose.
- 349 pruebas unitarias en 45 suites: cero fallos, errores u omisiones. Incluyen nueve casos nuevos de recuperación de reconocimiento.
- Lint: cero errores, 59 advertencias y 3 sugerencias.
- Integridad ZIP, firma con `apksigner` y alineación con `zipalign -c -P 16 4`: aprobadas.
- APK de pruebas instrumentadas compilado. Se actualizaron sus respuestas HTTP simuladas al formato Chat Completions y se añadieron casos para escribir desde el inicio, abrir el editor tras terminar/fallar y conservar la sesión después de un error de reconocimiento.
- Las pruebas instrumentadas se compilaron, pero **no se ejecutaron**: no hay emulador ni Motorola conectado a este entorno.
- No se utilizaron claves reales ni se hicieron llamadas facturables a DeepSeek.

## Comprobación pendiente en el Motorola

1. Actualizar la instalación existente y abrir «Escribir» antes de activar el micrófono.
2. Enviar un mensaje y comprobar la respuesta de DeepSeek con la clave configurada en el teléfono.
3. Probar el micrófono; ante un fallo, comprobar el aviso y que «Escribir» permita continuar.

La disponibilidad real del idioma, el servicio de voz, los permisos y el funcionamiento del micrófono necesitan prueba en el dispositivo. Las pruebas locales no garantizan esos comportamientos del Motorola.

## Servidor

No requiere despliegue de servidor: las rutas modificadas son locales y la app se conecta directamente a DeepSeek. La integración alojada sigue deshabilitada. No se modificaron iOS, contratos del servidor ni el formato del historial, y no se fusionó el PR.
