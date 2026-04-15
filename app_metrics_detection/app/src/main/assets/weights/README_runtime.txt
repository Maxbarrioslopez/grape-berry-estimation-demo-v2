Bundle ONNX generado.

Carpeta: /content/drive/MyDrive/Paper-uvas/v4/two_models_bimodal_hist_qty_residual_w1/onnx_bundle
- hist_model.onnx
- qty_model.onnx
- unified_runtime.onnx
- manifest.json

Uso:
1) Instancia UnifiedGrapeOnnxRuntime(bundle_dir=<onnx_bundle>, segmentation_onnx_path=<seg.onnx>)
2) Llama runtime.predict_image(image_path=..., variety_name=...)
