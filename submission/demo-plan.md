# Demo recording plan

The bounty's proof: a public post on X or Farcaster showing the app running offline, several
example queries and responses (including ones a 1B model would fail on), a link to the GitHub
repo and a brief explanation of the approach; then a poidh claim with a screenshot and links.

## Before recording

1. Phone: Pixel 8 Pro with the current dev build installed and the assets in
   `/data/local/tmp/bmoe` (as set up on 2026-09-23).
2. Let the phone cool for 10-15 minutes with nothing running, unplugged from the charger if
   possible (charging adds heat; sustained runs throttle near 39 C skin temperature).
3. Turn on airplane mode and turn Wi-Fi and Bluetooth off by hand, so the status bar shows it.
4. Open AndroidLM once and let the model load (a question started right after launch waits
   about 25-30 s for the load; either keep that in the video or ask a warm-up question first
   and cut it).
5. Leave 10-15 minutes between questions. Three back-to-back questions on 2026-09-24 took the
   skin temperature from 30 C to 37 C; generation in those runs was 2.4-2.9 tokens/s, against
   3.1-3.6 in earlier app runs.
6. Use Android's built-in screen recorder (Quick Settings > Screen record, "Record audio" off,
   "Show touches" on). Over USB, `adb shell screenrecord --time-limit 0` also records without a
   length limit (tested on this phone; 6 Mbit/s gives about 40MB per minute at 1008x2244).

## Shots

1. **Offline proof (10 s):** pull down Quick Settings to show airplane mode, then Settings >
   Apps > AndroidLM > Permissions, or simply state that the APK has no INTERNET permission and
   show `aapt2 dump permissions` in the write-up.
2. **Long-tail question, retrieval-first (about 3.5 min real time):** one a 1B model fails
   (chosen from the side-by-side run: see `submission/questions.md`). Show the planned
   articles, the numbered sources, and the cited answer.
3. **General question, answer-first with source check (about 5-6 min real time):** the draft
   starts streaming after about 30 s; then the source check with citations.
4. **Travel question (optional).**
5. **Telemetry:** scroll to the tok/s panel at the end of an answer.

## Editing and honesty

- Speed the long waits up (2x-8x) but label every sped-up stretch on screen, and state the real
  timings in the post (first words, total). Do not cut out waiting silently.
- Keep one continuous unedited recording of at least one full question in the repo
  (`docs/demo/`) as the raw evidence.
- Screenshot for the poidh claim: a completed cited answer with the airplane-mode icon visible.

## Recorded (2026-09-24)

Four takes, driven over USB with `scripts/demo/take.sh` (adb screen recording, scripted typing and
taps, phone in airplane mode with Wi-Fi and Bluetooth off; the charging icon is the USB cable that
drove the recording), each question's phases logged by the app. Takes started at a skin
temperature of 30.5 C or within 9 minutes of the previous one, so only the first two include the
model load.

| Take | Route | First words | Done |
|---|---|---|---|
| 1983 Harrods bombing | sources first | 123 s | 186 s (after a 21 s model load) |
| Big Motor | sources first | 80 s | 109 s (after a 20 s model load) |
| Pink Floyd, "Hey, Hey, Rise Up!" | sources first | 143 s | 167 s |
| Altitude in Leh, Ladakh | answer first, then source check | 23 s | 284 s |

All four answers were correct and cited. `scripts/demo/make_cut.py` edits a take into a clip: every
sped-up stretch carries a label with its speed and real duration, placed from the app's phase log.
Short cut (2:11; Harrods and Leh; within X's 2:20 limit for standard accounts) and full cut (3:22;
all four) are kept outside the repository with the raw takes, which are the unedited evidence.
