# Update for Vitalik (draft; the user posts)

Context: Vitalik tried AndroidLM (v1.0/v1.1) and found the bounty's apps weakest at specialized
travel questions; his tests were "Tell me the best vegan restaurants in [city I am currently in]"
and "Which signature algorithms are quantum resistant?". This is a follow-up on what changed.

**Numbers:** from `notes/2026-10-01-vitalik-bar-121.md`, section 1. For each question one blind
grader saw the 9/28 answer, the 1.2.1 answer and the web reference side by side. Do not quote the
9/28 "64%" next to them: that came from a different grader (section 2 explains why the two
methods differ).

## Thread

1/ @VitalikButerin, an update on AndroidLM since you tried it. Measured on a Pixel 8 Pro in
airplane mode, against Claude with web search on 61 questions in the style of your post, graded
blind side by side with the version you tried: 53% -> 65% of the reference. [clip]

2/ Your crypto question. The app now carries a 19MB Ethereum and cryptography library: all 1,208
EIPs and ERCs, each with its status and the upgrade that shipped it, the consensus specs,
ethereum.org, Upgrading Ethereum, and NIST's post-quantum standards. "Which signature algorithms
are quantum resistant?" is answered from NIST's documents. Crypto: 44% -> 54% of the reference,
factual errors 30 -> 8.

3/ Your restaurant question. The answer names six to eight places, each with what it serves, its
street and its hours, in the language of the question, from 21M places (OpenStreetMap + Overture
+ Wikivoyage). "Near me" uses GPS. Restaurants: 46% -> 72% of the reference, better on 19 of 20.

4/ Faster. The engine keeps its fixed instructions read between questions: from the second
question on, the first words of an answer come after about 4 s instead of 15 s.

5/ Honest limits: the reference still wins every one of the 61 questions. Map data still lists
places that have closed (the biggest error left in restaurant answers), and a full answer with its
source check takes 1-2 minutes.

6/ Everything is reproducible: github.com/Phineas1500/AndroidLM, signed APK in the release,
every eval answer and grade in eval/.

## Notes for the user

- The graders had no web search left and checked places by fetching OpenStreetMap, HappyCow
  and the restaurants' own sites. A grader that searched more found many more closed places
  (notes, section 2).
- Setup without a computer (download in the phone's browser, import in the app) is built and
  passed its phone test, but it is not released. Add it to 4/ only once it ships.
- "Graded blind side by side" means one grader per group saw both app versions and the reference
  together. That is not the method behind the 9/28 "64%" in the README.
