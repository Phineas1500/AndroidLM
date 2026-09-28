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
| ik_llama.cpp | https://github.com/ikawrakow/ik_llama.cpp | MIT (Copyright (C) 2024-2025 Iwan Kawrakow and the ik_llama.cpp authors) | Its aarch64 matrix-multiplication kernels for IQ2_XS and IQ3_XXS weights, adapted in `patches/llama.cpp/0001-ggml-cpu-iqk-moe-kernels.patch` (the new file keeps the MIT notice) |
| BigMoeOnEdge | https://github.com/Helldez/BigMoeOnEdge | Apache-2.0 | Expert-streaming engine and the Android app structure this project's app is derived from; `scripts/build-android-engine.sh` is a bash port of its `scripts/build-android.ps1` |
| OpenStreetMap | https://www.openstreetmap.org, via QLever (https://qlever.dev) | ODbL 1.0 (© OpenStreetMap contributors) | Places with diet tags, opening hours and cuisines in the places database (`places.db`, built by `scripts/build_places.py`) |
| Overture Maps places | https://overturemaps.org (release 2026-09-23.1) | CDLA-Permissive-2.0; Foursquare Open Source Places records Apache-2.0; AllThePlaces records CC0-1.0 | Places to eat, drink and stay in `places.db` |
| GeoNames | https://www.geonames.org | CC BY 4.0 | City, region and country names in `places.db`, to find the city a question names |
| Wikivoyage | https://en.wikivoyage.org | CC BY-SA 4.0 | Travel guide text in `voyage.db`; listing names matched to places in `places.db` |
| Ethereum Improvement Proposals and ERCs | https://github.com/ethereum/EIPs, https://github.com/ethereum/ERCs | CC0-1.0 | Documents of the Ethereum and cryptography pack (`ethereum.db`, built by `scripts/build_pack.py`, shipped inside the APK) |
| Ethereum consensus specifications | https://github.com/ethereum/consensus-specs | CC0-1.0 | Documents of the pack |
| ethereum.org | https://github.com/ethereum/ethereum-org-website | MIT (Copyright (c) 2019-2026 ethereum.org contributors; the notice is stored in the pack as meta `notice_ethereum_org`) | English pages of the site, documents of the pack |
| Upgrading Ethereum, by Ben Edgington | https://eth2book.info (https://github.com/benjaminion/upgrading-ethereum-book) | CC BY-SA 4.0 | Chapters of the book, documents of the pack |
| NIST publications | FIPS 203, 204 and 205, SP 800-208, IR 8413, 8545 and 8547 (https://csrc.nist.gov), pages of nist.gov and csrc.nist.gov | Works of the U.S. Government (not subject to copyright in the U.S.) | Post-quantum standards and reports, documents of the pack |
| Boar's evaluation questions | https://github.com/rferrari/boar-app (pull request #14, `eval/dataset/questions.v2.jsonl`) | MIT (Copyright (c) 2026 aoair contributors; notice in `eval/LICENSE-boar-questions.txt`) | The 61 questions of `eval/questions_vitalik.jsonl`, with our notes on what a good answer holds |

Text derived from Wikipedia must remain under CC BY-SA 4.0 when redistributed, including inside
a packaged corpus database.

The pack (`ethereum.db`) contains "Upgrading Ethereum", so it is distributed under CC BY-SA 4.0 as a
whole, with its other parts under their own terms; it records each document's source, address and
licence in its `sources` table and the licence texts in its `meta` table. The app names each
passage it shows by its document's title.

`places.db` contains OpenStreetMap data and is published under the ODbL 1.0 in its own dataset
repository; the app shows the map-data credit under every list of places.
