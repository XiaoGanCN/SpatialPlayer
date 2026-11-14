#!/usr/bin/env python3
"""Live source pixels, full-track drags, endpoint holds, refresh votes and stable drawer navigation."""
import importlib.util,json,time
from pathlib import Path
from png_reader import Png
ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'tools'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
ui=module('ui','verify-redesign.py');control=module('control','verify-controls.py')
OUT=ROOT/'smoke/out/glass-followup';OUT.mkdir(parents=True,exist_ok=True);ui.OUT=OUT
adb=ui.adb;snap=ui.snapshot;tap=ui.tap;verify=ui.verify;launch=ui.launch

def motion(kind,x,y):adb('shell','input','motionevent',kind,str(round(x)),str(round(y)))
def home():adb('shell','am','force-stop',ui.PKG);launch('MainActivity');time.sleep(.4)
def main():
 import subprocess
 fixture=ROOT/'smoke/media/glass_60fps.mp4'
 if not fixture.exists():subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=size=640x360:rate=60','-t','20','-an','-c:v','libx264','-preset','fast','-crf','25',str(fixture)],check=True)
 adb('push',str(fixture),'/sdcard/Movies/SpatialPlayerTest/glass_60fps.mp4')
 original={k:adb('shell','settings','get','system',k) for k in ['screen_off_timeout','user_rotation','accelerometer_rotation']};jump=None
 try:
  adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
  adb('shell','settings','put','system','screen_off_timeout','1800000');adb('shell','settings','put','system','accelerometer_rotation','0');adb('shell','settings','put','system','user_rotation','0')
  home();s=snap('LIBRARY');jump=s['skipMs'];verify(s['uiRefreshRate']>=119,'UI window explicitly requests 120Hz on the reference device')
  (OUT/'refresh.json').write_text(json.dumps({k:s[k] for k in ['uiRefreshRate','displayRefreshRate']}))
  # Hold a button without a long-click action; Open would launch the folder picker.
  bx0,by0,bx1,by1=s['views']['buttonLibrary']['bounds'];cx=(bx0+bx1)//2;cy=(by0+by1)//2
  motion('DOWN',cx,cy);time.sleep(.3);ui.shot('button-held.png')
  image=Png(OUT/'button-held.png');expanded=image.rgb(cx,by0-2);outside=image.rgb(cx,by0-15)
  verify(sum(expanded)-sum(outside)>30,'held button spring expands beyond its original bounds without clipping')
  motion('MOVE',1,1);motion('UP',1,1)
  launch('LiveGlassProbeActivity');time.sleep(2)
  report=adb('logcat','-d','-s','LiveGlassProbe:I');(OUT/'live-source.log').write_text(report)
  verify('PASS: four live source flips' in report and 'FAIL:' not in report,'actual cached GPU glass follows four source changes')
  home();tap(snap('LIBRARY')['views']['buttonSettings']['bounds']);s=snap('SETTINGS')
  slider=next(n for n in s['nodes'] if n['kind']=='GlassSlider');x0,y0,x1,y1=slider['bounds'];d=s['width']/418
  left=x0+32*d;right=x1-32*d;y=(y0+y1)/2
  motion('DOWN',left+(right-left)*.65,y);time.sleep(.15);a=snap('SETTINGS');p=next(n for n in a['nodes'] if n['path']==slider['path'])
  verify(p['engaged'] and p['progress']>slider['max']*.5,'touching the bar changes the real value before release')
  motion('MOVE',left+(right-left)*.85,y);time.sleep(.15);b=snap('SETTINGS');q=next(n for n in b['nodes'] if n['path']==slider['path'])
  verify(q['engaged'] and q['progress']>p['progress'],'dragging the bar moves the handle and reading while held')
  ui.shot('bar-drag-held.png');motion('UP',left+(right-left)*.85,y)
  for name,x in [('left',left),('right',right)]:
   motion('DOWN',x,y);time.sleep(.35);state=snap('SETTINGS');head=next(n for n in state['nodes'] if n['path']==slider['path']);ui.shot('slider-'+name+'-held.png')
   verify(head['engaged'] and head['progress']==(0 if name=='left' else head['max']),name+' endpoint is reachable with its full held glass handle')
   motion('UP',x,y)
  snap('SETTINGS',restore_jump=str(jump));adb('shell','input','keyevent','KEYCODE_BACK')
  launch('SmokeTestActivity','--es','smoke_path','/sdcard/Movies/SpatialPlayerTest/glass_60fps.mp4','--es','smoke_mime','video/mp4','--ez','smoke_muted','true');time.sleep(.8)
  a=snap(controls=True);start=time.monotonic();time.sleep(1.2);b=snap(controls=True);elapsed=time.monotonic()-start;rate=(b['videoPreviewFrames']-a['videoPreviewFrames'])/elapsed
  (OUT/'preview-rate.json').write_text(json.dumps({'copiesPerSecond':rate,'state':b['videoBackdropState']}));verify(rate>35,'video glass preview exceeds its former 30Hz cap')
  tap(b['views']['buttonMore']['bounds']);s=snap();identity=s['sheetWindow'];bottom=next(n for n in s['nodes'] if n['path']=='sheet')['bounds'][3]
  verify(identity!=0,'playback drawer has a live window')
  for index,panel in [(0,'SPEED'),(1,'SCALING'),(2,'SUBTITLES')]:
   tap(s['views']['overflow_action_'+str(index)]['bounds']);s=snap(include_text=True)
   verify(s['panel']==panel and s['sheetWindow']==identity,'drawer to '+panel+' retains its window and glass shell')
   verify(not s['views']['controlGlass']['visible'] and abs(next(n for n in s['nodes'] if n['path']=='sheet')['bounds'][3]-bottom)<=1,panel+' keeps its bottom anchor and player chrome hidden')
   if panel=='SCALING':verify(any(n['selected'] and n['tint']!=0 for n in s['nodes'] if n['path'].startswith('sheet')) and not any(n.get('text')=='✓' for n in s['nodes'] if n['path'].startswith('sheet')),'selected drawer options use tint without checkmarks')
   tap(s['views']['sheet_back']['bounds']);s=snap();verify(s['sheetWindow']==identity and s['overflowOpen'],panel+' Back reuses the same drawer window')
  for rotation in [0,1]:
   adb('shell','settings','put','system','user_rotation',str(rotation));time.sleep(.6);s=snap()
   fade=next(n for n in s['nodes'] if n['path'].startswith('sheet') and n['kind']=='TopFadeBlurView');scroll=next(n for n in s['nodes'] if n['path'].startswith('sheet') and n['kind']=='ScrollView')
   verify(abs(fade['solidHeight']-scroll['paddingTop'])<1 and fade['height']>scroll['paddingTop']+40,'drawer fade covers its header and overlaps content in rotation '+str(rotation));ui.shot('drawer-'+str(rotation)+'.png')
  print(f'PASS: {ui.checks} glass follow-up checks',flush=True)
 finally:
  try:motion('UP',1,1)
  except Exception:pass
  home()
  if jump is not None:snap('LIBRARY',restore_jump=str(jump))
  for k,v in original.items():adb('shell','settings','put','system',k,v)
if __name__=='__main__':main()
