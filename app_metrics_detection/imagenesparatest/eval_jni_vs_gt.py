from __future__ import annotations
import json
import numpy as np
import pandas as pd
from pathlib import Path
from typing import Dict, Any, Optional

# ============================================================
# CONFIGURACIÓN TÉCNICA (ENTORNO MAXI)
# ============================================================
ROOT_TEST_DIR = Path(r"C:\Users\Maxi Barrios\Desktop\resultados_test_uvas")
GT_CSV_PATH = Path(r"C:\Users\Maxi Barrios\Desktop\modelo_nuevo\optimizacion_uvas\app_metrics_detection\imagenesparatest\manifest_subsample.csv")
BINS = np.arange(7, 33)

def wasserstein_1d_numpy_from_prob(pred_prob: np.ndarray, gt_prob: np.ndarray, bin_centers: np.ndarray) -> float:
    """EMD Forense - Implementación solicitada por Gaia Robotics."""
    p, g = pred_prob.reshape(1, -1), gt_prob.reshape(1, -1)
    p = p / np.clip(p.sum(axis=1, keepdims=True), 1e-8, None)
    g = g / np.clip(g.sum(axis=1, keepdims=True), 1e-8, None)
    pred_cdf, gt_cdf = np.cumsum(p, axis=1), np.cumsum(g, axis=1)
    deltas = np.diff(bin_centers)
    deltas = np.concatenate([deltas, deltas[-1:]], axis=0) if len(deltas) > 0 else np.array([1.0])
    w1 = np.sum(np.abs(pred_cdf - gt_cdf) * deltas[None, :], axis=1)
    return float(np.mean(w1))

