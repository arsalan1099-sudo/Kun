# KUN 0.6 Smart Preview

This version adds a genuinely trained **task classifier**, specialized response guidance and user-controlled correction memory. It does not fine-tune the Ollama chat model or turn the experimental character generator into a general assistant.

## Install

Install KUN-0.6-Smart-Preview.apk as an update to the delivered 0.5 Preview. Both previews use app.kun.offline.preview and the same development signing key in this build session. The original KUN app remains separate. Do not uninstall either app to resolve signing errors; preserve chats first. Android 12+ is required.

For real generated answers, connect your PC's Ollama model using [the PC setup guide](KUN-0.5-SETUP.md). The PC remains necessary. No paid API key is embedded or required.

## Use the improvements

- **Automatic task selection:** the bundled classifier chooses architecture, business, coding, writing, or general guidance. Weak predictions fall back to general. Settings → Smart mode lets you override it.
- **Teach KUN:** open Settings → Smart mode → Teach KUN, or tap **Correct & save** under an answer. Enter a question and a reviewed correct answer. Save & use stores it privately on the phone and enables relevant-example retrieval.
- **Review/delete:** Settings → Smart mode → Saved examples. You can inspect, edit or remove each example. Maximum: 100 examples, 500 characters per question and 2,000 per answer.
- **Memory OFF:** disables use of saved examples in subsequent answers. It does not delete existing chat history or remove text already sent to a PC. New chat starts a fresh conversation context. Use delete to remove stored examples.
- **Offline recall:** if no PC or experimental model is selected, an exact saved question can return its saved answer, explicitly labelled as a saved example. It does not generate a new answer. Other questions open PC setup.
- **Export:** export only reviewed examples to JSONL with the Android file picker. Export is a dataset preparation step, not a claim of fine-tuning. Nothing is uploaded automatically to cloud training.

Relevant saved examples may be sent to the PC selected in Connect PC AI, after enabling memory/using Save & use. The saved examples are reference data; the prompt instructs the model not to treat them as higher-priority instructions. A prompt alone is not a security guarantee. Do not store passwords or secrets as training examples.

## Training performed

- 200 authored synthetic questions in English, Roman Urdu and Urdu; five categories.
- TF-IDF word/character features and a fitted multiclass logistic-regression classifier.
- 40 separate synthetic evaluation questions, excluded from fitting. Raw: **39/40**, conservative fallback: **37/40**.
- These are small developer-authored tests, not a real-user benchmark or a measurement of answer accuracy/general intelligence.
- Exported 2,369 features and fitted numeric weights are bundled in `app/src/main/assets/task-router.json` and used by Android at runtime.
- Reproduce: `python -m pip install -r training/requirements.txt`, then `python training/make_dataset.py` and `python training/train_router.py`.
- Details, dataset hashes and limitations: `training/report.json`.

No paid GPU job or LLM fine-tune was submitted. Cloud fine-tuning requires suitable compute access and permission to persist model weights. Model weights for the selected Ollama model remain unchanged. The reviewed-example export can support a later separately evaluated fine-tune.

## Validation

- Original Java character-model tests passed.
- 21 HTTP client contract checks passed against a mock server.
- 259 smart-feature assertions passed, including all 40 Python-to-Java probability fixtures (maximum error 5.56e-16), memory update/reload/retrieval/deletion/limits/export and prompt construction.
- Full Android Java sources compiled against SDK 35. Resources, assets and DEX packaged with official Android SDK tools. APK signing verified; bundled router asset matches the trained artifact.
- Gradle dependencies could not be resolved in this environment; the APK was assembled directly with official SDK tools. CI runs the equivalent source build and tests.
- No Android emulator, Redmi installation or real Ollama response test performed here. Those remain required before a production claim.
