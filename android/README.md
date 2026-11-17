# AVN Android app

Built phase by phase; the prompts are in `docs/claude-code/android-prompts.md` and the rules in `android/CLAUDE.md`. Feature seams stay injectable. The debug app enables camera, detector and tracker by default.

## Modules
- `:contracts` — pure Kotlin models matching `contracts/schemas/`. Its tests read `contracts/fixtures/` in place.
- `:engine` — pure Kotlin. Holds `EngineInput` and `SpeechOutput`; the priority engine arrives in Phase 3.
- `:app` — Android, by feature package (`core`, `data`, `camera`, `vision`, `voice`, `screen`, `navigation`, `sos`, `ui`).

## Build
Requirements: JDK 17 and the Android SDK (Android Studio latest stable installs both). Run from `android/`:

```bash
./gradlew assembleDebug check
```

`check` runs the unit tests, Android lint and ktlint for all modules. No phone is needed. `./gradlew ktlintFormat` fixes style findings.

## Fakes and real implementations
`app/src/main/kotlin/avn/app/core/di/ModulesModule.kt` is the only place a seam is bound. The gradle property `avn.realSeams` is exposed as `BuildConfig.REAL_SEAMS`; its normal debug default is `detector,tracker,camera`. Set `-Pavn.realSeams=` to force the fakes.

The bundled fine-tuned YOLOv8s ONNX model runs through ONNX Runtime Mobile. The normal debug app
binds `YoloV8OnnxDetector` and `IouTracker`; the **See objects** tab captures one frame with
CameraX and runs inference on-device. The frame stays in memory and is released after inference.
The model has eight classes: person, transportation, pothole, bench, chair, fire hydrant, stop sign,
and parking meter. Held-out test mAP50-95 is 0.387; bench and chair recall are weak. Its training
data does not establish accuracy on Egyptian traffic or roads. Host inference and the packaged APK
were verified; CameraX/NNAPI runtime behavior still needs an Android emulator or device.

The **Read text** tab follows the useful capture-then-read interaction from the smart-glass
reference: request one still frame, review the recognized text, then explicitly send it to the
speech engine. OCR is not run continuously, overlapping captures are rejected, and the frame/text
are not persisted. CameraX now supplies the capture, but the OCR binding remains a fake; Arabic
text recognition still needs a real OCR engine before the Read text tab can recognize text.

## On a phone
Install the debug APK and follow `docs/permissions-guide.md`. The Debug tab has the fake wake word, transcript and event injection, the offline toggle and the `X-Mock-Scenario` choice.

## Run the camera detector
The default debug build uses the real CameraX capture and the bundled YOLOv8s general ONNX model. Grant
Camera permission in Onboarding, open **See objects**, then tap **Check scene**. This is an
on-demand still capture; it is not continuous video detection. To test without a phone, install an
Android emulator with a webcam-backed virtual camera in Android Studio, boot it, then run
`./gradlew installDebug` from `android/`. The installed SDK on the current development machine
does not yet include an emulator or system image.

The detector currently supports the eight general classes listed above. The available test metrics
do not validate Egyptian-specific traffic or road hazards; that requires reviewed Egyptian images
with bounding-box labels. The Read text tab also still uses a fake OCR engine.