def run_evaluation():
    all_runs = sorted([d for d in ROOT_TEST_DIR.iterdir() if d.is_dir() and d.name.startswith("run_")], reverse=True)
    if not all_runs: return print("[ERROR] No se encontraron carpetas de run.")
    run_actual = all_runs[0]

    md_output = []
    def log(msg: str, console: bool = True):
        if console: print(msg)
        md_output.append(msg)

    log(f"\n{'='*155}")
    log(f"{'AUDITORÍA FORENSE GAIA ROBOTICS v5.8':^155}")
    log(f"{'='*155}")
    log(f"RUN ID: {run_actual.name}")

    # 1. CARGAR GROUND TRUTH
    df_gt = pd.read_csv(GT_CSV_PATH)
    for k in BINS:
        c1, c2 = f"Cal {k}", f"Cal {k}.5"
        v1 = pd.to_numeric(df_gt[c1], errors="coerce").fillna(0.0) if c1 in df_gt.columns else pd.Series(0.0, index=df_gt.index)
        v2 = pd.to_numeric(df_gt[c2], errors="coerce").fillna(0.0) if c2 in df_gt.columns else pd.Series(0.0, index=df_gt.index)
        df_gt[f"gt_bin_{k}"] = v1 + v2
    df_gt["match_key"] = df_gt["subsample_output_relpath"].str.replace("\\", "/").str.lower().str.replace("images/", "")
    gt_index = df_gt.set_index("match_key").to_dict("index")

    # 2. PROCESAR RESULTADOS
    with open(run_actual / "manifest.json") as f: manifest = json.load(f)
    records = []
    for entry in manifest.get("files", []):
        if entry.get("status") != "ok": continue
        rel_path = entry["relative_path"].lower()
        gt_data = gt_index.get(rel_path) or next((v for k, v in gt_index.items() if Path(rel_path).name in k), None)
        if not gt_data: continue

        pred_json = json.load(open(run_actual / entry["json_path"]))
        h = pred_json.get("histogram", {})
        pred_hist = np.array(pred_json.get("hist_prob") or h.get("counts") or h.get("final") or [0]*len(BINS))
        gt_hist = np.array([float(gt_data.get(f"gt_bin_{k}", 0.0)) for k in BINS])
        pred_total = float(np.sum(pred_hist))
        gt_total = float(gt_data["grape_count_total"])

        records.append({
            "file": Path(rel_path).name,
            "full_path": rel_path,
            "variety": gt_data.get("variety", "UNK"),
            "gt_total": gt_total,
            "pred_total": pred_total,
            "seg_base": float(pred_json.get("seg_count_base", 0)),
            "det_count": len(pred_json.get("detections", [])),
            "w1_dist": wasserstein_1d_numpy_from_prob(pred_hist, gt_hist, BINS),
            "inf_ms": pred_json.get("inference_ms", 0),
            "fidelidad": (1 - (min(1, abs(pred_total - gt_total) / gt_total))) * 100 if gt_total > 0 else 0,
            "img_raw": str(entry["json_path"]).replace(".json", "_raw.png"),
            "img_pro": str(entry["json_path"]).replace(".json", "_pro.jpg")
        })

    df = pd.DataFrame(records)
    df["qty_err"] = df["pred_total"] - df["gt_total"]

    # 3. REPORTES
    log("\n### 1. MÉTRICAS GLOBALES")
    log(f"{'MAE Conteo':<30} | {df['qty_err'].abs().mean():.4f} | Fidelidad Media: {df['fidelidad'].mean():.2f}%")
    log(f"{'W1 Distance (EMD)':<30} | {df['w1_dist'].mean():.4f} | Target: <0.5mm")

    log("\n### 2. ANALISIS TÉCNICO DE SEGMENTACIÓN (DETALLE)")
    log(f"{'ARCHIVO':<50} | {'GT':<6} | {'DET_COUNT':<10} | {'SEG_BASE':<10} | {'MASCARA BINARIA'}")
    log("-" * 155)
    for _, r in df.head(15).iterrows():
        log(f"{r['file'][:48]:<50} | {r['gt_total']:<6.1f} | {r['det_count']:<10} | {r['seg_base']:<10.1f} | {r['img_raw']}")

    log("\n### 5. FALLOS CRÍTICOS (PEDIDO MATÍAS SOTO: ERR < 0 O SEG_BASE < GT)")
    crit = df[(df["qty_err"] < 0) | (df["seg_base"] < df["gt_total"])]
    if not crit.empty:
        log(f"{'ARCHIVO':<50} | {'GT':<6} | {'ERR':<6} | {'ANALIZAR MÁSCARA BINARIA'}")
        log("-" * 155)
        for _, r in crit.iterrows():
            log(f"{r['file'][:48]:<50} | {r['gt_total']:<6.1f} | {r['qty_err']:<6.1f} | {r['img_raw']}")
    else:
        log(">>> NO SE DETECTARON FALLOS CRÍTICOS <<<")

    log("\n### 6. RESUMEN FINAL DE CUMPLIMIENTO POR CRITERIO")
    log(f"{'CRITERIO':<45} | {'% ÉXITO'}")
    log("-" * 60)
    log(f"{'Fidelidad General >= 90%':<45} | {(df['fidelidad'] >= 90).mean()*100:6.2f} %")
    log(f"{'Detección Base Ok (SegBase >= GT)':<45} | {(df['seg_base'] >= df['gt_total']).mean()*100:6.2f} %")
    log(f"{'Sin Sub-conteo (Pred >= GT)':<45} | {(df['qty_err'] >= 0).mean()*100:6.2f} %")

    # GUARDAR
    df.to_csv(run_actual / "audit_detallada_maxi.csv", index=False, encoding="utf-8-sig")
    md_path = run_actual / "reporte_forense_maxi.md"
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(f"# REPORTE FORENSE GAIA ROBOTICS - RUN {run_actual.name}\n\n```text\n" + "\n".join(md_output) + "\n```")
    print(f"\n[OK] Reporte completo guardado en: {run_actual}")

if __name__ == "__main__":
    run_evaluation()
