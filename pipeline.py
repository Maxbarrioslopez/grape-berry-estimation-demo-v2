#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import os
import re
import unicodedata
from pathlib import Path
from typing import Dict, Any, List, Tuple

import cv2
import numpy as np
import onnxruntime as ort
import matplotlib.pyplot as plt
import pandas as pd

# ============================================================
# CONFIG
# ============================================================
from pathlib import Path
import os

# Raíz del proyecto = carpeta donde está este script
PROJECT_ROOT = Path(__file__).resolve().parent

# Carpeta de data
WEIGHTS_DIR = PROJECT_ROOT / "weights"
DATA_DIR = PROJECT_ROOT / "data"

# Permite override por variables de entorno, si quieres
SEG_ONNX_PATH = Path(os.getenv("SEG_ONNX_PATH", WEIGHTS_DIR / "seg_best.onnx")).resolve()
REG_ONNX_PATH = Path(os.getenv("REG_ONNX_PATH", WEIGHTS_DIR / "best_model_5ch_residual.onnx")).resolve()

# Validacion opcional contra GT CSV (no participa del flujo de inferencia).
USE_GT_VALIDATION = os.getenv("USE_GT_VALIDATION", "0") == "1"
CSV_PATH = Path(os.getenv("CSV_PATH", DATA_DIR / "labels_v4.csv")).resolve()
IMAGE_ROOT = Path(os.getenv("IMAGE_ROOT", DATA_DIR / "images")).resolve()
IMAGE_PATH = Path(os.getenv("IMAGE_PATH", IMAGE_ROOT / "EPG-18-MAGENTA_IMAGEN_35.jpg")).resolve()

IMGSZ = 512
CONF_THRES = 0.25
MASK_THRES = 0.25

CAL_MIN = 7
CAL_MAX = 32
BINS = list(range(CAL_MIN, CAL_MAX + 1))

CLASS_NAMES = {
    0: "bunch_black",
    1: "bunch_green",
    2: "bunch_red",
    3: "grape_black",
    4: "grape_green",
    5: "grape_red",
    6: "pingpong",
}

# Mantener este orden alineado con los IDs de variedad que recibe el modelo.
VARIETY_CLASSES = [
    "ALLISON",
    "AUTUMN CRISP",
    "CRIMSON",
    "IVORY",
    "MAGENTA",
    "RED GLOBE",
    "SCARLOTTA",
    "SUPERIOR",
    "SWEET GLOBE",
    "THOMPSON",
    "TIMCO",
    "TIMPSON",
]
VARIETY_TO_IDX = {v: i for i, v in enumerate(VARIETY_CLASSES)}
VARIETY_OVERRIDE = None   # por ej. "MAGENTA"

# ===========================================================
# UTILS
# ===========================================================

