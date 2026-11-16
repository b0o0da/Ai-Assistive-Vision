# Face-embedding model spike (WS08-01, AVN-137)

16 Nov 2026 · Owner: Marwan · Reviewer: Mostafa S. · Status: **Desk phase done; measurements pending**

WS-08 (faces) is a **Should**, cut first if Gate 2 is no-go (Notion: "CUT LADDER 2"). This note gives the
team what the Gate 2 go/no-go needs: two candidates, the criteria, and a recommendation. Size and licence
come from public sources; on-phone latency and accuracy are **not measured yet** (step list at the end).
No face images were used or stored for this note.

## What WS-08 needs (architecture.md WS-08)
- ML Kit face detection → crop and align → embedding → cosine match against a local cache of opted-in people.
- 50–150 ms per face on the phone; one embedding per track, only when a person is near and stationary.
- Bias to "someone you don't know": a wrong name is the worst failure.
- Licence must allow our use (research prototype, possibly shown publicly). Embeddings only, never images.
- `/faces/enroll` and `/faces/match` carry `model_version`, so switching model later means re-enrolling.

## Candidates

| | 1. MobileFaceNet | 2. ArcFace (InsightFace model zoo, e.g. `buffalo_s` / `buffalo_l`) |
|---|---|---|
| Input / output | 112 x 112 aligned face → 128-d embedding | 112 x 112 aligned face → 512-d embedding |
| Size | ~4.0 MB float, ~1M parameters (paper; Qualcomm AI Hub port) | MobileFaceNet-backbone variant is small (`buffalo_s`); ResNet variants are tens to hundreds of MB |
| Reported accuracy | 99.55 % LFW (paper) | Higher on hard benchmarks than MobileFaceNet (larger backbones) |
| Phone latency | **to measure**; Qualcomm AI Hub publishes NPU benchmarks for its port | **to measure**; ResNet variants likely over the 150 ms budget on CPU |
| Licence | **to confirm per weight file.** Paper is open; each implementation and its weights carry their own licence, and many weights are trained on MS-Celeb-1M (withdrawn dataset) | Code MIT, but **pretrained models and training data: non-commercial research only** |
| Fit | Built for phones; matches "MobileFaceNet" named in architecture.md | Strong accuracy, licence blocks anything beyond non-commercial research |

## Go / no-go criteria (proposed)
A model is **go** only if all hold:
1. **Licence**: written licence for the exact weight file allows our use, including a public demo; the training-data
   terms do not forbid it. Recorded in the licence log (AVN-73).
2. **Size**: ≤ 10 MB after export (TFLite fp16 or int8), so APK + models stay reasonable.
3. **Latency**: median ≤ 100 ms, p95 ≤ 150 ms per face on the team test phone (CPU or GPU delegate), measured over 50 crops.
4. **Accuracy at our threshold**: false-accept rate ≤ 1 % on the volunteer set (wrong name is the worst failure),
   false-reject ≤ 20 % (an unknown-person fallback is acceptable).
5. **Privacy**: runs fully on the phone; only the embedding leaves it (already the contract).

If no candidate passes 1–3 by the Gate 2 review: **no-go**; WS-08 is cut as the Notion cut ladder says.

## Recommendation
**MobileFaceNet**, provided a weight file with a clear permissive licence is found (criterion 1). It fits the size and
latency budget by design and is the model architecture.md already names. ArcFace/InsightFace pretrained weights are
**not** usable for us beyond non-commercial research, so they serve only as an accuracy reference.

If no permissively licensed MobileFaceNet weights exist, the options are: train MobileFaceNet ourselves on a dataset whose
licence allows it (time cost, likely past Gate 3), or no-go.

## Still to do (needs the phone and a licence check)
1. Pick the exact MobileFaceNet weight file, read its LICENSE and the training-data terms; add a row to the licence log.
2. Export to TFLite (reuse `computer_vision/export/export.py` from AVN-5) and record the file size.
3. Time 50 synthetic or consented crops on the test phone; record median and p95.
4. FAR/FRR on the volunteer set at 2–3 thresholds (only with written consent; embeddings only).
5. Fill the table above with the numbers and set Status to Decided.

## Sources
- MobileFaceNets paper (4.0 MB, 99.55 % LFW): https://arxiv.org/abs/1804.07573
- Qualcomm AI Hub MobileFaceNet (size, parameters, on-device benchmarks): https://aihub.qualcomm.com/mobile/models/mobile_facenet
- InsightFace (code MIT; pretrained models non-commercial research only): https://github.com/deepinsight/insightface
