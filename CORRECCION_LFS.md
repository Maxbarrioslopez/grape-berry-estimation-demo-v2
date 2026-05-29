# CORRECCIÓN: Reglas .gitattributes LFS

## Hallazgo

El archivo `.gitattributes` tenía 4 reglas correctas para archivos binarios grandes (`.onnx`, `.onnx.data`, `.so`, `.tflite`) seguidas de 4 reglas que las contradecían usando `!text !filter !merge !diff`.

El resultado neto era que LFS no funcionaba para ningún tipo de archivo, y Git trataba los binarios como texto (riesgo de corrupción).

## Corrección aplicada

Eliminadas las líneas 5-8 que negaban las reglas LFS:

```diff
- *.onnx !text !filter !merge !diff
- *.onnx.data !text !filter !merge !diff
- *.so !text !filter !merge !diff
- *.tflite !text !filter !merge !diff
```

## Estado

| Archivo | Antes | Después |
|---------|-------|---------|
| `.gitattributes` | 8 líneas, reglas contradictorias | 4 líneas, LFS funcional |

## Recomendación

Verificar que los archivos binarios ya trackeados (`.onnx`, `.so`) estén almacenados correctamente con LFS. Puede requerir migración.
