"""Sinh contracts/schema/*.json. Chạy trong thư mục ai/: `uv run python -m askfirst.domain.export [out_dir]`."""
import pathlib
import sys

from askfirst.domain.models import export_json_schemas

if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "../contracts/schema")
    for path in export_json_schemas(out):
        print(path)
