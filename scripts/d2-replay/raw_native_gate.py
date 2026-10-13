from common import *
results=[]
for p in sorted(root.glob('*communication_speaker*.zip')):
 with zipfile.ZipFile(p) as z:
  name=next(n for n in z.namelist() if n.endswith('/microphone.wav'))
  with wave.open(io.BytesIO(z.read(name))) as w: x=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
 for mode,interval in [('native_gate_final_only',sys.float_info.max),('native_gate_periodic',.25)]:
  t=Transcriber(str(args.moonshine),ModelArch.SMALL_STREAMING,update_interval=interval,options={'vad_threshold':'0.5','identify_speakers':'false','return_audio_data':'false','transcription_interval':'0.25'})
  lines={}
  def event(e):
   if hasattr(e,'line') and e.line is not None:lines[e.line.line_id]=e.line.text
  t.add_listener(event);t.start()
  for i in range(0,len(x),1600):t.add_audio(x[i:i+1600],16000)
  stream=t.get_default_stream();assert t._lib.moonshine_stop_stream(t._handle,stream._handle)==0
  result=stream.update_transcription(1)
  row={'file':p.name,'mode':mode,'text':' '.join(v for v in lines.values() if v),'final':str(result)}
  print(json.dumps(row),flush=True);results.append(row);t.close()
(args.output / 'gated-results.json').write_text(json.dumps(results,indent=2))
