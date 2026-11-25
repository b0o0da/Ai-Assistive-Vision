"""Export tooling for the detector (WS-05).

Detector-agnostic on purpose: TD-001 (YOLO nano AGPL vs an Apache-licensed
detector) is still Open in docs/decisions/README.md, so nothing in this
package imports a concrete detector library. Callers pass in a loader
function that returns an already-instantiated torch.nn.Module; see
`export.py`'s module docstring for the loader contract.
"""
