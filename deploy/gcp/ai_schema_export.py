#!/usr/bin/env python3
"""Export pydantic model_json_schema of an AI checkout's app/schemas for the model names BE pins.

  python3 ai_schema_export.py <ai-checkout> <be schemas.json> <out.json>

Imports only app.schemas.* of the given checkout (pydantic models; the job installs the AI-pinned pydantic).
Same method that produced src/main/resources/ai/schemas.json; a name missing from the AI source exports as null.
"""
import importlib
import inspect
import json
import pkgutil
import sys
from pathlib import Path


def export(root, names):
    sys.path.insert(0, str(Path(root).resolve()))
    import pydantic
    import app.schemas as package
    models = {}
    for info in pkgutil.iter_modules(package.__path__):
        module = importlib.import_module('app.schemas.' + info.name)
        for name, value in vars(module).items():
            if inspect.isclass(value) and issubclass(value, pydantic.BaseModel) and value.__module__ == module.__name__:
                models.setdefault(name, value)
    return {name: (models[name].model_json_schema() if name in models else None) for name in names}


if __name__ == '__main__':
    root, pinned, out = sys.argv[1:4]
    names = list(json.loads(Path(pinned).read_text()))
    Path(out).write_text(json.dumps(export(root, names), sort_keys=True, indent=2, ensure_ascii=False) + '\n')
