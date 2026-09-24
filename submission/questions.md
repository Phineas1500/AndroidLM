# Demo questions

Chosen from the side-by-side run against Qwen3-1.7B (`notes/2026-09-24-small-model-comparison.md`).
Type each question exactly as written: the planner and router were tested on this wording.
Scores are 0-10 from that run (small model / ours). Times are measured on the Pixel 8 Pro from
the moment the question is sent, with the model already loaded; a hot phone is slower.

## Record these

1. **1983 Harrods bombing** (obscure subject, sources first; 0 / 10)

   > What happened in the Harrods bombing in London in December 1983, how many people were
   > killed, and how did the IRA leadership respond afterwards?

   - Expect: planned article "1983 Harrods bombing", sources-first route, a cited answer in about
     2.5-3 min. Already run on the phone (first words at 117-151 s, done at 158-192 s).
   - Right answer: car bomb on 17 December 1983; a warning 37 minutes before, no evacuation; six
     killed (three police officers, three civilians), 90 injured; the IRA Army Council said it had
     not authorised it and expressed regret.
   - The 1.7B said: 12 December, "a Marks & Spencer store in Harrods", 10 dead, 150 injured, and an
     IRA statement "reaffirming their commitment to peace".

2. **Pink Floyd's "Hey, Hey, Rise Up!"** (obscure subject, sources first; 0 / 9)

   > What is Pink Floyd's 2022 song 'Hey, Hey, Rise Up!' based on, who sings on it, and what was
   > the purpose of its release?

   - Expect: sources first, about 2.5-3 min. Not yet run on the phone.
   - Right answer: the 1914 Ukrainian song "Oh, the Red Viburnum in the Meadow"; vocals by Andriy
     Khlyvnyuk of BoomBox; released in April 2022 to support Ukraine, proceeds to humanitarian
     relief.
   - The 1.7B said: an American Revolution satire featuring John Mulaney.

3. **Penicillin's Nobel Prize** (well-known, multi-step, answer first plus source check; 0.5 / 8.5)

   > The scientist who discovered penicillin shared a Nobel Prize with two others. Who were they
   > and what did each contribute?

   - Expect: answer first; first words after about 30-35 s, then the source check; about 6-7 min
     in total. Not yet run on the phone.
   - Right answer: Ernst Chain and Howard Florey, 1945 Nobel Prize in Physiology or Medicine;
     Florey and Chain's Oxford team purified penicillin and showed it worked in animals and
     patients.
   - The 1.7B said: "Carl Henning Wieland and Frederick Twyford".

4. **Altitude in Leh, Ladakh** (travel, answer first plus source check; 2.5 / 8)

   > What should I know about staying safe and healthy at altitude when travelling to Leh in
   > Ladakh, and how should I plan my first days there?

   - Expect: about 7 min. Already run on the phone: the check added Leh's elevation (3,524 m)
     and the acetazolamide dose, with no false corrections.
   - The 1.7B starts the first days with tours to Pangong Lake (about 4,200 m) and Khardung La
     (about 5,400 m), which is dangerous before acclimatising, and lists Tiger's Nest Monastery,
     which is in Bhutan.

## Optional

5. **A book that does not exist** (honesty; 0 / 9)

   > Summarize the plot of the novel 'The Glass Cartographer of Veld' by Imre Solvang.

   - Ours says it cannot find the book. The 1.7B writes a confident plot summary.

## Backups

- Big Motor (0 / 10): "What happened to the Japanese used-car dealer Big Motor in 2023, who
  founded it, and what became of the company afterwards?" The 1.7B invents a founder and a
  Toyota takeover.
- The Angola Three (1 / 9): "Who were the Angola Three, what crime were two of them convicted of
  in the early 1970s, and how long did they spend in solitary confinement?"

## Avoid on camera

Malaria vs dengue (cmp-03, the only question the 1.7B won), Albstadt earthquakes (tail-03),
NZXT H1 (tail-19) and Asman Airlines (tail-23): our answers to these still contain errors.