def clean_cal_columns(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    cal_cols = [c for c in df.columns if str(c).startswith("Cal ")]
    for c in cal_cols:
        df[c] = pd.to_numeric(df[c], errors="coerce").fillna(0.0)
    return df


def build_integer_histogram_from_cal_columns(
    df: pd.DataFrame,
    cal_min: int = 7,
    cal_max: int = 32,
) -> pd.DataFrame:
    df = df.copy()

    for k in range(cal_min, cal_max + 1):
        col_int = f"Cal {k}"
        col_half = f"Cal {k}.5"

        if col_int in df.columns:
            v_int = pd.to_numeric(df[col_int], errors="coerce").fillna(0.0)
        else:
            v_int = pd.Series(0.0, index=df.index, dtype=np.float32)

        if col_half in df.columns:
            v_half = pd.to_numeric(df[col_half], errors="coerce").fillna(0.0)
        else:
            v_half = pd.Series(0.0, index=df.index, dtype=np.float32)

        df[f"count_{k}"] = v_int.astype(np.float32) + v_half.astype(np.float32)

    return df


def add_total_count(df: pd.DataFrame, count_cols: list) -> pd.DataFrame:
    df = df.copy()
    df["grape_count_total"] = df[count_cols].sum(axis=1).astype(np.float32)
    return df


def add_normalized_hist_from_counts(df: pd.DataFrame, count_cols: list, hist_cols: list) -> pd.DataFrame:
    df = df.copy()
    C = df[count_cols].values.astype(np.float32)
    row_sum = C.sum(axis=1, keepdims=True)
    row_sum[row_sum <= 0] = 1.0
    H = C / row_sum
    df[hist_cols] = H
    return df


def add_mean_mm_from_hist(df: pd.DataFrame, hist_cols: list, cal_min: int = 7, cal_max: int = 32) -> pd.DataFrame:
    df = df.copy()
    bin_centers = np.arange(cal_min, cal_max + 1, dtype=np.float32)
    H = df[hist_cols].values.astype(np.float32)
    df["mean_mm"] = (H * bin_centers[None, :]).sum(axis=1).astype(np.float32)
    return df


def load_gt_dataframe(csv_path: str, image_root: str):
    count_cols = [f"count_{k}" for k in BINS]
    hist_cols = [f"hist_{k}" for k in BINS]

    df = pd.read_csv(csv_path, low_memory=False)
    df = clean_cal_columns(df)

    df["image_path"] = df["path"].astype(str).apply(lambda p: os.path.join(image_root, Path(p).name))
    df["exists"] = df["image_path"].apply(os.path.exists)
    df = df[df["exists"]].reset_index(drop=True)

    df = build_integer_histogram_from_cal_columns(df, CAL_MIN, CAL_MAX)
    df = add_total_count(df, count_cols)
    df = add_normalized_hist_from_counts(df, count_cols, hist_cols)
    df = add_mean_mm_from_hist(df, hist_cols, CAL_MIN, CAL_MAX)

    return df


def find_gt_row_by_image_path(df_gt: pd.DataFrame, image_path: str) -> pd.Series:
    sub = df_gt[df_gt["image_path"].astype(str) == str(image_path)].copy()
    if len(sub) == 0:
        raise RuntimeError(f"No encontré GT para image_path={image_path}")
    return sub.iloc[0]


# ============================================================
# HELPERS
# ============================================================
def normalize_variety(x: str) -> str:
    if x is None:
        return None
    x = str(x).strip()
    x = unicodedata.normalize("NFKD", x).encode("ascii", "ignore").decode("utf-8")
    x = x.upper()
    x = re.sub(r"\s+", " ", x)
    alias_map = {
        "AUTUM CRISP": "AUTUMN CRISP",
        "AUTUMN CRISP": "AUTUMN CRISP",
        "RED GLOVE": "RED GLOBE",
        "TINCO": "TIMCO",
    }
    return alias_map.get(x, x)


def infer_variety_from_filename(image_path: str, variety_classes: List[str]) -> str:
    name = Path(image_path).name.upper()
    for v in variety_classes:
        if v in name:
            return v
    raise RuntimeError(
        f"No pude inferir variety desde el nombre del archivo: {name}. "
        f"Usa VARIETY_OVERRIDE."
    )


def load_onnx_session(onnx_path: str | Path):
    onnx_path = str(onnx_path)

    if not os.path.exists(onnx_path):
        raise FileNotFoundError(f"No existe el modelo ONNX: {onnx_path}")

    providers = ort.get_available_providers()
    if "CUDAExecutionProvider" in providers:
        sess = ort.InferenceSession(
            onnx_path,
            providers=["CUDAExecutionProvider", "CPUExecutionProvider"]
        )
    else:
        sess = ort.InferenceSession(
            onnx_path,
            providers=["CPUExecutionProvider"]
        )
    return sess


def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


def letterbox(image, new_shape=(512, 512), color=(114, 114, 114)):
    shape = image.shape[:2]

    if isinstance(new_shape, int):
        new_shape = (new_shape, new_shape)

    r = min(new_shape[0] / shape[0], new_shape[1] / shape[1])
    new_unpad = (int(round(shape[1] * r)), int(round(shape[0] * r)))

    dw = new_shape[1] - new_unpad[0]
    dh = new_shape[0] - new_unpad[1]
    dw /= 2
    dh /= 2

    if shape[::-1] != new_unpad:
        image = cv2.resize(image, new_unpad, interpolation=cv2.INTER_LINEAR)

    top = int(round(dh - 0.1))
    bottom = int(round(dh + 0.1))
    left = int(round(dw - 0.1))
    right = int(round(dw + 0.1))

    image = cv2.copyMakeBorder(
        image, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color
    )
    return image, r, (dw, dh)


def preprocess_yolo(image_bgr, imgsz=512):
    img_lb, ratio, dwdh = letterbox(image_bgr, new_shape=(imgsz, imgsz))
    img_rgb = cv2.cvtColor(img_lb, cv2.COLOR_BGR2RGB)
    x = img_rgb.astype(np.float32) / 255.0
    x = np.transpose(x, (2, 0, 1))
    x = np.expand_dims(x, 0)
    x = np.ascontiguousarray(x, dtype=np.float32)
    return x, img_lb, ratio, dwdh


def scale_boxes_back_xyxy(boxes, ratio, dwdh, orig_shape):
    boxes = boxes.copy().astype(np.float32)
    dw, dh = dwdh

    boxes[:, [0, 2]] -= dw
    boxes[:, [1, 3]] -= dh
    boxes[:, :4] /= ratio

    h, w = orig_shape[:2]
    boxes[:, [0, 2]] = np.clip(boxes[:, [0, 2]], 0, w - 1)
    boxes[:, [1, 3]] = np.clip(boxes[:, [1, 3]], 0, h - 1)
    return boxes


def crop_mask(mask, box):
    x1, y1, x2, y2 = box.astype(int)
    out = np.zeros_like(mask, dtype=np.uint8)
    out[y1:y2, x1:x2] = mask[y1:y2, x1:x2]
    return out


# ============================================================
# SEGMENTATION ONNX
# ============================================================
def decode_segmentation_postnms(outputs, conf_thres=0.25):
    det = outputs[0][0]
    protos = outputs[1][0]

    boxes = det[:, 0:4].astype(np.float32)
    scores = det[:, 4].astype(np.float32)
    cls_ids = det[:, 5].astype(np.int32)
    mask_coeffs = det[:, 6:].astype(np.float32)

    keep = scores >= conf_thres
    boxes = boxes[keep]
    scores = scores[keep]
    cls_ids = cls_ids[keep]
    mask_coeffs = mask_coeffs[keep]

    return boxes, scores, cls_ids, mask_coeffs, protos


def reconstruct_masks(mask_coeffs, protos, boxes_lb, img_size=512, mask_thres=0.5):
    if len(mask_coeffs) == 0:
        return np.zeros((0, img_size, img_size), dtype=np.uint8)

    c, mh, mw = protos.shape
    masks = mask_coeffs @ protos.reshape(c, -1)
    masks = sigmoid(masks).reshape(-1, mh, mw)

    out_masks = []
    for i in range(len(masks)):
        m = masks[i]
        m_up = cv2.resize(m, (img_size, img_size), interpolation=cv2.INTER_LINEAR)
        m_bin = (m_up > mask_thres).astype(np.uint8)
        m_bin = crop_mask(m_bin, boxes_lb[i])
        out_masks.append(m_bin)

    return np.stack(out_masks, axis=0).astype(np.uint8)


def scale_mask_back_to_original(mask_lb, ratio, dwdh, orig_shape):
    dw, dh = dwdh
    h0, w0 = orig_shape[:2]

    x1 = int(round(dw))
    y1 = int(round(dh))
    x2 = int(mask_lb.shape[1] - round(dw))
    y2 = int(mask_lb.shape[0] - round(dh))

    cropped = mask_lb[y1:y2, x1:x2]
    mask_orig = cv2.resize(cropped.astype(np.uint8), (w0, h0), interpolation=cv2.INTER_NEAREST)
    return mask_orig


def run_segmentation_onnx(session, image_path, imgsz=512, conf_thres=0.25, mask_thres=0.5):
    image_bgr = cv2.imread(image_path, cv2.IMREAD_COLOR)
    if image_bgr is None:
        raise FileNotFoundError(f"No se pudo leer imagen: {image_path}")

    orig_rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)

    x, _, ratio, dwdh = preprocess_yolo(image_bgr, imgsz=imgsz)
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: x})

    boxes_lb, scores, cls_ids, mask_coeffs, protos = decode_segmentation_postnms(
        outputs, conf_thres=conf_thres
    )

    masks_lb = reconstruct_masks(
        mask_coeffs=mask_coeffs,
        protos=protos,
        boxes_lb=boxes_lb,
        img_size=imgsz,
        mask_thres=mask_thres
    )

    boxes_orig = scale_boxes_back_xyxy(boxes_lb, ratio, dwdh, image_bgr.shape)

    masks_orig = np.stack(
        [scale_mask_back_to_original(m, ratio, dwdh, image_bgr.shape) for m in masks_lb],
        axis=0
    ) if len(masks_lb) > 0 else np.zeros((0, image_bgr.shape[0], image_bgr.shape[1]), dtype=np.uint8)

    return {
        "orig_bgr": image_bgr,
        "orig_rgb": orig_rgb,
        "boxes": boxes_orig,
        "scores": scores,
        "cls_ids": cls_ids,
        "masks": masks_orig,   # 0/1
        "num_det": len(boxes_orig),
    }


