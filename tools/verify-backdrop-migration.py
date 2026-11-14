#!/usr/bin/env python3
"""Production Backdrop, quiet gestures, safe adaptive drawers, glow, and stable fade pixels."""
import importlib.util
import json
from pathlib import Path
import subprocess
import time
from statistics import mean
from png_reader import Png
ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'tools'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
ui=module('ui','verify-redesign.py');control=module('control','verify-controls.py')
OUT=ROOT/'smoke/out/backdrop-migration';OUT.mkdir(parents=True,exist_ok=True);ui.OUT=OUT
adb=ui.adb;snap=ui.snapshot;tap=ui.tap;verify=ui.verify;launch=ui.launch

def motion(kind,x,y):adb('shell','input','motionevent',kind,str(round(x)),str(round(y)))
def home():adb('shell','am','force-stop',ui.PKG);launch('MainActivity');time.sleep(.8)
def main():
    original={k:adb('shell','settings','get','system',k) for k in ['screen_off_timeout','user_rotation','accelerometer_rotation']}
    volume=control.current_volume();ambient=None;spatial=None
    log=(OUT/'runtime.log').open('w');stream=subprocess.Popen([ui.ADB,'logcat','-T','1','-v','brief','AndroidRuntime:E','*:S'],stdout=log)
    try:
        adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
        adb('shell','settings','put','system','screen_off_timeout','1800000')
        adb('shell','settings','put','system','accelerometer_rotation','0');adb('shell','settings','put','system','user_rotation','0')
        home();s=snap('LIBRARY');ambient=s['ambientSetting'];spatial=s['spatialSetting'];verify(s['backdropRecords']>0,'production surfaces execute the real Backdrop drawing API')
        time.sleep(.7);first=snap('LIBRARY');time.sleep(.4);second=snap('LIBRARY')
        # Sensor motion is allowed to redraw. A stable desk should not have an invalidation loop.
        if sum((a-b)**2 for a,b in zip(first['light'],second['light']))<.0001:
            verify(second['backdropRecords']-first['backdropRecords']<25,'idle native/Compose bridge does not continuously redraw')
        tap(s['views']['buttonLibrary']['bounds']);s=snap('LIBRARY');v=s['views']
        verify(v['collectionSummary']['bounds'][1]>=v['fileList']['bounds'][1] and v['listTopFade']['bounds'][1]==v['fileList']['bounds'][1],'media count and refresh overlay the list instead of reserving a black slab')
        before=s['haptics'];x=str(s['width']//2);adb('shell','input','swipe',x,'1800',x,'850','400');s=snap('LIBRARY')
        verify(s['haptics']==before,'scrolling the media list is silent')
        ui.shot('library.png');tap(s['views']['buttonSettings']['bounds']);time.sleep(.6);s=snap('SETTINGS')
        verify(sum(n['kind']=='GlassSwitch' for n in s['nodes'])>=3 and sum(n['kind']=='GlassSlider' for n in s['nodes'])>=3,'production settings retain native accessible switch/slider contracts')
        switch=next(n for n in s['nodes'] if n['kind']=='GlassSwitch');b=switch['bounds'];density=s['width']/418
        x=b[0]+density*(13 if spatial else 67);y=(b[1]+b[3])/2
        tap([x,y,x,y]);changed=snap('SETTINGS')
        verify(changed['spatialSetting']!=spatial,'tapping the exposed switch track changes the real setting')
        x=b[0]+density*(67 if spatial else 13);tap([x,y,x,y]);s=snap('SETTINGS')
        verify(s['spatialSetting']==spatial,'the opposite track end restores the real switch setting')
        ui.shot('settings.png');adb('shell','input','keyevent','KEYCODE_BACK')
        launch('SmokeTestActivity','--es','smoke_path','/sdcard/Movies/SpatialPlayerTest/grid_720p.mp4','--es','smoke_mime','video/mp4','--ez','smoke_muted','true');time.sleep(1.2)
        s=snap(controls=True);v=s['views']
        verify(v['buttonPlayPause']['bounds'][0]-v['buttonRewind']['bounds'][2]>=16 and v['buttonForward']['bounds'][0]-v['buttonPlayPause']['bounds'][2]>=16,'transport circles have distinct gaps on both sides of Play')
        before=s['haptics'];x=s['width']*.5;y=s['height']*.35
        adb('shell','input','tap',str(int(x)),str(int(y)));time.sleep(.5);s=snap()
        verify(not s['views']['controlGlass']['visible'] and s['haptics']==before,'hiding playback controls is silent')
        s=snap(controls=True);tap(s['views']['buttonMore']['bounds']);s=snap()
        root=next(n for n in s['nodes'] if n['path']=='sheet');scroll=next(n for n in s['nodes'] if n['path'].startswith('sheet') and n['kind']=='ScrollView')
        verify(root['clip'] and scroll['scrollRange']==0,'portrait command drawer fits its whole list without tiny unnecessary scrolling')
        verify(any(n['kind']=='TopFadeBlurView' and n['frames']>0 for n in s['nodes'] if n['path'].startswith('sheet')),'drawer header uses live progressive blur')
        ui.shot('drawer-portrait.png')
        # Rotate with the same dialog open, and test the visible viewport, not offscreen row layouts.
        adb('shell','settings','put','system','user_rotation','1');time.sleep(.8);s=snap()
        root=next(n for n in s['nodes'] if n['path']=='sheet');x0,y0,x1,y1=root['bounds']
        verify(root['clip'] and 0<=x0<x1<=s['width'] and 0<=y0<y1<=s['height'],'open drawer resizes and clips inside the landscape viewport')
        ui.shot('drawer-landscape.png');tap(s['views']['sheet_close']['bounds'])
        for panel,index in [('SPEED',0),('SCALING',1),('SUBTITLES',2)]:
            s=snap(controls=True);tap(s['views']['buttonMore']['bounds']);s=snap();tap(s['views']['overflow_action_'+str(index)]['bounds']);time.sleep(.3);s=snap()
            root=next(n for n in s['nodes'] if n['path']=='sheet')
            verify(s['panel']==panel and root['bounds'][3]<=s['height'] and root['clip'],panel+' stays within its landscape shell')
            verify(any(n['kind']=='TopFadeBlurView' for n in s['nodes'] if n['path'].startswith('sheet')),panel+' has a progressive header')
            ui.shot(panel.lower()+'-landscape.png');tap(s['views']['sheet_close']['bounds'])
        adb('shell','settings','put','system','user_rotation','0');time.sleep(.6)
        # A live-resized native slider must use the new width and retain sub-step motion.
        s=snap(controls=True);tap(s['views']['buttonSpeed']['bounds'])
        def reset_speed():
            state=snap();tap(state['views']['speed_1.0']['bounds']);time.sleep(.2);return snap()
        def slider_geometry(state):
            box=state['views']['speed_slider']['bounds'];d=state['width']/418 if state['width']<state['height'] else state['height']/418
            left=box[0]+32*d;right=box[2]-32*d
            x=left+(right-left)*(.75/2.75)
            return x,(box[1]+box[3])/2,right-left
        state=reset_speed();x,y,width=slider_geometry(state)
        adb('shell','input','swipe',str(round(x)),str(round(y)),str(round(x+width*.2)),str(round(y)),'600');time.sleep(.3)
        portrait=snap()['speed'];verify(abs(portrait-1.55)<.08,'portrait slider honors an exact fractional drag')
        adb('shell','settings','put','system','user_rotation','1');time.sleep(.7)
        state=reset_speed();x,y,width=slider_geometry(state)
        adb('shell','input','swipe',str(round(x)),str(round(y)),str(round(x+width*.2)),str(round(y)),'600');time.sleep(.3)
        landscape=snap()['speed'];verify(abs(landscape-portrait)<.08,'live rotation preserves fractional slider drag sensitivity')
        state=reset_speed();x,y,width=slider_geometry(state)
        motion('DOWN',x,y)
        for i in range(1,21):motion('MOVE',x+i*3,y)
        motion('UP',x+60,y);time.sleep(.3)
        expected=round((1+60/width*2.75)/.05)*.05
        verify(abs(snap()['speed']-expected)<.026 and expected>1.05,'slow sub-step movement accumulates into the expected snapped value')
        reset_speed();tap(snap()['views']['sheet_close']['bounds'])
        adb('shell','settings','put','system','user_rotation','0');time.sleep(.6)
        s=snap(controls=False,restore_ambient=True);updates=s['ambientUpdates'];time.sleep(1.2);s=snap()
        verify(s['ambientVisible'] and s['ambientUpdates']>updates,'ambient glow receives live shared-preview colors with controls hidden')
        ui.shot('ambient-on.png');s=snap(restore_ambient=False);verify(not s['ambientVisible'],'ambient toggle immediately removes the letterbox wash')
        ui.shot('ambient-off.png');snap(restore_ambient=ambient)
        # A stable heavily blurred strip should not flash when the high-frequency source flips.
        home();launch('TopFadeProbeActivity');time.sleep(.5);s=snap('SETTINGS');fade=next(n for n in s['nodes'] if n['kind']=='TopFadeBlurView');x0,y0,x1,y1=fade['bounds'];values=[]
        for i in range(6):
            snap('SETTINGS',mutate_backdrop=True,redraw_fade=True);time.sleep(.06);ui.shot('fade-'+str(i)+'.png');img=Png(OUT/('fade-'+str(i)+'.png'))
            values.append(mean(img.luma(x,y0+2) for x in range(x0+90,x1-90)))
        (OUT/'fade-edge.json').write_text(json.dumps(values));verify(max(values)-min(values)<2,'top blur edge remains stable across repeated source changes')
        log.flush();verify('FATAL EXCEPTION' not in (OUT/'runtime.log').read_text(),'no crash during migration interactions, dialog rotation or fade redraw')
        print(f'PASS: {ui.checks} production Backdrop checks',flush=True)
    finally:
        try:motion('UP',1,1)
        except subprocess.CalledProcessError:pass
        home()
        if ambient is not None:snap('LIBRARY',restore_ambient=ambient,restore_spatial=spatial)
        for k,v in original.items():adb('shell','settings','put','system',k,v)
        control.restore_volume(volume);stream.terminate();stream.wait();log.close()
if __name__=='__main__':main()
