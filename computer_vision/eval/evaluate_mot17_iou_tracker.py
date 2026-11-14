#!/usr/bin/env python3
"""Evaluate the WS-06 IoU/ByteTrack-style association baseline on local MOT17 files.

Uses the MOT17 public detector output (det.txt) and ground truth (gt.txt), not image
pixels. The metric therefore isolates association quality from detector quality.
Run from the repository root:
  python3 computer_vision/eval/evaluate_mot17_iou_tracker.py
"""
from __future__ import annotations

import argparse
import csv
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "datasets/data/tracking/mot17/train"
HIGH_CONFIDENCE = 0.45
LOW_CONFIDENCE = 0.10
MATCH_IOU = 0.30
MAX_LOST_FRAMES = 8
EVAL_IOU = 0.50


@dataclass(frozen=True)
class Box:
    x: float
    y: float
    w: float
    h: float
    score: float = 1.0


@dataclass
class Track:
    track_id: int
    box: Box
    age: int = 1
    lost: int = 0
    history: list[Box] = field(default_factory=list)


def iou(a: Box, b: Box) -> float:
    left, top = max(a.x, b.x), max(a.y, b.y)
    right, bottom = min(a.x + a.w, b.x + b.w), min(a.y + a.h, b.y + b.h)
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    union = a.w * a.h + b.w * b.h - intersection
    return intersection / union if union > 0 else 0.0


class GreedyIouTracker:
    """Greedy high-score association with a low-score recovery pass."""

    def __init__(self) -> None:
        self.tracks: dict[int, Track] = {}
        self.next_id = 1

    def update(self, detections: list[Box]) -> list[tuple[int, Box]]:
        for track in self.tracks.values():
            track.lost += 1
        self.tracks = {
            key: track for key, track in self.tracks.items() if track.lost <= MAX_LOST_FRAMES
        }
        high = [d for d in detections if d.score >= HIGH_CONFIDENCE]
        low = [d for d in detections if LOW_CONFIDENCE <= d.score < HIGH_CONFIDENCE]
        visible: list[tuple[int, Box]] = []
        matched_high: set[int] = set()
        self._associate(high, visible, matched_high)
        self._associate(low, visible, set())
        for index, detection in enumerate(high):
            if index in matched_high:
                continue
            track = Track(self.next_id, detection, history=[detection])
            self.tracks[self.next_id] = track
            visible.append((self.next_id, detection))
            self.next_id += 1
        return visible

    def _associate(
        self,
        detections: list[Box],
        visible: list[tuple[int, Box]],
        matched_detections: set[int],
    ) -> None:
        pairs = sorted(
            (
                (iou(self._predicted_box(track), detection), track_id, det_index)
                for track_id, track in self.tracks.items()
                if track.lost > 0 or all(existing_id != track_id for existing_id, _ in visible)
                for det_index, detection in enumerate(detections)
            ),
            reverse=True,
        )
        matched_tracks: set[int] = set()
        for overlap, track_id, det_index in pairs:
            if overlap < MATCH_IOU or track_id in matched_tracks or det_index in matched_detections:
                continue
            track = self.tracks[track_id]
            detection = detections[det_index]
            track.box = detection
            track.history = (track.history + [detection])[-6:]
            track.age += 1
            track.lost = 0
            matched_tracks.add(track_id)
            matched_detections.add(det_index)
            visible.append((track_id, detection))

    @staticmethod
    def _predicted_box(track: Track) -> Box:
        """Linear box-edge extrapolation over short detector gaps."""
        if len(track.history) < 2 or track.lost <= 0:
            return track.box
        previous = track.history[-2]
        last = track.history[-1]
        steps = track.lost
        return Box(
            x=max(0.0, last.x + (last.x - previous.x) * steps),
            y=max(0.0, last.y + (last.y - previous.y) * steps),
            w=max(1.0, last.w + (last.w - previous.w) * steps),
            h=max(1.0, last.h + (last.h - previous.h) * steps),
            score=last.score,
        )


def read_mot_rows(path: Path) -> dict[int, list[list[float]]]:
    rows: dict[int, list[list[float]]] = defaultdict(list)
    with path.open(newline="") as stream:
        for row in csv.reader(stream):
            if row:
                rows[int(float(row[0]))].append([float(value) for value in row])
    return rows


def load_sequence(sequence: Path):
    detections_by_frame = read_mot_rows(sequence / "det/det.txt")
    ground_truth_by_frame = read_mot_rows(sequence / "gt/gt.txt")
    frame_ids = sorted(set(detections_by_frame) | set(ground_truth_by_frame))
    tracker = GreedyIouTracker()
    predicted: dict[int, list[tuple[int, Box]]] = {}
    ground_truth: dict[int, list[tuple[int, Box]]] = {}
    update_seconds = 0.0

    for frame_id in frame_ids:
        detections = [
            Box(x=row[2], y=row[3], w=row[4], h=row[5], score=row[6])
            for row in detections_by_frame.get(frame_id, [])
            if row[4] > 0 and row[5] > 0 and row[6] >= LOW_CONFIDENCE
        ]
        start = time.perf_counter()
        predicted[frame_id] = tracker.update(detections)
        update_seconds += time.perf_counter() - start

        # MOT17 class 1 = pedestrian; mark=1 excludes ignored / distractor regions.
        gt_items = []
        for row in ground_truth_by_frame.get(frame_id, []):
            if len(row) >= 8 and int(row[6]) == 1 and int(row[7]) == 1 and row[4] > 0 and row[5] > 0:
                gt_items.append((int(row[1]), Box(row[2], row[3], row[4], row[5])))
        ground_truth[frame_id] = gt_items
    return frame_ids, predicted, ground_truth, update_seconds


