# TD-008: On-demand OCR interaction

## Decision

Use a two-step camera-text flow in the Android UI:

1. **Capture text** requests one still frame and runs the `OcrEngine` once.
2. The user reviews the result and presses **Read aloud** when ready.

Only one OCR request may run at a time. The captured frame is not saved, and recognized text is held only in UI memory until cleared or the screen is left. All speech requests continue through `EngineInput`; the UI does not call a TTS implementation directly.

## Source and adaptation

This borrows the reference smart-glass repository's ACTION/capture and READ/playback separation, plus its policy of running OCR on demand instead of on every frame. The interaction is adapted to Android and the existing `FrameSource`, `OcrEngine`, and `EngineInput` seams. The reference itself targets Raspberry Pi 5 and Bangla/English, so its OCR configuration, hardware setup, and model weights are not reused.

## Current implementation boundary

The `Read text` screen and workflow are wired, but the default `FrameSource` and `OcrEngine` are still fakes. A real phone flow still needs CameraX `captureStill()` and an Arabic-capable OCR implementation. Do not treat this UI wiring as evidence that live OCR is available or evaluated.
