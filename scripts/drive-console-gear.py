#!/usr/bin/env python3
"""Drive only the local Pleos emulator's official Virtual Console, always ending in Park."""
import json
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8088"
CLIENT = "quiet-drive-app-test"
stopped = threading.Event()


def call(path, method="GET"):
    request = urllib.request.Request(BASE + path, method=method)
    with urllib.request.urlopen(request, timeout=3) as response:
        return json.load(response)


def heartbeat():
    while not stopped.is_set():
        try:
            call("/browser/session?client=" + CLIENT, "POST")
        except OSError:
            pass
        stopped.wait(.7)


worker = threading.Thread(target=heartbeat, daemon=True)
worker.start()
try:
    deadline = time.monotonic() + 10
    while not call("/vehicle/state")["gear"]["bridge_ready"]:
        if time.monotonic() > deadline:
            raise RuntimeError("Pleos DDS bridge did not become ready")
        time.sleep(.2)
    time.sleep(2)
    reverse = call("/vehicle/gear?gear=Reverse", "PUT")
    print("Reverse accepted by official console", flush=True)
    time.sleep(4)
    park = call("/vehicle/gear?gear=Park", "PUT")
    print("Park restored by official console", flush=True)
    time.sleep(3)
finally:
    try:
        call("/vehicle/gear?gear=Park", "PUT")
    finally:
        stopped.set()
        worker.join(timeout=4)
        call("/browser/session?client=" + CLIENT + "&closing=1", "POST")
