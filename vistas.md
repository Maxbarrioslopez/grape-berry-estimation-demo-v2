# Vistas de la aplicación

## Objetivo

Este documento registra las principales vistas de la aplicación Android Metrics Detection, capturadas desde emulador, siguiendo el flujo funcional actual de la app con usuario administrador.

## Entorno de captura

- Sistema: Linux
- Ruta base: `/home/maxi/Escritorio/modelo_nuevo/optimizacion_uvas`
- Proyecto Android detectado: `/home/maxi/Escritorio/modelo_nuevo/optimizacion_uvas/app_metrics_detection`
- Dispositivo: Emulador Android `emulator-5554`
- Modelo del emulador: `sdk_gphone16k_x86_64`
- Version Android del emulador: `17`
- Metodo de captura: `adb exec-out screencap -p`
- Fecha de captura: `2026-05-18 15:09:38 -04`
- Fecha de captura adicional en modo claro: `2026-05-18 15:23:38 -0400`
- Package name: `com.gaiaspa.metrics_detection`
- Launcher: `com.gaiaspa.metrics_detection/.LauncherActivity`
- Usuario de prueba: `admin@metrics.com`

## Flujo general capturado

1. Login
2. Registro
3. Recuperacion de contrasena
4. Creacion de lote
5. Seleccion de variedad
6. Captura/carga de imagenes del racimo
7. Selector de origen de imagen
8. Galeria del sistema
9. Resultado de racimo e histograma compacto
10. Detalle del racimo
11. Guardado de lote
12. Historial
13. Filtro de historial
14. Detalle de lote
15. Imagen en pantalla completa
16. Exportacion/compartir PDF
17. Perfil y configuracion
18. Soporte y FAQ

## Capturas

La primera serie corresponde al flujo principal capturado inicialmente.

### 01. Login

**Archivo:** `capturas_vistas_app/01_login.png`

**Descripcion:**  
Pantalla inicial de acceso con campos de email, contrasena, recuperacion y acceso a registro.

![Login](capturas_vistas_app/01_login.png)

---

### 02. Registro

**Archivo:** `capturas_vistas_app/02_registro.png`

**Descripcion:**  
Pantalla de registro en etapa inicial, con email y codigo de invitacion.

![Registro](capturas_vistas_app/02_registro.png)

---

### 03. Recuperacion

**Archivo:** `capturas_vistas_app/03_recuperacion.png`

**Descripcion:**  
Pantalla para recuperacion/cambio de contrasena mediante email, RUT y nueva clave.

![Recuperacion](capturas_vistas_app/03_recuperacion.png)

---

### 04. Crear lote

**Archivo:** `capturas_vistas_app/04_crear_lote.png`

**Descripcion:**  
Vista inicial posterior al login para ingresar Company, Vessel, Block y Variety.

![Crear lote](capturas_vistas_app/04_crear_lote.png)

---

### 05. Selector de variedad

**Archivo:** `capturas_vistas_app/05_selector_variedad.png`

**Descripcion:**  
Menu desplegable de variedades disponibles para asociar al lote.

![Selector de variedad](capturas_vistas_app/05_selector_variedad.png)

---

### 06. Datos de lote completos

**Archivo:** `capturas_vistas_app/06_lote_datos_completos.png`

**Descripcion:**  
Formulario de lote completo con variedad seleccionada y boton de inicio habilitado.

![Datos de lote completos](capturas_vistas_app/06_lote_datos_completos.png)

---

### 07. Captura de racimos

**Archivo:** `capturas_vistas_app/07_captura_racimos.png`

**Descripcion:**  
Vista de procesamiento/captura de racimos con estado vacio y accion para crear un nuevo racimo.

![Captura de racimos](capturas_vistas_app/07_captura_racimos.png)

---

### 08. Selector de origen de imagen

**Archivo:** `capturas_vistas_app/08_selector_origen_imagen.png`

**Descripcion:**  
Bottom sheet para seleccionar captura por camara o carga desde galeria.

![Selector de origen de imagen](capturas_vistas_app/08_selector_origen_imagen.png)

---

### 09. Galeria del sistema

**Archivo:** `capturas_vistas_app/09_galeria_picker.png`

**Descripcion:**  
Photo picker del sistema Android abierto desde la app para seleccionar imagen.

![Galeria del sistema](capturas_vistas_app/09_galeria_picker.png)

