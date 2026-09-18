# 니아의 게임월드 (Niah's Game World)

DJMAX Respect V 스타일 4레인 리듬게임입니다. 이터널리턴(Eternal Return)의 니아(Niah)를
테마로 한 개인 팬 프로젝트이며, 두 가지 방식으로 실행할 수 있습니다.

- **독립 실행 (Standalone)** — Little LUMI 없이 이 프로젝트만으로 바로 실행됩니다.
- **Little LUMI Model 플러그인** — [Little LUMI Model(STUDIO LUMI)](https://store.steampowered.com/)의
  모드로 설치해, 니아 캐릭터를 우클릭하거나 트레이 메뉴에서 열 수 있습니다.

두 방식 모두 같은 게임 코드(`src/djmax/`)를 공유합니다 — 곡 라이브러리, 채보 에디터,
설정, 점수/등급 기록 등 기능은 완전히 동일합니다.

## 독립 실행 (Standalone)

요구 사항: JDK 25 이상.

```
python build_standalone.py
java -jar dist\niah-game-world.jar
```

빌드된 jar 하나로 끝입니다 — 설치 과정이 따로 없고, 실행하면 바로 곡 선택 화면이 뜹니다.
데이터(설정·곡 라이브러리·최고 기록)는 `%USERPROFILE%\.niah-game-world\`에 저장됩니다.

`build_standalone.py`는 Little LUMI SDK에 의존하는 파일 4개(`DjmaxPlugin.java`,
`LittleLumiGameHost.java`, `LittleLumiMascotIntegration.java`, `LumiPrefsAdapter.java`)만
빼고 나머지를 전부 컴파일합니다 — 독립 실행 빌드는 Little LUMI SDK jar를 전혀 필요로
하지 않습니다.

## Little LUMI Model 플러그인으로 설치

자세한 설치 방법은 [`README.txt`](README.txt)를 참고해주세요 (Little LUMI Mod SDK로
빌드해서 `mods\` 폴더에 넣는 방식입니다). Little LUMI Model은 이 저장소에 포함되어
있지 않으며, 별도로 구해야 합니다.

## 알려진 제한 사항

- 원본 게임 성우 음성 파일은 저작권 문제로 포함하지 않았습니다. 대사 음성이 들리지
  않는 것은 정상입니다.
- 유튜브 URL로 곡 추가하기 기능은 `yt-dlp.exe`/`ffmpeg.exe`가 필요하며, 최초 사용 시
  자동으로 내려받습니다.
- 독립 실행 버전에는 (당연히) Little LUMI의 데스크톱 캐릭터가 없으므로, 게임 시작 시
  캐릭터를 옆으로 비켜서게 하는 연출은 빠집니다 — 플러그인 버전에만 있는 연출입니다.

## 저작권

니아(Niah) 캐릭터 및 세계관의 저작권은 Nimble Neuron에 있습니다. 자세한 내용은
[`NOTICE.txt`](NOTICE.txt)를 확인해주세요. 이 프로젝트는 무료로 배포되며 상업적으로
판매되지 않습니다.
