import app.engine.FdtdWaveSimulator;
import app.model.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 발표용: 앱의 검증 엔진 FdtdWaveSimulator(CFL 0.90)를 헤드리스로 돌려 실제 파동장 PNG + 성능 산출. */
public class FdtdWaveDemo {
    static int[] cmap(double t){t=Math.max(0,Math.min(1,t));
        double[][] s={{30,95,168},{46,134,193},{39,174,140},{139,195,74},{244,178,62},{242,121,43},{217,83,79}};
        double x=t*(s.length-1);int i=(int)Math.floor(x);if(i>=s.length-1)i=s.length-2;double f=x-i;
        return new int[]{(int)(s[i][0]+(s[i+1][0]-s[i][0])*f),(int)(s[i][1]+(s[i+1][1]-s[i][1])*f),(int)(s[i][2]+(s[i+1][2]-s[i][2])*f)};}

    public static void main(String[] a) throws Exception {
        new javafx.embed.swing.JFXPanel(); // JavaFX 툴킷 부팅(헤드리스)
        double scale=0.02; int widthPx=400, heightPx=300, cellPx=1; // 8m x 6m, dx=2cm
        WifiEnvironment env=new WifiEnvironment(); env.setScaleMPerPx(scale);
        // 세로 콘크리트 내벽(아래 문틈) + 오른쪽 위 석고보드 칸막이
        env.getWalls().add(new Wall(200,0,200,180,WallMaterial.CONCRETE_WALL));
        env.getWalls().add(new Wall(205,100,380,100,WallMaterial.DRY_WALL));
        AP ap=new AP(); ap.x=120; ap.y=150; ap.enabled=true; ap.heightM=2.5; ap.name="src";
        env.getAps().add(ap);

        FdtdWaveSimulator sim=new FdtdWaveSimulator(env,widthPx,heightPx,cellPx,Band.GHZ_24);
        System.out.println("sources="+sim.sourceCount()+" grid="+sim.gridNx()+"x"+sim.gridNy()
            +" pml="+sim.pmlCells()+" dx="+String.format("%.4f",sim.dxMeters())+"m dt="+String.format("%.3e",sim.dtSeconds())+"s courant="+String.format("%.3f",sim.courantNumber()));

        int total=4000; long t0=System.nanoTime();
        for(int s=0;s<total;s+=200) sim.step(200);
        long ms=(System.nanoTime()-t0)/1_000_000;
        long cells=(long)sim.gridNx()*sim.gridNy();
        System.out.printf("steps=%d  wall=%,dms (%.2fs)  %,.0f steps/s  %.0f Mcell/s  simTime=%.1fns%n",
            total, ms, ms/1000.0, total/(ms/1000.0), (double)cells*total/(ms/1000.0)/1e6, sim.timeNs());

        // 앱의 실제 시각화 프레임을 그대로 캡처 (실시간 파동 패턴 + 앱 컬러맵)
        javafx.scene.image.WritableImage fx = sim.renderFrame();
        BufferedImage img = javafx.embed.swing.SwingFXUtils.fromFXImage(fx, null);
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(img, 0, 0, null);
        ImageIO.write(rgb,"png",new File("presentation/fdtd_field.png"));
        System.out.println("PNG(renderFrame): presentation/fdtd_field.png "+rgb.getWidth()+"x"+rgb.getHeight());
        System.exit(0);
    }
}
