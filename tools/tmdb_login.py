#!/usr/bin/env python3
"""Gets the TMDB session that lets Explore follow your profile (favorites, ratings, watchlist).

Run it in your own terminal: python3 tools/tmdb_login.py
It reads tmdb_key from config.properties, asks TMDB for a request token, waits while you approve it in the browser,
then prints the line to paste into config.properties. The session is secret: don't share it or commit it.
"""
import json
import sys
import urllib.error
import urllib.request


def key():
    try:
        for line in open("config.properties", encoding="utf-8"):
            if line.strip().startswith("tmdb_key") and "=" in line:
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return ""


def call(path, k, body=None):
    bearer = k.startswith("eyJ")
    url = "https://api.themoviedb.org/3" + path + ("" if bearer else ("&" if "?" in path else "?") + "api_key=" + k)
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None)
    req.add_header("Content-Type", "application/json")
    if bearer:
        req.add_header("Authorization", "Bearer " + k)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


def main():
    k = key()
    if not k:
        sys.exit("Put your tmdb_key in config.properties first (run ./build.sh once to create the file).")
    try:
        token = call("/authentication/token/new", k)["request_token"]
        print("\nOpen this link, sign in to TMDB and press Approve:\n\n  https://www.themoviedb.org/authenticate/" + token + "\n")
        input("When you have approved it, press Enter here... ")
        session = call("/authentication/session/new", k, {"request_token": token})["session_id"]
    except (urllib.error.URLError, KeyError) as e:
        sys.exit("TMDB didn't accept it (did you press Approve?): %s" % e)
    print("\nPaste this line into config.properties, then run ./build.sh:\n\n  tmdb_session=" + session + "\n")


if __name__ == "__main__":
    main()
