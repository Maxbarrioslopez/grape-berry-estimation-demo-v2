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

- JNI/C++.
- ONNX/assets/modelos.
- Room/DAO/entities.
- Retrofit/API/network.
- WorkManager/sync.
- Pipeline ML.
- Navegacion critica.
