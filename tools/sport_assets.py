#!/usr/bin/env python3
"""Downloads the fonts and the background that belong to Formula 1 and MotoGP, for the app and for preview/home.html.

Run it on your own computer:  python3 tools/sport_assets.py
Needs (once):  pip3 install fonttools brotli      (for the fonts)
Optional:      pip3 install cairosvg              (turns MotoGP's SVG background into a PNG the app can use;
                                                   without it the app downloads the SVG itself when it needs it)

It writes, all git-ignored (they belong to Formula One and to MotoGP, so they stay on your machine):
  app/assets/fonts/Formula1-Black.ttf, app/assets/fonts/MGPDisplay-Bold.ttf, app/assets/sports/motogp.png
  preview/fonts/*.woff2 and preview/fonts/motogp-bar.svg      (for the HTML preview)
./build.sh packs whatever is in app/assets.
"""
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = [
    ("https://www.formula1.com/s/fonts/formula1/Formula1-Black.woff2", "Formula1-Black", "https://www.formula1.com/"),
    ("https://www.motogp.com/resources/v8.28.8/fonts/MGPDisplay-Bold.woff2", "MGPDisplay-Bold", "https://www.motogp.com/"),
]
SVG = ("https://www.motogp.com/resources/v8.28.8/i/svg-files/elements/slim-bar-background.svg", "https://www.motogp.com/")


def fetch(url, referer):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Referer": referer})
    return urllib.request.urlopen(req, timeout=30).read()


def main():
    web = os.path.join(ROOT, "preview", "fonts")
    os.makedirs(web, exist_ok=True)
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        TTFont = None
        print("fonttools/brotli missing: the preview fonts are saved, but not the app's. pip3 install fonttools brotli")
    for url, name, referer in FONTS:
        try:
            data = fetch(url, referer)
        except Exception as e:
            print("Couldn't download %s: %s" % (name, e))
            continue
        woff = os.path.join(web, name + ".woff2")
        with open(woff, "wb") as f:
            f.write(data)
        if TTFont:
            out = os.path.join(ROOT, "app", "assets", "fonts", name + ".ttf")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            font = TTFont(woff)
            font.flavor = None
            font.save(out)
            print("Saved", out)
    try:
        svg = fetch(*SVG)
        with open(os.path.join(web, "motogp-bar.svg"), "wb") as f:
            f.write(svg)
        print("Saved preview/fonts/motogp-bar.svg")
        try:
            import cairosvg
            out = os.path.join(ROOT, "app", "assets", "sports", "motogp.png")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            cairosvg.svg2png(bytestring=svg, write_to=out, output_width=1280)
            print("Saved", out)
        except ImportError:
            print("cairosvg missing: the app will download MotoGP's SVG itself (pip3 install cairosvg to bundle it).")
    except Exception as e:
        print("Couldn't download the MotoGP background: %s" % e)


if __name__ == "__main__":
    main()
