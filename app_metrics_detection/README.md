# Metrics Detection Android

Este modulo contiene la app Android `com.gaiaspa.metrics_detection`.

Documentacion tecnica actualizada del proyecto:

- `../README.md`: informe tecnico profesional.
- `../README2.md`: documento tipo paper.
- `../vistas.md`: registro de capturas reales.
- `../capturas_vistas_app/`: capturas historicas disponibles.
- `../capturas_vistas_app_4/`: capturas actualizadas en modo claro y oscuro.

## Build

```bash
./gradlew assembleDebug
```

## Alcance de la Iteracion Visual

Esta primera iteracion moderniza XML/Material Components, radios, spacing,
tipografia, empty states, toolbars e iconografia sin modificar:

- JNI/C++ (excepto overlay visual: ver `../README.md` seccion "Overlay Visual").
- ONNX/assets/modelos.
- Room/DAO/entities.
- Retrofit/API/network.
- WorkManager/sync.
- Pipeline ML productivo.
- Navegacion critica.

### Overlay Visual (iteracion actual)

El overlay visual fue refactorizado como pipeline paralelo independiente:
- `grape_pipeline_postprocess.cpp`: funciones modulares de render visual.
- `grape_pipeline_config.hpp` (`namespace overlay_visual`): constantes visuales.
- Documentacion completa en `../README.md` (seccion Overlay Visual) y `../README2.md` (paper tecnico).
