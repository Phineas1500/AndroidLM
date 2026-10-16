# Testing AndroidLM

Thanks for trying it. AndroidLM is a signed release that sets itself up on the phone: install the
APK from the [latest release](https://github.com/Phineas1500/AndroidLM/releases/latest), then
tap **Download all missing** (46GB over Wi-Fi), or use the `-offline` APK and import the files.
[`INSTALL.md`](INSTALL.md) has every route, and the README's [For judges](README.md#for-judges)
section has the short version: which phone, how long setup and answers take, and questions to
try. It is tested on a Pixel 8 Pro under stock Android 16 and under GrapheneOS.

## What to try

Turn on airplane mode and ask things you would actually look up while travelling or reading.
- **Places:** "best vegan restaurants in Lisbon", "a pharmacy near me" (GPS, still no network).
- **Ethereum and cryptography:** "What is EIP-4844 and what are blobs in Ethereum?", "Which
  signature algorithms are quantum resistant?"
- **Obscure subjects** (a small town, a minor historical event, a niche technology): these show
  whether the offline Wikipedia lookup is doing its job.
- **Follow-ups** in the same chat ("What about Osaka?", "How long are they kept?").
- **A long session:** ten or more questions in a row. The phone gets warm and answers slow down;
  the answers should not get worse.

The most useful reports are questions where the answer was wrong, where the cited sources were
irrelevant, or where it was too slow to bother with, along with the phone model.

## What to include in a report

Open a **Device report** issue (there is a template), or send it to whoever pointed you here.
- Phone model, RAM, Android version (and whether it is stock, GrapheneOS, or another ROM)
- Free storage before you started, and which APK (online or `-offline`)
- The memory preset (Settings) and any setting you changed
- For each question: the time to the first words and to the end of the answer; the timings are
  under "How it was answered" below each answer
- Anything that went wrong, in your own words; screenshots of the answer, and of the speed and
  memory panel (Model and files, in the menu), are ideal