---

### 10. Imagen seleccionada en galeria

**Archivo:** `capturas_vistas_app/10_galeria_imagen_seleccionada.png`

**Descripcion:**  
Estado del picker con una imagen seleccionada y accion Listo disponible.

![Imagen seleccionada en galeria](capturas_vistas_app/10_galeria_imagen_seleccionada.png)

---

### 11. Captura de reverso

**Archivo:** `capturas_vistas_app/11_frente_cargado.png`

**Descripcion:**  
Bottom sheet mostrado tras cargar el Frente, solicitando la imagen de Reverso.

![Captura de reverso](capturas_vistas_app/11_frente_cargado.png)

---

### 12. Resultado y resumen

**Archivo:** `capturas_vistas_app/12_procesamiento_resultado.png`

**Descripcion:**  
Resultado calculado para el racimo con cantidad estimada, metricas y mini histograma de calibres.

![Resultado y resumen](capturas_vistas_app/12_procesamiento_resultado.png)

---

### 13. Detalle del racimo

**Archivo:** `capturas_vistas_app/13_resultado_detalle_racimo.png`

**Descripcion:**  
Detalle del racimo en vista de resumen, con metricas consolidadas Frente/Reverso.

![Detalle del racimo](capturas_vistas_app/13_resultado_detalle_racimo.png)

---

### 14. Imagenes del racimo

**Archivo:** `capturas_vistas_app/14_racimo_imagenes.png`

**Descripcion:**  
Pestana de imagenes del racimo con Frente y Reverso cargados como imagenes validas.

![Imagenes del racimo](capturas_vistas_app/14_racimo_imagenes.png)

---

### 15. Confirmar guardado de lote

**Archivo:** `capturas_vistas_app/15_confirmar_guardar_lote.png`

**Descripcion:**  
Dialogo de confirmacion antes de guardar el lote con las fotos actuales.

![Confirmar guardado de lote](capturas_vistas_app/15_confirmar_guardar_lote.png)

---

### 16. Lote guardado

**Archivo:** `capturas_vistas_app/16_lote_guardado_home.png`

**Descripcion:**  
Retorno al formulario inicial luego de guardar el lote correctamente.

![Lote guardado](capturas_vistas_app/16_lote_guardado_home.png)

---

### 17. Historial

**Archivo:** `capturas_vistas_app/17_historial.png`

**Descripcion:**  
Listado de lotes con tarjeta de lote, estado de sincronizacion, seleccion para PDF y acceso a detalle.

![Historial](capturas_vistas_app/17_historial.png)

---

### 18. Filtro de historial

**Archivo:** `capturas_vistas_app/18_historial_filtro.png`

**Descripcion:**  
Filtro desplegable del historial con opciones Todos, Sincronizados y No sincronizados.

![Filtro de historial](capturas_vistas_app/18_historial_filtro.png)

---

### 19. Detalle de lote

**Archivo:** `capturas_vistas_app/19_detalle_lote.png`

**Descripcion:**  
Detalle del lote guardado con metadata, fecha, imagen, prediccion y accion de compartir PDF visible.

![Detalle de lote](capturas_vistas_app/19_detalle_lote.png)

---

### 20. Imagen en pantalla completa

**Archivo:** `capturas_vistas_app/20_imagen_pantalla_completa.png`

**Descripcion:**  
Vista ampliada de imagen/prediccion abierta desde el detalle del lote.

![Imagen en pantalla completa](capturas_vistas_app/20_imagen_pantalla_completa.png)

---

### 21. Exportar PDF

**Archivo:** `capturas_vistas_app/21_exportar_pdf_compartir.png`

**Descripcion:**  
Hoja del sistema Android para compartir el PDF generado desde el detalle del lote.

![Exportar PDF](capturas_vistas_app/21_exportar_pdf_compartir.png)

---

### 22. Perfil y configuracion

**Archivo:** `capturas_vistas_app/22_perfil_configuracion.png`

**Descripcion:**  
Perfil del usuario administrador, estado disponible, descarga de lotes, cierre de sesion y controles de configuracion visibles.

![Perfil y configuracion](capturas_vistas_app/22_perfil_configuracion.png)

---

### 23. Configuracion y almacenamiento

**Archivo:** `capturas_vistas_app/23_configuracion_almacenamiento.png`

