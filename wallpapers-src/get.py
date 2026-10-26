import json, re, urllib.request, urllib.parse, sys
UA = {"User-Agent":"casa-tv-wallpaper-search/0.1 (personal project)"}
titles = ["File:Lower Manhattan from Jersey City November 2016 002.jpg","File:Canary Wharf from Limehouse London June 2016 HDR.jpg",
 "File:Flatirons Sunrise.jpg","File:Aerial image of Stromboli (view from the northeast).jpg","File:Charles River Esplanade Boston May 2018 panorama.jpg",
 "File:1 lake louise pano 2019.jpg"]
p = {"action":"query","format":"json","titles":"|".join(titles),"prop":"imageinfo","iiprop":"url|extmetadata","iiurlwidth":"1920"}
d = json.load(urllib.request.urlopen(urllib.request.Request("https://commons.wikimedia.org/w/api.php?"+urllib.parse.urlencode(p), headers=UA), timeout=30))
credits=[]
for i,pg in enumerate(d["query"]["pages"].values()):
    ii = pg["imageinfo"][0]; m = ii["extmetadata"]
    name = re.sub(r"[^a-z0-9]+","-",pg["title"][5:-4].lower()).strip("-")[:40]+".jpg"
    data = urllib.request.urlopen(urllib.request.Request(ii["thumburl"], headers=UA), timeout=60).read()
    open("wallpapers/"+name,"wb").write(data)
    artist = re.sub(r"<[^>]+>","",m.get("Artist",{}).get("value","?"))
    credits.append(f"- {name}: \"{pg['title'][5:]}\" by {artist}, {m.get('LicenseShortName',{}).get('value')}, {ii['descriptionurl']}")
    print(name, len(data))
open("wallpapers/CREDITS.md","w",encoding="utf-8").write("# Wallpaper credits (Wikimedia Commons, scaled to 1920 px)\n\n"+"\n".join(credits)+"\n")
