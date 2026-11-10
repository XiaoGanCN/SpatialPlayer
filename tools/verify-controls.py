#!/usr/bin/env python3
"""Real touch checks for external-file home, live scrubbing/HUD, glass sliders/switches, and GPU blur."""
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('device_ui',ROOT/'tools/verify-redesign.py')
ui=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui)
OUT=ROOT/'smoke/out/controls';OUT.mkdir(parents=True,exist_ok=True);ui.OUT=OUT
adb=ui.adb;launch=ui.launch;snapshot=ui.snapshot;tap=ui.tap;verify=ui.verify
PKG=ui.PKG

def motion(kind,x,y): adb('shell','input','motionevent',kind,str(round(x)),str(round(y)))
def focus(): return adb('shell','dumpsys','activity','activities')
def current_volume(): return int(re.search(r'- STREAM_MUSIC:.*?streamVolume:(\d+)',adb('shell','dumpsys','audio'),re.S).group(1))
def restore_volume(value):
    for _ in range(32):
        current=current_volume()
        if current==value:return
        adb('shell','input','keyevent','KEYCODE_VOLUME_UP' if current<value else 'KEYCODE_VOLUME_DOWN')
        time.sleep(.1)
    raise AssertionError('Volume did not restore')

def main():
    original_rotation=adb('shell','settings','get','system','user_rotation')
    original_accel=adb('shell','settings','get','system','accelerometer_rotation')
    original_volume=current_volume()
    original_timeout=adb('shell','settings','get','system','screen_off_timeout')
    original_source=None
    original_spatial=None
    log=(OUT/'regression-log.txt').open('w')
    stream=subprocess.Popen([ui.ADB,'logcat','-T','1','-v','brief','GlassOpticsProbe:I','GlassLensRenderer:E','AndroidRuntime:E','*:S'],stdout=log)
    try:
        adb('shell','input','keyevent','KEYCODE_WAKEUP')
        adb('shell','wm','dismiss-keyguard')
        adb('shell','settings','put','system','screen_off_timeout','1800000')
        adb('shell','settings','put','system','accelerometer_rotation','0')
        adb('shell','settings','put','system','user_rotation','0')
        adb('shell','am','force-stop',PKG);launch('MainActivity');time.sleep(1.5)
        state=snapshot('LIBRARY');original_source=state['source'];original_spatial=state['spatialSetting']
        if state['light']:
            original_light=state['light']
            adb('shell','settings','put','system','user_rotation','1');time.sleep(.8)
            rotated=snapshot('LIBRARY')
            change=sum((a-b)**2 for a,b in zip(original_light,rotated['light']))
            if max(state['planarGravity'],rotated['planarGravity'])<.5:
                verify(change<.0001,'flat phone keeps the reference stable-light fallback without sensor jitter')
            else:
                verify(len(rotated['light'])==2 and abs(sum(x*x for x in rotated['light'])-1)<.01,'sensor highlight keeps a finite unit direction across orientation changes')
            adb('shell','settings','put','system','user_rotation','0');time.sleep(.8)
            state=snapshot('LIBRARY')
        v=state['views']
        verify(v['buttonOpenFile']['width']>v['buttonLibrary']['width'] and v['buttonOpenFile']['height']>v['buttonLibrary']['height'],'external-file action is larger than each library action')
        verify(v['buttonOpenFolder']['visible'] and v['buttonOpenFolder']['bounds'][0]>v['buttonOpenFile']['bounds'][2]+10,'folder action has its own spaced, visible target')
        for button,label in [('buttonOpenFile','file'),('buttonOpenFolder','folder')]:
            tap(snapshot('LIBRARY')['views'][button]['bounds']);time.sleep(.8)
            verify('documentsui' in focus().lower(),label+' action opens the real system picker')
            adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(.5)
        if original_source!='NONE':
            tap(snapshot('LIBRARY')['views']['buttonPoweramp' if original_source=='POWERAMP' else 'buttonLibrary']['bounds'])
        state=snapshot('LIBRARY')
        verify(not state['views']['libraryBody']['visible'] and state['views']['buttonOpenFile']['visible'],'library can collapse without hiding external-file opening')
        verify(state['views']['chipStrip']['visible'],'useful display/spatial status chips remain visible')
        ui.shot('home.png')
        launch('SmokeTestActivity','--es','smoke_path','/sdcard/Movies/SpatialPlayerTest/grid_720p.mp4','--es','smoke_mime','video/mp4','--ez','smoke_muted','true')
        time.sleep(1.5)
        adb('shell','am','broadcast','-p',PKG,'-a',PKG+'.GLASS_CAPTURE','--ez','visible','true');time.sleep(.4)
        state=snapshot(controls=True)
        verify(state['views']['playerChips']['visible'],'player status chips are visible')
        bar=state['views']['seekBar']['bounds'];left,top,right,bottom=bar;y=(top+bottom)/2
        motion('DOWN',left+(right-left)*.2,y);time.sleep(.15)
        first=snapshot()
        motion('MOVE',left+(right-left)*.8,y);time.sleep(.15)
        held=snapshot()
        verify(first['seekDragging'] and held['seekDragging'] and held['seekDisplayed']-first['seekDisplayed']>.5,'timeline handle moves while the finger is still down')
        verify(held['views']['timeCurrent']['text']!=first['views']['timeCurrent']['text'],'timeline timecode follows the live scrub')
        ui.shot('timeline-held.png')
        motion('UP',left+(right-left)*.8,y);time.sleep(.7)
        released=snapshot()
        verify(not released['seekDragging'] and abs(released['positionMs']-held['seekDisplayed']*released['durationMs'])<600,'release commits the selected playback position')
        tap([int(left+(right-left)*.3)-2,int(y)-2,int(left+(right-left)*.3)+2,int(y)+2])
        state=snapshot();verify(.2<state['positionMs']/state['durationMs']<.4,'tap-to-seek commits directly')
        before=state['haptics']
        for x_fraction,label in [(.25,'Brightness'),(.75,'Volume')]:
            snapshot(controls=False);time.sleep(.4)
            state=snapshot();x=state['width']*x_fraction;y=state['height']*.52
            motion('DOWN',x,y)
            motion('MOVE',x,y-55);time.sleep(.15)
            motion('MOVE',x,y-100);time.sleep(.05)
            motion('MOVE',x,y-190);time.sleep(.12)
            live=snapshot();feedback=live['views']['seekFeedback']
            verify(feedback['visible'] and feedback['alpha']>.8 and label in feedback['text'],label+' shows the actual value before the finger lifts')
            ui.shot(label.lower()+'-held.png')
            motion('UP',x,y-190);time.sleep(.2)
        state=snapshot(controls=True);time.sleep(.3);tap(state['views']['buttonSpeed']['bounds'])
        state=snapshot();bar=state['views']['speed_slider']['bounds'];x0,y0,x1,y1=bar
        adb('shell','input','swipe',str(round(x0+(x1-x0)*.2)),str((y0+y1)//2),str(round(x0+(x1-x0)*.7)),str((y0+y1)//2),'600')
        state=snapshot();reading=float(state['views']['speed_readout']['text'].replace('×',''))
        verify(reading>1.5 and abs(reading-state['speed'])<.02,'glass speed slider updates both its reading and the actual player')
        ui.shot('speed.png')
        tap(state['views']['speed_1.0']['bounds']);tap(snapshot()['views']['sheet_close']['bounds'])
        state=snapshot(controls=True);time.sleep(.3);tap(state['views']['buttonBack']['bounds'])
        state=snapshot('LIBRARY');tap(state['views']['buttonSettings']['bounds'])
        switches=[n for n in ui.nodes() if n.get('class')=='android.widget.Switch']
        switch=switches[0];original_checked=switch.get('checked');bounds=list(map(int,re.findall(r'\d+',switch.get('bounds'))))
        tap(bounds)
        current=[n for n in ui.nodes() if n.get('class')=='android.widget.Switch'][0]
        verify(current.get('checked')!=original_checked,'new glass switch toggles the real setting')
        tap(bounds)
        current=[n for n in ui.nodes() if n.get('class')=='android.widget.Switch'][0]
        verify(current.get('checked')==original_checked,'glass switch restores the original setting')
        x=(bounds[0]+bounds[2])/2;y=(bounds[1]+bounds[3])/2
        motion('DOWN',x,y);time.sleep(.3);ui.shot('switch-held.png');motion('UP',x,y)
        tap(bounds) # restore after the held press also toggled it
        state=snapshot('LIBRARY')
        if state['hapticsEnabled']: verify(state['haptics']>before+6,'buttons, timeline, sliders and switch dispatch system haptics')
        adb('shell','input','keyevent','KEYCODE_BACK')
        launch('GlassOpticsProbeActivity');time.sleep(2)
        log.flush();report=(OUT/'regression-log.txt').read_text()
        verify('PASS: GPU Gaussian blur measured' in report,'actual GPU blur suppresses high-frequency backdrop detail')
        verify('FATAL EXCEPTION' not in report and 'Lens AGSL compile failed' not in report,'no touch crash or shader fallback during real interactions')
        print(f'PASS: {ui.checks} control checks',flush=True)
    finally:
        try: motion('UP',1,1)
        except subprocess.CalledProcessError: pass
        adb('shell','am','force-stop',PKG);launch('MainActivity');time.sleep(.5)
        if original_source is not None:
            try:snapshot('LIBRARY',restore_source=original_source,restore_spatial=original_spatial)
            except AssertionError:print('NOTE: foreground snapshot unavailable during cleanup',flush=True)
        adb('shell','settings','put','system','user_rotation',original_rotation)
        adb('shell','settings','put','system','accelerometer_rotation',original_accel)
        adb('shell','settings','put','system','screen_off_timeout',original_timeout)
        restore_volume(original_volume)
        stream.terminate();stream.wait();log.close()

if __name__=='__main__':main()
