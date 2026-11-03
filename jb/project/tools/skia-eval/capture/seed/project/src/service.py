import json

def load(path: str) -> dict:
    with open(path) as f:
        return json.load(f)
