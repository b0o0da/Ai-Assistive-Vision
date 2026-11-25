# OCR fixtures (WS07-01)

Replayed by `FakeOcrEngine` through `OcrFixtures.parse()` in `android/app/.../vision/Ocr.kt`.

| Field | Type | Meaning |
| --- | --- | --- |
| `language` | string | Language code, default `en` |
| `source` | string | Where the reading came from, default `fixture` |
| `blocks` | array | Text blocks top to bottom; empty means no text found |
| `blocks[].text` | string | Recognised text, not blank |
| `blocks[].box` | object | `x`, `y`, `w`, `h` in pixels of the analysed frame |
| `blocks[].confidence` | number | 0 to 1 |

Files:
- `sign_pharmacy.json`: a shop sign with two lines
- `blank_wall.json`: nothing to read

Use invented text only. Never put real messages, names or documents here.
