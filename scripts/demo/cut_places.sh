#!/usr/bin/env bash
# A short clip of one places take: real time up to the list, the model's writing sped up (and
# labelled with its real duration), real time for the end. Times are seconds into the take.
# usage: cut_places.sh <take.mp4> <out.mp4> <from> <list_at> <write_end> <speed> "<found caption>" "<writing label>"
set -euo pipefail
IN=$1; OUT=$2; FROM=$3; LIST=$4; END=$5; SPEED=$6; FOUND=$7; WRITING=$8
FONT="/System/Library/Fonts/Supplemental/Arial Bold.ttf"
esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/:/\\:/g' -e "s/'/’/g" -e 's/%/\\%/g'; }
TOP="drawtext=fontfile='$FONT':text='$(esc "Airplane mode · fully offline · Pixel 8 Pro")':fontcolor=white:fontsize=34:box=1:boxcolor=black@0.72:boxborderw=14:x=(w-text_w)/2:y=128"
lab() { echo "drawtext=fontfile='$FONT':text='$(esc "$1")':fontcolor=white:fontsize=40:box=1:boxcolor=0x5b3fa0@0.9:boxborderw=20:x=(w-text_w)/2:y=h*0.42$2"; }
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$IN")
A_END=$(python3 -c "print(min($LIST + 3.0, $END))")
SHOW=$(python3 -c "print($LIST - $FROM)")
ffmpeg -v error -y -i "$IN" -filter_complex "\
[0:v]trim=$FROM:$A_END,setpts=PTS-STARTPTS,$TOP,$(lab "$FOUND" ":enable='gte(t,$SHOW)'")[a];\
[0:v]trim=$A_END:$END,setpts=(PTS-STARTPTS)/$SPEED,$TOP,$(lab "$WRITING" "")[b];\
[0:v]trim=$END:$DUR,setpts=PTS-STARTPTS,$TOP[c];\
[a][b][c]concat=n=3:v=1[v]" -map "[v]" -c:v libx264 -preset slow -crf 23 -pix_fmt yuv420p -movflags +faststart "$OUT"
ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT"
