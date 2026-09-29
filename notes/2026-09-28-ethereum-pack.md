# An offline Ethereum and cryptography library (2026-09-28)

In the Vitalik-bar round ([2026-09-28-vitalik-bar.md](2026-09-28-vitalik-bar.md)) crypto was
the weakest group. The app reached 52% of internet search + frontier AI there, with 32 errors
against 0. The model misstated recent Ethereum mechanics (EIP-7251's 2,048 ETH, EIP-7702's
lasting delegation) and invented names. The source check could not correct them, because
Wikipedia says little about individual EIPs or the consensus specs. This round gives the app
those sources.

## The library

`ethereum.db`, 19MB, ships inside the APK. The app copies it to its files on first use. It
holds 1,510 documents, 20.4MB of text in 36,021 passages:

| Source | Documents | Licence |
|---|---|---|
| EIPs and ERCs (github.com/ethereum/EIPs, /ERCs) | 1,208 | CC0-1.0 |
| Consensus specs (github.com/ethereum/consensus-specs) | 100 | CC0-1.0 |
| ethereum.org, English pages (no tutorials, videos or site pages) | 174 | MIT |
| Upgrading Ethereum by Ben Edgington (eth2book.info), one per chapter | 15 | CC BY-SA 4.0 |
| NIST: FIPS 203, 204 and 205, SP 800-208, IR 8413, 8545 and 8547 (the prose, without the pseudocode), and six post-quantum pages | 13 | US Government works |

- **EIP key facts:** each EIP's preamble becomes its key facts: number, status, type,
  category, created, requires. They add the network upgrade that included it, from the
  hardfork meta EIPs and ethereum.org's timeline of forks: "Network upgrade: Pectra, live on
  Ethereum mainnet since May 7, 2025", or "scheduled for the Glamsterdam upgrade (not yet
  live)".
- **Text:** tables become one line per row. Code blocks stay, indented. Link-list sections
  ("Further reading", "Related topics") go.
- **Source date:** the database records when its sources date from ("September 2026"), and
  the answer prompt says so.
- **Build:** `scripts/fetch_pack_sources.sh`, `build_pack.py`, `build_corpus.py` (the
  Wikipedia corpus's own builder, so the app's search code reads it unchanged), then
  `finish_pack.py`. It takes a minute.

## Which questions use it

A question goes to the library when both hold:
- one of its words is at least e^3, about 20 times, more common there than in Wikipedia
  ("ethereum", "eip", "rollup", "signature", "quantum");
- less than a quarter of its weight (Wikipedia idf) is in words the library uses no more than
  Wikipedia does.

The second condition keeps out "How do I check that my passport is still valid?" ("valid" is
100 times more common in the library, but "passport" is foreign to it), and likewise "Which
fork should I use for fish?", "What was the Kyoto Protocol?", "What is a lymph node?" and "Are
there ATM fees in Japan?".

- **Eval sets:** 29 of 254 questions go to the library, and they are exactly the crypto and
  Ethereum ones.
- **Probes:** 28 made-up questions, of which the nearest miss is "What is the price of gas in
  California?" at a foreign share of 0.28.

The rule is `pack_affinity` / `pack_route` in rag.py and `Pack.affinity` / `Pack.routes` in the
app, held equal by `PackGoldenTest`.

## How it answers

A library question has the library's four best passages ahead of Wikipedia's. The first
passage taken from an EIP carries the EIP's key facts. The question is answered with these
sources in context, under a prompt that names the library and its date. Two ways were
compared on the VM (llama-server, the same model and settings as the app), each graded blind
against the web reference by a separate grader:

| The 20 crypto questions | Share of the reference | Errors (app / reference) | Median answer |
|---|---|---|---|
| Before: answer first, then a check against Wikipedia (the phone, last round) | 52% | 32 / 0 | 1,693 chars |
| Sources first with the library's passages (before the search fix below) | 54% | 14 / 1 | 1,148 chars |
| Answer first, then a check against the library's passages | 51% | 55 / 0 | 2,379 chars |

- **Sources first:** this more than halves the errors. The largest gains were the three worst
  answers:
  - finality: 1.5 → 4;
  - EIP-7702: 1.5 → 7;
  - EIP-7251: 1 → 7.
- **Why the share barely rises:** the answers are shorter, and the grader docks "correct but
  thin".
