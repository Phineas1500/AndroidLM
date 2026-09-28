#!/usr/bin/env python3
"""The last step of building the Ethereum and cryptography pack (see build_pack.py): records in
the pack database when its sources date from (meta `as_of`, which the answer prompt quotes), each
document's source, address and licence (table `sources`, from build_pack.py's TSV), and the
licence texts that must travel with copies of the material (meta `licence`, and the MIT notice of
the ethereum.org pages as meta `notice_ethereum_org`).

Usage: finish_pack.py ethereum.db sources.tsv "September 2026" ethereum-org-website/LICENSE
"""
import sqlite3
import sys

db_path, sources_tsv, as_of, org_licence = sys.argv[1:5]
LICENCE = (
    "This database collects: Ethereum Improvement Proposals and ERCs (github.com/ethereum/EIPs, "
    "github.com/ethereum/ERCs) and the Ethereum consensus specifications (github.com/ethereum/consensus-specs), "
    "CC0-1.0; pages of ethereum.org (github.com/ethereum/ethereum-org-website), MIT licence, copyright "
    "ethereum.org contributors (notice in notice_ethereum_org); \"Upgrading Ethereum\" by Ben Edgington "
    "(eth2book.info), CC BY-SA 4.0; and publications of the U.S. National Institute of Standards and Technology "
    "(FIPS 203, 204 and 205, SP 800-208, IR 8413, 8545 and 8547, and pages of nist.gov and csrc.nist.gov), works of "
    "the U.S. Government. Because it contains \"Upgrading Ethereum\", the database as a whole is distributed under "
    "CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/); the other parts keep their own terms. The "
    "text was converted to plain markdown (links, images and page components removed; tables as lines)."
)

db = sqlite3.connect(db_path)
db.execute("INSERT OR REPLACE INTO meta VALUES('as_of', ?)", (as_of,))
db.execute("INSERT OR REPLACE INTO meta VALUES('licence', ?)", (LICENCE,))
db.execute("INSERT OR REPLACE INTO meta VALUES('notice_ethereum_org', ?)", (open(org_licence).read(),))
db.execute("DROP TABLE IF EXISTS sources")
db.execute("CREATE TABLE sources(article_id INTEGER PRIMARY KEY, source TEXT NOT NULL, url TEXT NOT NULL, licence TEXT NOT NULL)")
rows = [line.rstrip("\n").split("\t") for line in open(sources_tsv)][1:]
db.executemany("INSERT INTO sources VALUES(?,?,?,?)", [(int(r[0]), r[2], r[3], r[4]) for r in rows])
db.commit()
db.execute("VACUUM")
db.close()
print(len(rows), "sources recorded")
