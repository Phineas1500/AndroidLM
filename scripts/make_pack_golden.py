#!/usr/bin/env python3
"""Reference outputs of rag.py's Ethereum and cryptography pack code, for the Kotlin port's
PackGoldenTest: for each question, its stems in the pack, the routing figures (pack_affinity,
pack_route) against the sample Wikipedia, the pack's passages (pack_hits), and the context the
answer or the source check reads when they lead Wikipedia's; plus the pack's prompts.

Usage: make_pack_golden.py sample_wiki.db ethereum.db > pack_golden.json
"""
import json
import sys

import rag
from rag import Corpus, build_context, check_context

CASES = [
    # (question, planned titles, a draft for the source check)
    ("Which signature algorithms are quantum resistant?", ["Post-quantum cryptography", "Digital signature"],
     "ML-DSA (FIPS 204), SLH-DSA (FIPS 205) and FALCON resist quantum attacks; RSA and ECDSA do not."),
    ("What is the maximum effective balance of an Ethereum validator after EIP-7251?", ["Ethereum", "Proof of stake"],
     "EIP-7251 raised the maximum effective balance from 32 ETH to 2048 ETH in the Pectra upgrade of May 2025."),
    ("What does EIP-7702 let a regular Ethereum account (EOA) do?", ["Ethereum"],
     "EIP-7702 lets an EOA set code for a single transaction; the delegation is removed afterwards."),
    ("How does finality work in Ethereum proof of stake?", ["Proof of stake", "Ethereum"],
     "Casper FFG finalizes checkpoints every epoch with a two-thirds supermajority; finality takes about 13 minutes."),
    ("What are stateful hash-based signatures like XMSS and LMS, and why does the 'state' matter?",
     ["Hash-based cryptography", "Merkle signature scheme"],
     "XMSS and LMS use one-time keys; reusing a key lets an attacker forge signatures, so the signer must keep state."),
    ("What is the difference between ERC-20 and ERC-721 tokens?", ["ERC-20", "Non-fungible token"],
     "ERC-20 tokens are fungible balances; ERC-721 tokens are unique, each with its own tokenId."),
    ("How do I check that my passport is still valid?", ["Passport"], ""),
    ("What is the price of gas in California?", ["Gasoline and diesel usage and pricing"], ""),
    ("Which fork should I use for fish?", ["Fish knife"], ""),
    ("What was the Kyoto Protocol?", ["Kyoto Protocol"], ""),
    ("Is tipping expected in restaurants in Portugal?", ["Portugal"], ""),
    ("what is ethereum", ["Ethereum"], ""),
]

wiki, rag.PACK = Corpus(sys.argv[1]), Corpus(sys.argv[2])
rag.WIKI_N_INDEXED[0] = wiki.n_indexed
out = {"as_of": rag.pack_as_of(), "wiki_n_indexed": wiki.n_indexed,
       "answer_system": rag.pack_answer_system(rag.pack_as_of()),
       "check_followup": rag.pack_check_followup(rag.pack_as_of()), "cases": []}
for question, titles, draft in CASES:
    ws = wiki.stems(question)
    ps = rag.PACK.stems(question)
    aff = rag.pack_affinity(question, ws)
    case = {
        "question": question, "titles": titles,
        "pack_stems": [[s, round(idf, 6)] for s, idf in ps],
        "wiki_stems": [[s, round(idf, 6)] for s, idf in ws],
        "affinity": None if aff is None else [None if aff[0] is None else round(aff[0], 6), round(aff[1], 6)],
        "route": rag.pack_route(question, ws),
    }
    hits = rag.pack_hits(question)
    case["pack_hits"] = [{"title": h["title"], "section": h["section"], "start": h["start"], "score": h["score"],
                          "via": h["via"], "text": h["text"], "lead": bool(h.get("lead"))} for h in hits]
    merged = hits + wiki.retrieve(question, titles)
    context, used = build_context(merged, 4000)
    case["context"] = context
    case["context_used"] = len(used)
    if draft:
        case["draft"] = draft
        case["check_context"] = check_context(merged, draft, question, 2000)[0]
    out["cases"].append(case)
json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
