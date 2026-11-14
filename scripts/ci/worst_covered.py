import csv
import sys

path = sys.argv[1]
min_lines = int(sys.argv[2]) if len(sys.argv) > 2 else 40

rows = list(csv.DictReader(open(path, encoding="utf-8")))
out = []
for r in rows:
    lm = int(r["LINE_MISSED"])
    lc = int(r["LINE_COVERED"])
    total = lm + lc
    if total >= min_lines:
        out.append((lm, total, r["CLASS"], lm / total))
out.sort(reverse=True)

print("miss   tot   cov  class")
for lm, total, cls, ratio in out[:25]:
    print("%5d %5d %5.0f%%  %s" % (lm, total, (1 - ratio) * 100, cls))
print()
print("classes with >=%d lines: %d" % (min_lines, len(out)))