**Descripcion:**  
Seccion de almacenamiento local, modo oscuro y zona de peligro visibles. No se ejecuto ninguna accion destructiva.

![Configuracion y almacenamiento](capturas_vistas_app/23_configuracion_almacenamiento.png)

---

### 24. Soporte

**Archivo:** `capturas_vistas_app/24_soporte.png`

**Descripcion:**  
Centro de soporte con informacion de contacto y preguntas frecuentes.

![Soporte](capturas_vistas_app/24_soporte.png)

---

### 25. FAQ de soporte

**Archivo:** `capturas_vistas_app/25_soporte_faq.png`

**Descripcion:**  
Pregunta frecuente expandida dentro del centro de soporte.

![FAQ de soporte](capturas_vistas_app/25_soporte_faq.png)

---

## Capturas en modo claro

Serie adicional capturada tras desactivar Modo oscuro desde Perfil. El orden siguiente es logico de flujo; los archivos conservan el prefijo `claro_XX` segun el momento real de captura.

### Claro 01. Login

**Archivo:** `capturas_vistas_app/claro_22_login.png`

**Descripcion:**  
Pantalla inicial de acceso en modo claro, con campos de email y contrasena.

![Login modo claro](capturas_vistas_app/claro_22_login.png)

---

### Claro 02. Registro

**Archivo:** `capturas_vistas_app/claro_23_registro.png`

**Descripcion:**  
Pantalla de registro en modo claro con email y codigo de invitacion.

![Registro modo claro](capturas_vistas_app/claro_23_registro.png)

---

### Claro 03. Recuperacion

**Archivo:** `capturas_vistas_app/claro_24_recuperacion.png`

**Descripcion:**  
Pantalla de recuperacion/cambio de contrasena en modo claro.

![Recuperacion modo claro](capturas_vistas_app/claro_24_recuperacion.png)

---

### Claro 04. Crear lote

**Archivo:** `capturas_vistas_app/claro_04_crear_lote.png`

**Descripcion:**  
Formulario inicial de lote en modo claro posterior al login administrador.

![Crear lote modo claro](capturas_vistas_app/claro_04_crear_lote.png)

---

### Claro 05. Selector de variedad

**Archivo:** `capturas_vistas_app/claro_05_selector_variedad.png`

**Descripcion:**  
Selector desplegable de variedades disponible durante la creacion del lote.

![Selector de variedad modo claro](capturas_vistas_app/claro_05_selector_variedad.png)

---

### Claro 06. Datos de lote completos

**Archivo:** `capturas_vistas_app/claro_06_lote_datos_completos.png`

**Descripcion:**  
Formulario completo con Company, Vessel, Block y Variety listos para iniciar lote.

![Datos de lote modo claro](capturas_vistas_app/claro_06_lote_datos_completos.png)

---

### Claro 07. Captura de racimos

**Archivo:** `capturas_vistas_app/claro_07_captura_racimos.png`

**Descripcion:**  
Vista de procesamiento de racimos en estado inicial, sin racimos cargados.

![Captura de racimos modo claro](capturas_vistas_app/claro_07_captura_racimos.png)

---

### Claro 08. Selector de origen de imagen

**Archivo:** `capturas_vistas_app/claro_08_selector_origen_imagen.png`

**Descripcion:**  
Bottom sheet para elegir entre camara y galeria en modo claro.

![Selector de origen modo claro](capturas_vistas_app/claro_08_selector_origen_imagen.png)

---

### Claro 09. Galeria del sistema

**Archivo:** `capturas_vistas_app/claro_09_galeria_picker.png`

**Descripcion:**  
Photo Picker del sistema Android abierto desde el flujo en modo claro.

![Galeria modo claro](capturas_vistas_app/claro_09_galeria_picker.png)

---

### Claro 10. Imagen seleccionada en galeria

**Archivo:** `capturas_vistas_app/claro_10_galeria_imagen_seleccionada.png`

**Descripcion:**  
Estado del Photo Picker con una imagen seleccionada antes de confirmar.

![Imagen seleccionada modo claro](capturas_vistas_app/claro_10_galeria_imagen_seleccionada.png)

---

### Claro 11. Captura de reverso

**Archivo:** `capturas_vistas_app/claro_11_frente_cargado.png`

**Descripcion:**  
Estado posterior a cargar Frente, solicitando la imagen de Reverso.

