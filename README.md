# Calendario

App Android para convertir cualquier texto seleccionado o compartido en una nota/recordatorio de calendario.

## Qué hace

- Aparece en **Compartir** cuando seleccionas texto en otra app.
- También aparece como acción **Procesar texto** en apps compatibles.
- Copia el texto completo como descripción del evento.
- Genera un título editable a partir de la primera línea.
- Permite escoger **fecha**, **hora**, **duración**, **calendario** y **aviso** antes de guardar.
- Detecta expresiones sencillas como `hoy`, `mañana`, `pasado mañana`, días de la semana y horas como `3 PM` o `15:30` para precargar los campos.
- Escribe el evento directamente en el calendario elegido usando `CalendarContract`.
- Recuerda el último calendario y aviso seleccionados.
- Funciona localmente; no usa servidor ni API externa.

## Permisos

La escritura automática requiere los permisos de Android `READ_CALENDAR` y `WRITE_CALENDAR`. Si el usuario no los concede, la app no modifica el calendario.

## Proyecto

- Kotlin
- Android Views + ViewBinding
- minSdk 26
- targetSdk 35

## Compilar

El repositorio incluye un workflow de GitHub Actions. Cada push a `main` compila `app-debug.apk` y lo publica como artifact del workflow.

También puede compilarse localmente con Gradle 8.10.2:

```bash
gradle :app:assembleDebug
```
