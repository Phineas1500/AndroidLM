#!/usr/bin/env python3
"""Cuts sample_places.db, the test fixture, out of a full places.db: the places inside a few
boxes (with their travel-guide listings) and all of the cities, names and categories.

    python scripts/make_places_sample.py places.db sample_places.db
"""
import os
import sqlite3
import sys

BOXES = {
    "Buenos Aires": (-34.8, -58.6, -34.5, -58.3),
    "London": (51.3, -0.5, 51.7, 0.3),
    "Paris, Texas": (33.6, -95.6, 33.7, -95.5),
}


def main():
    src, out = sys.argv[1], sys.argv[2]
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.execute("attach database ? as src", (src,))
    for (sql,) in db.execute("select sql from src.sqlite_master where type = 'table' and sql is not null").fetchall():
        db.execute(sql)
    for t in ("meta", "kinds", "cities", "city_names", "countries", "region_names"):
        db.execute(f"insert into main.{t} select * from src.{t}")
    where = " or ".join(f"(lat5 between {round(s * 1e5)} and {round(n * 1e5)} and lon5 between {round(w * 1e5)} and {round(e * 1e5)})"
                        for s, w, n, e in BOXES.values())
    db.execute(f"insert into main.places select * from src.places where {where} order by id")
    db.execute("insert into main.guide select g.* from src.guide g where g.place in (select id from main.places) order by g.rowid")
    for (sql,) in db.execute("select sql from src.sqlite_master where type = 'index' and sql is not null").fetchall():
        db.execute(sql)
    db.commit()
    n = db.execute("select count(*) from places").fetchone()[0]
    db.execute("detach database src")
    db.execute("vacuum")
    db.close()
    print(f"wrote {out}: {n} places, {os.path.getsize(out) // 1_000_000} MB")


if __name__ == "__main__":
    main()
