# Wi-Fi Heatmap Simulator — 안드로이드 포팅 계획서 (경로 2: 네이티브 재작성)

> 작성일: 2026-06-17
> 대상: JavaFX 21 데스크탑 앱 → Android (Kotlin + Jetpack Compose) 네이티브 앱
> 범위: **전체 기능** (Legacy · DPM · FDTD · AP 추천 포함)

---

## 0. 한 줄 결론

엔진(`engine/`, `model/`)의 **계산 로직 자체는 순수 Java/수학 코드라 90% 이식 가능**하다.
실제 포팅 작업의 대부분은 (1) JavaFX 타입(`Point2D`/`Color`/`WritableImage`/`Property`)을 걷어내고,
(2) UI를 Compose로 새로 짜고, (3) Aparapi GPU 가속을 모바일용으로 대체하거나 CPU로 떨어뜨리는 일이다.

작업량 체감: **엔진 추출/순수화 = 중, UI 재작성 = 상, GPU 대체 = 상(또는 보류), FDTD 성능 튜닝 = 상.**

---

## 1. 현재 구조의 이식성 진단

코드 스캔 결과(`grep` 기반 실측):

| 모듈 | 총 라인 | JavaFX 의존 | 이식 난이도 | 비고 |
|------|--------|------------|-----------|------|
| `engine/fdtd/TezFdtdSolver` | 291 | **없음** (순수 Java) | ★☆☆ | `double[][]` + `BiConsumer`만 사용. 거의 그대로 복붙 가능 |
| `engine/fdtd/*` 나머지 | ~700 | `FdtdHeatmapGenerator`만 `Color/WritableImage` | ★★☆ | 머티리얼 그리드/설정은 순수, 출력부만 교체 |
| `engine/WifiMath` | 1228 | `Point2D`×65, `Color`×11 | ★★☆ | 기하/물리 공식. Point2D 치환이 핵심 작업 |
| `engine/HeatmapGenerator` | 914 | `Point2D`×17, `Color/WritableImage`×7 | ★★☆ | Legacy 모델. 병렬처리(ForkJoin)는 그대로 동작 |
| `engine/ApRecommender` | 823 | `Point2D`만 | ★★☆ | record 다수 사용. FDTD 검증 루프 포함 |
| `engine/FdtdWaveSimulator` | 749 | `Color/WritableImage`×14 | ★★★ | 계산+시각화 혼재 → 분리 필요 |
| `engine/DpmPathGrid` | 677 | 확인 필요(경미) | ★★☆ | DPM 모델 |
| `solver/v2/AparapiGpuWaveSolver` | 976 | Aparapi(OpenCL) | ✖ | **안드로이드 불가**. 재작성 또는 보류 |
| `model/*` | ~1500 | `Property`/`ObservableList`/`Point2D` | ★★☆ | 상태→StateFlow, 컬렉션→일반 List |
| `ui/` `dialog/` `controller/` | ~6500 | 전면 JavaFX | ✖(재작성) | Compose로 신규 작성 |

**좋은 신호**
- FDTD 핵심 솔버가 순수 Java라 수치 정확도를 그대로 옮길 수 있다 (이게 제일 중요).
- 병렬화는 `ForkJoinPool`/`parallelStream` 사용 — 안드로이드 JVM(ART)에서 **그대로 동작**.
- 직렬화는 Jackson — 안드로이드에서 동작하나, 가볍게 `kotlinx.serialization`으로 갈아타도 좋다.

**걸림돌**
- `Aparapi`는 OpenCL 기반 → 안드로이드 GPU에서 미동작. (4번 항목 참조)
- `record`가 7개 파일에서 사용됨 → 안드로이드 빌드 시 D8/R8 desugaring 필요 (AGP 8.x 기본 지원).
- `switch ->`(arrow) 13개 파일 → desugaring으로 처리됨. `sealed` 클래스는 미사용(다행).
- `FdtdWaveSimulator`는 계산과 렌더링이 섞여 있어 분리가 필요.

---

## 2. 타깃 아키텍처

