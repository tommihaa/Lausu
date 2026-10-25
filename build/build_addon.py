# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Tommi Haanranta
"""Pakkaa addon/-kansion NVDA-lisäosaksi dist/lausu-<versio>.nvda-addon.

NVDA-lisäosa on zip-tiedosto, jonka juuressa on manifest.ini. Pohjana ei käytetä
NV Accessin AddonTemplatea (SCons ja uv), koska kokeilu ei vielä tarvitse käännöksiä
eikä lokalisointia; pakkaus on tämän tiedoston verran.

Ajo: python -X utf8 build/build_addon.py
"""
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADDON = ROOT / "addon"


def main() -> None:
    manifest = (ADDON / "manifest.ini").read_text(encoding="utf-8")
    version = re.search(r"^version\s*=\s*(\S+)", manifest, re.M).group(1)
    out = ROOT / "dist" / f"lausu-{version}.nvda-addon"
    out.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in sorted(ADDON.rglob("*")):
            if f.is_file() and "__pycache__" not in f.parts:
                z.write(f, f.relative_to(ADDON).as_posix())
    print(f"{out.relative_to(ROOT)}: {out.stat().st_size / 1e6:.2f} Mt")


if __name__ == "__main__":
    main()