- **Answer first:** the check cannot repair a draft with this many errors ("Account Owned
  Account", "GHOSTDAG", EIP-7251 in Dencun).

The app therefore answers sources first (`RunService.PACK_SOURCES`). The VM answers, grades and
keys are in `eval/answers_vm_pack_*.jsonl`, `grades_vm_pack_*.json` and `key_vm_pack_*.json`.

A second prompt asked for "the full answer an expert would give" and to "never describe the
sources". On the first question it described what "the snippets" lacked anyway, and named
"ML-DSS". The cause was the search: for "Which signature algorithms are quantum resistant?"
none of the passages named the signature standards. The search drops the words in more than
2% of an index's chunks, and in this library that includes "signature" and "algorithm".
`pack_terms` now drops only the words common in Wikipedia, that is, common in English. On the
20 questions, 56 of the top 80 passages then come from the documents that answer them, against
52, and no question does worse.

## On the phone

The 20 crypto questions, asked in the app on the Pixel 8 Pro (build e17f251, from a cold start
each). All 20 went to the library. One grader then scored three answers per question side by
side, blind and in random order (`scripts/answer_sets.py`, `set_grades.py`;
`eval/sets_pack_cry.json`, `key_sets_pack_cry.json`, `grades_sets_pack_cry.json`):
- the app's answers from the last round, before the library;
- the app's answers with the library (`eval/answers_phone_pack_cry.jsonl`);
- the web reference.

That puts one grader's strictness on all three.

| The 20 crypto questions, on the phone | Mean /10 | Share of the reference | Errors | First words (median) | Done (median) |
|---|---|---|---|---|---|
| Before (answer first, Wikipedia check) | 4.70 | 47% | 30 | 18 s | 124 s |
| With the library (sources first) | 5.85 | **58%** | **6** | 61 s | 113 s (85-168 s) |
| Internet search + frontier AI | 10.00 | 100% | 0 | | |

- **Per question:** the library's answers are better on 12, the same on 3 and worse on 5.
- **Largest gains:** where the answer turns on a recent, specific fact:

  | Question | Before | With the library |
  |---|---|---|
  | EIP-7251's maximum effective balance | 1 | 7 |
  | what EIP-7702 lets an account do | 1 | 7 |
  | stateful hash-based signatures | 4 | 7 |
  | the Merge | 4 | 7 |
  | how withdrawals reach the execution layer | 4 | 7 |
  | RSA against SHA-256 under quantum attack | 5 | 8 |

- **Losses:** explanatory questions where the answer got thinner:

  | Question | Before | With the library |
  |---|---|---|
  | optimistic against ZK rollups | 8 | 6 |
  | ERC-4337 | 5 | 3 |
  | zk-SNARKs against zk-STARKs | 6 | 4 |
  | ERC-4626 | 7 | 5 |
  | ML-DSA against SLH-DSA | 5 | 4 |

- **Remaining errors:** the six left are details: BLS's G1/G2 groups swapped, "validators" instead of bundlers in ERC-4337, the delegation indicator called an opcode, a slashing threshold, EIP-712 called a layer-2 scheme, and SNARK against STARK scaling.
- **Grader strictness:** this grader scored the earlier answers at 47% of the reference, where last round's grader gave them 52%. The two rounds' figures are not comparable, which is why the before and after were graded together.
- **An extrapolation, not a measurement:** if the gain carries over, crypto would be about 64% on last round's scale, and all 61 questions about 68% instead of 64%.

Vitalik's own question, "Which signature algorithms are quantum resistant?", is now answered from NIST's documents in 98 s. The answer names ML-DSA (FIPS 204, from CRYSTALS-Dilithium), SLH-DSA (FIPS 205, from SPHINCS+) and FALCON (FN-DSA). It no longer invents a "LEED" scheme or calls Rainbow selected. It still leaves out LMS and XMSS, and does not say that RSA and ECDSA fail.

## What it does not fix

- **Answer length:** the sources-first answers are shorter than the reference's and than the
  model's own. The grader counts what is missing.
- **Time to first words:** first words come after about a minute, not 18 s (sources first
  reads a 900-1,300-token prompt before writing). The whole answer is done about as soon as
  before.
- **Sources outside the library:** questions about things it does not cover (Bitcoin, other
  chains, TLS) still depend on Wikipedia and the model.
