Debug guide for heatmap and FDTD solver issues.

Issue to debug: $ARGUMENTS

## Common Issues and Where to Look

### Heatmap not rendering
- Check `CanvasView.java` render() method
- Verify `MainController.java` is calling render after solver completes
- Check `HeatmapGenerator.java` or `FdtdHeatmapGenerator.java` return value

### FDTD solver hanging or slow
- Check `TezFdtdSolver.java` — time step count in `FdtdConfig`
- Check PML depth settings in `FdtdConfig.java`
- Check `FdtdProgress.java` for progress tracking
- GPU fallback: check `HeatmapGenerator.java` solver selection logic

### Wrong signal values / RSSI incorrect
- Check `WifiMath.java` path loss formula
- Verify `Wall.java` attenuation values for the material
- Check `FdtdMaterialGrid.java` for material properties mapping

### AP not responding to interaction
- `ApController.java` — hover/select/drag logic
- `CanvasView.java` — mouse event routing
- `AppState.java` — current tool mode check (must be SELECT mode)

### Scale calibration broken
- `ToolsController.java` — scale tool logic
- `AppState.java` — scale factor storage

Read the relevant files and trace the call stack from the symptom.