def build_global_masks_from_seg_output(seg_out: Dict[str, Any], class_names: Dict[int, str]):
    h, w = seg_out["orig_bgr"].shape[:2]

    bunch = np.zeros((h, w), dtype=np.uint8)
    grapes = np.zeros((h, w), dtype=np.uint8)
    pingpong = np.zeros((h, w), dtype=np.uint8)

    num_bunch = 0
    num_grapes = 0
    num_pingpong = 0

    for cls_id, mask in zip(seg_out["cls_ids"], seg_out["masks"]):
        cls_name = class_names.get(int(cls_id), str(cls_id)).lower()
        mask_u8 = ((mask > 0).astype(np.uint8) * 255)

        if "bunch" in cls_name:
            bunch = np.maximum(bunch, mask_u8)
            num_bunch += 1
        elif "grape" in cls_name:
            grapes = np.maximum(grapes, mask_u8)
            num_grapes += 1
        elif "ping" in cls_name:
            pingpong = np.maximum(pingpong, mask_u8)
            num_pingpong += 1

    return {
        "bunch": bunch,
        "grapes": grapes,
        "pingpong": pingpong,
        "num_bunch_det": int(num_bunch),
        "num_grape_det": int(num_grapes),
        "num_pingpong_det": int(num_pingpong),
    }


