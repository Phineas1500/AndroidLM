# 8GB phones: memory presets, and a load that no longer reads the whole model (2026-10-08)

A reviewer ran v1.3.0 in the Android emulator on a laptop, gave the emulator 12GB (INSTALL.md asked
for a 12GB phone), and got 0.2 tokens/s: the app saw a 12GB device, took the 12GB settings (a
5,000 MiB expert cache and the 1,256 MiB compute buffer of 1,280-token prompt reading, about 8GB in
all) and the host swapped. Smaller phones got a fixed 2,000 MiB cache and 512-token reading.

## An 8GB phone on the Pixel 8 Pro

`hogx` (work/memtest/hogx.c) holds 4,000 MiB of incompressible memory, rewritten every 2 s so the
kernel can neither compress nor park it: MemAvailable falls from 8.0 to 3.9GB, about what a Pixel 8
(7.4 GiB) has. (The older `hog` used half-zero pages, which zram halves; and the first hogx was
optimised away by the compiler, since nothing read the buffer back.)

The engine alone (started from adb, which the low-memory killer never kills), one 1,216-token
research prompt and two 300-token drafts, 512-token prompt reading:

| Expert cache | Prompt | Writing | Load | Lowest MemAvailable |
|---|---|---|---|---|
| 12GB settings, no hog (5,000 MiB, 1,280) | 31.6 s | 5.5 tokens/s | 20 s | 970 MiB |
| 2,000 MiB | 67.0 s | 4.2 | 57 s | 49 MiB, keyboard and wallpaper killed |
| 1,500 MiB | 59.1 s | 4.0 | 31 s | 514 MiB |
| none | 49.5 s | 2.4 | 40 s | 1,532 MiB |

So the old small-phone default (2,000 MiB) is too big for 8GB, and the engine refuses a cache under
1,500 MiB (smaller ones thrash). In the app, though, every setting was killed while loading:
the engine is the app's own child process (oom_score_adj 0), and the killer reached it after the
background apps ("low watermark is breached"), with the cache at 1,500, 1,000 or 0 MiB alike.

## Why: the whole 11.4GB file was read at load

Sampling the engine's RssAnon/RssFile every 0.3 s during a load: file-backed pages rose to 4.48GB in
the first seconds, then drained as the repacked dense weights (1.24GB) and the cache filled in.
llama.cpp maps the model with MAP_POPULATE unless some tensor is marked lazy, and in its auto mode
only tensors over 4 GiB are; Qwen3.6's expert tensors are 74-136 MiB each. So the loader read the
whole file, 11.4GB of which the engine uses 1.7GB through the mapping (it streams the experts with
its own direct reads). On a 12GB phone that is a 20 s load; on an 8GB one it is what gets the app
killed.

`patches/llama.cpp/0005-llama-lazy-experts.patch`: with LLAMA_LAZY_EXPERTS=1 the routed expert
tensors (`_exps`) are left out of the mapping's prefetch, so only the dense weights are read ahead;
`patches/0009-bigmoeonedge-lazy-experts.patch` sets it whenever the engine streams. Under the hog,
1,500 MiB cache: file pages at load 4.48 -> 1.26GB, load 30.5 -> 9.9 s, lowest MemAvailable about
1.0GB while loading.

On the 12GB settings, no hog, the same request alternating the two engines (phone at 39.5-40 C):

| Engine | Load | 1,216-token prompt | 300-token draft | Cache hits (3 requests) |
|---|---|---|---|---|
| 1.4.0 | 22.4 s, 30.2 s | 59.8 s, 63.1 s | 3.78, 3.89 tokens/s | 16.0 / 84.0 / 84.7% |
| lazy experts | 12.0 s, 10.4 s | 62.9 s, 61.3 s | 3.84, 3.90 tokens/s | 16.0 / 84.0 / 84.7% |

The generated text of all four runs is identical; the load takes half as long on every phone.

## Memory presets

Settings -> Memory preset (MemoryPreset.kt), Auto by default, which picks from the RAM the phone
reports (16 at 14.5 GiB or more, 12 at 11.0 or more, else 8):

| Preset | Expert cache | Prompt reading | Dense weights | The app takes |
|---|---|---|---|---|
| 8 GB phone | 1,500 MiB | 512 tokens | anonymous memory | about 4GB |
| 12 GB phone | 5,000 MiB | 1,280 tokens | pinned | about 8GB |
| 16 GB phone or more | 8,000 MiB | 1,280 tokens | pinned | about 11GB |

Pinning copies the repacked dense weights once more while the model loads (1.2GB more at the
peak), so the 8GB preset keeps them in plain app memory. In an emulator the screen says to give it
8GB and choose the 8GB preset; below 7 GiB it says the phone is too small. The 16GB preset is
untested on a 16GB phone.

The app on the simulated 8GB phone (hog running, 8GB preset, patched engine), nothing killed:

| Question | First words | Writing | Done | Lowest MemAvailable |
|---|---|---|---|---|
| Mozi's universal love (answer first, then the check) | 19 s | 3.3 tokens/s | 3 min 51 s | 502 MiB |
| Albstadt's earthquakes (sources first, 1,006-token prompt) | 78 s | 2.7 | 2 min 56 s | 514 MiB |
| Vegan restaurants in Berlin (places, 810-token prompt) | 36 s | 2.5 | 3 min 41 s | 480 MiB |

About half the speed of the 12GB phone, and it answers. (With the 1.4.0 engine the same setup was
killed while loading in four of six attempts.)
