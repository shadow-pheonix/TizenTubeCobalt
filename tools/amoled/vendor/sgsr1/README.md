# Pinned SGSR1 source

Upstream: https://github.com/SnapdragonGameStudios/snapdragon-gsr

Commit: `d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e`

Path: `sgsr/v1/include/glsl/sgsr1_shader_mobile.frag`

The original shader and BSD-3-Clause license are retained unchanged. The build
checks its SHA-256 in `sgsr_shader.py` and verifies the generated Java shader.
The adaptation uses GLES2 external-OES sampling with the decoder's texture
transform, implements the green-channel gather using texel-center samples,
adds an enable/bilinear path, uses high precision and protects flat-block divisions.
Algorithm coefficients, edge threshold, sharpening and contrast limiter are retained.

Research and integration tradeoffs:
https://github.com/shadow-pheonix/TizenTube/blob/amoled04/docs/video-upscaler.md
