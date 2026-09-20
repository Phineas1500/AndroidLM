#!/usr/bin/env python3
"""Drive BigMoeOnEdge's `bmoe-cli --session` line protocol: one resident model, many prompts.

Requests are one JSON object per line on stdin; responses are `BMOE_<TAG> {json}` lines on
stdout (see BigMoeOnEdge docs/telemetry.md). This is the same interface the Android app uses,
so timings taken through it carry over to the app's architecture.
"""
import json
import subprocess
import time


class BmoeSession:
    def __init__(self, cli, model, cache_mb=5000, threads=4, ctx=4096, ubatch=512, extra=()):
        cmd = [cli, "-m", model, "-t", str(threads), "-c", str(ctx), "--ubatch", str(ubatch),
               "--chatml", "--no-think", "--session", "--progress",
               "--moe-stream", "--cache-mb", str(cache_mb), "--overlap", "--dense-weights", "anon",
               *extra]
        t0 = time.time()
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.ready = self._wait("BMOE_READY")
        self.load_s = round(time.time() - t0, 1)
        self._id = 0

    def _wait(self, tag):
        for line in self.proc.stdout:
            if line.startswith(tag + " "):
                return json.loads(line[len(tag) + 1:])
            if line.startswith("BMOE_ERROR"):
                raise RuntimeError(line.strip())
        raise RuntimeError(f"engine exited before {tag}")

    def generate(self, prompt, n_predict):
        """Run one independent prompt (fresh KV, warm expert cache). Returns text and timings."""
        self._id += 1
        req = {"cmd": "generate", "id": self._id, "prompt": prompt, "n_predict": n_predict,
               "think": False, "clear_kv": True}
        t0 = time.time()
        self.proc.stdin.write(json.dumps(req) + "\n")
        self.proc.stdin.flush()
        first = None
        parts = []
        for line in self.proc.stdout:
            if line.startswith("BMOE_PROGRESS "):
                if first is None:
                    first = time.time() - t0
                parts.append(json.loads(line[14:]).get("delta_text", ""))
            elif line.startswith("BMOE_DONE "):
                done = json.loads(line[10:])
                break
            elif line.startswith("BMOE_ERROR"):
                raise RuntimeError(line.strip())
        else:
            raise RuntimeError("engine exited mid-generation")
        return {"text": done.get("text") or "".join(parts), "wall_s": round(time.time() - t0, 1),
                "ttft_s": round(first or 0, 1), "done": done}

    def close(self):
        try:
            self.proc.stdin.write('{"cmd":"close"}\n')
            self.proc.stdin.flush()
            self.proc.wait(timeout=60)
        except Exception:
            self.proc.kill()
