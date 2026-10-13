from common import *
results=[]
for p in sorted(root.glob('*communication_speaker*.zip')):
 with zipfile.ZipFile(p) as z:
  name=next(n for n in z.namelist() if n.endswith('/microphone.wav'))
  with wave.open(io.BytesIO(z.read(name))) as w: pcm=w.readframes(w.getnframes())
 x=np.frombuffer(pcm,dtype='<i2').astype(np.float32)/32768
 for mode,interval in [('final_only',sys.float_info.max),('periodic',0.25)]:
  start=time.monotonic()
  t=Transcriber(str(args.moonshine),ModelArch.SMALL_STREAMING,update_interval=interval,options={'vad_threshold':'0.0','identify_speakers':'false','return_audio_data':'false'})
  lines={};events=[]
  def event(e):
   if hasattr(e,'line') and e.line is not None:
    lines[e.line.line_id]=e.line.text
    events.append({'kind':type(e).__name__,'text':e.line.text})
  t.add_listener(event);t.start()
  for i in range(0,len(x),1600): t.add_audio(x[i:i+1600],16000)
  stream=t.get_default_stream()
  assert t._lib.moonshine_stop_stream(t._handle,stream._handle)==0
  final=stream.update_transcription(1)
  result={'file':p.name,'mode':mode,'events':events,'text':' '.join(v for v in lines.values() if v),'final':str(final),'elapsed':time.monotonic()-start}
  print(json.dumps(result),flush=True);results.append(result);t.close()
(args.output / 'moonshine-results.json').write_text(json.dumps(results,indent=2))