# ============================================================
# REGRESSOR INPUT BUILD
# ============================================================
def letterbox_image_and_masks(image_bgr: np.ndarray, masks_dict: Dict[str, np.ndarray], out_size: int = 512):
    h0, w0 = image_bgr.shape[:2]
    image_lb_bgr, ratio, (dw, dh) = letterbox(image_bgr, new_shape=(out_size, out_size), color=(114, 114, 114))
    rgb_lb = cv2.cvtColor(image_lb_bgr, cv2.COLOR_BGR2RGB)

    masks_lb = {}
    new_unpad_w = int(round(w0 * ratio))
    new_unpad_h = int(round(h0 * ratio))

    top = int(round(dh - 0.1))
    bottom = int(round(dh + 0.1))
    left = int(round(dw - 0.1))
    right = int(round(dw + 0.1))

    for name, mask in masks_dict.items():
        m = (np.asarray(mask) > 0).astype(np.uint8) * 255
        m_rs = cv2.resize(m, (new_unpad_w, new_unpad_h), interpolation=cv2.INTER_NEAREST)
        m_lb = cv2.copyMakeBorder(m_rs, top, bottom, left, right, cv2.BORDER_CONSTANT, value=0)
        masks_lb[name] = m_lb.astype(np.uint8)

    meta = {
        "orig_h": int(h0),
        "orig_w": int(w0),
        "ratio": float(ratio),
        "dw": float(dw),
        "dh": float(dh),
        "out_size": int(out_size),
    }
    return rgb_lb, masks_lb, meta


def compute_grapes_distance_transform(grapes_mask_u8: np.ndarray) -> np.ndarray:
    mask_bin = (grapes_mask_u8 > 0).astype(np.uint8)
    if mask_bin.sum() == 0:
        return np.zeros_like(grapes_mask_u8, dtype=np.uint8)

    dt = cv2.distanceTransform(mask_bin, distanceType=cv2.DIST_L2, maskSize=5)
    dt_max = float(dt.max())
    if dt_max > 0:
        dt = dt / dt_max
    return np.clip(dt * 255.0, 0, 255).astype(np.uint8)


