"""Text-region selection and reading order (WS07-04, AVN-131).

An OCR pass over one frame returns every text block the frame holds,
including background clutter the chest-mounted camera was never pointed
at (docs/demo-script.md scenario 5: "a sign, menu, medicine box" -- not
whatever else happens to be in frame). This module scores blocks by size,
centrality and confidence to find the one text region the user is most
likely pointing at, then puts its own blocks in top-to-bottom reading
order. It is OCR-engine-agnostic: [TextBlock] is a plain box + text +
confidence, independent of avn.app.vision.OcrEngine (WS07-01) or any
particular recognizer -- this is the post-processing step between
whatever produced the raw blocks and the sentence the app reads aloud.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TextBlock:
    text: str
    confidence: float
    x: float
    y: float
    width: float
    height: float

    @property
    def center_x(self) -> float:
        return self.x + self.width / 2

    @property
    def center_y(self) -> float:
        return self.y + self.height / 2

    @property
    def area(self) -> float:
        return self.width * self.height


def _distance(ax: float, ay: float, bx: float, by: float) -> float:
    return ((ax - bx) ** 2 + (ay - by) ** 2) ** 0.5


def _region_score(block: TextBlock, frame_width: float, frame_height: float) -> float:
    """Higher for a block that is larger and closer to the frame's center.
    Confidence is deliberately not part of this score -- it is a separate
    gate the caller applies first, so a confidently read stray block in a
    corner cannot outscore the real, larger, central sign just because its
    own text was read cleanly."""
    frame_area = frame_width * frame_height
    size_score = block.area / frame_area if frame_area > 0 else 0.0

    max_distance = _distance(0, 0, frame_width / 2, frame_height / 2)
    distance = _distance(block.center_x, block.center_y, frame_width / 2, frame_height / 2)
    centrality_score = 1.0 - (distance / max_distance if max_distance > 0 else 0.0)

    return size_score + centrality_score


def select_regions(
    blocks: list[TextBlock],
    frame_width: float,
    frame_height: float,
    min_confidence: float = 0.5,
    proximity_fraction: float = 0.35,
) -> list[TextBlock]:
    """The blocks that belong to the one text region the user is pointing
    at, in reading order. Drops every block under [min_confidence], scores
    the rest by size and centrality to find the anchor (the real sign, not
    a smaller or off-center distractor), then keeps only blocks within
    [proximity_fraction] of the frame's diagonal from that anchor -- a
    second sign elsewhere in frame is excluded even if confidently read,
    while the anchor's own other lines (a menu's several rows) stay in."""
    confident = [b for b in blocks if b.confidence >= min_confidence]
    if not confident:
        return []

    anchor = max(confident, key=lambda b: _region_score(b, frame_width, frame_height))
    threshold = proximity_fraction * _distance(0, 0, frame_width, frame_height)

    nearby = [
        b for b in confident
        if _distance(b.center_x, b.center_y, anchor.center_x, anchor.center_y) <= threshold
    ]
    return reading_order(nearby)


def reading_order(blocks: list[TextBlock], row_tolerance_fraction: float = 0.5) -> list[TextBlock]:
    """Top-to-bottom; blocks that sit on roughly the same physical line
    (within [row_tolerance_fraction] of the shorter block's own height)
    sort left-to-right instead of by their exact pixel y, so per-element
    jitter in the OCR output does not reorder one physical line."""

    def same_row(a: TextBlock, b: TextBlock) -> bool:
        tolerance = row_tolerance_fraction * min(a.height, b.height)
        return abs(a.center_y - b.center_y) <= tolerance

    ordered: list[TextBlock] = []
    remaining = sorted(blocks, key=lambda b: b.center_y)
    while remaining:
        row = [remaining.pop(0)]
        index = 0
        while index < len(remaining):
            if same_row(row[-1], remaining[index]):
                row.append(remaining.pop(index))
            else:
                index += 1
        row.sort(key=lambda b: b.center_x)
        ordered.extend(row)
    return ordered


def read_text(
    blocks: list[TextBlock],
    frame_width: float,
    frame_height: float,
    min_confidence: float = 0.5,
) -> str:
    """The final string for one frame: the selected region's text, one
    line per block, in reading order. Empty when nothing passes the
    confidence gate -- phrasing a spoken "no readable text found" fallback
    is the caller's job (docs/demo-script.md scenario 5), not this
    module's."""
    selected = select_regions(blocks, frame_width, frame_height, min_confidence)
    return "\n".join(block.text for block in selected)
