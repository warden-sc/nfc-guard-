# 세로형 소개 영상 원본

결과물: [`../nfc-guard-promo-vertical.mp4`](../nfc-guard-promo-vertical.mp4) (1080×1920, 30fps, 43초, 효과음 포함)

문구·타이밍 수정: `promo.html`의 각 장면(`#s1`~`#s8`)과 하단 스크립트의 장면 구간 `S`, `render(t)`를 고친다.
브라우저에서 `promo.html`을 열면 0초 화면이 보인다(개발자 도구에서 `render(25)`처럼 특정 시점 확인).

다시 만들기 (Node + Playwright + ffmpeg):
```bash
cd docs/media/promo-source
mkdir -p frames && node render.js                      # 프레임 렌더링 (약 1분)
ffmpeg -y -f lavfi -i "aevalsrc='$(cat expr.txt)':s=44100:d=43" -af "afade=t=out:st=41.5:d=1.5,volume=6dB" sfx.wav
ffmpeg -y -framerate 30 -i frames/%05d.jpg -i sfx.wav -c:v libx264 -crf 20 -pix_fmt yuv420p \
  -movflags +faststart -c:a aac -b:a 160k -shortest ../nfc-guard-promo-vertical.mp4
```

문구 원칙: [확정 범위](../../PROJECT_SCOPE.md) 기준으로 구현·검증한 기능만 표현한다
("차단/막아준다", 공격 확률 표현 금지, 출시 전에는 "출시 준비 중").
