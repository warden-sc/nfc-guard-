# 세로형 소개 영상 원본

결과물: [`../nfc-guard-promo-vertical.mp4`](../nfc-guard-promo-vertical.mp4) (1080×1920, 30fps, 43초, 배경음악 + 효과음)

배경음악 `bgm-suno.mp3`: Suno로 생성. **상업적 사용(스토어·광고)은 유료 플랜으로 생성한 곡만 가능** - 생성 계정의 플랜을 확인할 것.

문구·타이밍 수정: `promo.html`의 각 장면(`#s1`~`#s8`)과 하단 스크립트의 장면 구간 `S`, `render(t)`를 고친다.
브라우저에서 `promo.html`을 열면 0초 화면이 보인다(개발자 도구에서 `render(25)`처럼 특정 시점 확인).

다시 만들기 (Node + Playwright + ffmpeg):
```bash
cd docs/media/promo-source
mkdir -p frames && node render.js                      # 프레임 렌더링 (약 1분)
ffmpeg -y -f lavfi -i "aevalsrc='$(cat expr.txt)':s=44100:d=43" -af "afade=t=out:st=41.5:d=1.5,volume=6dB" sfx.wav
ffmpeg -y -framerate 30 -i frames/%05d.jpg -i sfx.wav -c:v libx264 -crf 20 -pix_fmt yuv420p \
  -movflags +faststart -c:a aac -b:a 160k -shortest promo-sfx.mp4

# 배경음악 믹스: 곡 12.10초 강조음을 로고 등장(12.2초)에 맞춰 0.1초 지연,
# 효과음(로고 차임·진동·마무리 차임) 순간에 음악을 줄이고 -16 LUFS로 정규화
ffmpeg -y -i bgm-suno.mp3 -i sfx.wav -filter_complex "\
[0:a]atrim=0:42.9,asetpts=PTS-STARTPTS,adelay=100|100,afade=t=in:d=0.8,afade=t=out:st=41.3:d=1.7,\
volume='0.55-0.3*(exp(-pow((t-12.8)/0.6,2))+exp(-pow((t-24.1)/0.5,2))+exp(-pow((t-40.6)/0.6,2)))':eval=frame[m];\
[1:a]aformat=channel_layouts=stereo,aresample=48000[s];\
[m][s]amix=inputs=2:normalize=0:duration=first,loudnorm=I=-16:TP=-1.5:LRA=11[a]" -map "[a]" -ar 48000 mix.wav
ffmpeg -y -i promo-sfx.mp4 -i mix.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k \
  -movflags +faststart -shortest ../nfc-guard-promo-vertical.mp4
```
(위 sfx.wav 생성 단계에 이미 volume=6dB가 들어 있으므로 믹스에서는 다시 키우지 않는다.)

문구 원칙: [확정 범위](../../PROJECT_SCOPE.md) 기준으로 구현·검증한 기능만 표현한다
("차단/막아준다", 공격 확률 표현 금지, 출시 전에는 "출시 준비 중").
