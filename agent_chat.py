#!/usr/bin/env python3
"""Provito agent chat - tiny client for the Cloudflare Worker + local mirror.

Usage:
  python3 agent_chat.py watch                 # sync every 3s into agent_chat.txt
  python3 agent_chat.py send backend "hello"  # post a message
  python3 agent_chat.py messages              # print messages
"""
import json
import os
import sys
import time
import urllib.request

BASE = "https://provito-agent-chat.csomeone301.workers.dev"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "agent_chat.txt")
INTERVAL = 3


def _request(path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        BASE + path,
        data=data,
        headers={"Content-Type": "application/json", "User-Agent": "provito-agent-chat/1"},
    )
    with urllib.request.urlopen(req, timeout=15) as res:
        return json.load(res)


def send(author, text):
    return _request("/message/create", {"author": author, "text": text})


def messages(since=None):
    suffix = f"?since={since}" if since else ""
    return _request("/messages" + suffix)["messages"]


def render(msgs):
    lines = [
        "PROVITO - AGENT CHAT",
        "====================",
        f"API: {BASE}",
        f"Mirrored every {INTERVAL}s by agent_chat.py. Post with:",
        '  python3 agent_chat.py send <author> "<text>"',
        "",
    ]
    for m in msgs:
        lines.append(f"[{m.get('at', '')}] [{m.get('author', 'anon')}]")
        for line in str(m.get("text", "")).splitlines() or [""]:
            lines.append("  " + line)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def write(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def watch():
    last = None
    while True:
        try:
            msgs = messages()
            text = render(msgs)
            if text != last:
                write(OUT, text)
                last = text
                print(f"synced {len(msgs)} message(s) -> {OUT}", flush=True)
        except Exception as e:
            print(f"sync error: {e}", flush=True)
        time.sleep(INTERVAL)


def main():
    args = sys.argv[1:]
    if args[:1] == ["send"]:
        author = args[1] if len(args) > 1 else "anon"
        text = " ".join(args[2:]) if len(args) > 2 else sys.stdin.read()
        if not text.strip():
            print('usage: agent_chat.py send <author> "<text>"')
            return
        print(json.dumps(send(author, text), indent=2))
        return
    if args[:1] == ["messages"]:
        for m in messages():
            print(f"[{m['at']}] [{m['author']}] {m['text']}")
        return
    watch()


if __name__ == "__main__":
    main()
