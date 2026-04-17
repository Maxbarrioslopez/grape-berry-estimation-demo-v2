# -*- coding: utf-8 -*-
"""
eval_jni_vs_gt.py - v6.9 MAESTRA (Forense Avanzado Detallado)
Auditoría Forense con Análisis de W1 (EMD) por Imagen, Oclusión y Latencia Tail.
"""
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
    """Implementación oficial Gaia Robotics para Wasserstein Distance (EMD)."""
    if len(pred_prob) == 0 or len(gt_prob) == 0 or np.sum(pred_prob) == 0 or np.sum(gt_prob) == 0:
        return 99.0
    p = pred_prob.reshape(1, -1)
    g = gt_prob.reshape(1, -1)
    p = p / np.clip(p.sum(axis=1, keepdims=True), 1e-8, None)
    g = g / np.clip(g.sum(axis=1, keepdims=True), 1e-8, None)
    pred_cdf = np.cumsum(p, axis=1)
    gt_cdf = np.cumsum(g, axis=1)
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
    log(f"{'SISTEMA DE AUDITORÍA FORENSE MAESTRA GAIA ROBOTICS v6.9':^155}")
    log(f"{'='*155}")
    log(f"RUN EVALUADO: {run_actual.name}")

    # 1. CARGAR GROUND TRUTH
    df_gt = pd.read_csv(GT_CSV_PATH)
    for k in BINS:
        c1, c2 = f"Cal {k}", f"Cal {k}.5"
        v1 = pd.to_numeric(df_gt[c1], errors="coerce").fillna(0.0) if c1 in df_gt.columns else 0.0
        v2 = pd.to_numeric(df_gt[c2], errors="coerce").fillna(0.0) if c2 in df_gt.columns else 0.0
        df_gt[f"gt_bin_{k}"] = v1 + v2
    df_gt["match_key"] = df_gt["subsample_output_relpath"].str.replace("\\", "/").str.lower().str.split("/").str[-1]
    gt_index = df_gt.set_index("match_key").to_dict("index")

    # 2. PROCESAR RESULTADOS JNI
    with open(run_actual / "manifest.json") as f: manifest = json.load(f)
    records = []
    for entry in manifest.get("files", []):
        if entry.get("status") != "ok": continue
        fname = Path(entry["relative_path"]).name.lower()
        gt_data = gt_index.get(fname)
        if not gt_data: continue

        with open(run_actual / entry["json_path"]) as jf: pj = json.load(jf)
        h_obj = pj.get("histogram", {})
        pred_hist = np.array(h_obj.get("pred") or [0]*len(BINS))
        prob_hist = np.array(h_obj.get("hist_prob") or [0]*len(BINS))
        gt_hist = np.array([float(gt_data.get(f"gt_bin_{k}", 0.0)) for k in BINS])

        p_total = float(np.sum(pred_hist))
        if p_total <= 0: p_total = float(pj.get("result", {}).get("qty_total") or pj.get("count_total", 0.0))
        g_total = float(gt_data["grape_count_total"])
        q_err = p_total - g_total
        s_base = float(pj.get("seg_count_base", 0.0))

        ocl_idx = (p_total - s_base) / p_total if p_total > 0 else 0

        records.append({
            "file": fname, "variety": gt_data.get("variety", "UNK"),
            "gt_total": g_total, "pred_total": p_total,
            "qty_err": q_err, "qty_abs_err": abs(q_err),
            "w1_dist": wasserstein_1d_numpy_from_prob(prob_hist, gt_hist, BINS),
            "fidelidad": (1 - (min(1.0, abs(q_err) / g_total))) * 100 if g_total > 0 else 100.0,
            "seg_base": s_base, "ocl_idx": ocl_idx,
            "inf_ms": float(pj.get("inf_ms") or pj.get("inference_ms", 0.0)),
            "mm_px": float(pj.get("mm_per_px") or 0.0),
            "iso": pj.get("exif", {}).get("iso", "N/A"),
            "throt": pj.get("throttling_warning", False)
        })

    df = pd.DataFrame(records)
    if df.empty: return log("[ERROR] Sin paridad entre archivos.")

    # 3. REPORTES POR VARIEDAD (INCLUYE W1 DETALLADO)
    log("\n### 1. COMPARATIVA DE PRECISIÓN Y CALIBRE (W1) POR VARIEDAD")
    log(f"{'VARIEDAD':<15} | {'FOTOS':<5} | {'MAE':<6} | {'FIDEL':<8} | {'OCL %':<6} | {'W1 MEDIA'}")
    log("-" * 155)
    df_var = df.groupby("variety").agg({
        "file":"count", "qty_abs_err":"mean", "fidelidad":"mean", "ocl_idx":"mean", "w1_dist":"mean"
    }).sort_values("qty_abs_err", ascending=False)
    for var, row in df_var.iterrows():
        log(f"{var[:15]:<15} | {int(row['file']):<5} | {row['qty_abs_err']:<6.2f} | {row['fidelidad']:>7.2f}% | {row['ocl_idx']*100:>5.1f}% | {row['w1_dist']:<10.4f}")

    # 4. EXTREMOS POR VARIEDAD (TOP 2 vs BOTTOM 2 con W1 INDIVIDUAL)
    log("\n### 2. ANÁLISIS DE CASOS EXTREMOS (MEJORES Y PEORES POR VARIEDAD)")
    for var in df["variety"].unique():
        dv = df[df["variety"] == var]
        log(f"\n>> VARIEDAD: {var}")
        log(f"{'TIPO':<8} | {'ARCHIVO':<40} | {'GT':<6} | {'PRED':<6} | {'W1 (EMD)':<8} | {'FIDEL'}")
        log("-" * 90)
        m, p = dv.sort_values("fidelidad", ascending=False).head(2), dv.sort_values("fidelidad", ascending=True).head(2)
        for _, r in m.iterrows(): log(f"{'EXITO':<8} | {r['file'][:38]:<40} | {r['gt_total']:<6.1f} | {r['pred_total']:<6.1f} | {r['w1_dist']:<8.4f} | {r['fidelidad']:>6.1f}%")
        for _, r in p.iterrows(): log(f"{'FALLO':<8} | {r['file'][:38]:<40} | {r['gt_total']:<6.1f} | {r['pred_total']:<6.1f} | {r['w1_dist']:<8.4f} | {r['fidelidad']:>6.1f}%")

    log("\n### 3. ANÁLISIS DE ROBUSTEZ Y LATENCIA TAIL")
    p95_inf = np.percentile(df["inf_ms"], 95)
    log(f" > Latencia P95 (Peor caso): {p95_inf:6.1f} ms")
    log(f" > Estabilidad Calibración (STD mm/px): {df['mm_px'].std():.6f}")
    log(f" > Alertas de Throttling detectadas: {df['throt'].sum()} fotos")

    log("\n### 4. RESUMEN FINAL DE CUMPLIMIENTO (TARGET KPIs)")
    log(f" > Fidelidad General >= 90%:  { (df['fidelidad'] >= 90).mean()*100:6.2f} %")
    log(f" > Error Absoluto <= 5 uvas:  { (df['qty_abs_err'] <= 5).mean()*100:6.2f} %")
    log(f" > Calibre (W1 Dist) < 0.5mm: { (df['w1_dist'] <= 0.5).mean()*100:6.2f} %")
    log(f" > Segmentación Base Ok (>80%): { (df['seg_base'] >= df['gt_total']*0.8).mean()*100:6.2f} %")

    # GUARDAR
    df.to_csv(run_actual / "audit_detallada_maxi.csv", index=False, encoding="utf-8-sig")
    md_path = run_actual / "reporte_forense_maxi.md"
    with open(md_path, "w", encoding="utf-8") as f: f.write(f"# REPORTE GAIA ROBOTICS - {run_actual.name}\n\n```text\n" + "\n".join(md_output) + "\n```")
    print(f"\n[OK] Auditoría v6.9 finalizada. W1 detallado por imagen incluido en: {run_actual}")

if __name__ == "__main__":
    run_evaluation()
