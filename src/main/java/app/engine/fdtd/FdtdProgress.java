package app.engine.fdtd;

/**
 * FDTD 진행률 전달용 DTO.
 * - currentStep: 현재 소스 시뮬레이션의 step
 * - totalSteps: 현재 소스 시뮬레이션 총 step
 * - totalCompletedSteps: 전체(모든 AP 소스) 기준 누적 진행 step
 * - totalPlannedSteps: 전체(모든 AP 소스) 기준 총 step
 */
public record FdtdProgress(
        int sourceIndex,
        int sourceCount,
        int currentStep,
        int totalSteps,
        long totalCompletedSteps,
        long totalPlannedSteps,
        double simTimeNs,
        String message
) {
}

