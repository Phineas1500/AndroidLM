# poidh claim draft (second claim, 1.8.0)

For the user to file at https://poidh.xyz/mainnet/bounty/31 as a new claim. Nothing here has
been submitted.

- **Why a new claim:** claim 125 (filed 2026-09-25) links v1.0.0. That release has none of the
  places, the Ethereum library, the full-text Wikipedia, the in-app setup or the GrapheneOS
  testing below.
- **When:** file after the 1.8.0 release is out and the GrapheneOS thread is posted, so the post
  link and `releases/latest` show what the claim describes.
- **Before 1.8.0:** to file earlier, use 21.1M places, 2.9GB and 45.6GB, and drop "shops by kind
  and places to go out".

**Title:** AndroidLM: a 35B model with all of Wikipedia and 25M places, offline on a Pixel 8 Pro
(Android and GrapheneOS)

**Image:** `claim_gos_lisbon.png` in `/Volumes/T7/AndroidLM-dev/androidlm-tools/demo-gos/`. It
is the last frame of the GrapheneOS video: the finished answer to "Tell me the best vegan
restaurants in Lisbon", with the airplane-mode icon and "Done in 1 min 58 s".

**Description:**

AndroidLM answers research and travel questions on a 12GB Pixel 8 Pro with no network, on stock
Android 16 and on GrapheneOS. The -offline APK has no INTERNET permission, and nothing uses Google
Play Services.

- Model: Qwen3.6-35B-A3B at 2-bit (12.3GB). Only 3B parameters are active per token, so the
  engine streams the experts each token needs from flash into a 5GB cache (BigMoeOnEdge on
  llama.cpp, plus our patches, including ik_llama.cpp's ARM kernels). About 7.9GB of RAM in use,
  4-6 tokens/s. On GrapheneOS the engine runs under hardware memory tagging.
- Knowledge, 46.2GB with the model, within the 50GB:
  - all 6.06M English Wikipedia articles in full, with a full-text index, redirects and pageviews (30.1GB);
  - 25.3M places worldwide (3.5GB): where to eat, drink and stay, pharmacies, ATMs, hospitals,
    stations, shops by kind and places to go out, from Overture Maps, OpenStreetMap, GeoNames and
    Wikivoyage;
  - Wikivoyage (0.3GB);
  - an Ethereum and cryptography library inside the APK: all EIPs and ERCs, the consensus specs,
    ethereum.org and NIST's post-quantum standards.
- Research, not recall:
  - The model names the articles it needs.
  - Obscure subjects are answered from the retrieved passages, with citations.
  - Well-known subjects get an answer first, then a cited source check that corrects it where
    the sources disagree.
  - "The best vegan restaurants in Lisbon" gets a ranked list of real places in 0.1 s, then six to
    eight recommendations from it, with their streets and hours.
- Against the bounty's bar:
  - The set: 61 questions in the style of Vitalik's examples (vegan restaurants, post-quantum
    signatures and Ethereum, travel, emergencies, travel arithmetic).
  - The app answered them on the phone, and Claude Opus 5.5 answered with web search.
  - Graded blind side by side: on 1.2.1 the app came to 65% of the reference (restaurants 72%,
    crypto 54%). The reference was better on all 61.
  - Since then Wikipedia went from lead sections to every article in full. That has not been
    measured against the reference again.
- Timings on the phone:
  - Places: the list comes at once, and the recommendations are done in about 2 minutes.
  - Well-known subjects: first words after about 15 s (about 4 s from the second question on),
    the answer done after about 50 s, the source check after about 103 s.
  - Obscure subjects: a cited answer in about 1.6 min.
- Install on the phone alone: install the APK, and the app downloads its five files itself,
  resumable and checked against their SHA-256. With the offline APK, the browser downloads them
  and the app imports them. Or scripts/install.sh sets it up from a computer over USB.

Post: {{POST_URL}}
Earlier demo: https://x.com/sriramkiron/status/2103699153293357056
Code, eval sets, grades and notes: https://github.com/Phineas1500/AndroidLM
Signed APKs: https://github.com/Phineas1500/AndroidLM/releases/latest
Data: https://huggingface.co/datasets/rammingaway/androidlm-corpus and https://huggingface.co/datasets/rammingaway/androidlm-places
