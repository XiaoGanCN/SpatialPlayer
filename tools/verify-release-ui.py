#!/usr/bin/env python3
"""Screen-space lens sampling, time-based seeking and warm drawer navigation on the real phone."""
import importlib.util,json,time,re,subprocess
from pathlib import Path
from png_reader import Png
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('ui',ROOT/'tools/verify-redesign.py');u=importlib.util.module_from_spec(spec);spec.loader.exec_module(u)
OUT=ROOT/'smoke/out/release-020';u.OUT=OUT;a=u.adb;snap=u.snapshot;tap=u.tap;check=u.verify

def motion(action,x,y):a('shell','input','motionevent',action,str(round(x)),str(round(y)))
def launch(path,mime='video/mp4'):
 u.launch('SmokeTestActivity','--es','smoke_path',path,'--es','smoke_mime',mime,'--ez','smoke_muted','true');time.sleep(1)
def text(s,label):return next(n for n in s['nodes'] if n.get('text')==label and n['path'].startswith('sheet'))
def main():
 old={k:a('shell','settings','get','system',k) for k in ['user_rotation','accelerometer_rotation']};prefs=None
 try:
  a('shell','settings','put','system','accelerometer_rotation','0');a('shell','settings','put','system','user_rotation','0')
  a('shell','am','force-stop',u.PKG);u.launch('MainActivity');s=snap('LIBRARY');prefs={k:s[k] for k in ['spatialSetting','upmixSettings','ambientSetting','skipMs']}
  snap('LIBRARY',restore_spatial=False)
  u.launch('ScreenSpaceGlassProbeActivity');time.sleep(.8);s=snap('SETTINGS');b=next(n for n in s['nodes'] if n.get('tag')=='sampling_probe_button')['bounds'];cx=(b[0]+b[2])//2;cy=(b[1]+b[3])//2
  u.shot('sample-rest.png');motion('DOWN',cx,cy);time.sleep(.35);u.shot('sample-held.png');motion('MOVE',1,1);motion('UP',1,1)
  rest=Png(OUT/'sample-rest.png');held=Png(OUT/'sample-held.png');stripe=round(32*s['width']/418)
  # Locate broad stripe transitions away from the edge lens; stretching moves them several pixels.
  offsets=[]
  for boundary in [cx-150,cx+150]:
   lo=boundary-25;hi=boundary+25
   def edge(img):return max(range(lo,hi),key=lambda x:abs(img.luma(x+2,cy)-img.luma(x-2,cy)))
   offsets.append(abs(edge(rest)-edge(held)))
  (OUT/'sample-offsets.json').write_text(json.dumps(offsets));check(max(offsets)<=2,'held lens grows while physical backdrop boundaries remain stationary')
  moving=u.moving_fixture();long=ROOT/'smoke/media/gesture_80s.mp4'
  if not long.exists():subprocess.run(['ffmpeg','-v','error','-stream_loop','3','-i',str(ROOT/'smoke/media/glass_60fps.mp4'),'-c','copy','-t','80',str(long)],check=True)
  a('push',str(long),'/sdcard/Movies/SpatialPlayerTest/gesture_80s.mp4')
  offsets=[]
  for path in [moving,'/sdcard/Movies/SpatialPlayerTest/gesture_80s.mp4']:
   launch(path);s=snap(controls=True);x=s['width']*.25;y=s['height']*.5
   motion('DOWN',x,y);motion('MOVE',x+160,y);motion('MOVE',x+350,y);s=snap()
   hud=s['views']['seekFeedback'];check(hud['visible'] and hud['alpha']>.9 and '·' in hud['text'],'surface seeking shows live offset and target time before release')
   offsets.append(int(re.search(r'(\d+)s',hud['text'])[1]));u.shot('seek-'+str(s['durationMs'])+'.png');motion('UP',x+350,y)
  check(offsets[0]==offsets[1] and 5<=offsets[0]<=9,'identical swipe seeks the same actual time in short and long files')
  launch('/sdcard/Movies/SpatialPlayerTest/spiderverse_dv7_truehd_30s.mkv','video/x-matroska');s=snap(controls=True);tap(s['views']['buttonMore']['bounds']);s=snap()
  window=s['sheetWindow']
  for idx,panel in [(0,'SPEED'),(1,'SCALING'),(2,'SUBTITLES'),(4,'AUDIO'),(5,'PLAYBACK')]:
   tap(s['views']['overflow_action_'+str(idx)]['bounds']);s=snap(include_text=True)
   check(s['panel']==panel and s['sheetWindow']==window,panel+' retains its original drawer window')
   check(all(n['fallbacks']==0 for n in s['nodes'] if n['path'].startswith('sheet/0') and n['frames']>0),panel+' publishes warmed materials without fallback paint')
   if panel=='SCALING':check(not any(n.get('text') in ['Ambient glow','Double-tap jump length'] for n in s['nodes'] if n['path'].startswith('sheet')),'Scaling contains only picture geometry')
   if panel=='SUBTITLES':
    check(sum(n['frames']>0 for n in s['nodes'] if n['path'].startswith('sheet'))<24,'subtitles open with a bounded first batch of glass rows')
    more=next(n for n in s['nodes'] if n['path'].startswith('sheet') and (n.get('text') or '').startswith('More tracks'))
    scroll=next(n for n in s['nodes'] if n['path'].startswith('sheet') and n['kind']=='ScrollView')
    if more['bounds'][3]>scroll['bounds'][3]-30:
     x=scroll['bounds'][0]+30;a('shell','input','swipe',str(x),str(scroll['bounds'][3]-60),str(x),str(scroll['bounds'][1]+scroll['paddingTop']+80),'450');time.sleep(.8);s=snap(include_text=True)
     more=next(n for n in s['nodes'] if n['path'].startswith('sheet') and (n.get('text') or '').startswith('More tracks'))
    before=next(n['scrollY'] for n in s['nodes'] if n['path'].startswith('sheet') and n['kind']=='ScrollView')
    tap(more['bounds']);s=snap(include_text=True)
    after=next(n['scrollY'] for n in s['nodes'] if n['path'].startswith('sheet') and n['kind']=='ScrollView')
    check(after>=before-2,'loading more subtitle tracks preserves the browsing position')
   if panel=='AUDIO':
    identity=s['playerIdentity'];rendered=s['rendered']
    for name in ['Spatial audio','Ask the system not to spatialise','Spatial audio']:
     tap(text(s,name)['bounds']);s=snap(include_text=True)
    check(s['playerIdentity']==identity and s['rendered']>=rendered,'native surround spatial choices preserve the video decoder/player')
    check(s['disabledUpmixBypass'],'disabled upmix remains bypassed when a future input is stereo')
   if panel=='PLAYBACK':check(any(n.get('text')=='Ambient glow' for n in s['nodes'] if n['path'].startswith('sheet')),'general Playback owns ambient and seek-step settings')
   u.shot('drawer-'+panel.lower()+'.png');tap(s['views']['sheet_back']['bounds']);s=snap()
  s=snap(rapid_panels=True);check(s['publishedRows']>0,'rapid submenu replacements retain a visible published page')
  time.sleep(.4);s=snap();check(s['panel']=='SPEED' and s['publishedRows']>0,'the newest rapid navigation request publishes its complete page')
  print(f'PASS: {u.checks} release UI checks',flush=True)
 finally:
  try:motion('UP',1,1)
  except Exception:pass
  a('shell','am','force-stop',u.PKG);u.launch('MainActivity')
  if prefs:snap('LIBRARY',restore_spatial=prefs['spatialSetting'],restore_upmix=json.dumps(prefs['upmixSettings']),restore_ambient=prefs['ambientSetting'],restore_jump=str(prefs['skipMs']))
  for k,v in old.items():a('shell','settings','put','system',k,v)
if __name__=='__main__':main()
