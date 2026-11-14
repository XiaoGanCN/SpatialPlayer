#!/usr/bin/env python3
"""Actual drawer selection, centered press, fade bounds and keyboard action regressions."""
import importlib.util
import json
from pathlib import Path
import time
from png_reader import Png

ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/file)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
ui=module('ui','verify-redesign.py')
OUT=ROOT/'smoke/out/drawers';OUT.mkdir(parents=True,exist_ok=True);ui.OUT=OUT
adb=ui.adb;snap=ui.snapshot;tap=ui.tap;verify=ui.verify
def motion(kind,x,y):adb('shell','input','motionevent',kind,str(round(x)),str(round(y)))
def home():
    adb('shell','am','force-stop',ui.PKG);ui.launch('MainActivity');time.sleep(.4)
def settings():
    home();tap(snap('LIBRARY')['views']['buttonSettings']['bounds'])
def text(s,label,prefix=''):
    return next(n for n in s['nodes'] if n['path'].startswith(prefix) and n.get('text')==label)
def glass_ids(s,prefix):
    return {n['path']:n['identity'] for n in s['nodes'] if n['path'].startswith(prefix) and (n['frames']>0 or n['kind']=='GlassSlider')}
def main():
    original={k:adb('shell','settings','get','system',k) for k in ['screen_off_timeout','user_rotation','accelerometer_rotation']}
    prefs=None
    try:
        adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
        adb('shell','settings','put','system','screen_off_timeout','1800000')
        adb('shell','settings','put','system','accelerometer_rotation','0');adb('shell','settings','put','system','user_rotation','0')
        ui.launch('TopFadeProbeActivity','--ez','nested_glass','true','--ez','solid_color','true');time.sleep(.4)
        s=snap('SETTINGS');fade=next(n for n in s['nodes'] if n['kind']=='TopFadeBlurView')
        x0,y0,x1,y1=fade['bounds'];pixel=((x0+x1)//2,int(y0+(y1-y0)*.3))
        ui.shot('nested-red.png');red=Png(OUT/'nested-red.png').rgb(*pixel)
        snap('SETTINGS',mutate_backdrop=True);time.sleep(.25);ui.shot('nested-blue.png');blue=Png(OUT/'nested-blue.png').rgb(*pixel)
        updated=snap('SETTINGS');frames=next(n for n in updated['nodes'] if n['kind']=='TopFadeBlurView')['frames']
        verify(red[0]>150 and blue[2]>150 and sum(abs(a-b) for a,b in zip(red,blue))>300,'cached GPU frosting follows a stationary descendant change inside a glass container')
        time.sleep(.4)
        verify(next(n for n in snap('SETTINGS')['nodes'] if n['kind']=='TopFadeBlurView')['frames']<=frames+1,'cached frosting settles without polling after a descendant update')
        settings();s=snap('SETTINGS',include_text=True)
        prefs={k:s[k] for k in ['verticalGain','skipMs','ambientSetting','upmixSettings']}
        verify(not any(n.get('text') in ['Glass material','Regular','Clear'] for n in s['nodes']),'obsolete material selector is removed')
        tap(next(n['bounds'] for n in s['nodes'] if n.get('tag')=='settings_row:Stereo stage'))
        s=snap('SETTINGS',include_text=True);tap(text(s,'Matrix','sheet')['bounds']);s=snap('SETTINGS',include_text=True)
        before=glass_ids(s,'sheet')
        tap(text(s,'Rear R','sheet')['bounds']);s=snap('SETTINGS',include_text=True)
        verify(all(glass_ids(s,'sheet').get(p)==v for p,v in before.items()),'matrix channel selection retains its existing glass controls')
        b=text(s,'Both','sheet')['bounds'];tap(b);s=snap('SETTINGS',include_text=True)
        verify(all(glass_ids(s,'sheet').get(p)==v for p,v in before.items()),'matrix source selection retains its existing glass controls')
        verify(s['upmixSettings']['matrix'].split('|')[5]=='50,50','matrix source applies the real selected channel gains')
        held=text(s,'Front L','sheet');x0,y0,x1,y1=held['bounds'];motion('DOWN',(x0+x1)/2,(y0+y1)/2);time.sleep(.18)
        h=text(snap('SETTINGS',include_text=True),'Front L','sheet')
        verify(h['scaleX']>1 and abs(h['scaleX']-h['scaleY'])<.001 and
            abs(h['pivotX']-h['width']/2)<1 and abs(h['pivotY']-h['height']/2)<1,'drawer press scales the whole control around its center')
        cx=h['bounds'][0]+h['width']*h['scaleX']/2;cy=h['bounds'][1]+h['height']*h['scaleY']/2
        verify(abs(cx-(x0+x1)/2)<2 and abs(cy-(y0+y1)/2)<2,'held drawer control keeps its visual center fixed')
        ui.shot('matrix-held.png');motion('MOVE',1,1);motion('UP',1,1)
        for rotation in [0,1]:
            adb('shell','settings','put','system','user_rotation',str(rotation));time.sleep(.5)
            settings();s=snap('SETTINGS',include_text=True)
            row=next(n for n in s['nodes'] if n.get('tag')=='settings_row:Drag sensitivity')
            label=next(n for n in s['nodes'] if (n.get('text') or '').endswith('%') and row['path']+'/' in n['path'])
            # Landscape has a much shorter viewport; use its actual dimensions until visible.
            for _ in range(6):
                if 180<label['bounds'][1]<s['height']-140:break
                adb('shell','input','swipe',str(s['width']-40),str(s['height']-140),str(s['width']-40),'230','400')
                time.sleep(.8)
                s=snap('SETTINGS',include_text=True);label=next(n for n in s['nodes'] if n['identity']==label['identity'])
            tap(label['bounds']);time.sleep(.5);s=snap('SETTINGS',include_text=True)
            panel=next(n for n in s['nodes'] if n['path']=='editor')['bounds']
            actions=[text(s,label,'editor') for label in ['Cancel','Apply']]
            verify(all(panel[0]<=n['bounds'][0]<n['bounds'][2]<=panel[2] and panel[1]<n['bounds'][1]<n['bounds'][3]<=min(panel[3],s['editorVisibleBottom']) for n in actions),'numeric actions stay inside their shell and above the keyboard in rotation '+str(rotation))
            field=next(n for n in s['nodes'] if n['path'].startswith('editor') and n['kind'].endswith('EditText'))
            verify(panel[0]<=field['bounds'][0]<field['bounds'][2]<=panel[2] and panel[1]<=field['bounds'][1]<field['bounds'][3]<=min(panel[3],s['editorVisibleBottom']),'numeric input remains fully visible above the keyboard in rotation '+str(rotation))
            verify('00000' not in field['text'],'numeric editor has a clean initial value in rotation '+str(rotation))
            ui.shot('value-'+str(rotation)+'.png')
            tap(actions[0]['bounds']);verify(not any(n['path']=='editor' for n in snap('SETTINGS')['nodes']),'Cancel dismisses the real value editor in rotation '+str(rotation))
        adb('shell','settings','put','system','user_rotation','0')
        ui.launch('SmokeTestActivity','--es','smoke_path','/sdcard/Movies/SpatialPlayerTest/grid_720p.mp4','--es','smoke_mime','video/mp4','--ez','smoke_muted','true');time.sleep(.7)
        s=snap(controls=True);tap(s['views']['buttonMore']['bounds']);s=snap();tap(s['views']['overflow_action_1']['bounds']);s=snap(include_text=True)
        before=glass_ids(s,'sheet');window=s['sheetWindow']
        for label in ['Fill (no crop)','Fit']:
            tap(text(s,label,'sheet')['bounds']);s=snap(include_text=True)
            verify(s['sheetWindow']==window and glass_ids(s,'sheet')==before,'scaling '+label+' updates selection without replacing glass hosts')
        for rotation in [0,1]:
            adb('shell','settings','put','system','user_rotation',str(rotation));time.sleep(.5);s=snap()
            pane=next(n for n in s['nodes'] if n['path']=='sheet')
            fade=next(n for n in s['nodes'] if n['path'].startswith('sheet') and n['kind']=='TopFadeBlurView')
            scroller=next(n for n in s['nodes'] if n['path'].startswith('sheet') and n['kind']=='ScrollView')
            verify(fade['bounds'][1]>=pane['bounds'][1] and fade['bounds'][3]<=pane['bounds'][3] and abs(fade['height']-scroller['paddingTop'])<1,'header fade stays within its shell and ends before resting content in rotation '+str(rotation))
            ui.shot('scaling-'+str(rotation)+'.png')
        print(f'PASS: {ui.checks} drawer checks',flush=True)
    finally:
        try:motion('UP',1,1)
        except Exception:pass
        home()
        if prefs:snap('LIBRARY',restore_gain=str(prefs['verticalGain']),restore_jump=str(prefs['skipMs']),restore_ambient=prefs['ambientSetting'],restore_upmix=json.dumps(prefs['upmixSettings']))
        for k,v in original.items():adb('shell','settings','put','system',k,v)
if __name__=='__main__':main()
