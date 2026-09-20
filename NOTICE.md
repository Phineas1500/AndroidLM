# Third-party material

This project's own code (`scripts/`, and the additions under `app-android/`) is licensed under
Apache-2.0 (see `LICENSE`). It builds on, downloads, or redistributes the
following, each under its own terms.

| Component | Source | License | How it is used |
|---|---|---|---|
| Wikipedia text | https://en.wikipedia.org, via the FineWiki extraction | CC BY-SA 4.0 | Article text in the corpus database; passages shown to the user are attributed by article title. `eval/tail_sample.jsonl` and `eval/questions_tail.jsonl` contain excerpts and facts from Wikipedia articles, named in each record. |
| FineWiki | https://huggingface.co/datasets/HuggingFaceFW/finewiki | See the dataset card (Wikipedia content under CC BY-SA 4.0) | Source of cleaned article text and infoboxes |
| Wikimedia pageviews and redirect dumps | https://dumps.wikimedia.org | CC0 (pageviews); CC BY-SA 4.0 (redirect and title data) | Article ranking and title resolution |
| Qwen3.6-35B-A3B | https://huggingface.co/Qwen/Qwen3.6-35B-A3B | Apache-2.0 | The language model |
| Unsloth GGUF quantizations | https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF | Apache-2.0 (same as the model) | The 2-bit model file |
| llama.cpp | https://github.com/ggml-org/llama.cpp | MIT | Inference runtime |
| BigMoeOnEdge | https://github.com/Helldez/BigMoeOnEdge | Apache-2.0 | Expert-streaming engine and the Android app structure this project's app is derived from; `scripts/build-android-engine.sh` is a bash port of its `scripts/build-android.ps1` |

Text derived from Wikipedia must remain under CC BY-SA 4.0 when redistributed, including inside
a packaged corpus database.
