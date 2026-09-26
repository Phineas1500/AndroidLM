# X / Farcaster post draft

For the user to post from their own account. Nothing here has been posted. Attach the v3 short cut
(2:07, fits X's 2:20 limit: Harrods, Big Motor, light travel time) to the first post, or the v3 full
cut (3:22, adds altitude in Leh) where longer video is allowed. The numbers come from
`notes/2026-09-24-small-model-comparison.md`.

## Main post (under 280 characters)

> A 35B-parameter model doing research on a Pixel 8 Pro with no network: it looks things up in a
> 21GB offline Wikipedia and cites its sources.
>
> About 8GB of RAM, 34GB on disk, no internet permission at all.
>
> Code: github.com/Phineas1500/AndroidLM
> For poidh.xyz/mainnet/bounty/31

## Thread

**2/ Why a big model fits.** Qwen3.6-35B-A3B at 2-bit is 12.3GB, but only 3B of its parameters
work on any one token. The engine (BigMoeOnEdge, built on llama.cpp) streams the experts each
token needs from flash into a 5GB cache. 4-6 tokens/s on the phone.

**3/ Research, not recall.** The model first names the Wikipedia articles it needs. For obscure
subjects it answers from the retrieved passages, with citations. For well-known ones it answers
at once, then checks its answer against the sources and posts cited corrections.

**4/ Against a 1.7B model.** Same 72 questions, Qwen3-1.7B answering from memory, each answer
graded 0-10 by Claude: 2.6 vs 7.0, ours higher on 71 of 72. Obscure subjects 1.3 vs 7.2 (the
1.7B made things up in 23 of 24). Travel 2.0 vs 6.2. General 4.2 vs 7.4.

**5/ 1983 Harrods bombing.** The 1.7B: "a Marks & Spencer store in Harrods", 10 dead, 150
injured. Ours, from the offline Wikipedia: a car bomb on 17 December, a 37-minute warning,
6 killed, 90 injured, and the IRA Army Council saying it had not authorised it.

**6/ Reasoning, not just lookup.** "How long does light take from the Sun to Earth and to
Neptune? Show the reasoning." The 1.7B: 150 million km / 300,000 km/s = 300 s, blamed on a "curved
path"; Neptune "about 35 minutes". Ours, on the phone: 499 s (8 min 20 s) and 4 h 10 min.

**7/ Penicillin's Nobel Prize.** The 1.7B: shared with "Carl Henning Wieland and Frederick
Twyford". Ours: Ernst Chain and Howard Florey, 1945, with what each of them did.

**8/ Not perfect.** It still gets details wrong, mostly side details in long travel answers,
and a source check occasionally "corrects" something that was right. Every answer and grade is
in the repo.

**9/ Real timings, on the phone.** Well-known subjects: first words in about 18 s, a cited
check in about 3 min. Obscure subjects: a cited answer in about 1.6 min (medians of 24 runs).
The video speeds up waits and labels each with its real length.

**10/ Reproducible.** Install script with checksums, the corpus build, eval questions, every
answer and grade, and our engine patches are in the repo. The corpus is on Hugging Face under
CC BY-SA.

## Notes for the user

- The comparison answers were produced with the same model and pipeline on a server (the phone
  runs the identical pipeline; its unit tests match the server code). The video shows the
  phone's own answers, which will differ in wording.
- The other entry on this bounty uses Qwen3-1.7B. The draft compares against that model without
  naming the entry; naming it is your call.
- Every post fits in 280 characters, so the same split works on Farcaster; if your account allows
  longer casts there, posts 1-3 can be merged.
