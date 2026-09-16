/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.math.*;
public class Diag {
    static void show(double d, double a, double b, double c, double got) {
        a /= d; b /= d; c /= d;
        double sq_A = a*a, p = (1.0/3.0)*((-1.0/3.0)*sq_A + b), sub = (1.0/3.0)*a;
        double q = 0.5*((2.0/27.0)*a*sq_A - sub*b + c), cb_p = p*p*p, D = q*q + cb_p;
        System.out.printf("a=%.17g b=%.17g c=%.17g%n p=%.17g q=%.17g p^3=%.6g q^2=%.6g D=%.6g  D/q^2=%.3g%n", a,b,c,p,q,cb_p,q*q,D,D/(q*q));
        if (D > 0) { double s = Math.sqrt(D), u = Math.cbrt(s - q), v = -Math.cbrt(s + q);
            System.out.printf(" Cardano: sqrt_D=%.17g u=%.17g v=%.17g u+v=%.17g sub=%.17g root=%.17g (cancel u+v: %.2g)%n", s,u,v,u+v,sub,u+v-sub, Math.abs(u+v)/Math.abs(u)); }
        else { double phi = Math.acos(-q/Math.sqrt(-cb_p))/3, t = 2*Math.sqrt(-p);
            System.out.printf(" trig: arg=%.17g phi=%.17g t=%.17g%n", -q/Math.sqrt(-cb_p), phi, t); }
        // reference q, p in BigDecimal from the *normalized* double a,b,c (isolates rounding inside p,q formulas)
        MathContext mc = new MathContext(50);
        BigDecimal A = new BigDecimal(a), B = new BigDecimal(b), C = new BigDecimal(c), th = BigDecimal.valueOf(3);
        BigDecimal P = B.subtract(A.multiply(A).divide(th, mc)).divide(th, mc);
        BigDecimal Q = A.pow(3).multiply(BigDecimal.valueOf(2)).divide(BigDecimal.valueOf(27), mc).subtract(A.multiply(B).divide(th, mc)).add(C).divide(BigDecimal.valueOf(2), mc);
        System.out.printf(" exact p=%s (err %.2g ulp)  exact q=%s (err %.2g ulp)%n", P.round(new MathContext(20)), P.subtract(new BigDecimal(p)).abs().doubleValue()/Math.ulp(p), Q.round(new MathContext(20)), Q.subtract(new BigDecimal(q)).abs().doubleValue()/Math.ulp(q));
        BigDecimal DD = Q.multiply(Q).add(P.pow(3));
        System.out.printf(" exact D=%s (computed D err %.2g ulp)%n%n", DD.round(new MathContext(20)), DD.subtract(new BigDecimal(D)).abs().doubleValue()/Math.ulp(D));
    }
    public static void main(String[] x) {
        System.out.println("# worst 'good' Cardano [0,1): got 0.20395039788845726 ref 0.20395039676974312781 (1.1e-9 abs, 4e7 ulp)");
        show(0.0017750449326245892, 0.001740231002954636, 5.68565094493601E-4, -2.034038604354049E-4, 0.20395039788845726);
        System.out.println("# worst abs Marlin xPoints: got 0.5725829827661073 ref 0.57258299947578063200 (1.7e-8 abs)");
        show(-0.16526380323693957, 4687.6188571365965, -2199.834407894735, -277.2230880948232, 0.5725829827661073);
        System.out.println("# misclassified 3-roots [0,1): got 0.4736735901647511 ref 0.38971934258184597481");
        show(-0.10350075254108025, 0.13749129100654833, -0.06057554712283826, 0.008851440768698657, 0.4736735901647511);
        System.out.println("# misclassified 3 roots in [0,0.1]: got 0.07772334030424641 ref 0.0019556259715940328700");
        show(-6.09925834857172, 0.9066247266343933, -0.03038994675015342, 5.6009625214047525E-5, 0.07772334030424641);
    }
}
