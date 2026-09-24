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

   - Expect: sources first. Rehearsed on the phone on 2026-09-24: planned "Hey, Hey, Rise Up!"
     (2,626 monthly views, so sources first), first words at 133 s, done at 163 s, every fact
     right and cited.
   - Right answer: the 1914 Ukrainian song "Oh, the Red Viburnum in the Meadow"; vocals by Andriy
     Khlyvnyuk of BoomBox; released in April 2022 to support Ukraine, proceeds to humanitarian
     relief.
   - The 1.7B said: an American Revolution satire featuring John Mulaney.

3. **Penicillin's Nobel Prize** (well-known, multi-step, answer first plus source check; 0.5 / 8.5)

   > The scientist who discovered penicillin shared a Nobel Prize with two others. Who were they
   > and what did each contribute?

   - Expect: answer first, then the source check; about 5.5 min in total. Rehearsed on the phone
     on 2026-09-24: it named Chain and Florey and the 1945 prize correctly, and the check added
     the 1941 first patient. But the draft said Florey's team took up the work "in the early
     1930s" (it was 1938-39) and that Chain's structure work enabled "large-scale synthesis"
     (penicillin was made by fermentation). The check missed both. Record it only if you are
     happy to show those slips; otherwise use a backup.
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
   - Rehearsed on the phone on 2026-09-24: the draft was right ("I cannot find any record of a
     novel with this title"; first words at 27 s). The source check then said "No corrections"
     and filled its space with facts about an unrelated Dutch cartographer, van de Velde,
     which looks odd on camera. Show only the draft, or skip it.

## Backups

- **Big Motor** (0 / 10), recommended in place of penicillin: "What happened to the Japanese
  used-car dealer Big Motor in 2023, who founded it, and what became of the company
  afterwards?" The 1.7B invents a founder, "Tetsuya Mihara", and a Toyota takeover. Rehearsed on
  the phone on 2026-09-24: sources first (1,066 monthly views), first words at 91 s, done at
  130 s. It named the insurance-fraud scandal, Hiroyuki Kaneshige founding it in 1976 as
  Kaneshige Auto Center, the licence revocation, and the sale to Itochu as WECARS, all cited.
- **The Angola Three** (1 / 9), not recommended: "Who were the Angola Three, what crime were two
  of them convicted of in the early 1970s, and how long did they spend in solitary
  confinement?" Rehearsed on 2026-09-24 with a warm phone: first words at 150 s, done at 233 s.
  The names, the 1972 killing of a guard and the 40-plus years are right, but it dates both
  convictions to January 1974 and its last sentence on Woodfox is muddled.

## Avoid on camera

Malaria vs dengue (cmp-03, the only question the 1.7B won), Albstadt earthquakes (tail-03),
NZXT H1 (tail-19) and Asman Airlines (tail-23): our answers to these still contain errors.