def score_sequence(sequence: Path) -> dict[str, float | str]:
    frame_ids, predicted, ground_truth, update_seconds = load_sequence(sequence)
    gt_total = sum(len(items) for items in ground_truth.values())
    pred_total = sum(len(items) for items in predicted.values())
    pair_counts: Counter[tuple[int, int]] = Counter()
    det_tp = 0
    switches = 0
    previous_assignment: dict[int, int] = {}

    for frame_id in frame_ids:
        gt_items = ground_truth[frame_id]
        pred_items = predicted[frame_id]
        if not gt_items or not pred_items:
            continue
        overlaps = np.array(
            [[iou(gt_box, pred_box) for _, pred_box in pred_items] for _, gt_box in gt_items],
            dtype=float,
        )
        rows, cols = linear_sum_assignment(1.0 - overlaps)
        frame_matches = []
        for row, col in zip(rows, cols):
            if overlaps[row, col] < EVAL_IOU:
                continue
            gt_id, _ = gt_items[row]
            pred_id, _ = pred_items[col]
            pair_counts[(gt_id, pred_id)] += 1
            det_tp += 1
            frame_matches.append((gt_id, pred_id))
            if gt_id in previous_assignment and previous_assignment[gt_id] != pred_id:
                switches += 1
            previous_assignment[gt_id] = pred_id
        seen_gt = {gt_id for gt_id, _ in frame_matches}
        for gt_id in list(previous_assignment):
            if gt_id not in seen_gt:
                previous_assignment.pop(gt_id, None)

    gt_ids = sorted({gt for gt, _ in pair_counts})
    pred_ids = sorted({pred for _, pred in pair_counts})
    idtp = 0
    if gt_ids and pred_ids:
        counts = np.zeros((len(gt_ids), len(pred_ids)), dtype=float)
        gt_index = {value: index for index, value in enumerate(gt_ids)}
        pred_index = {value: index for index, value in enumerate(pred_ids)}
        for (gt_id, pred_id), count in pair_counts.items():
            counts[gt_index[gt_id], pred_index[pred_id]] = count
        rows, cols = linear_sum_assignment(-counts)
        idtp = int(counts[rows, cols].sum())

    idfp = pred_total - idtp
    idfn = gt_total - idtp
    idf1 = 2 * idtp / (2 * idtp + idfp + idfn) if 2 * idtp + idfp + idfn else 0.0
    precision = det_tp / pred_total if pred_total else 0.0
    recall = det_tp / gt_total if gt_total else 0.0
    return {
        "sequence": sequence.name,
        "frames": len(frame_ids),
        "gt": gt_total,
        "pred": pred_total,
        "precision": precision,
        "recall": recall,
        "idf1": idf1,
        "id_switches": switches,
        "tracker_ms_per_frame": update_seconds * 1000 / max(1, len(frame_ids)),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "computer_vision/eval/mot17_iou_tracker_report.md")
    parser.add_argument("--split", choices=("train",), default="train")
    args = parser.parse_args()
    sequences = sorted(DATA.glob("*-FRCNN"))
    if not sequences:
        raise SystemExit(f"No MOT17 FRCNN sequences found under {DATA}")
    results = [score_sequence(sequence) for sequence in sequences]
    totals = {key: sum(float(row[key]) for row in results) for key in ("gt", "pred")}
    total_frames = sum(int(row["frames"]) for row in results)
    # Aggregate IDF1 must use global identities, so use frame-weighted sequence IDF1 as a
    # concise descriptive summary; individual sequence rows remain the main results.
    weighted_idf1 = sum(float(row["idf1"]) * int(row["frames"]) for row in results) / total_frames
    lines = [
        "# MOT17 IoU tracker baseline",
        "",
        "Generated from the local MOT17 train split using the supplied FRCNN detections. This isolates association quality; it does not measure the app detector or camera performance.",
        "",
        "Association: IoU >= 0.30, high score >= 0.45, low score >= 0.10 (low-score boxes can only recover an existing ID), 8-frame lost-track retention. Detection matching for metrics uses IoU >= 0.50.",
        "",
        "| Sequence | Frames | GT boxes | Tracked boxes | Detection precision | Detection recall | IDF1 | ID switches | Tracker ms/frame (Python reference) |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in results:
        lines.append(
            f"| {row['sequence']} | {row['frames']} | {row['gt']} | {row['pred']} | "
            f"{float(row['precision']):.3f} | {float(row['recall']):.3f} | {float(row['idf1']):.3f} | "
            f"{row['id_switches']} | {float(row['tracker_ms_per_frame']):.3f} |"
        )
    lines.extend([
        "",
        f"Aggregate sequence-weighted IDF1: **{weighted_idf1:.3f}** ({total_frames} frames).",
        "",
        f"Total GT boxes: {int(totals['gt'])}; tracked boxes: {int(totals['pred'])}.",
        "",
        "Limitations: MOT17 is pedestrian tracking, so this does not validate vehicle approach, near/medium/far calibration, chest-mounted Egyptian street scenes, or detector accuracy. Run the same replay against held-out field video before claiming those behaviors.",
        "",
    ])
    args.output.write_text("\n".join(lines), encoding="utf-8")
    print(f"Wrote {args.output}")
    for row in results:
        print(f"{row['sequence']}: IDF1={float(row['idf1']):.3f}, switches={row['id_switches']}")


if __name__ == "__main__":
    main()
