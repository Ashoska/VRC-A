"""Writes the boot cat's thinking loop (4 timing variants) and calm (Background) loop as AnimatedVectorDrawables."""
import math
HEAD="M3.92,10.46 L4.3,3 L8.2,7.1 Q12,5.1 15.8,7.1 L19.9,2.6 L20.08,10.46 A8.6,8.6 0 1,1 3.92,10.46 Z"
Y=13.6
DOTS=f"M8,{Y} a1.3,1.3 0 1,0 2.6,0 a1.3,1.3 0 1,0 -2.6,0 Z M13.4,{Y} a1.3,1.3 0 1,0 2.6,0 a1.3,1.3 0 1,0 -2.6,0 Z"
LINES=f"M8.1,{Y} h2.4 M13.5,{Y} h2.4"
CYCLE=2400
def sm(x): x=max(0,min(1,x)); return x*x*(3-2*x)
def kf(samples):  # [(fraction,value)] -> keyframes xml
    return "".join(f'<keyframe android:fraction="{f:.4f}" android:value="{v:.4f}"/>' for f,v in samples)
def holder(prop,samples,dur,delay=0,repeat=True):
    rep='android:repeatCount="infinite"' if repeat else ''
    off=f'android:startOffset="{delay}"' if delay else ''
    return (f'<objectAnimator android:duration="{dur}" {rep} {off} android:interpolator="@android:anim/linear_interpolator">'
            f'<propertyValuesHolder android:propertyName="{prop}" android:valueType="floatType">{kf(samples)}</propertyValuesHolder></objectAnimator>')
def sampled(fn,n=48):  # fn(t in 0..1) over one cycle
    return [(i/n,fn(i/n)) for i in range(n+1)]
def wrap_offset(phase,loops):  # trimPathOffset going round `loops` times per cycle from `phase`, wrapping 1 -> 0 cleanly
    pts=[(0.0,phase)]
    for k in range(1,loops+1):
        t=(k-phase)/loops
        if t<1-1e-6: pts+=[(t-1e-4,1.0),(t,0.0)]
    pts.append((1.0,1.0 if phase==0 else phase))
    return pts
def think(phase):
    ph2=(2*phase)%1
    look=lambda t: 0.9*math.sin(2*math.pi*((2*t+ph2)%1))
    def squint(t):
        p=((t+phase)%1)*72
        if 36<=p<42: return 1-0.7*sm((p-36)/6)
        if 42<=p<60: return 0.3
        if 60<=p<66: return 0.3+0.7*sm((p-60)/6)
        return 1
    def closed(t):
        p=((t+phase)%1)*72
        return 1 if 42<=p<60 else 0
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED (tools/symbol-lab/boot_cat_avd.py): the boot cat thinking while a check runs, timing variant {phase}.
     An AnimatedVectorDrawable so Android plays it on the RenderThread (keeps moving while the main thread is busy).
     A light runs round the head every 1.2 s; the eyes drift side to side; every 2.4 s they ease into a flat "hmm"
     squint and back. The light grows in over 0.4 s; after 8 s the eyes glance toward the row's label once. White,
     tinted from code. -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt">
  <aapt:attr name="android:drawable">
    <vector android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
      <path android:pathData="{HEAD}" android:strokeColor="#FFFFFFFF" android:strokeAlpha="0.25" android:strokeWidth="1.9" android:strokeLineCap="round" android:strokeLineJoin="round"/>
      <path android:name="light" android:pathData="{HEAD}" android:strokeColor="#FFFFFFFF" android:strokeAlpha="0" android:strokeWidth="2.3" android:strokeLineCap="round" android:strokeLineJoin="round" android:trimPathStart="0" android:trimPathEnd="0" android:trimPathOffset="{ph2:.4f}"/>
      <group android:name="glance">
        <group android:name="look">
          <group android:name="lid" android:pivotY="{Y}">
            <path android:name="dots" android:pathData="{DOTS}" android:fillColor="#FFFFFFFF"/>
          </group>
          <path android:name="lines" android:pathData="{LINES}" android:strokeColor="#FFFFFFFF" android:strokeAlpha="0" android:strokeWidth="1.9" android:strokeLineCap="round"/>
        </group>
      </group>
    </vector>
  </aapt:attr>
  <target android:name="light"><aapt:attr name="android:animation"><set>
    {holder("trimPathOffset",wrap_offset(ph2,2),CYCLE)}
    {holder("trimPathEnd",[(0,0),(1,0.28)],400,repeat=False)}
    {holder("strokeAlpha",[(0,0),(1,1)],400,repeat=False)}
  </set></aapt:attr></target>
  <target android:name="look"><aapt:attr name="android:animation">{holder("translateX",sampled(look),CYCLE)}</aapt:attr></target>
  <target android:name="lid"><aapt:attr name="android:animation">{holder("scaleY",sampled(squint,72),CYCLE)}</aapt:attr></target>
  <target android:name="dots"><aapt:attr name="android:animation">{holder("fillAlpha",sampled(lambda t:1-closed(t),72),CYCLE)}</aapt:attr></target>
  <target android:name="lines"><aapt:attr name="android:animation">{holder("strokeAlpha",sampled(closed,72),CYCLE)}</aapt:attr></target>
  <target android:name="glance"><aapt:attr name="android:animation"><set>
    {holder("translateX",[(0,0),(0.19,-1.5),(0.81,-1.5),(1,0)],1600,delay=8000,repeat=False)}
    {holder("translateY",[(0,0),(0.19,-0.5),(0.81,-0.5),(1,0)],1600,delay=8000,repeat=False)}
  </set></aapt:attr></target>
