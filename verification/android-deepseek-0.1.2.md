# Android DeepSeek 0.1.2: interrupciones de audio

## Problema y cambio

El usuario instaló 0.1.1 y recibió «El audio de voz se detuvo de forma inesperada». Esa versión solicitaba audio focus al conectar y lo conservaba durante el reconocimiento y las consultas a DeepSeek. Cerraba la conversación ante cualquier pérdida de focus, incluso una pérdida temporal. El mismo mensaje aparecía si Android denegaba la solicitud inicial; la captura no permite distinguir esos dos casos.

0.1.2 solicita el audio solo cuando tiene una respuesta lista para leer y lo libera al terminar, antes de abrir el reconocimiento. Cada respuesta posee una solicitud independiente: los avisos tardíos de solicitudes anteriores se ignoran.

Ante una interrupción temporal, pausa la voz y muestra «Voz en pausa. Esperando el audio…». Si Android devuelve el audio, vuelve a leer la respuesta pendiente desde el principio, sin duplicar el texto ni hacer otra llamada a DeepSeek. Si deniega el audio, lo retira de forma permanente o no lo devuelve en ocho segundos, libera los recursos y mantiene la misma conversación para continuar con «Escribir». Los avisos distinguen estos tres casos.

El agotamiento de la espera para iniciar el motor de voz también pasa a texto, en lugar de quedar como una conexión pendiente. El indicador de micrófono se muestra desactivado mientras la app genera, reproduce o espera audio.

Esto sigue las recomendaciones de [Android sobre audio focus](https://developer.android.com/media/optimize/audio-focus): solicitarlo al reproducir, respetar las interrupciones y liberarlo cuando termina la reproducción. El comportamiento específico del Motorola necesita verificación en el dispositivo.

## APK

- Código compilado: `17076b0ab2fa7cbf67a73d732a7552a964514403`.
- Archivo: `mural-deepseek-0.1.2.apk` (15725954 bytes).
- Paquete: `chat.mural.android`; versión `0.1.2`, código `10`.
- SHA-256: `8265c5f3eb98ec6b4f381d375df020d02393124d77fe2a6537335034616ec141`.
- Certificado SHA-256: `ab09947b991a1d09c5276178bab6552b9d5a2dde276b9630530edfe5ec90a14c`.
- Android mínimo API 26; destino API 36; arquitecturas: arm64-v8a, armeabi-v7a, x86, x86_64.
- APK de depuración para pruebas personales. Firma idéntica a 0.1 y 0.1.1: instalar como actualización, sin desinstalar ni borrar datos.
- Ninguna clave API está incorporada al APK o al repositorio.

## Verificación

JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.1.20, SDK 36 y Build Tools 35.0.0.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleUiTestAndroidTest
```

- Compilación completa aprobada.
- 361 pruebas unitarias en 46 suites: cero fallos, errores u omisiones. Doce pruebas nuevas simulan concesión, demora, denegación, pérdidas temporales, recuperación, pérdida permanente, cierre y callbacks tardíos.
- Lint aprobado: cero errores, 58 advertencias y 3 sugerencias.
- Firma e integridad ZIP del APK verificadas; `zipalign -c -P 16 4` aprobado.
- APK de pruebas de interfaz compilado, incluido el caso que conserva la sesión y permite enviar texto tras un fallo de reproducción.
- Las pruebas instrumentadas **no se ejecutaron**: no hay emulador ni teléfono conectado a este entorno. No se hicieron llamadas facturables ni se usó una clave real de DeepSeek.

## Prueba pendiente en el Motorola

Actualizar la instalación, probar «Escribir» y luego iniciar una conversación con el micrófono. Comprobar la secuencia escuchar/responder/volver a escuchar. Una interrupción breve debería mostrar la pausa y reanudar la reproducción; si no se recupera, el aviso debe permitir continuar por texto conservando la conversación. La voz, los permisos y los servicios instalados requieren prueba real.

## Servidor y revisión

No requiere despliegue del servidor: cambia exclusivamente la coordinación local del audio. El contrato con DeepSeek se mantiene y la integración alojada sigue deshabilitada. No se modificaron iOS, el formato del historial ni las credenciales, y no se fusionó el PR. Los APK anteriores y sus informes se conservan para referencia.
