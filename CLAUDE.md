# Wi-Fi Heatmap 프로젝트

## 개요
JavaFX 21 기반 2D 전파 전파 시뮬레이터.
도면 위에 AP와 벽을 배치하고 Wi-Fi 신호 강도(RSSI) 히트맵을 생성.

## 빌드 & 실행
- 실행: `./gradlew run`
- 빌드: `./gradlew build`
- 정리: `./gradlew clean`
- 테스트 없음 (src/test/ 미존재)

## 아키텍처 (MVC)
```
src/main/java/app/
├── Main.java                  # 진입점, JavaFX Application
├── model/                     # 데이터 모델
│   ├── AppState.java          # 전역 UI 상태 (툴 모드, 솔버 모드 등)
│   ├── WifiEnvironment.java   # AP + 벽 컨테이너
│   ├── AP.java                # 액세스 포인트 (위치, RadioConfig)
│   ├── Wall.java              # 벽 선분 + 재질
│   ├── WallMaterial.java      # 재질 프리셋 (drywall, brick 등)
│   └── Band.java              # 주파수 대역 (2.4/5/6 GHz)
├── controller/
│   ├── MainController.java    # 주 오케스트레이터
│   ├── ViewportController.java # 패닝/줌
│   ├── ToolsController.java   # 툴 상호작용 (스케일, 벽, AP 배치)
│   └── ApController.java      # AP 선택/드래그/호버
├── engine/
│   ├── HeatmapGenerator.java  # Legacy 모델 (경로 손실 + 벽 감쇠)
│   ├── FdtdWaveSimulator.java # FDTD 실시간 시각화
│   ├── WifiMath.java          # 기하학 계산, 경로 손실 공식
│   └── fdtd/                  # FDTD 서브시스템
│       ├── TezFdtdSolver.java # 핵심 TEz FDTD 솔버
│       ├── FdtdHeatmapGenerator.java
│       ├── FdtdMaterialGrid.java
│       └── FdtdConfig.java
├── ui/
│   ├── MainWindow.java        # 루트 BorderPane 레이아웃
│   ├── CanvasView.java        # 메인 드로잉 캔버스
│   ├── LeftPanel.java         # AP/벽 편집 사이드 패널
│   ├── TopToolbar.java        # 툴 선택, 모델 전환
│   └── BottomBar.java         # 솔버 상태 표시
└── dialog/
    └── ApEditorDialog.java    # AP 상세 편집 모달 (더블클릭)
```

## 핵심 패턴
- **상태 흐름**: AppState (전역) → MainController → UI 컴포넌트
- **렌더링**: CanvasView.render() ← MainController가 직접 호출
- **비동기**: JavaFX Task로 HeatmapGenerator/FdtdSolver 실행 (UI 블로킹 방지)
- **데이터 바인딩**: JavaFX ObservableList/Property 사용
- **직렬화**: WifiEnvironment ↔ JSON (Jackson)

## 히트맵 모델
- **Legacy**: HeatmapGenerator - 거리 + 벽 감쇠 + 반사/회절 (빠름)
- **FDTD**: TezFdtdSolver - 실제 전자기파 시뮬레이션, PML 경계 (느리지만 정확)

## 기술 스택
- Java 21, JavaFX 21.0.9, Gradle 8.14
- Jackson 2.17.2 (JSON), OpenCV 4.7.0 (이미지 처리)
- CPU/GPU 솔버 지원 (GPU는 fallback 포함)

## 작업 규칙
- 새 컴포넌트 추가 시 위 패키지 구조 준수
- UI 변경은 항상 CanvasView 또는 해당 Panel에서 처리
- 솔버 로직은 engine/ 패키지에만 위치
- 한국어 주석 허용

## 커스텀 명령어 (슬래시 Skills)
- `/build` - 빌드/실행 단축 명령어
- `/new-feature [기능명]` - 새 기능 추가 체크리스트
- `/review-pr` - PR 전 변경사항 검토
- `/debug-heatmap [증상]` - 히트맵/FDTD 디버깅 가이드
