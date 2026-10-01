# Tasa BCV — widget para Android

Widget de pantalla de inicio que muestra la tasa oficial del Banco Central de Venezuela
(Bs. por USD y por EUR), con fecha valor, variación frente a la tasa anterior (▲/▼) y
actualización automática.

## Cómo se actualiza
- Revisa la tasa cada 3 horas en segundo plano (WorkManager, solo con internet). El BCV
  publica la tasa del día siguiente en la tarde, así que el widget la toma el mismo día.
- Toca el ícono ↻ del widget para actualizar al instante. Toca el resto del widget para abrir la app.
- Fuente: `ve.dolarapi.com` (replica la tasa oficial del BCV); si falla, lee `bcv.org.ve` directamente.
- Si no hay conexión, muestra la última tasa guardada.

## Instalar en el Galaxy S26 Ultra

### Opción A — sin instalar nada (GitHub)
1. Crea un repositorio en GitHub y sube esta carpeta completa.
2. En la pestaña **Actions** se ejecuta "Build APK" (≈3 min). Abre la ejecución y descarga
   el artefacto **TasaBCV-apk** (un .zip con `app-release.apk`).
3. Pasa el .apk al teléfono y ábrelo. Permite "Instalar apps desconocidas" cuando lo pida.

### Opción B — Android Studio
1. Abre la carpeta en Android Studio y deja que sincronice.
2. Conecta el teléfono con depuración USB y pulsa ▶ Run, o usa
   *Build → Build APK(s)*.

### Después de instalar
1. Abre **Tasa BCV** una vez (programa la actualización diaria).
2. Pulsa **Añadir widget a la pantalla de inicio**, o mantén pulsada la pantalla de inicio →
   Widgets → Tasa BCV. Es redimensionable (3×2 por defecto).
3. Recomendado en Samsung: Ajustes → Apps → Tasa BCV → Batería → **Sin restricciones**,
   para que One UI no "duerma" la app y deje de actualizarse.

## Diseño
- Usa los colores Material You del fondo de pantalla (Android 12+) y el radio de esquina del sistema.
- Modo claro/oscuro automático.