def build_regressor_inputs_from_photo(seg_session, image_path: str, variety: str):
    seg_out = run_segmentation_onnx(
        seg_session,
        image_path=image_path,
        imgsz=IMGSZ,
        conf_thres=CONF_THRES,
        mask_thres=MASK_THRES,
    )

    global_masks = build_global_masks_from_seg_output(seg_out, CLASS_NAMES)

    rgb_lb, masks_lb, meta = letterbox_image_and_masks(
        seg_out["orig_bgr"],
        masks_dict={
            "bunch": global_masks["bunch"],
            "grapes": global_masks["grapes"],
            "pingpong": global_masks["pingpong"],
        },
        out_size=IMGSZ,
    )

    grapes_dt = compute_grapes_distance_transform(masks_lb["grapes"])

    # 5 canales: RGB + grapes + grapes_dt
    rgb = rgb_lb.astype(np.float32) / 255.0
    grapes = (masks_lb["grapes"].astype(np.float32) / 255.0)[..., None]
    grapes_dt_f = (grapes_dt.astype(np.float32) / 255.0)[..., None]

    x = np.concatenate([rgb, grapes, grapes_dt_f], axis=2)   # H,W,5
    x = np.transpose(x, (2, 0, 1))[None, ...].astype(np.float32)  # 1,5,H,W

    variety_norm = normalize_variety(variety)
    if variety_norm not in VARIETY_TO_IDX:
        raise RuntimeError(f"Variety no encontrada en vocab: {variety_norm}")

    variety_idx = np.array([VARIETY_TO_IDX[variety_norm]], dtype=np.int64)
    visible_count = np.array([float(global_masks["num_grape_det"])], dtype=np.float32)

    return {
        "x": x,
        "variety_idx": variety_idx,
        "visible_count": visible_count,
        "rgb_lb": rgb_lb,
        "grapes_lb": masks_lb["grapes"],
        "grapes_dt": grapes_dt,
        "seg_out": seg_out,
        "global_masks": global_masks,
        "meta": meta,
        "variety": variety_norm,
    }


# ============================================================
# REGRESSOR ONNX INFERENCE
# ============================================================
def run_regressor_onnx(reg_session, x: np.ndarray, variety_idx: np.ndarray, visible_count: np.ndarray):
    inputs = {
        "x": x.astype(np.float32),
        "variety_idx": variety_idx.astype(np.int64),
        "visible_count": visible_count.astype(np.float32),
    }

    outs = reg_session.run(None, inputs)
    out_names = [o.name for o in reg_session.get_outputs()]
    out_map = {k: v for k, v in zip(out_names, outs)}

    return {
        "residual_count": float(out_map["residual_count"][0]),
        "count_total": float(out_map["count_total"][0]),
        "hist_logits": out_map["hist_logits"][0].astype(np.float32),
        "hist_prob": out_map["hist_prob"][0].astype(np.float32),
        "hist_counts": out_map["hist_counts"][0].astype(np.float32),
        "mean": float(out_map["mean"][0]),
    }


# ============================================================
# VIS
# ============================================================
def draw_segmentation_overlay(orig_rgb, seg_out):
    img = orig_rgb.copy()
    overlay = orig_rgb.copy()

    color_table = [
        (255, 0, 0),
        (0, 180, 255),
        (0, 255, 0),
        (255, 255, 0),
        (255, 0, 255),
        (0, 255, 255),
    ]

    for box, score, cls_id, mask in zip(seg_out["boxes"], seg_out["scores"], seg_out["cls_ids"], seg_out["masks"]):
        color = color_table[int(cls_id) % len(color_table)]
        x1, y1, x2, y2 = box.astype(int)

        m = mask.astype(bool)
        overlay[m] = (0.6 * overlay[m] + 0.4 * np.array(color)).astype(np.uint8)

        cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
        label = f"{CLASS_NAMES.get(int(cls_id), int(cls_id))} {score:.2f}"
        cv2.putText(img, label, (x1, max(18, y1 - 5)), cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 2, cv2.LINE_AA)

    out = cv2.addWeighted(overlay, 0.45, img, 0.55, 0)
    return out


