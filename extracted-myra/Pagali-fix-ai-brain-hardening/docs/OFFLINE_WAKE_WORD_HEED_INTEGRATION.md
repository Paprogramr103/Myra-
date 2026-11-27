# MYRA offline wake-word integration

## Target

Custom offline wake phrase: **Hey MYRA**. Hindi/English aliases such as "हे मायरा" are handled by the assistant after the primary wake phrase; they are not assumed to be separate neural-model outputs.

## Selected engine

Heed Wake Word (Apache-2.0): https://github.com/AndreiBulzan/heed-wakeword

Heed exports a model plus a matching `wake.json` preprocessing contract. The Android app must ship both together. Do not copy third-party proprietary model files or the openWakeWord pretrained weights (those model assets have CC BY-NC-SA 4.0 terms).

## Train the MYRA model

On a computer with Python 3.10+:

```bash
python -m pip install "heed-wakeword[all]"
heed doctor
heed init myra --phrase "hey myra"
heed train myra --tts-pos 400 --kokoro-pos 200
heed export myra
```

Use Heed's cross-speaker evaluation and live-test the model before shipping. Add hard negatives such as "hey Mira", "hey Maya", "myra", "hey there", and ordinary Hindi conversation. Do not lower the threshold merely to force detections; false triggers can open MYRA unexpectedly.

The export contains `wake.onnx`, `wake.int8.onnx`, `wake.tflite`, and `wake.json`. Keep `wake.json` paired with the exact model export.

## Android integration contract

- Capture mono PCM at 16 kHz in a dedicated, permission-gated foreground microphone service.
- Run preprocessing and inference locally; never upload microphone audio.
- The preprocessing parameters must be read/validated against `wake.json` (Heed uses a 512-point FFT, 400-sample window, 160-sample hop, 40 mel bins and 101 frames in its current reference implementation).
- Use a confidence threshold, consecutive-frame requirement, and cooldown; report detection confidence and current mode in Diagnostics.
- On detection, release/stop the wake-word capture before handing microphone ownership to Gemini Live. Never run a second microphone capture concurrently with Gemini Live.
- Keep the current Android SpeechRecognizer path as a clearly labelled fallback until the custom model is present and verified.
- Do not modify `GeminiLiveClient.kt`, `AudioEngine.kt`, Aoede voice configuration, or Gemini Live audio routing.

## Current status

The app does **not** yet contain a trained "Hey MYRA" model or the native Heed preprocessing/inference runtime. The existing `WakeWordService` is still Android SpeechRecognizer (which may use the network depending on device/provider), not dedicated offline hotword detection. A model file alone is insufficient: the matching preprocessing contract and inference runtime must also be integrated and tested. Do not label the existing path "fully offline".
