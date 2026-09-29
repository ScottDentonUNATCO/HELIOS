# Video module plan

Helios gets a video-making module as **sockets**, not as one hardcoded tool —
per the omnipotent-builder mandate. Researched 2026-09-29; licenses verified
against the GitHub API the same day.

## Honest hardware ceiling

The Moto g stylus 2025 (Snapdragon 6 Gen 3, 8 GB RAM, Adreno 710) cannot run
multi-billion-parameter video diffusion at usable speed. Full on-device
text-to-video on that phone is a minutes-per-clip, thermally-throttled,
best-effort affair. The architecture below doesn't pretend otherwise.

## The three sockets

### 1. Generative socket (server side) — ComfyUI or LTX-Video

- **ComfyUI** — https://github.com/comfyanonymous/ComfyUI — **GPL-3.0**
  (verified). Per socket licensing policy it stays a **separate
  user-installed sidecar/server, never compiled into the APK**. The app
  drives it over its HTTP API (`/prompt`, `/history`, workflow JSON).
  Runs Wan 2.1/2.2, LTX-Video, HunyuanVideo, Mochi-1, CogVideoX —
  usable down to ~4 GB VRAM + 8 GB RAM via weight streaming.
- **LTX-Video** — https://github.com/Lightricks/LTX-Video — code
  **Apache-2.0** (verified), but checkpoints v0.9.5+ are under
  **OpenRail-M** (responsible-AI license, not OSI open-source) — the
  per-socket license acceptance must name the checkpoint license.
  Pure server (Python/CUDA); the 2B distilled model serves on a modest
  GPU or RunPod serverless. Helios = thin client over HTTP.

Either gives genuinely good video output. The GPU box can be his desk
machine or a rented endpoint.

### 2. Assembly socket (on-device) — ffmpeg-kit-next

- https://github.com/arthenica/ffmpeg-kit-next — **LGPL-3.0** (verified;
  default builds stay LGPL — build with default flags, never
  `--enable-gpl`). Shipped Android AAR, FFmpeg 9.0.1: transcode, trim,
  concat, filters, subtitles, stabilization, frame extraction.
- This is the on-device half of the module: whatever socket generates
  frames/clips, this assembles, edits, captions, and compresses them
  with zero server. Works on the Moto today.

### 3. Experimental on-device generative socket — llmedge

- https://github.com/sapn-ai/llmedge — **Apache-2.0** (verified).
  Shipped Android AAR; `edge.image.generateVideo(...)` does text-to-video
  with **Wan 2.1** (4–64 frames, 512×512, 20 steps) via stable-diffusion.cpp.
  12 GB RAM recommended, **8 GB minimum with `forceSequentialLoad=true`**.
- This is the closest thing to "somebody ported it to Android" — a real
  Android video-generation library, today. Ship it **gated**:
  "experimental, 8 GB+ devices", honest about minutes-per-clip on the Moto.

## Port-it-ourselves option (later, only if offline becomes a priority)

- **MobileI2V** — https://github.com/hustvl/MobileI2V — **Apache-2.0**
  (verified): 270M distilled image-to-video, 720p in ~2.24 s on mobile —
  but training/inference scripts only, no shipped Android library.
  Best DIY port candidate: model export → quantization → MNN or
  ONNX Runtime Mobile + JNI → frame assembly via ffmpeg-kit-next.
  Multi-week engineering. Do not start until the server socket ships
  and Scott asks for offline.

## Ruled out

- **neodragon-demo** (BSD-3-Clear): real offline T2V Android app, but
  binaries are compiled for one chip (Snapdragon 8 Elite / Galaxy S25
  Ultra, ~8 GB of them) — reference only, not adoptable on the Moto.
- **Open-Sora / OpenSoraPlan / Mochi-1 / HunyuanVideo / Wan2.x**: no
  Android ports exist; server-side only.
- **MediaPipe image generation**: deprecated by Google — do not adopt.

## Adoption order

1. Server generative socket (ComfyUI sidecar or LTX-Video server) — real quality now.
2. ffmpeg-kit-next bundled in-app — on-device editing/export now.
3. llmedge behind the experimental gate — on-device generation, honestly labeled.
4. MobileI2V port — only if offline generation becomes the priority.

## Offline sources — the whole map (links)

Same treatment as video: one socket per tool, license accepted at load
time. Licenses below verified against the GitHub API 2026-09-29 unless
noted. **Code license ≠ weights license** — model weights often carry
their own, sometimes non-commercial, terms; the socket must name both.

### Music-making (offline)

- **magenta/magenta** — https://github.com/magenta/magenta — Apache-2.0.
  Google's music/image ML toolkit; generation models run offline.
- **facebookresearch/audiocraft** — https://github.com/facebookresearch/audiocraft —
  code MIT, but **MusicGen weights are CC-BY-NC 4.0 (non-commercial)** —
  the socket must surface the weights license separately.
- **stability-ai/stable-audio-tools** — https://github.com/stability-ai/stable-audio-tools —
  code MIT; **Stable Audio Open weights under the Stability AI Community
  License (non-commercial)**.
- **riffusion/riffusion-hobby** — https://github.com/riffusion/riffusion-hobby —
  MIT. Spectrogram-diffusion text-to-music; small enough to run offline.

### Game-making (offline)

- The NES toolkit already lives in this repo (`nes/`).
- **chrismaltby/gb-studio** — https://github.com/chrismaltby/gb-studio — MIT.
  Desktop Game Boy studio; socket candidate (driven as a tool, or its
  engine design referenced), not Android-native.

### Offline LLM runtimes (Android — the brains behind offline sockets)

- **ggml-org/llama.cpp** — https://github.com/ggml-org/llama.cpp — MIT.
  The standard offline LLM runtime; server + Android via JNI.
- **google-ai-edge/mediapipe** — https://github.com/google-ai-edge/mediapipe —
  Apache-2.0. LLM Inference API runs Gemini-class small models on-device.
- **google-ai-edge/LiteRT** — https://github.com/google-ai-edge/LiteRT —
  Apache-2.0. Formerly TFLite; on-device inference runtime.
- **alibaba/MNN** — https://github.com/alibaba/MNN — Apache-2.0.
  Lightweight mobile inference; the planned MobileI2V port target.
- **microsoft/onnxruntime** — https://github.com/microsoft/onnxruntime — MIT.
  ONNX Runtime Mobile for the DIY port path.
- **pytorch/executorch** — https://github.com/pytorch/executorch —
  license file present, SPDX unclassified (BSD-style; verify at install).

## FetchLink — the "paste a link, get a socket" tool

Users shouldn't need to know what a socket is. They paste a link; Helios
does the rest:

1. **Identify** — GitHub repo? HuggingFace model? Direct file? Docs page?
2. **Show before fetching** — what it is, its license (code AND weights),
   download size, what it needs (GPU? 8 GB RAM?). Nothing downloads yet.
3. **User confirms** — one tap. Big downloads warn on mobile data.
4. **Fetch** — resumable download with progress, checksum verified when
   the publisher provides one.
5. **Register** — becomes a socket/tool in the catalog with its license
   recorded, ready to use.

Safety rules: only fetches links the user (or their agent, on their
behalf) explicitly supplies — never auto-follows links from pages;
no credentialed/private URLs; license is shown and accepted before
anything runs. This is the user-facing half of agent-driven tool
acquisition: the catalog holds what we know, FetchLink handles
everything else.
