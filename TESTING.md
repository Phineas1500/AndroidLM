# Helping test AndroidLM

Thanks for offering. There are two ways to help. The first works today and takes about half an
hour plus a download; the second is for when our own app has a published build.

## 1. Tell us how fast the engine runs on your phone (works today)

AndroidLM runs a 35B-parameter model by streaming parts of it from the phone's flash storage.
How fast that is depends heavily on the phone, and we only have numbers from a server so far.
The engine we build on, [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge), publishes its
own demo app with a model downloader, so you can measure your phone without anything from us.
It is a separate open-source project (Apache-2.0); we are not affiliated with it, and
installing its APK is your call, as with any sideloaded app.

**You need:** an arm64 Android phone (Android 10+), ideally with 12GB of RAM (8GB is worth
trying and worth reporting), about 14GB of free storage, and Wi-Fi for a 12.3GB download.

1. Install `app-dev-release.apk` from the
   [BigMoeOnEdge v0.24.0 release](https://github.com/Helldez/BigMoeOnEdge/releases/tag/v0.24.0)
   (you will have to allow installing from your browser or file manager).
2. Open it, go to **Get a model**, and instead of a catalog entry paste this URL into the
   download field (it is the exact model file AndroidLM uses, 12.3GB):

   ```
   https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF/resolve/main/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf
   ```

3. When it finishes, select the model. In **Settings** leave everything at its default except:
   turn thinking **off** if it is on, and note the **Expert cache** size (default 2000 MiB).
4. Plug the phone in, close other apps, and run these two prompts. After each, write down what
   the telemetry panel shows: **tok/s**, **cache hit %** and **flash per token**, plus the
   **time from pressing Send to the first word** (use a stopwatch if the panel does not show a
   prefill figure).
   - Short: `Explain why the Dead Sea is so salty, in one paragraph.`
   - Research-sized: paste the whole of
     [`testing/bench_prompt_research.txt`](testing/bench_prompt_research.txt) (about 1,100
     tokens: this is what a real AndroidLM question looks like to the model). The time until
     the first word appears is the number we care about most.
5. If you have 12GB of RAM, raise **Expert cache** to 4000 MiB (or 5000 if it lets you), restart
   the model and repeat the two prompts.
6. Run the short prompt three more times in a row and note whether tok/s drops as the phone
   warms up.

Then open a **Device report** issue (there is a template) or send the numbers to whoever
pointed you here. Useful extras: whether the phone became unresponsive, whether Android killed
the app, and how hot it got.

To clean up: delete the model inside the app (or uninstall it); that frees the 12.3GB.

## 2. Try AndroidLM itself (not ready for outside testers yet)

What is missing, so you know what you would be signing up for:

- The app runs on our Pixel 8 Pro (Android 16) end to end, but has not been tried on any other
  phone yet, and there is no signed release APK.
- Installing needs a computer with `adb` and about 35GB free: the app has no network permission
  by design, so everything is copied over USB. See [`INSTALL.md`](INSTALL.md).

When a build is published, testing will look like this: run `scripts/install.sh`, put the phone
in airplane mode, and ask it things you would actually look up while travelling or reading.
The most useful reports are questions where the answer was wrong, where the cited sources were
irrelevant, or where it was too slow to bother with, along with the phone model. Questions
about obscure subjects (a small town, a minor historical event, a niche technology) are the
ones that show whether the offline Wikipedia lookup is doing its job.

## What to include in any report

- Phone model, RAM, Android version (and whether it is stock, GrapheneOS, or another ROM)
- Free storage before you started
- Expert cache size and any setting you changed
- For each prompt: tok/s, prefill time or speed, cache hit %, flash per token
- Anything that went wrong, in your own words; screenshots of the telemetry panel are ideal
