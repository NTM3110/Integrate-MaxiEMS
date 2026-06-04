package io.openems.edge.controller.tools;

import java.util.Arrays;

/* ===================== Matrix (NumPy-like) ===================== */
public class Matrix {
    private final int rows, cols;
    final double[][] a;

    Matrix(int r, int c) {
        if (r <= 0 || c <= 0) throw new IllegalArgumentException("Invalid shape");
        rows = r; cols = c; a = new double[r][c];
    }
    Matrix(double[][] data) {
        rows = data.length; 
        cols = data[0].length; 
        a = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            if (data[i].length != cols) throw new IllegalArgumentException("Jagged array");
            System.arraycopy(data[i], 0, a[i], 0, cols);
        }
    }

    static Matrix zeros(int r, int c){ return new Matrix(r,c); }
    static Matrix ones(int r, int c)
    { 
    	Matrix m=new Matrix(r,c); 
    	for(int i=0;i<r;i++) 
    		Arrays.fill(m.a[i],1.0); 
    	return m; 
	}
    static Matrix eye(int n)
    { 
    	Matrix m=new Matrix(n,n); 
    	for(int i=0;i<n;i++) 
    		m.a[i][i]=1.0; 
    	return m; 
	}
    static Matrix diag(double... d)
    { 
    	Matrix m=new Matrix(d.length,d.length); 
    	for(int i=0;i<d.length;i++) 
    		m.a[i][i]=d[i]; 
    	return m; 
	}
    static Matrix row(double... v){ return new Matrix(new double[][]{ v }); }     // 1×n
    static Matrix col(double... v)
    { 
    	double[][] d=new double[v.length][1]; 
    	for(int i=0;i<v.length;i++) 
    		d[i][0]=v[i]; 
    	return new Matrix(d); 
	} // n×1

    int rows(){ return rows; }
    int cols(){ return cols; }
    double get(int r,int c){ return a[r][c]; }
    Matrix set(int r,int c,double v){ a[r][c]=v; return this; }

    Matrix T(){
        Matrix t=new Matrix(cols,rows);
        for(int i=0;i<rows;i++) 
        	for(int j=0;j<cols;j++) 
        		t.a[j][i]=a[i][j];
        return t;
    }
    Matrix scale(double k){
        Matrix m=new Matrix(rows,cols);
        for(int i=0;i<rows;i++) 
        	for(int j=0;j<cols;j++) 
        		m.a[i][j]=k*a[i][j];
        return m;
    }
    Matrix add(Matrix b){
        if(rows!=b.rows||cols!=b.cols) throw new IllegalArgumentException("Shape mismatch add");
        Matrix m = new Matrix(rows,cols);
        for(int i=0;i<rows;i++) 
        	for(int j=0;j<cols;j++) 
        		m.a[i][j]=a[i][j]+b.a[i][j];
        return m;
    }
    Matrix sub(Matrix b){
        if(rows!=b.rows||cols!=b.cols) throw new IllegalArgumentException("Shape mismatch sub");
        Matrix m=new Matrix(rows,cols);
        for(int i=0;i<rows;i++) 
        	for(int j=0;j<cols;j++) 
        		m.a[i][j]=a[i][j]-b.a[i][j];
        return m;
    }
    Matrix hadamard(Matrix b){
        if(rows!=b.rows||cols!=b.cols) throw new IllegalArgumentException("Shape mismatch hadamard");
        Matrix m=new Matrix(rows,cols);
        for(int i=0;i<rows;i++) 
        	for(int j=0;j<cols;j++) 
        		m.a[i][j]=a[i][j]*b.a[i][j];
        return m;
    }
    Matrix mmul(Matrix b){
        if(cols!=b.rows) throw new IllegalArgumentException("Shape mismatch mmul");
        Matrix m=new Matrix(rows,b.cols);
        for(int i=0;i<rows;i++){
            for(int k=0;k<cols;k++){
                double aik=a[i][k];
                for(int j=0;j<b.cols;j++) 
                	m.a[i][j]+=aik*b.a[k][j];
            }
        }
        return m;
    }
    // (1×n)·(n×1) -> scalar
    double dot(Matrix colVec){
        if(rows!=1||colVec.cols!=1||cols!=colVec.rows) throw new IllegalArgumentException("dot expects (1×n)·(n×1)");
        double s=0.0; 
        for(int i=0;i<cols;i++) 
        	s+=a[0][i]*colVec.a[i][0]; 
        return s;
    }

    // Square-matrix inverse via Gauss-Jordan (fine for small n)
    Matrix inv(){
        if(rows!=cols) throw new IllegalArgumentException("Inverse requires square matrix");
        int n=rows; 
        Matrix aug=new Matrix(n,2*n);
        for(int i=0;i<n;i++){ System.arraycopy(a[i],0,aug.a[i],0,n); aug.a[i][n+i]=1.0; }
        for(int p=0;p<n;p++){
            int max=p; 
            for(int i=p+1;i<n;i++) 
            	if(Math.abs(aug.a[i][p])>Math.abs(aug.a[max][p])) max=i;
            if(Math.abs(aug.a[max][p])<1e-15) throw new ArithmeticException("Matrix not invertible");
            if(max!=p){ 
            	double[] tmp=aug.a[p]; 
            	aug.a[p]=aug.a[max]; 
            	aug.a[max]=tmp; 
        	}
            double div=aug.a[p][p]; 
            for(int j=0;j<2*n;j++) 
            	aug.a[p][j]/=div;
            for(int i=0;i<n;i++){ 
            	if(i==p) continue; 
            	double f=aug.a[i][p]; 
            	if(f==0) continue;
                for(int j=0;j<2*n;j++) 
                	aug.a[i][j]-=f*aug.a[p][j]; 
            }
        }
        Matrix inv=new Matrix(n,n);
        for(int i=0;i<n;i++) System.arraycopy(aug.a[i],n,inv.a[i],0,n);
        return inv;
    }

    @Override public String toString(){
        StringBuilder sb=new StringBuilder(); sb.append('[');
        for(int i=0;i<rows;i++){ if(i>0) sb.append('\n'); sb.append("  ").append(Arrays.toString(a[i])); }
        return sb.append(']').toString();
    }
}
