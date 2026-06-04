package io.openems.edge.controller.tools;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-state EKF for SoC estimation:
 *   State: x = [ SoC, Vp ]
 *   OCV(SoC,T) = Vcut + SoC*(Vmax - Vcut) + alpha_T*(T - 25)
 *   Measurement: U = OCV - Vp - I*R0
 *
 * step(U_meas, I, T, R0) -> Result(SoC, Vp, y_pred, innovation, R0_used)
 */
public class EKFSoC {

	private static final Logger logger = LoggerFactory.getLogger(ControllerBmsHuasuTcBulkCreatorImpl.class);
    // ---- Tunables / params ----
    private final double dt;            // s
    private final double Qn_As;         // As (A*s), i.e., Ah * 3600
    private final double Vcut;          // V
    private final double Vmax;          // V
    private final double DeltaV;        // Vmax - Vcut
    private final double R1;            // ohm
    private final double C1;            // F
    private final double alphaTemp;     // V/°C
    private final double R_meas;        // measurement variance (V^2)

    // ---- State ----
    private double SoC;                 // 0..1
    private double Vp;                  // V

    // ---- Covariances (2x2) ----
    private Matrix P;                   // covariance
    private final Matrix Q;             // process noise

    // ---- Constant matrices ----
    // H = [[DeltaV, -1]]
    private Matrix H;                   // 1x2
    private static final Matrix I2 = Matrix.eye(2);

    public static class Result {
        public final double SoC;       // posterior SoC
        public final double Vp;        // posterior Vp
        public final double y_pred;    // predicted measurement
        public final double nu;        // innovation (U_meas - y_pred)
        public final double R0_used;   // R0 passed in

        public Result(double SoC, double Vp, double y_pred, double nu, double R0_used) {
            this.SoC = SoC;
            this.Vp = Vp;
            this.y_pred = y_pred;
            this.nu = nu;
            this.R0_used = R0_used;
        }
    }

    // ---- Constructors ----

    /** Defaults chosen to mirror the Python version. */
    public EKFSoC() {
		this.dt = 0;
		this.Qn_As = 0;
		this.Vcut = 0;
		this.Vmax = 0;
		this.DeltaV = 0;
		this.R1 = 0;
		this.C1 = 0;
		this.alphaTemp = 0;
		this.R_meas = 0;
		this.Q = null;
    }

    public EKFSoC(double dt,
                  double Qn_Ah,
                  double Vcut,
                  double Vmax,
                  double R1,
                  double C1,
                  double alpha_temp,
                  double R_meas) {

        this.dt = dt;
        this.Qn_As = Qn_Ah * 3600.0;
        this.Vcut = Vcut;
        this.Vmax = Vmax;
        this.DeltaV = Vmax - Vcut;
        this.R1 = R1;
        this.C1 = C1;
        this.alphaTemp = alpha_temp;
        this.R_meas = R_meas;

        double socInit = 0.8;
        this.SoC = socInit;
        this.P = Matrix.diag(Math.pow(0.1, 2), Math.pow(0.02, 2));
        this.Q = Matrix.diag(1e-8, 1e-5); 
        // H is constant for linear OCV(SoC)
        this.H = Matrix.row(this.DeltaV, -1.0);
    }

    // ---- One EKF iteration ----
    public Result step(double U_meas, double I, double T, double R0) {
        // Debug similar to Python prints
        // logger.info("voltage: {}, current: {}, temperature: {}", U_meas, I, T);
        // logger.info("SoC: {}, Vp: {}", SoC, Vp);

        // ---------- Predict ----------
        double SoC_pred = SoC - (I * dt) / Qn_As;
        double Vp_pred = Vp + dt * (-Vp / (R1 * C1) + I / C1);

        // logger.info("SoC_pred: {}", SoC_pred);

        // x_pred vector as 2x1
        Matrix x_pred = Matrix.col(SoC_pred, Vp_pred);

        // F = [[1, 0], [0, 1 - dt/(R1*C1)]]
        double f22 = 1.0 - dt / (R1 * C1);
        Matrix F = new Matrix(new double[][]{
            {1.0, 0.0},
            {0.0, f22}
        });

        // P_pred = F * P * F^T + Q
        Matrix P_pred = F.mmul(P).mmul(F.T()).add(Q);

        // ---------- Measurement prediction ----------
        double OCV_pred = Vcut + SoC_pred * DeltaV + alphaTemp * (T - 25.0);
        double y_pred = OCV_pred - Vp_pred - I * R0;

        
//        logger.info("SoC_pred: {}", y_pred);
        // innovation
        double nu = U_meas - y_pred;

        // ---------- Update ----------
        // S = H * P_pred * H^T + R_meas   -> scalar
        // H: 1x2, P_pred:2x2, H^T:2x1 => 1x1
        Matrix S_mat = H.mmul(P_pred).mmul(H.T()).add(Matrix.row(R_meas)); // 1x1
        double S = S_mat.get(0, 0);

        // K = (P_pred * H^T) / S   -> (2x1)
        Matrix K = P_pred.mmul(H.T()).scale(1.0 / S);

        // x_upd = x_pred + K * nu
        Matrix x_upd = x_pred.add(K.scale(nu));

        // P_upd = (I - K*H) * P_pred
        Matrix KH = K.mmul(H);                // (2x1)*(1x2) = (2x2)
        Matrix I_minus_KH = I2.sub(KH);       // 2x2
        Matrix P_upd = I_minus_KH.mmul(P_pred);

        // Symmetrize P (numerical hygiene): 0.5*(P + P^T)
        P_upd = P_upd.add(P_upd.T()).scale(0.5);

        // Clamp SoC
        double SoC_new = clamp(x_upd.get(0, 0), 0.0, 1.0);
        double Vp_new = x_upd.get(1, 0);

        // Commit
        this.SoC = SoC_new;
        this.Vp = Vp_new;
        this.P = P_upd;

        return new Result(this.SoC, this.Vp, y_pred, nu, R0);
    }

    // ---- Helpers ----

    private static void ensure2x2(Matrix m, String name) {
        Objects.requireNonNull(m, name + " is null");
        if (m.rows() != 2 || m.cols() != 2) {
            throw new IllegalArgumentException(name + " must be 2x2");
        }
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    // ---- Getters ----
    public double getSoC() { return SoC; }
    public double getVp() { return Vp; }
    public Matrix getP() { return P; }
    public Matrix getQ() { return Q; }
    public double getDt() { return dt; }
    public double getVcut() { return Vcut; }
    public double getVmax() { return Vmax; }
    public double getDeltaV() { return DeltaV; }
    public double getR1() { return R1; }
    public double getC1() { return C1; }
    public double getAlphaTemp() { return alphaTemp; }
    public double getR_meas() { return R_meas; }
    public void setSoC(double SoC) {this.SoC = SoC;}
}