def plot_pipeline_result(pipe: Dict[str, Any], reg_out: Dict[str, Any], gt_row: pd.Series | None = None):
    ncols = 4 if gt_row is not None else 3
    fig, axes = plt.subplots(2, ncols, figsize=(6 * ncols, 10))

    axes[0, 0].imshow(pipe["seg_out"]["orig_rgb"])
    axes[0, 0].set_title("Foto original")
    axes[0, 0].axis("off")

    axes[0, 1].imshow(draw_segmentation_overlay(pipe["seg_out"]["orig_rgb"], pipe["seg_out"]))
    axes[0, 1].set_title("Segmentación ONNX")
    axes[0, 1].axis("off")

    axes[0, 2].imshow(pipe["rgb_lb"])
    axes[0, 2].set_title("RGB letterbox")
    axes[0, 2].axis("off")

    if gt_row is not None:
        gt_counts = gt_row[[f"count_{k}" for k in BINS]].values.astype(np.float32)
        axes[0, 3].bar(np.array(BINS), gt_counts)
        axes[0, 3].set_xticks(np.array(BINS))
        axes[0, 3].set_title(
            f"Hist real (GT)\nGT total={float(gt_row['grape_count_total']):.1f} | GT mean={float(gt_row['mean_mm']):.2f}"
        )
        axes[0, 3].grid(True, axis="y", alpha=0.25)

    axes[1, 0].imshow(pipe["grapes_lb"], cmap="gray")
    axes[1, 0].set_title("Grapes mask")
    axes[1, 0].axis("off")

    axes[1, 1].imshow(pipe["grapes_dt"], cmap="magma")
    axes[1, 1].set_title("Grapes DT")
    axes[1, 1].axis("off")

    x = np.array(BINS)
    axes[1, 2].bar(x, reg_out["hist_counts"])
    axes[1, 2].set_xticks(x)
    axes[1, 2].set_xlabel("Calibre (mm)")
    axes[1, 2].set_ylabel("Conteo")
    axes[1, 2].set_title(
        f"Hist predicho\nvisible={pipe['visible_count'][0]:.1f} | "
        f"total={reg_out['count_total']:.1f} | mean={reg_out['mean']:.2f}"
    )
    axes[1, 2].grid(True, axis="y", alpha=0.25)

    if gt_row is not None:
        gt_counts = gt_row[[f"count_{k}" for k in BINS]].values.astype(np.float32)
        visible = np.zeros_like(gt_counts)
        pred = reg_out["hist_counts"].astype(np.float32)

        n = min(len(visible), len(pred))
        visible[:n] = 0.0
        visible[:n] = 0.0

        axes[1, 3].bar(x - 0.25, gt_counts, width=0.25, label="GT")
        axes[1, 3].bar(x, pred, width=0.25, label="Pred")
        axes[1, 3].bar(x + 0.25, np.repeat(float(pipe["visible_count"][0]) / len(BINS), len(BINS)), width=0.25, alpha=0.0)

        axes[1, 3].set_xticks(x)
        axes[1, 3].set_xlabel("Calibre (mm)")
        axes[1, 3].set_ylabel("Conteo")
        axes[1, 3].set_title("GT vs Pred")
        axes[1, 3].grid(True, axis="y", alpha=0.25)
        axes[1, 3].legend()

    plt.tight_layout()
    plt.show()

    if gt_row is not None:
        gt_counts = gt_row[[f"count_{k}" for k in BINS]].values.astype(np.float32)
        gt_total = float(gt_row["grape_count_total"])
        gt_mean = float(gt_row["mean_mm"])

        hist_mae = float(np.mean(np.abs(gt_counts - reg_out["hist_counts"])))
        count_abs = float(abs(gt_total - reg_out["count_total"]))
        mean_abs = float(abs(gt_mean - reg_out["mean"]))

        plt.figure(figsize=(14, 4))
        plt.bar(x - 0.15, gt_counts, width=0.3, label="GT")
        plt.bar(x + 0.15, reg_out["hist_counts"], width=0.3, label="Pred")
        plt.xticks(x)
        plt.xlabel("Calibre (mm)")
        plt.ylabel("Conteo")
        plt.title(
            f"Comparación detallada | hist_mae={hist_mae:.3f} | "
            f"count_abs={count_abs:.3f} | mean_abs={mean_abs:.3f}"
        )
        plt.grid(True, axis="y", alpha=0.25)
        plt.legend()
        plt.tight_layout()
        plt.show()

        print("=" * 90)
        print("GT VS PRED")
        print("=" * 90)
        print("GT total   :", gt_total)
        print("Pred total :", reg_out["count_total"])
        print("GT mean    :", gt_mean)
        print("Pred mean  :", reg_out["mean"])
        print("hist_mae   :", hist_mae)
        print("count_abs  :", count_abs)
        print("mean_abs   :", mean_abs)