![Captura de reverso modo claro](capturas_vistas_app/claro_11_frente_cargado.png)

---

### Claro 12. Resultado y resumen

**Archivo:** `capturas_vistas_app/claro_12_resultado_resumen.png`

**Descripcion:**  
Resultado calculado en modo claro con cantidad, metricas e histograma compacto.

![Resultado modo claro](capturas_vistas_app/claro_12_resultado_resumen.png)

---

### Claro 13. Detalle del racimo

**Archivo:** `capturas_vistas_app/claro_13_resultado_detalle_racimo.png`

**Descripcion:**  
Detalle de racimo en modo claro con resumen, metricas consolidadas e histograma.

![Detalle de racimo modo claro](capturas_vistas_app/claro_13_resultado_detalle_racimo.png)

---

### Claro 14. Imagenes del racimo

**Archivo:** `capturas_vistas_app/claro_14_racimo_imagenes.png`

**Descripcion:**  
Pestana de imagenes con Frente y Reverso validos en modo claro.

![Imagenes del racimo modo claro](capturas_vistas_app/claro_14_racimo_imagenes.png)

---

### Claro 15. Confirmar guardado de lote

**Archivo:** `capturas_vistas_app/claro_15_confirmar_guardar_lote.png`

**Descripcion:**  
Dialogo de confirmacion para guardar el lote con las fotos actuales.

![Confirmacion guardado modo claro](capturas_vistas_app/claro_15_confirmar_guardar_lote.png)

---

### Claro 16. Lote guardado

**Archivo:** `capturas_vistas_app/claro_16_lote_guardado_home.png`

**Descripcion:**  
Retorno al formulario inicial con confirmacion de lote guardado localmente.

![Lote guardado modo claro](capturas_vistas_app/claro_16_lote_guardado_home.png)

---

### Claro 17. Historial

**Archivo:** `capturas_vistas_app/claro_17_historial.png`

**Descripcion:**  
Listado de lotes en modo claro con estado de sincronizacion y acceso a detalle.

![Historial modo claro](capturas_vistas_app/claro_17_historial.png)

---

### Claro 18. Filtro de historial

**Archivo:** `capturas_vistas_app/claro_18_historial_filtro.png`

**Descripcion:**  
Filtro desplegable del historial visible en modo claro.

![Filtro historial modo claro](capturas_vistas_app/claro_18_historial_filtro.png)

---

### Claro 19. Detalle de lote

**Archivo:** `capturas_vistas_app/claro_19_detalle_lote.png`

**Descripcion:**  
Detalle de lote en modo claro con metadata, prediccion, histograma y acciones visibles.

![Detalle de lote modo claro](capturas_vistas_app/claro_19_detalle_lote.png)

---

### Claro 20. Imagen en pantalla completa

**Archivo:** `capturas_vistas_app/claro_20_imagen_pantalla_completa.png`

**Descripcion:**  
Vista ampliada de la imagen/prediccion abierta desde el detalle del lote.

![Imagen completa modo claro](capturas_vistas_app/claro_20_imagen_pantalla_completa.png)

---

### Claro 21. Exportar PDF

**Archivo:** `capturas_vistas_app/claro_21_exportar_pdf_compartir.png`

**Descripcion:**  
Hoja del sistema Android para compartir el PDF generado desde el detalle.

![Exportar PDF modo claro](capturas_vistas_app/claro_21_exportar_pdf_compartir.png)

---

### Claro 22. Perfil y configuracion

**Archivo:** `capturas_vistas_app/claro_01_perfil_configuracion.png`

**Descripcion:**  
Perfil del administrador en modo claro con estado, descarga de lotes, cierre de sesion y controles visibles.

![Perfil modo claro](capturas_vistas_app/claro_01_perfil_configuracion.png)

---

### Claro 23. Soporte

**Archivo:** `capturas_vistas_app/claro_02_soporte.png`

**Descripcion:**  
Centro de soporte en modo claro con informacion de contacto y acceso a FAQ.

![Soporte modo claro](capturas_vistas_app/claro_02_soporte.png)

---

### Claro 24. FAQ de soporte

**Archivo:** `capturas_vistas_app/claro_03_soporte_faq.png`

**Descripcion:**  
Pregunta frecuente expandida dentro del centro de soporte en modo claro.

![FAQ modo claro](capturas_vistas_app/claro_03_soporte_faq.png)

---