# Wi-Fi Heatmap Simulator

## 개요
JavaFX 21 기반 실내 Wi-Fi 전파 시뮬레이터.
도면 위에 AP와 벽을 배치하고, DPM(지배경로 모델)으로 RSSI 히트맵을 생성. FDTD 전자기파 시뮬레이션은 "전파 흐름 보기"와 AP 추천 검증에 사용.
Flood Fill 기반 영역 선택 + 그리디 Ray-cast + FDTD 검증을 통한 AP 최적 배치 추천 기능 포함.

## 빌드 & 실행
- 실행: `./gradlew run`
- 빌드: `./gradlew build`
- 정리: `./gradlew clean`
- 테스트: `./gradlew test` — 골든 격자·신호 지도 픽셀 해시·자리 추천 회귀 + 작업 파일 왕복. 화면은 수동 검증

## 기술 스택
- Java 21, JavaFX 21.0.9, Gradle 8.14
- Aparapi 3.0.2 (GPU 가속, fallback 포함)
- JNA 5.14.0 (macOS 제목 막대 꾸미기 전용)

## 아키텍처 (MVC)
```
src/main/java/app/
├── Main.java                     # JavaFX Application 진입점
├── model/                        # 데이터 모델
│   ├── AppState.java             # 전역 UI 상태 (툴 모드, 솔버 모드 등)
│   ├── WifiEnvironment.java      # AP + 벽 컨테이너, sampleRssiAt() RSSI 계산
│   ├── AP.java                   # 액세스 포인트 (위치, 밴드별 RadioConfig)
│   ├── RadioConfig.java          # 밴드별 설정 (txPower, antennaGain, channel, BW)
│   ├── Band.java                 # 주파수 대역 enum (2.4/5/6 GHz)
│   ├── Wall.java                 # 벽 선분 + 재질
│   ├── WallMaterial.java         # 재질 프리셋 (drywall, concrete, glass 등)
│   ├── ApPreset.java             # 공유기 기종 프리셋
│   ├── RssiLevel.java            # 신호 등급(강함/양호/약함/사각지대)·색 기준 — 단일 출처
│   └── RssiResult.java           # RSSI 측정 결과 DTO
├── controller/
│   ├── MainController.java       # 주 오케스트레이터 (파일 I/O, 다이얼로그 연동)
│   ├── ViewportController.java   # 패닝/줌
│   ├── ToolsController.java      # 툴 상호작용 (스케일, 벽, AP 배치)
│   └── ApController.java         # AP 선택/드래그/호버
├── engine/                       # 계산 엔진 (UI 코드 금지)
│   ├── HeatmapGenerator.java     # DPM 신호 지도 생성 (int[] ARGB, 백그라운드 스레드)
│   ├── DpmPathGrid.java          # DPM 지배경로 Dijkstra
│   ├── BuildingMask.java         # 건물 안쪽 마스크 (히트맵을 건물 안에만 칠함)
│   ├── FdtdWaveSimulator.java    # FDTD 실시간 시각화 + AP추천 검증용
│   ├── ApRecommender.java        # AP 위치 추천 (그리디 + FDTD 피드백 루프)
│   ├── WifiMath.java             # 기하학 계산, 경로손실 공식
│   ├── LinkBudget.java           # 수신 신호 세기 공식 (EIRP − 경로손실 − 대역폭 보정) — 단일 출처
│   └── fdtd/                     # FDTD 재질 격자·설정 (솔버는 FdtdWaveSimulator / GPU)
│       ├── FdtdConfig.java       # FDTD 파라미터 (dx, dt, PML 등)
│       ├── FdtdMaterialGrid.java
│       ├── FdtdMaterialGridBuilder.java
│       ├── FdtdWallPreset.java
│       └── FdtdReferenceMode.java
├── solver/v2/                    # "전파 흐름 보기" 실행 엔진 (CPU/GPU 백엔드 선택)
│   ├── SolverV2Engine.java       # 백엔드 선택 + close() 로 스레드 풀·GPU 커널 해제
│   ├── GpuWaveSolver.java        # GPU 백엔드 SPI (META-INF/services 로 등록)
│   ├── AparapiGpuWaveSolver.java # Aparapi(OpenCL) 구현
│   └── FloorplanGridTransform.java
├── ui/                           # UI 컴포넌트
│   ├── MainWindow.java           # 루트 BorderPane 레이아웃
│   ├── CanvasView.java           # 메인 캔버스 (도면 + 히트맵 + AP 렌더링)
│   ├── LeftPanel.java            # AP/벽 편집 사이드 패널
│   ├── TopToolbar.java           # 툴 선택, 보기 전환, 공유기 자리 추천 버튼 (+ 캔버스 줌 컨트롤)
│   ├── BottomBar.java            # 진행 상태 + 신호 범례
│   ├── StepIndicator.java        # 작업 단계 표시 (도면→축척→벽→공유기→신호 지도)
│   ├── Icons.java                # 단색 벡터 아이콘
│   ├── MacWindowChrome.java      # macOS 제목 막대를 앱 테마에 맞춤 (JNA, 실패 시 무시)
│   └── Styles.java               # Liquid Glass 테마 시스템 (다크/라이트)
├── io/
│   └── ProjectFile.java          # 작업 파일(.wifisettings) 읽기·쓰기
├── dialog/
│   ├── WelcomeDialog.java        # 시작 화면 (새 평면도 / 저장된 작업 / 빈 화면)
│   ├── AdvancedSettingsDialog.java # 고급(연구·실험) 모드 토글
│   └── ApRecommendDialog.java    # AP 추천 다이얼로그 (Flood Fill 영역 선택)
└── resources/
    └── glass.css                 # 글로벌 CSS (스크롤바, 셀 스타일)
```

