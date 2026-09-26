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

## Recorded again, v2 (2026-09-25)

The same four questions on the final build (57aad19 plus the plan-echo fix, 0b3f756: repacked
dense weights, the cleaned-up screen with progress bars and tappable citations), recorded with
`scripts/demo/record.sh` (which sets airplane mode, Do Not Disturb, touches and portrait, and
restores them) and `scripts/demo/take.sh`, edited with `scripts/demo/make_cut.py`.

| Take | Route | First words | Done |
|---|---|---|---|
| 1983 Harrods bombing | sources first | 102 s | 163 s (after a 21 s model load) |
| Big Motor | sources first | 67 s | 93 s |
| Pink Floyd, "Hey, Hey, Rise Up!" | sources first | 119 s | 142 s |
| Altitude in Leh, Ladakh | answer first, then source check | 21 s | 269 s |

All four answers correct and cited (the Harrods answer says "Provisional Irish IRA"; the Leh
check adds an off-topic, cited note on earthquakes). Short cut 2:13 (Harrods and Leh), full cut
3:18; raw takes kept with the cuts outside the repository.

## Recorded again, v3 (2026-09-25)

On the v1.0.0 release APK (signed, installed from scratch), with the faster prompt reading, search
and writing. The questions now include one of reasoning, as the bounty asks for explanation,
comparison, synthesis and reasoning beyond recall: how long light takes to reach Earth and Neptune,
"show the reasoning" (the 1.7B model scored 1.5 of 10 on it, ours 8.5). Recorded with
`scripts/demo/record.sh v3 ~/androidlm-tools/demo3 t1_harrods t2_bigmotor t3_light t4_leh`, each
take from 30.5 C.

| Take | Route | First words | Done |
|---|---|---|---|
| 1983 Harrods bombing | sources first | 49 s | 82 s (after a 21 s model load) |
| Big Motor | sources first | 42 s | 72 s |
| Light from the Sun to Earth and Neptune | answer first, then source check | 12 s | 162 s |
| Altitude in Leh, Ladakh | answer first, then source check | 14 s | 216 s |

All four answers correct and cited; the light answer shows its working (149.6 million km / 299,792
km/s = 499 s; 4.5 billion km, about 4 h 10 min) and its check confirms the Earth figure from the
sources. The Leh draft stops mid-sentence at its 600-token limit, before the source check.

Editing: `make_cut.py` places the app's log on the video by the mp4's creation time and length,
which was off by -1.0 to +4.6 s on these takes, so each clip is pinned to one event read off the
video (`SYNC=first_answer_token:87.8` for Harrods, `completed:90.3` for Big Motor, 30.8 s and
31.8 s for the two drafts); a second event checked on the light take landed within 0.3 s. The
phone's log dropped Big Motor's first-token line, so that moment was read off the video too
(`FIRST_ANSWER_AT=60.0`). Short cut 2:07 (Harrods, Big Motor, light), full cut 3:22 (all four);
claim screenshot `claim_v3_harrods.png`; raw takes, logs and cuts kept outside the repository.
