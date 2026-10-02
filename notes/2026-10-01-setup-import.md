# Setting the app up without a computer, and a route that waits for less (2026-10-01)

## 1. The import

Until now the Wikipedia, places and travel files could only reach the phone with adb (the model
could also come through the file picker). The bounty asks that someone gets the app running
"within a few minutes without substantial debugging", and adb was the weakest step of that.

**What the user does now** (INSTALL.md, "On the phone alone"):
1. Install the APK from the release in the phone's browser.
2. In the app's **Set up** card, tap **Download** next to each of the five files. The browser
   downloads it; the app itself still has no INTERNET permission (opening a link needs none).
3. Tap **Import files…** and select the five downloads (or the same files on a USB-C drive).

**What the app does:**
- **Recognises each file** by its name, or by its size when the browser renamed the download
  ("wiki (1).db"). The five sizes all differ, and the hash decides anyway.
- **Copies it into app storage** (`files/models`, `files/corpus`, where the scans look first),
  hashing as it goes. The file appears under its name only when its size and SHA-256 are the
  manifest's. Otherwise the part is deleted and the card says why: damaged, unfinished, or
  another version.
- **Runs as a foreground service** with a wake lock, so a 37GB copy goes on with the screen off.
  A notification shows the progress and has a Cancel button.
- **Deletes each download once its copy is checked**, when the box is ticked (the default), so the
  files do not take the space twice. Only files on the phone's own storage are deleted; a USB
  drive is never written.

**Why copy and not read the downloads in place.** The dev build has all-files access and could
open the files in Downloads directly. But Downloads is on the emulated (FUSE) storage, where the
engine's direct reads (O_DIRECT) do not work, and the engine falls back to buffered reads.
App storage is the same real filesystem as `/data/local/tmp`. For the same reason a copy found
in Downloads does not count as installed.

**Free space:** 37GB from a USB drive. From Downloads, with each download deleted after its
copy, the peak is about 58GB: the downloads not yet copied, the files already imported, and the
part being written (largest: `wiki.db`, 21.3GB).

**Code:**
- `SetupFiles` (research module): the list of files, held equal to `assets/manifest.json` by
  `SetupFilesTest`, and the verified copy.
- `SetupImport.kt`: where each file is, the import service and its progress.
- `SetupCard.kt`: the card.

Tests (JVM):
- the list is the manifest;
- a right file is copied and stamped;
- a damaged, short or long file is refused and leaves nothing behind;
- a cancelled copy leaves nothing behind;
- a renamed download is recognised by its size.

**Hugging Face's links:** a file URL redirects to a CDN URL whose signature expires after an
hour. The CDN supports range requests. A download that runs steadily goes on past the hour; an
interrupted one may not resume after it.

**On the phone** (Pixel 8 Pro, the dev release build, files placed in Downloads with adb):

| Test | Result |
|---|---|
| All files already pushed with adb | the card is hidden |
| Only optional files missing | the card shows one line, closed, naming them |
| `voyage.db` (0.33GB) and a damaged `wiki_df.db` (4 bytes changed) | `voyage.db` copied and checked in 2.0 s (162 MB/s), its download deleted; `wiki_df.db` refused with "SHA-256 does not match", its download kept |
| `wiki.db` (21.3GB) and the right `wiki_df.db`, screen turned off at once | `wiki.db` copied and checked in 143 s (149 MB/s) while the phone dozed; both downloads deleted |
| A research question with only the imported files | answered, with Wikipedia sources |

- **Picker:** the system picker opened on Downloads and took both files with a press and hold
  and a tap.
- **Not tried:** downloading the files in the phone's browser.

## 2. The route no longer waits behind later titles

Every corpus lookup runs on one thread. The plan's titles are looked up as each line of the plan
is written, and a title that is not an article (or a redirect) needs a full-text title search,
several seconds on a phone. The route, which only needs the first title, used to be queued behind
the lookups of all the others.

In the prefix-cache test ([2026-09-30-speed.md](2026-09-30-speed.md)) this cost 8.3 s on "What
causes the seasons on Earth?":
- the first title, "Earth's orbit", is an article;
- "Earth's revolution around the Sun" is not;
- the route came 8.3 s after the plan.

Now the first title's lookup also works out its route, and the pack's half of its routing (the
question's words in the Ethereum library) is read before the plan starts. The route is ready
when the first title is. Answers are identical: the route is the same function of the same
title.

**How often it matters,** over the 97 plans in the phone logs:

| First planned title | Plans | |
|---|---|---|
| An article or redirect, but a later title needs the full-text search | 25 | the fix removes the wait |
| Itself needs the full-text search | 39 | still waits for it |
| No full-text search at all | 33 | no wait before either |

The wait shows mostly from the second question of a session on, where the plan takes about 3 s
instead of 9 s (the prefix cache) and no longer hides the lookups.

**On the phone:** the same seasons question as the second of a session. The route came 1 ms
after the plan, not 8.3 s. The phone was throttled (38 C), so the plan itself took 8 s instead of 3 s.

Test: `routeDoesNotWaitForALaterTitlesLookup` holds the corpus thread with a lookup queued after
the first title's and checks that the draft starts while it is still held. It fails on the old
code (the draft waited 5.9 s) and passes on the new.
