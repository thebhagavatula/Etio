"""WCAG ratios for the Etio palette, both themes, every ground a text colour lands on.

Run after ANY palette edit. On the warm, low-contrast ground this app uses, the eye
is a far worse judge than it is on white: metallic gold on beige looks like text and
measures 1.83:1. AA for body text is 4.5:1, and nothing here is large enough to claim
the 3:1 large-text exemption.

    python tools/check-contrast.py
"""


def srgb(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(hexs):
    r = int(hexs[0:2], 16); g = int(hexs[2:4], 16); b = int(hexs[4:6], 16)
    return 0.2126 * srgb(r) + 0.7152 * srgb(g) + 0.0722 * srgb(b)


def ratio(fg, bg):
    a, b = lum(fg), lum(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def blend(fg, bg, alpha):
    out = ""
    for i in (0, 2, 4):
        f = int(fg[i:i + 2], 16); b = int(bg[i:i + 2], 16)
        out += "%02X" % round(alpha * f + (1 - alpha) * b)
    return out


AA = 4.5

LIGHT_ROLES = [
    ("textPrimary", "6B5000"),    # headings and body — gold
    ("textSecondary", "7D6055"),  # everything under them — light brown
    ("accent", "8A5A1B"),
    ("running", "2F6B43"),
    ("warning", "8A5A00"),
    ("delay", "A33228"),
    ("safety", "6B4A9E"),
]

DARK_ROLES = [
    ("textPrimary", "EBC96A"),
    ("textSecondary", "C0A48C"),
    ("accent", "E5A94A"),
    ("running", "5FC98A"),
    ("warning", "F0B44A"),
    ("delay", "E8796F"),
    ("safety", "C9A6F0"),
]

failures = 0


def check(ground_name, ground, roles):
    global failures
    print(f"  on {ground_name} #{ground}")
    for name, colour in roles:
        r = ratio(colour, ground)
        ok = r >= AA
        if not ok:
            failures += 1
        print(f"     {name:14s} #{colour}  {r:5.2f}:1  {'PASS' if ok else '** FAIL **'}")


print("=== LIGHT — gold on beige ===")
lbg, lsurf, lhero = "F5EFE1", "FBF7EE", "FFFDF7"
check("beige background", lbg, LIGHT_ROLES)
check("card surface", lsurf, LIGHT_ROLES)
check("hero surface", lhero, LIGHT_ROLES)
for label, alpha in (("glass 78%", 0.78), ("glass 92%", 0.92)):
    check(f"{label} over beige", blend(lsurf, lbg, alpha), LIGHT_ROLES)

print("\n=== DARK — gold on espresso ===")
dbg, dsurf, dhero = "17120B", "211A10", "2B2216"
check("espresso background", dbg, DARK_ROLES)
check("card surface", dsurf, DARK_ROLES)
check("hero surface", dhero, DARK_ROLES)
for label, alpha in (("glass 72% (blurred)", 0.72), ("glass 92% (flat)", 0.92)):
    check(f"{label} over espresso", blend(dhero, dbg, alpha), DARK_ROLES)

print()
if failures:
    raise SystemExit(f"{failures} role/ground combination(s) below {AA}:1 — fix before shipping.")
print(f"All role/ground combinations clear {AA}:1.")
