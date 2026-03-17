# Wi-Fi Heatmap / Solver v2 (JavaFX)

이 프로젝트는 평면도 기반 Wi-Fi 분석 도구입니다.

- **Heatmap(기존 모델)**: 로그거리 + 벽감쇠 + 반사/회절 근사 기반 RSSI 분포
- **Solver v2(신규 오버레이)**: 2D TEz FDTD(Ez/Hx/Hy) + PML 기반 파동 전파 시각화

## 왜 Solver v2가 더 현실적으로 보이나?

기존 heatmap은 정적 경로손실 중심이라 전파의 간섭무늬를 직접 보여주기 어렵습니다.
Solver v2는 시간영역 파동 계산으로 아래 현상을 오버레이에서 확인할 수 있습니다.

- 다중경로 간섭으로 인한 로브/줄무늬
- 벽/문/창문 재질 차이에 따른 반사/투과 패턴 차이
- 공간 구조(복도, 코너)로 인한 에너지 집중/음영

## 사용 흐름 (초보자 기준)

1. `평면도 열기`
2. (온보딩) 스케일 보정
3. AP 배치
4. 벽 그리기/재질 지정
5. `Solver` 툴 선택
6. `Solver 시작`

왼쪽 Solver 카드에서:

- 표시 밴드(2.4/5/6/All) 선택
- 오버레이 표시 on/off
- 상태(step/time/fps) 확인

## Solver v2 물리/수치 로그

Solver 시작 시 콘솔에 다음 항목이 출력됩니다.

- `backend`(CPU/GPU)
- `dx`, `dt`, `CFL`, `pml`
- `src`(활성 소스 수)
- 재질 셀 통계(`air/wall/door/window`)
- 실행 중 `runtime/step/fps` 1초 주기 텔레메트리

즉, 검증 시 필요한 핵심 런타임 지표를 바로 확인할 수 있습니다.

## GPU 가속 확장 포인트

기본 빌드는 CPU backend를 사용합니다.
GPU backend는 SPI(`app.solver.v2.GpuWaveSolver`) 구현체를 외부 모듈로 제공하면 AUTO 모드에서 자동 선택됩니다.

- 구현체가 없거나 초기화 실패 시 CPU fallback
- 콘솔에 `backend=CPU` 또는 `backend=GPU`가 출력됨

## 검증 시나리오 3종 (필수)

### 1) 빈 공간 (Empty)
- 벽 0개, AP 1개
- 기대 결과:
  - 원형에 가까운 파면
  - 경계에서 강한 반사 띠가 지속적으로 보이지 않음(PML 동작)

### 2) 단일 직선 벽 (Single Wall)
- AP와 관찰 영역 사이에 벽 1개
- 기대 결과:
  - 벽 전면 반사 패턴
  - 벽 후면 음영(그림자)
  - 문/창문 재질 사용 시 투과 증가

### 3) 실제 평면도 (Real Floorplan)
- 복도/방 구조 + 문/창문 포함
- 기대 결과:
  - 복도 방향 로브
  - 실내 간섭 줄무늬
  - 방 경계에 따른 음영 분포

## 권장 확인 체크리스트

- 스케일 변경 시 Solver 재생성(재질 격자 재빌드) 되는지
- AP/라디오 변경 시 full rebuild 없이 source refresh 되는지
- 벽/재질 수정 시 재빌드 후 패턴이 즉시 변하는지
- Solver 오버레이 on/off가 재빌드 없이 즉시 반영되는지

상세 시나리오 문서: `docs/solver-v2-validation.md`

## 빌드

```bash
./gradlew compileJava
```
