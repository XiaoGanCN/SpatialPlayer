#!/usr/bin/env python3
"""Real input regressions for the isolated Backdrop playground and scroll-edge / player fixes."""
import importlib.util
import json
from pathlib import Path
import subprocess
import time
from statistics import pstdev
from png_reader import Png
ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/file)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
ui=module('ui','verify-redesign.py');control=module('control','verify-controls.py')
OUT=ROOT/'smoke/out/glass-experiments';OUT.mkdir(parents=True,exist_ok=True);ui.OUT=OUT
adb=ui.adb;snap=ui.snapshot;tap=ui.tap;verify=ui.verify;launch=ui.launch

def motion(kind,x,y):adb('shell','input','motionevent',kind,str(round(x)),str(round(y)))
def home():
    adb('shell','am','force-stop',ui.PKG);launch('MainActivity');time.sleep(.7)
def settings():
    home();tap(snap('LIBRARY')['views']['buttonSettings']['bounds']);time.sleep(.4)
def text_node(s,text):return next(n for n in s['nodes'] if n.get('text')==text)
def main():
    import wave
    fixture=ROOT/'smoke/media/playground_stereo.wav'
    fixture.parent.mkdir(parents=True,exist_ok=True)
    if not fixture.exists():
        with wave.open(str(fixture),'wb') as file:
            file.setnchannels(2);file.setsampwidth(2);file.setframerate(48000);file.writeframes(b'\0'*48000*4*12)
    adb('push',str(fixture),'/sdcard/Movies/SpatialPlayerTest/playground_stereo.wav')
    moving=ui.moving_fixture()
    old={k:adb('shell','settings','get','system',k) for k in ['screen_off_timeout','user_rotation','accelerometer_rotation']}
    old_volume=control.current_volume();gain=None;upmix=None
    log=(OUT/'runtime.log').open('w');stream=subprocess.Popen([ui.ADB,'logcat','-T','1','-v','brief','AndroidRuntime:E','GlassLensRenderer:E','*:S'],stdout=log)
    try:
        adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
        adb('shell','settings','put','system','screen_off_timeout','1800000')
        adb('shell','settings','put','system','accelerometer_rotation','0');adb('shell','settings','put','system','user_rotation','0')
        home();s=snap('LIBRARY');gain=s['verticalGain'];upmix=s['upmixSettings']
        baseline={n:s['views'][n]['bounds'] for n in ['headerTitle','buttonOpenFile','buttonLibrary','buttonPoweramp','buttonSettings']}
        for name in ['buttonLibrary','buttonPoweramp','buttonPoweramp','buttonLibrary','buttonLibrary']:
            tap(s['views'][name]['bounds']);s=snap('LIBRARY')
            verify(all(s['views'][n]['bounds']==b for n,b in baseline.items()),'home controls stay fixed when toggling '+name)
        verify('buttonOpenFolder' not in s['views'],'home has no Add folder button')
        tap(s['views']['buttonLibrary']['bounds']);s=snap('LIBRARY')
        verify(s['views']['fileList']['rows']>0,'media rows populate immediately')
        verify(any(n['kind']=='TopFadeBlurView' and n['frames']>0 for n in s['nodes']),'main list scroll edge renders on the GPU')
        ui.shot('videos.png')
        # Measured contrast must increase as the *actual Gaussian radius* fades down the strip.
        launch('TopFadeProbeActivity');time.sleep(.5);s=snap('SETTINGS')
        fade=next(n for n in s['nodes'] if n['kind']=='TopFadeBlurView');ui.shot('progressive-blur.png')
        image=Png(OUT/'progressive-blur.png');x0,y0,x1,y1=fade['bounds']
        contrast=[pstdev(image.luma(x,int(y0+(y1-y0)*f)) for x in range(x0+80,x1-80)) for f in [.08,.45,.90]]
        (OUT/'contrast.json').write_text(json.dumps(contrast));verify(contrast[0]<contrast[1]<contrast[2] and contrast[2]>90,'progressive frosting becomes smoothly sharper toward its transparent foot')
        frames=fade['frames'];snap('SETTINGS',mutate_backdrop=True);time.sleep(.25)
        updated=snap('SETTINGS');verify(next(n for n in updated['nodes'] if n['kind']=='TopFadeBlurView')['frames']>frames,'stationary blur refreshes when its source changes without scrolling')
        ui.shot('progressive-blur-updated.png');changed=Png(OUT/'progressive-blur-updated.png');y=int(y0+(y1-y0)*.90)
        verify(sum(abs(image.luma(x,y)-changed.luma(x,y)) for x in range(x0+80,x1-80))/(x1-x0-160)>100,'stationary blur displays the updated source pixels')
        idle=next(n for n in updated['nodes'] if n['kind']=='TopFadeBlurView')['frames'];time.sleep(.4)
        verify(next(n for n in snap('SETTINGS')['nodes'] if n['kind']=='TopFadeBlurView')['frames']<=idle+1,'idle scroll-edge blur does not poll or continuously redraw')
        settings();s=snap('SETTINGS',include_text=True)
        verify(any(n['kind']=='TopFadeBlurView' and n['frames']>0 for n in s['nodes']),'settings scroll edge renders on the GPU')
        decoder=next(n for n in s['nodes'] if n.get('tag')=='settings_row:Decoder');selected=[n for n in s['nodes'] if n.get('selected') and n.get('text')=='Auto']
        verify(selected and selected[0]['tint']!=0,'selected decoder has its own illuminated blue material')
        ui.shot('settings.png')
        tap(next(n['bounds'] for n in s['nodes'] if n.get('tag')=='settings_row:Stereo stage'));s=snap('SETTINGS',include_text=True)
        verify(any(n['path']=='sheet' and n['frames']>0 for n in s['nodes']),'Stereo stage uses the real shared glass shell')
        mode=next(n for n in s['nodes'] if n['path'].startswith('sheet') and n.get('text')=='Matrix');tap(mode['bounds']);s=snap('SETTINGS',include_text=True)
        buttons=[text_node(s,name) for name in ['Front L','Front R','Centre','Sub','Rear L','Rear R']]
        verify(len({n['bounds'][2]-n['bounds'][0] for n in buttons})<=2,'matrix channel buttons have balanced widths')
        panel=next(n for n in s['nodes'] if n['path']=='sheet')['bounds']
        verify(all(panel[0]<=n['bounds'][0]<n['bounds'][2]<=panel[2] for n in buttons),'all six matrix channel buttons fit inside the panel')
        ui.shot('matrix.png');adb('shell','input','keyevent','KEYCODE_BACK')
        adb('shell','input','swipe','1020','2100','1020','800','400');s=snap('SETTINGS',include_text=True)
        row=next(n for n in s['nodes'] if n.get('tag')=='settings_row:Drag sensitivity')
        slider=next(n for n in s['nodes'] if n['kind']=='GlassSlider' and row['bounds'][1]<n['bounds'][1]<row['bounds'][3])
        x0,y0,x1,y1=slider['bounds'];tap([x1-30,(y0+y1)//2,x1-30,(y0+y1)//2]);s=snap('SETTINGS',include_text=True)
        verify(abs(s['verticalGain']-1.5)<.001,'drag sensitivity reaches the full 150% endpoint')
        tap(text_node(s,'150%')['bounds']);time.sleep(.5);s=snap('SETTINGS',include_text=True)
        verify(any(n['path']=='editor' and n['frames']>0 for n in s['nodes']),'numeric value input uses the shared real glass shell')
        editor=next(n for n in s['nodes'] if n['kind'].endswith('EditText'))
        verify(float(editor['text'])==150,'percentage input uses the displayed percent units')
        tap(editor['bounds']);adb('shell','input','keycombination','KEYCODE_CTRL_LEFT','KEYCODE_A');adb('shell','input','text','125');time.sleep(.3);s=snap('SETTINGS',include_text=True)
        ui.shot('numeric-input.png');verify(next(n for n in s['nodes'] if n['kind'].endswith('EditText'))['text']=='125','numeric editor accepts typed input')
        tap(text_node(s,'Apply')['bounds']);time.sleep(.3)
        verify(abs(snap('SETTINGS')['verticalGain']-1.25)<.001,'typing 125 applies 125% sensitivity')
        ui.shot('numeric-applied.png');snap('SETTINGS',restore_gain=str(gain),restore_upmix=json.dumps(upmix))
        # Playground is reached by the same buried Settings entry the user will use.
        s=snap('SETTINGS',include_text=True);tap(text_node(s,'Glass playground')['bounds']);time.sleep(.6)
        # Pause the moving background before querying accessibility (continuous animations never idle).
        ui.text_tap('Pause backdrop');time.sleep(.4)
        nodes=ui.nodes();switch=next(n for n in nodes if n.get('content-desc')=='Library switch')
        import re
        before=switch.get('checked');b=list(map(int,re.findall(r'\d+',switch.attrib['bounds'])));tap(b)
        after=next(n for n in ui.nodes() if n.get('content-desc')=='Library switch').get('checked')
        verify(before!=after,'library switch toggles its actual accessible state')
        foreground=control.focus()
        for _ in range(5):
            if 'GlassPlaygroundActivity' in foreground:break
            time.sleep(.2);foreground=control.focus()
        (OUT/'playground-focus.txt').write_text(foreground)
        verify('GlassPlaygroundActivity' in foreground,'library switch remains interactive without leaving the isolated playground')
        ui.shot('playground.png')
        adb('shell','input','swipe','24','2100','24','600','400');time.sleep(.3)
        optics=ui.nodes()
        verify(all(any(n.get('content-desc')==name and n.get('class')=='android.widget.SeekBar' for n in optics) for name in ['Blur','Refraction']),'both optics sliders expose named accessible range controls')
        ui.shot('playground-optics.png');adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(.3)
        verify('SettingsActivity' in control.focus(),'playground returns to settings')
        # Retained audio engine -> video must reacquire a valid preview, repeatedly.
        for i in range(2):
            launch('SmokeTestActivity','--es','smoke_path','/sdcard/Movies/SpatialPlayerTest/playground_stereo.wav','--es','smoke_mime','audio/wav','--ez','smoke_muted','true');time.sleep(.8)
            audio=snap(controls=True);verify(audio['channels']==2,'audio-only fixture has a real stereo stream')
            adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(.3)
            launch('SmokeTestActivity','--es','smoke_path',moving,'--es','smoke_mime','video/mp4','--ez','smoke_muted','true');time.sleep(1)
            s=snap(controls=True);f=s['videoPreviewFrames'];time.sleep(.5);s=snap()
            verify(s['videoBackdropReady'] and s['videoPreviewFrames']>f,'music-to-video transition '+str(i+1)+' restores a live glass preview')
            if i==0:
                portrait=s['views']['controlGlass']['height'];adb('shell','settings','put','system','user_rotation','1');time.sleep(.6);s=snap(controls=True)
                verify(s['views']['controlGlass']['height']<portrait*.6,'landscape capsule is less than 60% of portrait height')
                verify(s['views']['seekBar']['width']>200 and s['views']['buttonPlayPause']['height']>=110,'compact landscape keeps usable seeking and touch targets')
                ui.shot('landscape.png');adb('shell','settings','put','system','user_rotation','0');time.sleep(.5)
        log.flush();verify('FATAL EXCEPTION' not in (OUT/'runtime.log').read_text(),'no crashes in native and Compose glass or media transitions')
        print(f'PASS: {ui.checks} glass experiment checks',flush=True)
    finally:
        try:motion('UP',1,1)
        except subprocess.CalledProcessError:pass
        home()
        if gain is not None:snap('LIBRARY',restore_gain=str(gain),restore_upmix=json.dumps(upmix))
        for k,v in old.items():adb('shell','settings','put','system',k,v)
        control.restore_volume(old_volume);stream.terminate();stream.wait();log.close()
if __name__=='__main__':main()
