#!/usr/bin/env python3
"""Runs one bounded request against an OpenAI-compatible local LLM endpoint."""

import argparse
import json
import os
import time
import urllib.request


def extract_text(payload):
    if isinstance(payload.get("output_text"), str):
        return payload["output_text"]
    parts = []
    for item in payload.get("output", []):
        for content in item.get("content", []):
            text = content.get("text")
            if isinstance(text, str):
                parts.append(text)
    return "\n".join(parts)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:1234/v1")
    parser.add_argument("--model", default="local-qwen3-4b")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    body = json.dumps({
        "model": args.model,
        "input": [{
            "role": "user",
            "content": "/no_think\nОтветь одним коротким предложением: для чего научной поисковой системе нужен RAG?",
        }],
        "max_output_tokens": 80,
        "stream": False,
    }, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        args.base.rstrip("/") + "/responses",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer lm-studio"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            payload = json.loads(response.read())
            status = response.status
        result = {
            "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "passed": status == 200 and bool(extract_text(payload).strip()),
            "status": status,
            "model": payload.get("model", args.model),
            "durationMs": round((time.perf_counter() - started) * 1000, 2),
            "answer": extract_text(payload).strip(),
            "usage": payload.get("usage", {}),
        }
    except Exception as exc:
        result = {
            "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "passed": False,
            "status": 0,
            "model": args.model,
            "durationMs": round((time.perf_counter() - started) * 1000, 2),
            "error": type(exc).__name__ + ": " + str(exc),
        }

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
