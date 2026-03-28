# Wi-Fi Heatmap Simulator

## 개요
JavaFX 21 기반 실내 Wi-Fi 전파 시뮬레이터.
도면 위에 AP와 벽을 배치하고, Legacy 경로손실 모델 또는 FDTD 전자기파 시뮬레이션으로 RSSI 히트맵을 생성.
Flood Fill 기반 영역 선택 + 그리디 Ray-cast + FDTD 검증을 통한 AP 최적 배치 추천 기능 포함.

## 빌드 & 실행
- 실행: `./gradlew run`
- 빌드: `./gradlew build`
- 정리: `./gradlew clean`
- 테스트 없음 (수동 시각 검증)

## 기술 스택
- Java 21, JavaFX 21.0.9, Gradle 8.14
- Jackson 2.17.2 (JSON 직렬화)
- Aparapi 3.0.2 (GPU 가속, fallback 포함)

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
│   ├── RssiResult.java           # RSSI 측정 결과 DTO
│   └── PropagationPath.java      # 전파 경로 (디버그 오버레이용)
├── controller/
│   ├── MainController.java       # 주 오케스트레이터 (파일 I/O, 다이얼로그 연동)
│   ├── ViewportController.java   # 패닝/줌
│   ├── ToolsController.java      # 툴 상호작용 (스케일, 벽, AP 배치)
│   └── ApController.java         # AP 선택/드래그/호버
├── engine/                       # 계산 엔진 (UI 코드 금지)
│   ├── HeatmapGenerator.java     # Legacy 모델 (경로손실 + 벽감쇠 + 반사/회절)
│   ├── FdtdWaveSimulator.java    # FDTD 실시간 시각화 + AP추천 검증용
│   ├── ApRecommender.java        # AP 위치 추천 (그리디 + FDTD 피드백 루프)
│   ├── WifiMath.java             # 기하학 계산, 경로손실 공식
│   ├── GpuHeatmapSolver.java     # GPU 가속 히트맵
│   └── fdtd/                     # FDTD 서브시스템
│       ├── TezFdtdSolver.java    # 핵심 TEz FDTD 솔버
│       ├── FdtdConfig.java       # FDTD 파라미터 (dx, dt, PML 등)
│       ├── FdtdHeatmapGenerator.java
│       ├── FdtdMaterialGrid.java
│       ├── FdtdMaterialGridBuilder.java
│       ├── FdtdWallPreset.java
│       ├── FdtdProgress.java
│       └── FdtdReferenceMode.java
├── solver/v2/                    # GPU 솔버 v2
│   ├── SolverV2Engine.java
│   ├── GpuWaveSolver.java
│   ├── AparapiGpuWaveSolver.java
│   └── FloorplanGridTransform.java
├── ui/                           # UI 컴포넌트
│   ├── MainWindow.java           # 루트 BorderPane 레이아웃
│   ├── CanvasView.java           # 메인 캔버스 (도면 + 히트맵 + AP 렌더링)
│   ├── LeftPanel.java            # AP/벽 편집 사이드 패널
│   ├── TopToolbar.java           # 툴 선택, 모델 전환, AP 추천 버튼
│   ├── BottomBar.java            # 솔버 상태 표시
│   └── Styles.java               # Liquid Glass 테마 시스템 (다크/라이트)
├── dialog/
│   ├── ApEditorDialog.java       # AP 상세 편집 모달
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

### 직렬화
- `WifiEnvironment` ↔ JSON (Jackson)

### UI 테마 (Liquid Glass)
- `Styles.java`: `isDark()`, `setDark()`, `addThemeListener()`
- 모든 다이얼로그에 `Styles.styleAlert()` / `Styles.styleDialog()` 적용 필수
- 컴포넌트 빌더: `bgPanel()`, `accentBtn()`, `comboBase()` 등

## 히트맵 모델
| 모델 | 클래스 | 특성 |
|------|--------|------|
| Legacy | `HeatmapGenerator` | 경로손실 + 벽감쇠 + 반사/회절, 빠름 |
| FDTD | `TezFdtdSolver` / `FdtdWaveSimulator` | 전자기파 수치 시뮬레이션, PML 경계, 느리지만 정확 |

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
