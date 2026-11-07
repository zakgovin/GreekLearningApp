#!/usr/bin/env python3
"""Build alpha/omega monogram vector outlines from DejaVu Sans (fonttools needed)."""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
ROOT=Path(__file__).resolve().parents[1]
font=TTFont('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf');glyphs=font.getGlyphSet();cmap=font.getBestCmap()
scale=.024
widths=[font['hmtx'].metrics[cmap[ord(c)]][0]*scale for c in 'αω'];start=(108-sum(widths)+2)/2
paths=[]
for c,width,color in zip('αω',widths,['#FFFFFF','#62CBFF']):
 pen=SVGPathPen(glyphs);tp=TransformPen(pen,(scale,0,0,-scale,start,68));glyphs[cmap[ord(c)]].draw(tp)
 paths.append(f'    <path android:fillColor="{color}" android:pathData="{pen.getCommands()}" />')
 start+=width-2
prefix='<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
fg='\n'.join(paths)+'\n    <path android:fillColor="#62CBFF" android:pathData="M44,78L64,78Q65,78 65,79Q65,80 64,80L44,80Q43,80 43,79Q43,78 44,78Z" />\n'
res=ROOT/'app/src/main/res'
(res/'drawable/ic_launcher_foreground.xml').write_text(prefix+fg+'</vector>\n')
(res/'drawable/ic_launcher.xml').write_text(prefix+'    <path android:fillColor="#072E53" android:pathData="M24,0L84,0Q108,0 108,24L108,84Q108,108 84,108L24,108Q0,108 0,84L0,24Q0,0 24,0Z" />\n'+fg+'</vector>\n')
(res/'mipmap-anydpi').mkdir(exist_ok=True)
(res/'mipmap-anydpi/ic_launcher.xml').write_text((res/'drawable/ic_launcher.xml').read_text())
(res/'values/icon_colors.xml').write_text('<resources><color name="icon_background">#072E53</color></resources>\n')
(res/'mipmap-anydpi-v26').mkdir(exist_ok=True)
(res/'mipmap-anydpi-v26/ic_launcher.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/icon_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/><monochrome android:drawable="@drawable/ic_launcher_foreground"/></adaptive-icon>\n')
