#!/usr/bin/env bash
# Download the sources of the Ethereum and cryptography pack (scripts/build_pack.py) into DIR:
# shallow clones of Ethereum's EIPs, ERCs and consensus specs, of "Upgrading Ethereum" and of the
# English pages of ethereum.org (with the site's LICENSE), and NIST's post-quantum publications
# and pages. About 500MB; the clones are of the default branches as they are on the day.
# usage: fetch_pack_sources.sh DIR
set -euo pipefail
DIR=$1
mkdir -p "$DIR/nist" "$DIR/nist-web"
cd "$DIR"
for r in ethereum/EIPs ethereum/ERCs ethereum/consensus-specs benjaminion/upgrading-ethereum-book; do
  [ -d "$(basename $r)" ] || git clone -q --depth 1 "https://github.com/$r.git"
done
if [ ! -d ethereum-org-website ]; then
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/ethereum/ethereum-org-website.git
  (cd ethereum-org-website && git sparse-checkout set --no-cone '/public/content/*' '!/public/content/translations/*' '/LICENSE')
fi
for u in FIPS/NIST.FIPS.203.pdf FIPS/NIST.FIPS.204.pdf FIPS/NIST.FIPS.205.pdf SpecialPublications/NIST.SP.800-208.pdf \
         ir/2024/NIST.IR.8547.ipd.pdf ir/2025/NIST.IR.8545.pdf ir/2022/NIST.IR.8413-upd1.pdf; do
  f="nist/$(basename $u)"
  [ -s "$f" ] || curl -sSfL -o "$f" "https://nvlpubs.nist.gov/nistpubs/$u"
done
while read -r name url; do
  [ -s "nist-web/$name.html" ] || curl -sSfL -A "Mozilla/5.0" -o "nist-web/$name.html" "$url"
done <<'EOF'
news-2024-08-first-3-standards https://www.nist.gov/news-events/news/2024/08/nist-releases-first-3-finalized-post-quantum-encryption-standards
news-2025-03-hqc https://www.nist.gov/news-events/news/2025/03/nist-selects-hqc-fifth-algorithm-post-quantum-encryption
csrc-pqc-project https://csrc.nist.gov/projects/post-quantum-cryptography
csrc-pqc-dig-sig https://csrc.nist.gov/projects/pqc-dig-sig
csrc-sp-800-208 https://csrc.nist.gov/pubs/sp/800/208/final
nist-pqc-faq https://csrc.nist.gov/projects/post-quantum-cryptography/faqs
EOF
for d in EIPs ERCs consensus-specs upgrading-ethereum-book ethereum-org-website; do
  echo "$d $(git -C $d log -1 --format='%h %cs')"
done