## 핵심 패턴

### 상태 관리
- `AppState` (전역 싱글턴) → `MainController` → UI 컴포넌트
- JavaFX `ObservableList`/`Property` 바인딩

### 렌더링
- `CanvasView.render()` ← `MainController`가 직접 호출
- 비동기 히트맵: JavaFX `Task`로 솔버 실행 (UI 블로킹 방지)

### 메뉴·단축키
- `MainController.buildMenuBar()` — 파일/보기/도구 메뉴 (macOS 는 시스템 메뉴 막대)
- macOS 단축키는 메뉴가 처리, 장면 키 핸들러의 ⌘O/⌘S 는 macOS 외 OS 에서만 동작 (중복 실행 방지)

### 작업 파일 (.wifisettings)
- zip 컨테이너: `settings.properties`(축척·공유기·벽) + `floorplan.png`(평면도)
- 읽기·쓰기: `io/ProjectFile` (순수 입출력, `ProjectFileTest` 로 왕복 검증) → `MainController` 는 화면 반영만

### UI 테마 (Liquid Glass)
- `Styles.java`: `isDark()`, `setDark()`, `addThemeListener()`
- 모든 다이얼로그에 `Styles.styleAlert()` / `Styles.styleDialog()` 적용 필수
- 컴포넌트 빌더: `bgPanel()`, `accentBtn()`, `comboBase()` 등

## 히트맵 모델
| 모델 | 클래스 | 특성 |
|------|--------|------|
| DPM (히트맵 유일 모델) | `DpmPathGrid` → `HeatmapGenerator.generateDpm` | 지배경로 Dijkstra, 빠름 (1~2초) |
| FDTD | `FdtdWaveSimulator`(CPU) / `AparapiGpuWaveSolver`(GPU) | 전자기파 수치 시뮬레이션. "전파 흐름 보기" 실시간 표시 + AP 추천 정밀 검증 |
| Legacy (화면 비노출) | `WifiEnvironment.sampleRssiAt` | 경로손실 n + 반사/회절. 발표 비교 기준선(`presentation/MeasureHarness`)·골든 테스트용으로만 유지 |

## AP 추천 알고리즘 (`ApRecommender`)
```
1. Flood Fill 영역 선택 (벽 래스터화 → BFS)
2. 후보/측정 그리드 생성 (마스크 필터링)
3. Phase 1: 그리디 Ray-cast (ForkJoinPool 병렬)
   - 매 AP마다 "새로 커버되는 측정점 수" 최대화
4. Phase 2: FDTD 검증 + 피드백 루프 (정밀 모드)
   - 6000 스텝 FDTD 시뮬레이션
   - EIRP = txPowerDbm + antennaGain 기반 RSSI 판정
   - 커버율 < 80%이면 미커버 영역 방향으로 AP 이동 → 재검증 (최대 3회)
```

## 주요 물리 상수
- `RadioConfig.FIXED_TX_POWER_DBM = 17.0` (기본 AP 송신전력)
- `RadioConfig.DEFAULT_ANTENNA_GAIN_DBI = 5.0` (기본 안테나 이득)
- EIRP = 22.0 dBm (17 + 5)
- 타겟 RSSI: -65 dBm (AP 추천 기본값)
- FDTD: λ/15~λ/20 해상도, CFL 0.90, 6000 스텝

## 작업 규칙
- 새 컴포넌트는 위 패키지 구조 준수
- engine/ 패키지에 UI 코드 금지
- 모든 다이얼로그/팝업에 `Styles.styleAlert()` / `Styles.styleDialog()` 적용
- 한국어 주석 허용
- 컴파일 확인: `./gradlew compileJava`