</animated-vector>
'''
def calm():
    HALF=f"M8.25,{Y+0.35} a1.05,1.05 0 1,0 2.1,0 a1.05,1.05 0 1,0 -2.1,0 Z M13.65,{Y+0.35} a1.05,1.05 0 1,0 2.1,0 a1.05,1.05 0 1,0 -2.1,0 Z"
    LIDS=f"M7.8,{Y-0.3} h3 M13.2,{Y-0.3} h3"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED (tools/symbol-lab/boot_cat_avd.py): the boot cat for a check still running in the BACKGROUND (boot no
     longer waits for it): half-lidded relaxed eyes drifting slowly, a fainter light at half speed. RenderThread like
     the thinking loop; white, tinted from code. -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt">
  <aapt:attr name="android:drawable">
    <vector android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
      <path android:pathData="{HEAD}" android:strokeColor="#FFFFFFFF" android:strokeAlpha="0.18" android:strokeWidth="1.9" android:strokeLineCap="round" android:strokeLineJoin="round"/>
      <path android:name="light" android:pathData="{HEAD}" android:strokeColor="#FFFFFFFF" android:strokeAlpha="0.5" android:strokeWidth="2.3" android:strokeLineCap="round" android:strokeLineJoin="round" android:trimPathStart="0" android:trimPathEnd="0.28" android:trimPathOffset="0"/>
      <group android:name="look">
        <path android:pathData="{HALF}" android:fillColor="#FFFFFFFF" android:fillAlpha="0.7"/>
        <path android:pathData="{LIDS}" android:strokeColor="#FFFFFFFF" android:strokeAlpha="0.7" android:strokeWidth="1.5" android:strokeLineCap="round"/>
      </group>
    </vector>
  </aapt:attr>
  <target android:name="light"><aapt:attr name="android:animation">{holder("trimPathOffset",[(0,0),(1,1)],CYCLE)}</aapt:attr></target>
  <target android:name="look"><aapt:attr name="android:animation">{holder("translateX",sampled(lambda t:0.5*math.sin(2*math.pi*t)),3200)}</aapt:attr></target>
</animated-vector>
'''
if __name__=="__main__":
    import sys,os
    res=sys.argv[1] if len(sys.argv)>1 else "app/src/main/res/drawable"
    for i,ph in enumerate([0,0.15,0.3,0.45]):
        open(os.path.join(res,f"boot_cat_think_{i}.xml"),"w").write(think(ph))
    open(os.path.join(res,"boot_cat_calm.xml"),"w").write(calm())
    for i,ph in enumerate([0,0.15,0.3,0.45]):
        print(i, round(0.9*math.sin(2*math.pi*((2*ph)%1)),4))
