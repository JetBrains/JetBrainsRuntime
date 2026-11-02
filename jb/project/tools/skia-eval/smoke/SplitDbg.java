import java.awt.*; import java.awt.geom.*; import java.lang.reflect.*;
public class SplitDbg { public static void main(String[] a) throws Exception {
  Class<?> c = Class.forName("sun.java2d.skia.SKShapePipe"); Method m = c.getDeclaredMethod("splitBentCubics", Shape.class, AffineTransform.class); m.setAccessible(true);
  for (Shape s : new Shape[]{ new Ellipse2D.Double(30,25,100,60), new RoundRectangle2D.Double(10,10,50,30,6,6), cubic() }) {
    Shape out = (Shape) m.invoke(null, s, new AffineTransform());
    System.out.println(s.getClass().getSimpleName()+": segments "+count(s)+" -> "+count(out)+(out==s?" (unchanged)":""));
  } }
  static Shape cubic(){ Path2D.Double p=new Path2D.Double(); p.moveTo(10,100); p.curveTo(40,-30,90,150,150,20); return p; }
  static int count(Shape s){ int n=0; for (PathIterator pi=s.getPathIterator(null); !pi.isDone(); pi.next()) n++; return n; } }
