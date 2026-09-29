# HELIOS — Socket Checklist

Tick these off as you collect keys. Nothing shows "working" in the app until
that socket completes a real call on your phone. Keys live in the Android
Keystore vault — never in logs, never in crash reports.

## API keys — chat / reasoning
- [ ] OpenAI — platform.openai.com/api-keys
- [ ] Anthropic — console.anthropic.com (needs adapter; not OpenAI-compat)
- [ ] Google Gemini — aistudio.google.com/apikey
- [ ] xAI Grok — console.x.ai
- [ ] DeepSeek — platform.deepseek.com/api_keys
- [ ] Mistral — console.mistral.ai/api-keys
- [ ] Groq — console.groq.com/keys
- [ ] Together AI — api.together.xyz/settings/api-keys
- [ ] Fireworks AI — fireworks.ai (API keys page)
- [ ] OpenRouter — openrouter.ai/keys
- [ ] Cohere — dashboard.cohere.com/api-keys
- [ ] Perplexity — docs.perplexity.ai (API settings)
- [ ] Hugging Face — huggingface.co/settings/tokens (thousands of open models)

## API keys — video / music / voice / image
- [ ] Replicate — replicate.com/account/api-tokens (OSS video+music on cloud GPUs)
- [ ] ElevenLabs — elevenlabs.io (TTS + voice/music tooling)
- [ ] Runway — dev.runwayml.com (check current API availability)
- [ ] Luma — lumalabs.ai (check current API availability)
- [ ] Stability AI — platform.stability.ai/account/keys

## Logins (OAuth — needs a real sign-in flow, not wired yet)
- [ ] Google login → Gemini
- [ ] Meta login → Meta AI

## Offline / downloadable (no key needed)
- [ ] llama.cpp GGUF — download a 0.5–3B Q4 model (Qwen/Llama/Mistral)
- [ ] whisper.cpp — STT model (tiny/base)
- [ ] Piper or Kokoro — on-device TTS voice
- [ ] Ollama on a PC on your network — point Helios at http://<pc>:11434/v1

## Custom sockets (fill in the app)
- [ ] Azure OpenAI — your resource URL + deployment + key
- [ ] AWS Bedrock — needs SigV4 adapter (not built yet)
- [ ] Any OpenAI-compatible endpoint — base URL + key + model name

## Not an LLM (don't expect chat)
- [ ] IBM Quantum — quantum-computing experiments only. Real if you want it,
      but it will never answer a chat prompt.

## How to test a socket in the app
Key screen → pick socket → "Test" → Helios sends one tiny live call and shows
the raw result or the exact error (401 bad key, 403 forbidden, 429 no credits,
billing/quota). Green only on a real answer.