```
:app (Android, Kotlin)
├── ui/              # Jetpack Compose 화면 + Canvas 렌더링
│   ├── CanvasScreen.kt        # 도면+히트맵+AP, pan/zoom (기존 CanvasView 대응)
│   ├── ToolbarBar.kt          # 툴/모델 전환 (TopToolbar 대응)
│   ├── EditPanel.kt           # AP/벽 편집 (LeftPanel 대응, 모바일은 BottomSheet 권장)
│   └── dialog/                # ApEditor, ApRecommend → Compose Dialog/BottomSheet
├── viewmodel/
│   └── MainViewModel.kt       # AppState+MainController 역할, StateFlow 노출
└── render/
    └── HeatmapBitmap.kt       # double[][]/RSSI → Android Bitmap(IntArray ARGB)

:engine (순수 Kotlin/Java 모듈, 안드로이드 비의존)
├── model/           # AP, Wall, RadioConfig, Band, WifiEnvironment ... (POJO)
├── math/            # WifiMath, Vec2(=Point2D 대체)
├── legacy/          # HeatmapGenerator, DpmPathGrid
├── fdtd/            # TezFdtdSolver(거의 무수정), FdtdConfig, MaterialGrid ...
├── recommend/       # ApRecommender
└── render/          # RssiColorMap (Color 로직만, 플랫폼 비의존: int ARGB 반환)
```

핵심 원칙: **`:engine` 모듈은 `android.*`도 `javafx.*`도 import하지 않는다.**
출력은 항상 `double[][]`(필드값) 또는 `IntArray`(ARGB 픽셀)로 내보내고,
Bitmap 변환은 `:app` 쪽 `render/`에서만 한다. 이렇게 하면 엔진을 JUnit으로 단위 테스트할 수 있다(현재 테스트 없음 → 회귀 검증 도구가 생김).

---

## 3. JavaFX → Android/Kotlin 타입 매핑

| JavaFX / 데스크탑 | 안드로이드 / Kotlin 대체 | 메모 |
|------|------|------|
| `javafx.geometry.Point2D` | 자체 `Vec2(x: Double, y: Double)` data class | `.distance()`, `.getX/Y()` 메서드 포팅. 가장 빈번한 치환(약 146곳) |
| `javafx.scene.paint.Color` | `Int` (ARGB) 또는 `androidx.compose.ui.graphics.Color` | 엔진 내부는 ARGB int로, UI는 Compose Color |
| `WritableImage` / `PixelWriter` | `android.graphics.Bitmap` + `IntArray` (`setPixels`) | 엔진은 `IntArray` 반환, app이 `Bitmap.createBitmap(...)` |
| `ObjectProperty<T>` / `DoubleProperty` | `MutableStateFlow<T>` | ViewModel에서 노출, Compose가 `collectAsState()` |
| `ObservableList<T>` | `MutableStateFlow<List<T>>` 또는 `SnapshotStateList` | AP/벽 리스트 |
| JavaFX `Task` (백그라운드) | Kotlin Coroutine (`Dispatchers.Default`) + `Flow` 진행률 | UI 블로킹 방지 동일 패턴 |
| `Canvas` + `GraphicsContext` | Compose `Canvas` + `DrawScope` (또는 `SurfaceView`) | pan/zoom은 `Modifier.pointerInput` 제스처 |
| `Alert`/`Dialog` + `Styles` | Compose `AlertDialog`/`ModalBottomSheet` + Material3 테마 | Liquid Glass 테마는 Material3 ColorScheme로 재구성 |
| `Platform.runLater` | `withContext(Dispatchers.Main)` | |
| 파일 다이얼로그 | Storage Access Framework (`ActivityResultContracts`) | JSON 저장/열기 |
| Jackson | `kotlinx.serialization` (권장) 또는 Jackson 유지 | 모델에 `@Serializable` |

---

## 4. GPU 가속(Aparapi) 대체 전략

Aparapi = Java→OpenCL 변환 라이브러리. 안드로이드는 OpenCL을 공식 지원하지 않음(벤더 의존, 불안정). 따라서 그대로는 불가.

선택지 (우선순위順):

1. **1차: CPU 폴백으로 출시** — 코드에 이미 CPU 경로(`GpuHeatmapSolver`의 fallback, `SolverV2Engine` CPU 모드)가 있음. 멀티코어 + 해상도 적응으로 실용 속도 확보. **v1 권장.**
2. **2차: RenderScript 대체 → 불가/비권장** — RenderScript는 deprecated(API 31+).
3. **3차: Vulkan Compute 셰이더** — FDTD 업데이트 루프(`updateH`/`updateE`)는 셀별 독립 연산이라 GPU 친화적. 가장 큰 성능 이득. 단, 셰이더(GLSL/SPIR-V) 신규 작성 = 작업량 큼. v2 이후.
4. **4차: GPU 위임 라이브러리** — TFLite GPU delegate 등은 부적합(범용 컴퓨트 아님). 비권장.

