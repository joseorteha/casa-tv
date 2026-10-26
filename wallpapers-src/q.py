import json, sys, urllib.request, urllib.parse
cat = sys.argv[1]
p = {"action":"query","format":"json","generator":"categorymembers","gcmtitle":"Category:"+cat,"gcmtype":"file","gcmlimit":"60",
     "prop":"imageinfo","iiprop":"url|size|extmetadata","iiurlwidth":"1920"}
u = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(p)
r = urllib.request.Request(u, headers={"User-Agent":"casa-tv-wallpaper-search/0.1 (personal project)"})
d = json.load(urllib.request.urlopen(r, timeout=30))
for pg in d.get("query",{}).get("pages",{}).values():
    ii = pg["imageinfo"][0]; m = ii.get("extmetadata",{})
    w,h = ii["width"], ii["height"]
    if w < 3000 or not (1.6 <= w/h <= 1.9): continue
    print(pg["title"], w, h, m.get("LicenseShortName",{}).get("value"), "|", ii["thumburl"])
