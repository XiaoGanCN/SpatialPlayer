#!/usr/bin/env python3
"""Native M2TS/DTS-HD playback and seek regression, plus unchanged ordinary 188-byte TS."""
import importlib.util
from pathlib import Path
import re
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('device_ui',ROOT/'tools/verify-redesign.py')
ui=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui)
OUT=ROOT/'smoke/out/bluray';OUT.mkdir(parents=True,exist_ok=True);ui.OUT=OUT
PKG=ui.PKG

def main():
    try:
        for name,mime,codec in [('earth2_original_12s.m2ts','video/mp2t','hevc'),('ordinary_188.ts','video/mp2t','avc')]:
            path=OUT/(name+'.log')
            with path.open('w') as log:
                stream=subprocess.Popen([ui.ADB,'logcat','-T','1','-v','brief','TrackProbe:I','ExoPlayerImplInternal:E','*:S'],stdout=log)
                try:
                    ui.launch('TrackProbeActivity','-d','file:///sdcard/Movies/SpatialPlayerTest/'+name,'-t',mime,'--ez','track_probe_mute','true','--el','track_probe_seek_ms','7000','--el','track_probe_duration_ms','10000')
                    time.sleep(11)
                finally:stream.terminate();stream.wait()
            report=path.read_text()
            ui.verify('Playback error' not in report,name+' has no decoder/source error')
            ui.verify(re.search(r'performance :: .*decoder=c2\.qti\.'+codec,report) is not None,name+' selects the actual hardware video decoder')
            positions=re.findall(r'snapshot :: .*position=(\d+)',report)
            ui.verify(len(positions)>1 and int(positions[-1])>9000,name+' resumes rendering after a real mid-file seek')
            if name.endswith('m2ts'):
                ui.verify('audio/vnd.dts.hd' in report and 'upmix=false channels=6' in report,'M2TS preserves DTS-HD native surround without stereo upmix')
                ui.verify('videoSize=3840x2160' in report,'M2TS selects the 4K base picture')
        ui.launch('SmokeTestActivity','--es','smoke_path','/sdcard/Movies/SpatialPlayerTest/earth2_original_12s.m2ts','--es','smoke_mime','video/mp2t','--ez','smoke_muted','true')
        time.sleep(3)
        samples=[ui.snapshot(controls=True)]
        for _ in range(2):time.sleep(2.5);samples.append(ui.snapshot(controls=True))
        first,last=samples[0],samples[-1]
        ui.verify('hevc' in last['decoder'] and 'ffmpeg' not in last['decoder'],'production M2TS player keeps hardware decode with glass controls visible')
        ui.verify(last['rendered']-first['rendered']>=90 and last['dropped']-first['dropped']<=3,'production M2TS playback advances smoothly with the new glass')
        ui.verify(last['channels']==6 and not last['upmix'],'production DTS-HD 5.1 bypasses stereo upmix')
        layers=ui.adb('shell','dumpsys','SurfaceFlinger');(OUT/'hdr-layers.txt').write_text(layers)
        blocks=layers.split('* Layer ')
        video=next(b for b in blocks if 'SurfaceView['+PKG+'/com.gan.spatialplayer.PlayerActivity](BLAST)' in b.split('\n')[0])
        ui.verify('dataspace=BT2020_ITU_PQ' in video,'M2TS production video remains native PQ HDR')
        ui.shot('earth2-player.png')
        print(f'PASS: {ui.checks} Blu-ray/TS checks',flush=True)
    finally:
        ui.adb('shell','am','force-stop',PKG);ui.launch('MainActivity')

if __name__=='__main__':main()