권고: **v1은 CPU만으로 출시**하고, FDTD가 느리면 5번(성능) 튜닝으로 흡수. Vulkan은 별도 마일스톤.

---

## 5. FDTD 모바일 성능 — 현실 점검

현재 데스크탑 설정: λ/15~λ/20 해상도, 6000 스텝. 이건 데스크탑 멀티코어 기준이고 폰에선 무겁다.

`TezFdtdSolver.run()`은 매 스텝 `nx×ny` 격자를 3중(H, E, RMS누적) 순회. 폰 CPU는 데스크탑 대비 코어 수·클럭·메모리 대역이 낮아 체감 수 배 느림.

대응책:
- **해상도 적응**: 모바일에서 λ/10~λ/12로 낮춰 격자 셀 수↓ (정확도 약간 희생).
- **스텝 수 동적 종료**: RMS 수렴 감지 시 6000 스텝 전 조기 종료.
- **영역 제한**: AP 추천 검증은 전체가 아니라 관심 영역만 시뮬.
- **코루틴 + 진행률 UI**: `run()`의 `BiConsumer` 진행 콜백을 `Flow<Progress>`로 바꿔 프로그레스바 표시. (이미 콜백 훅이 있어 연결만 하면 됨)
- **타일/멀티스레드**: 스텝 내부 격자 루프를 `parallel`로. 단 H↔E 의존성 때문에 스텝 간은 순차.
- **백그라운드 잡**: 긴 시뮬은 `WorkManager` 포그라운드 서비스로 빼서 화면 꺼져도 유지.

기대치: Legacy/DPM 모델은 폰에서 실시간에 가깝게, FDTD는 "버튼 누르고 수 초~수십 초 대기 + 프로그레스" UX로 설계하는 게 현실적.

---

## 6. 단계별 마이그레이션 로드맵

### Phase 0 — 프로젝트 셋업 (0.5주)
- Android Studio 프로젝트 생성: `:app`(Android lib+app) + `:engine`(Kotlin/Java 라이브러리 모듈).
- `compileOptions`/`kotlinOptions` JVM target 17, AGP 8.x로 record/switch desugaring 활성화.
- minSdk 26+ 권장(coreLibraryDesugaring로 더 낮출 수 있음).
- CI에서 `:engine` 단위 테스트 돌도록 JUnit5 세팅.

### Phase 1 — 엔진 순수화 (핵심, 1.5~2주)
1. `Vec2` 도입, `Point2D` 전량 치환 (`WifiMath`, `HeatmapGenerator`, `ApRecommender`, `WifiEnvironment` 등).
2. `model/` POJO화: `AppState`의 `Property`→평범한 필드(상태는 ViewModel로 이동), `WifiEnvironment`의 `ObservableList`→`MutableList`.
3. 렌더 분리: `Color`/`WritableImage`를 쓰던 함수들이 **`IntArray`(ARGB) 또는 `double[][]`** 를 반환하도록 시그니처 변경. 색 매핑 로직은 `RssiColorMap`로 추출(플랫폼 비의존).
4. `FdtdWaveSimulator`에서 **계산부와 시각화부 분리** — 계산은 `double[][] rms` 반환, 색칠은 app.
5. `TezFdtdSolver`는 거의 그대로 이동(검증만).
6. **회귀 테스트**: 데스크탑에서 특정 입력(AP/벽 배치)의 RSSI 격자를 JSON으로 덤프 → 엔진 모듈 JUnit에서 동일 입력에 동일 출력 나오는지 비교. 수치 포팅 정확성 보증의 핵심.

### Phase 2 — 렌더링 & ViewModel (1.5주)
- `MainViewModel`: `AppState`+`MainController` 역할. `StateFlow`로 툴/모델/AP리스트/스케일 노출.
- `HeatmapBitmap`: 엔진의 `IntArray`→`Bitmap`.
- `CanvasScreen`: Compose `Canvas`로 도면 이미지·히트맵 Bitmap·AP/벽 오버레이 그리기. pan/zoom 제스처.
- 비동기: 히트맵/FDTD 계산을 `viewModelScope`+`Dispatchers.Default`로, 진행률 `Flow`.

