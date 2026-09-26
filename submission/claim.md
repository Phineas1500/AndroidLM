# poidh claim draft

For the user to file at https://poidh.xyz/mainnet/bounty/31 after the post is live. Nothing here
has been submitted.

**Title:** AndroidLM: a 35B-parameter research assistant with offline Wikipedia, on a Pixel 8 Pro

**Image:** a screenshot of a completed, cited answer with the airplane-mode icon visible: the
Harrods answer from the v3 recording, `claim_v3_harrods.png` beside the cuts (see `demo-plan.md`).

**Description:**

AndroidLM answers research questions on a stock 12GB Pixel 8 Pro with no network. The app
declares no INTERNET permission and uses no Google Play Services.

- Model: Qwen3.6-35B-A3B at 2-bit (12.3GB). Only 3B parameters are active per token, so the
  engine streams the experts each token needs from flash into a 5GB cache (BigMoeOnEdge on
  llama.cpp, plus engine patches of ours, including ik_llama.cpp's ARM kernels for the experts).
  About 7.9GB of RAM in use; 4-6 tokens/s written, 24-30 tokens/s read.
- Knowledge: an offline English Wikipedia (21GB SQLite: 2M articles in full, the rest as lead
  sections, full-text index, redirects, pageviews) and optional Wikivoyage. 33.9GB in total.
- Research, not recall: the model names the articles it needs. Obscure subjects are answered
  from retrieved passages with citations; well-known ones get an immediate answer followed by a
  cited source check that corrects it where the sources disagree. Explanation, comparison,
  synthesis and reasoning questions are part of the eval (the demo works out how long light takes
  to reach Earth and Neptune, which the 1.7B model got wrong by about 7x).
- Against a 1.7B model (Qwen3-1.7B, 4-bit) answering the same 72 questions from memory, each
  answer graded 0-10 by Claude with one rubric: 2.6 vs 7.0 overall, ours higher on 71 of 72. On
  obscure subjects 1.3 vs 7.2, with invented details left in 23 of 24 small-model answers vs 3 of
  ours. Answers and grades are in the repo.
- Real timings on the phone (medians over 24 questions asked in the app): a cited answer about an
  obscure subject in about 1.6 min; for well-known subjects, first words in about 18 s and a cited
  source check in about 3 min. The phone's answers graded the same as the server's (7.1 vs 7.2).

Post: {{POST_URL}}
Code, install script, eval sets and grades: https://github.com/Phineas1500/AndroidLM
Signed APK: https://github.com/Phineas1500/AndroidLM/releases/tag/v1.0.0
Corpus: https://huggingface.co/datasets/rammingaway/androidlm-corpus
