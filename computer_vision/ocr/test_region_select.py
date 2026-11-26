"""Tests for region_select.py (WS07-04, AVN-131). Synthetic blocks only --
no real photo is needed since this module operates purely on box geometry,
confidence and text already produced by some OCR pass."""

from __future__ import annotations

from computer_vision.ocr.region_select import TextBlock, reading_order, read_text, select_regions

FRAME_WIDTH = 1080.0
FRAME_HEIGHT = 1920.0


def test_region_largest_central():
    # The medicine box the user is holding up: large, centered, confident.
    main_sign = TextBlock("Paracetamol 500mg", confidence=0.92, x=340, y=760, width=400, height=120)
    # A second sign on a wall in the background: smaller, off in a corner,
    # and -- deliberately -- just as confident, so confidence alone cannot
    # be what excludes it.
    background_sign = TextBlock("Exit", confidence=0.95, x=20, y=20, width=80, height=40)

    selected = select_regions([background_sign, main_sign], FRAME_WIDTH, FRAME_HEIGHT)

    assert selected == [main_sign]


def test_region_drops_low_confidence_blocks():
    confident = TextBlock("Open 9am-9pm", confidence=0.8, x=400, y=900, width=300, height=80)
    unreadable = TextBlock("??????", confidence=0.1, x=410, y=1000, width=280, height=70)

    selected = select_regions([confident, unreadable], FRAME_WIDTH, FRAME_HEIGHT, min_confidence=0.5)

    assert selected == [confident]


def test_region_returns_empty_when_nothing_passes_confidence():
    blocks = [TextBlock("blur", confidence=0.2, x=0, y=0, width=50, height=20)]

    assert select_regions(blocks, FRAME_WIDTH, FRAME_HEIGHT, min_confidence=0.5) == []


def test_reading_order():
    # One physical menu card: a two-word title line, then a price line
    # below it -- handed to reading_order in a jumbled, not-top-to-bottom
    # order, the way a hierarchical OCR result might list blocks.
    price = TextBlock("45 EGP", confidence=0.9, x=600, y=500, width=120, height=50)
    title_right = TextBlock("Sandwich", confidence=0.9, x=620, y=300, width=160, height=50)
    title_left = TextBlock("Chicken", confidence=0.9, x=400, y=310, width=160, height=50)

    ordered = reading_order([price, title_right, title_left])

    assert ordered == [title_left, title_right, price]


def test_read_text_joins_the_selected_region_in_reading_order():
    line1 = TextBlock("Take one tablet", confidence=0.9, x=300, y=700, width=400, height=80)
    line2 = TextBlock("twice daily", confidence=0.9, x=300, y=800, width=400, height=80)
    distractor = TextBlock("Pharmacy", confidence=0.9, x=20, y=20, width=100, height=40)

    text = read_text([distractor, line2, line1], FRAME_WIDTH, FRAME_HEIGHT)

    assert text == "Take one tablet\ntwice daily"