### Phase 3 — 인터랙션 & 편집 UI (2주)
- 툴 모드(보기/스케일/벽/AP/솔버) 전환 — Compose 툴바.
- AP 배치·드래그·선택(`ApController` 로직 이식), 벽 그리기(`ToolsController`).
- AP 편집 다이얼로그(`ApEditorDialog`)→Compose Dialog/BottomSheet (밴드별 RadioConfig 폼).
- 스케일 캘리브레이션 UX.
- 도면 이미지 불러오기(갤러리/파일).

### Phase 4 — AP 추천 (1주)
- Flood Fill 영역 선택 UI(`ApRecommendDialog`) → 캔버스 위 영역 페인팅 제스처.
- `ApRecommender` 연결(이미 순수화됨). FDTD 검증 루프는 코루틴+진행률.

### Phase 5 — 저장/불러오기 & 마감 (1주)
- WifiEnvironment JSON ↔ SAF 파일 입출력.
- Material3 테마(다크/라이트), Liquid Glass 느낌 재현.
- 결과 내보내기(히트맵 PNG 공유 등).

### Phase 6 — 성능 튜닝 & (선택) GPU (지속)
- FDTD 해상도/스텝/수렴 종료 튜닝.
- (선택) Vulkan compute 솔버.

**대략 합계: ~8~10주 (1인 기준, GPU Vulkan 제외).** GPU Vulkan 추가 시 +3~4주.

---

## 7. 위험 요소 & 대응

| 위험 | 영향 | 대응 |
|------|------|------|
| FDTD가 폰에서 너무 느림 | 핵심 기능 UX 저하 | 해상도 적응 + 조기수렴 종료 + 프로그레스 UX. 최악의 경우 FDTD는 "정밀 검증" 옵션으로 격하 |
| 수치 포팅 중 미묘한 오차 | 히트맵 결과 달라짐 | Phase 1 회귀 테스트(골든 데이터 비교)로 차단 |
| Aparapi 제거로 속도 손실 | GPU 모드 사용자 | CPU 멀티스레드로 흡수, Vulkan은 후속 |
| `record`/`switch` desugaring 이슈 | 빌드 실패 | AGP 8.x + coreLibraryDesugaring, 안 되면 일반 클래스로 환원 |
| 메모리(큰 격자 `double[][]`) | OOM 위험 | `float[]` 1D 배열로 전환 고려(메모리 절반), 영역 제한 |
| UI 전면 재작성 분량 | 일정 지연 | 모바일은 기능 우선순위화(편집→히트맵→추천 順), 패널은 BottomSheet로 단순화 |

---

## 8. 바로 시작할 수 있는 첫 작업 (착수 시)

1. `:engine` Gradle 모듈 생성 + `Vec2` 작성 + `Point2D` 치환 (가장 영향 큰 1보).
2. 데스크탑 앱에서 골든 RSSI 격자 덤프 기능 추가 → 회귀 테스트 픽스처 확보.
3. `TezFdtdSolver`·`FdtdConfig`·`FdtdMaterialGrid` 그대로 `:engine`으로 이동 후 컴파일 확인.

---

## 부록 A. 모듈별 JavaFX 의존 실측 (grep)

- engine/solver/model에서 `import javafx` 쓰는 파일: 12개
- 사용 타입: `Point2D`(geometry), `Color`/`WritableImage`/`PixelWriter/Reader`(image), `Property`/`ObservableList`(base)
- Aparapi 사용: `solver/v2/AparapiGpuWaveSolver.java` 단 1개
- `record` 사용: 7개 파일 / `sealed`: 0개 / `switch ->`: 13개 파일
- 병렬처리(`ForkJoinPool`/`parallelStream`): `ApRecommender`, `FdtdWaveSimulator`, `HeatmapGenerator`

## 부록 B. 권장 기술 스택 (안드로이드)

- 언어: Kotlin (엔진은 Java 그대로 둬도 됨 — 점진 변환 가능)
- UI: Jetpack Compose + Material3
- 비동기: Coroutines + Flow
- 직렬화: kotlinx.serialization (또는 Jackson 유지)
- 최소 SDK: 26 (desugaring으로 조정 가능), 타깃: 최신
- 빌드: AGP 8.x, Gradle 8.x (현재 8.14 → 호환)
