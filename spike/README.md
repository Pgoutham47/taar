# spike/ — pre-event reference work

**Nothing in this directory is submitted.**

Per `HACKATHON.md` §1, the competition entry is written during the event window.
This directory holds work done beforehand to answer questions that would otherwise
consume event hours — design probes and simulations, not application code.

At the event:

```bash
git checkout --orphan main && git rm -rf .
# first commit is an empty Android scaffold, created live
```

Never merge, cherry-pick or `git add` from here onto the submission branch. Keep
this in a separate clone if that is easier to guarantee.

| Contents | What it answered |
|---|---|
| `dsp/` | Whether 50 Hz is recoverable at Android magnetometer rates, and which rates fail |
| `audio/` | Whether an arc separates from a ballast, speech, a rustle and a motor |
| `android/` | The Kotlin skeleton — **uncompiled**, with golden tests to prove the transcription |

Each has its own README with the findings and, more importantly, with what the
findings do not cover.
