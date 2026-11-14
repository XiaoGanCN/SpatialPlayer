#!/usr/bin/env python3
"""Held input, bounded settings scrolling, nested glass redraw and player-menu regressions."""
import importlib.util
from pathlib import Path
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/file)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
ui=module('ui','verify-redesign.py')
control=module('control','verify-controls.py')
OUT=ROOT/'smoke/out/polish';OUT.mkdir(parents=True,exist_ok=True);ui.OUT=OUT
adb=ui.adb;snap=ui.snapshot;tap=ui.tap;verify=ui.verify;launch=ui.launch

def motion(kind,x,y):adb('shell','input','motionevent',kind,str(round(x)),str(round(y)))
def main():
    old_timeout=adb('shell','settings','get','system','screen_off_timeout')
    old_volume=control.current_volume();jump=None
    log=(OUT/'runtime.log').open('w')
    stream=subprocess.Popen([ui.ADB,'logcat','-T','1','-v','brief','AndroidRuntime:E','GlassLensRenderer:E','*:S'],stdout=log)
    try:
        adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
        adb('shell','settings','put','system','screen_off_timeout','1800000')
        adb('shell','am','force-stop',ui.PKG);launch('MainActivity');time.sleep(1)
        state=snap('LIBRARY');jump=state['skipMs']
        verify(state['source']=='NONE' and not state['views']['libraryBody']['visible'],'both collections are collapsed on launch')
        verify(state['views']['buttonOpenFile']['height']<160 and 'buttonOpenFolder' not in state['views'],'compact home keeps folder management out of the primary actions')
        tap(state['views']['buttonLibrary']['bounds']);state=snap('LIBRARY')
        verify('buttonOpenFolder' not in state['views'],'Videos no longer adds a folder button')
        adb('shell','am','force-stop',ui.PKG);launch('MainActivity');time.sleep(.7)
        state=snap('LIBRARY');verify(state['source']=='NONE','an expanded collection is not restored on a new launch')
        ui.shot('home.png');tap(state['views']['buttonSettings']['bounds']);time.sleep(.5)
        state=snap('SETTINGS');slider=next(n for n in state['nodes'] if n['kind']=='GlassSlider')
        x0,y0,x1,y1=slider['bounds'];density=state['width']/418
        left=x0+32*density;right=x1-32*density;x=left+(right-left)*slider['progress']/slider['max'];y=(y0+y1)/2
        motion('DOWN',x,y);time.sleep(.35);held=snap('SETTINGS');head=next(n for n in held['nodes'] if n['path']==slider['path'])
        verify(head['engaged'] and head['progress']==slider['progress'],'a stationary snapping-slider press becomes glass without changing its value')
        if held['hapticsEnabled']:verify(held['lastHaptic']==3,'press requests KEYBOARD_PRESS')
        ui.shot('slider-held.png')
        move=min(right,x+max(50,(right-left)/slider['max']*3));motion('MOVE',move,y);time.sleep(.1);moved=snap('SETTINGS')
        if moved['hapticsEnabled']:verify(moved['lastHaptic']==26,'slider changes request SEGMENT_TICK')
        motion('UP',move,y);state=snap('SETTINGS')
        if state['hapticsEnabled']:verify(state['lastHaptic']==7,'release requests KEYBOARD_RELEASE')
        snap('SETTINGS',restore_jump=str(jump))
        baseline=snap('SETTINGS');rows={n['path']:n for n in baseline['nodes'] if str(n.get('tag','')).startswith('settings_row:')}
        for _ in range(10):adb('shell','input','swipe','24','650','24','2100','180')
        motion('DOWN',24,650);motion('MOVE',24,2400);time.sleep(.2)
        stress=snap('SETTINGS');ui.shot('settings-held-pull.png');motion('UP',24,2400)
        after={n['path']:n for n in stress['nodes'] if n['path'] in rows}
        verify(all(n['height']==rows[p]['height'] and n['width']==rows[p]['width'] and n['scaleY']==1 and n['translationY']==0 for p,n in after.items()),'repeated downward pulls do not stretch or translate settings cards')
        verify(next(n for n in stress['nodes'] if n['kind']=='ScrollView')['scrollY']==0,'settings stop at the top boundary')
        outer=next(n for n in stress['nodes'] if n.get('tag')=='settings_row:Decoder')
        snap('SETTINGS',refresh_glass=True);time.sleep(.2);fresh=snap('SETTINGS')
        verify(next(n for n in fresh['nodes'] if n['path']==outer['path'])['frames']>outer['frames'],'outer decoder glass continues GPU redraw after its nested controls draw')
        adb('shell','input','keyevent','KEYCODE_BACK')
        launch('SmokeTestActivity','--es','smoke_path','/sdcard/Movies/SpatialPlayerTest/grid_720p.mp4','--es','smoke_mime','video/mp4','--ez','smoke_muted','true');time.sleep(1)
        adb('shell','am','broadcast','-p',ui.PKG,'-a',ui.PKG+'.GLASS_CAPTURE','--ez','visible','true');time.sleep(.3)
        state=snap(controls=True)
        for name in ['buttonRewind','buttonForward']:
            b=state['views'][name]['bounds'];node=next(n for n in state['nodes'] if n['bounds']==b and n['frames']>0)
            verify(abs(node['radius']-node['height']/2)<1,name+' has a true circular glass background')
        b=state['views']['buttonRewind']['bounds'];x=(b[0]+b[2])/2;y=(b[1]+b[3])/2
        motion('DOWN',x,y);time.sleep(.1);pressed=snap()
        motion('UP',x,y);released=snap()
        if released['hapticsEnabled']:verify(released['lastHaptic']==7 and released['haptics']==pressed['haptics']+1,'seek button release dispatches exactly one KEYBOARD_RELEASE')
        if not released['playerPlaying']:tap(released['views']['buttonPlayPause']['bounds'])
        tap(state['views']['buttonMore']['bounds']);time.sleep(.4);state=snap()
        verify(state['overflowOpen'] and any(n['path']=='sheet' and n['frames']>0 for n in state['nodes']),'player drawer uses the real shared glass shell')
        frames=state['videoPreviewFrames'];time.sleep(.5);verify(snap()['videoPreviewFrames']>frames,'video backdrop keeps refreshing while player chrome is hidden behind the drawer')
        for index,panel in [(0,'SPEED'),(1,'SCALING'),(2,'SUBTITLES')]:
            tap(snap()['views']['overflow_action_'+str(index)]['bounds']);time.sleep(.4);state=snap()
            verify(state['sheetOpen'] and state['panel']==panel and sum(n['frames']>0 for n in state['nodes'] if n['path'].startswith('sheet'))>=3,panel+' submenu has live glass controls and navigation')
            ui.shot(panel.lower()+'.png');tap(state['views']['sheet_back']['bounds']);time.sleep(.4)
            verify(snap()['overflowOpen'],panel+' Back returns to the player drawer')
        tap(snap()['views']['sheet_close']['bounds']);time.sleep(.3)
        log.flush();verify('FATAL EXCEPTION' not in (OUT/'runtime.log').read_text(),'no crash during held inputs, nested glass or menu navigation')
        print(f'PASS: {ui.checks} polish checks',flush=True)
    finally:
        try:motion('UP',1,1)
        except subprocess.CalledProcessError:pass
        adb('shell','am','force-stop',ui.PKG);launch('MainActivity');time.sleep(.5)
        if jump is not None:snap('LIBRARY',restore_jump=str(jump))
        adb('shell','settings','put','system','screen_off_timeout',old_timeout)
        control.restore_volume(old_volume);stream.terminate();stream.wait();log.close()
if __name__=='__main__':main()
