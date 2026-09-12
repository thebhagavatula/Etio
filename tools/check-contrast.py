def srgb(c):
    c = c/255.0
    return c/12.92 if c <= 0.03928 else ((c+0.055)/1.055)**2.4
def lum(hexs):
    r=int(hexs[0:2],16); g=int(hexs[2:4],16); b=int(hexs[4:6],16)
    return 0.2126*srgb(r)+0.7152*srgb(g)+0.0722*srgb(b)
def ratio(fg,bg):
    a,b = lum(fg), lum(bg)
    hi,lo = max(a,b), min(a,b)
    return (hi+0.05)/(lo+0.05)
def blend(fg,bg,alpha):
    out=""
    for i in (0,2,4):
        f=int(fg[i:i+2],16); b=int(bg[i:i+2],16)
        out += "%02X" % round(alpha*f+(1-alpha)*b)
    return out

print("=== DARK ===")
bg="0B0F14"; surf="141A21"; glass="1C242E"
for name,alpha in (("glass 72% (blurred)",0.72),("glass 92% (flat)",0.92)):
    g=blend(glass,bg,alpha)
    print(f"  {name} -> #{g}")
    for tn,tc in (("textPrimary","F2F5F8"),("textSecondary","8E9BA8"),("accent","4DA3FF"),
                  ("running","3DDC97"),("warning","FFB84D"),("delay","FF6B6B"),("safety","B794F6")):
        r=ratio(tc,g); print(f"     {tn:14s} {r:5.2f}:1 {'PASS' if r>=4.5 else 'FAIL'}")
print("  on opaque surface #141A21")
for tn,tc in (("textPrimary","F2F5F8"),("textSecondary","8E9BA8"),("accent","4DA3FF"),
              ("running","3DDC97"),("warning","FFB84D"),("delay","FF6B6B"),("safety","B794F6")):
    r=ratio(tc,surf); print(f"     {tn:14s} {r:5.2f}:1 {'PASS' if r>=4.5 else 'FAIL'}")

print("=== LIGHT ===")
lbg="F7F9FB"; lsurf="FFFFFF"
for name,alpha in (("glass 78%",0.78),("glass 92%",0.92)):
    g=blend("FFFFFF",lbg,alpha)
    print(f"  {name} -> #{g}")
    for tn,tc in (("textPrimary","0B0F14"),("textSecondary","5A6875"),("accent","0B62C4"),
                  ("running","0F7C52"),("warning","8A5A00"),("delay","C02B2B"),("safety","6B3FB5")):
        r=ratio(tc,g); print(f"     {tn:14s} {r:5.2f}:1 {'PASS' if r>=4.5 else 'FAIL'}")