def histogram_to_integer_counts(hist_counts: np.ndarray, total_count: float) -> np.ndarray:
    """
    Convierte hist_counts float a enteros preservando exactamente:
      sum(hist_int) == round(total_count)

    Método:
    - floor
    - repartir remanente según mayores partes fraccionarias
    """
    h = np.asarray(hist_counts, dtype=np.float64).reshape(-1)
    h = np.clip(h, 0.0, None)

    total_int = int(round(float(total_count)))

    if total_int <= 0 or h.sum() <= 0:
        return np.zeros_like(h, dtype=np.int32)

    # reescalar por si hist_counts no suma exactamente total_count
    h = h * (total_int / max(h.sum(), 1e-12))

    base = np.floor(h).astype(np.int32)
    remainder = total_int - int(base.sum())

    if remainder > 0:
        frac = h - base
        idx = np.argsort(-frac)[:remainder]
        base[idx] += 1
    elif remainder < 0:
        frac = h - base
        idx = np.argsort(frac)[:(-remainder)]
        for i in idx:
            if base[i] > 0:
                base[i] -= 1

    return base
# ============================================================
# MAIN
# ============================================================
def main():
    seg_session = load_onnx_session(SEG_ONNX_PATH)
    reg_session = load_onnx_session(REG_ONNX_PATH)

    if VARIETY_OVERRIDE is not None:
        variety = normalize_variety(VARIETY_OVERRIDE)
    else:
        variety = infer_variety_from_filename(IMAGE_PATH, VARIETY_CLASSES)

    pipe = build_regressor_inputs_from_photo(
        seg_session=seg_session,
        image_path=IMAGE_PATH,
        variety=variety,
    )

    reg_out = run_regressor_onnx(
        reg_session=reg_session,
        x=pipe["x"],
        variety_idx=pipe["variety_idx"],
        visible_count=pipe["visible_count"],
    )

    pred_hist_int = histogram_to_integer_counts(
        hist_counts=reg_out["hist_counts"],
        total_count=reg_out["count_total"],
    )

    reg_out["hist_counts_float"] = reg_out["hist_counts"].copy()
    reg_out["hist_counts"] = pred_hist_int.astype(np.int32)
    reg_out["count_total"] = int(pred_hist_int.sum())

    gt_row = None
    if USE_GT_VALIDATION:
        # Validacion opcional: no altera la inferencia, solo compara resultados.
        df_gt = load_gt_dataframe(CSV_PATH, IMAGE_ROOT)
        gt_row = find_gt_row_by_image_path(df_gt, IMAGE_PATH)

    print("=" * 90)
    print("PIPELINE RESULT")
    print("=" * 90)
    print("image_path     :", IMAGE_PATH)
    print("variety        :", pipe["variety"])
    print("variety_idx    :", int(pipe["variety_idx"][0]))
    print("visible_count  :", float(pipe["visible_count"][0]))
    print("residual_count :", reg_out["residual_count"])
    print("count_total    :", reg_out["count_total"])
    print("mean_mm        :", reg_out["mean"])

    print("\nTop bins pred:")
    top_idx = np.argsort(-reg_out["hist_counts"])[:8]
    for i in top_idx:
        print(f"  Cal {BINS[i]} -> {reg_out['hist_counts'][i]:.3f}")

    if gt_row is not None:
        print("\nTop bins GT:")
        gt_counts = gt_row[[f'count_{k}' for k in BINS]].values.astype(np.float32)
        top_gt = np.argsort(-gt_counts)[:8]
        for i in top_gt:
            print(f"  Cal {BINS[i]} -> {gt_counts[i]:.3f}")

    plot_pipeline_result(pipe, reg_out, gt_row=gt_row)

if __name__ == "__main__":
    main()
