# Revisión técnica — Automix 2.0

## Cambios revisados

- Separación de la interfaz en Reproductor, Mashup y Ajustes, con navegación inferior.
- Preferencias persistentes para modo Smart Reorder, transición, porcentajes, fundido, intro y duración del Mashup.
- Cachés BPM/tonalidad/género usan mapas concurrentes para reducir riesgos al leer/escribir desde el análisis en segundo plano.
- La consulta de género ya no depende de MusicBrainz: intenta encontrar una coincidencia de título/artista en Deezer y después en iTunes; muestra únicamente etiquetas encontradas y mantiene una caché por pista.
- En el acceso a MediaStore, la columna `DATA` se trata como opcional; solo las columnas imprescindibles provocan error si faltan.
- Mashup admite seleccionar las dos pistas, recomendar una segunda pista por BPM/tonalidad/etiquetas disponibles y elegir duración. El bucle automático de cambio de canción se suspende durante la superposición para evitar un cambio de pista en mitad del Mashup.
- Se añadieron comprobaciones para no actualizar la interfaz desde callbacks después de cerrar la Activity y para no mostrar el resultado de una pista cuando ya cambió la canción actual.

## Comprobaciones ejecutadas en este entorno

- Se inspeccionaron los archivos Kotlin, el manifiesto, la configuración Gradle y el workflow.
- Se pasó el código por el parser/compilador de Kotlin disponible. No se encontraron errores de sintaxis como `expecting` o `unexpected tokens`; las compilaciones locales se detuvieron por la ausencia de clases Android (`android.*`) porque este entorno no incluye el Android SDK.
- No se pudo invocar una compilación Gradle: no hay una instalación local de Gradle ni SDK Android, y el entorno de contenedor no pudo resolver la red para descargarlos.

## Pendiente de verificación real

1. Ejecutar `Build APK` en GitHub Actions y confirmar que termina sin error.
2. Instalar el APK en el teléfono y probar el permiso de biblioteca, carga de canciones, reproducción, pausa, cambio automático y reproducción con pantalla apagada.
3. Probar las búsquedas de Deezer/iTunes con varios títulos locales; sus respuestas pueden cambiar, ser limitadas o no incluir género.
4. Probar Mashup con archivos largos y cortos, pausa/reanudación y navegación durante el Mashup.
5. Revisar consumo de batería durante el análisis completo de bibliotecas grandes.

## Limitaciones conocidas

- El análisis BPM/tonalidad es aproximado y no realiza beatmatching.
- Mashup solo mezcla dos pistas y realiza fundidos; no cambia el tempo ni la tonalidad para sincronizar beats.
- La consulta de género usa catálogos musicales públicos, no un LLM ni un motor de búsqueda general. No se simula una respuesta de IA cuando no se encuentran metadatos.
- Sin una compilación real en Android SDK y una prueba en el dispositivo, no se puede afirmar que el APK está libre de errores de ejecución.
